package com.github.lisama569.objectstoreversioningjava.tests;

import com.github.lisama569.objectstoreversioningjava.core.GcReport;
import com.github.lisama569.objectstoreversioningjava.core.ObjectVersion;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 版本保留与空间回收：
 * 1) 逻辑删除不回收任何块（历史版本仍引用数据）；
 * 2) 被多个版本共享的块，只要还有一个版本引用就不被清除；
 * 3) 永久删除版本后，仅“最后一个引用”消失的块才在 GC 时被回收；
 * 4) GcReport 的保留/删除集合可解释判定依据。
 */
class GarbageCollectionTest {

    private static byte[] b(String s) {
        return s.getBytes(StandardCharsets.UTF_8);
    }

    @Test
    void logicalDeleteReclaimsNothing() {
        var h = StoreTestSupport.newHarness();
        h.store().put("a", b("v1-content"));
        h.store().put("a", b("v2-content"));
        h.store().delete("a");
        int before = h.blobs().listAll().size();

        GcReport r = h.gc().collect();
        StoreTestSupport.log("GC", "逻辑删除后：磁盘块=%d，GC 删除=%d，保留=%d；保留集合=%s",
                before, r.deleted(), r.retainedContentIds().size(),
                r.retainedContentIds().stream().map(s -> s.substring(0, 8)).toList());

        assertEquals(0, r.deleted(), "逻辑删除只追加标记，历史版本仍引用块，不得回收");
        assertEquals(before, h.blobs().listAll().size());
        // 历史版本数据依旧可读
        assertArrayEquals(b("v1-content"), h.store().getVersion("a", 1).data());
        assertArrayEquals(b("v2-content"), h.store().getVersion("a", 2).data());
    }

    @Test
    void sharedBlobIsRetainedWhileAnyVersionReferencesIt() {
        var h = StoreTestSupport.newHarness();
        ObjectVersion v1 = h.store().put("k", b("same"));
        h.store().put("k", b("middle"));
        ObjectVersion v3 = h.store().put("k", b("same")); // 与 v1 共享
        ObjectVersion v2 = h.store().listVersions("k").get(1);
        StoreTestSupport.log("GC", "版本链：v1=%s v2=%s v3=%s；磁盘块=2",
                v1.describe(), v2.describe(), v3.describe());

        // 永久删除 v1：共享块仍被 v3 引用，必须保留
        h.store().purgeVersion("k", 1);
        GcReport r1 = h.gc().collect();
        StoreTestSupport.log("GC", "永久删除 v1 后 GC：删除=%d，保留=%d（共享块须保留）",
                r1.deleted(), r1.retainedContentIds().size());
        assertEquals(0, r1.deleted());
        Set<String> retained = Set.copyOf(r1.retainedContentIds());
        assertTrue(retained.contains(v1.contentId().sha256()));
        assertEquals(2, h.blobs().listAll().size());
        // v3 仍然能读到共享内容
        assertArrayEquals(b("same"), h.store().getVersion("k", 3).data());

        // 永久删除 v3：共享块失去最后引用，此时才允许回收；v2 的块继续保留
        h.store().purgeVersion("k", 3);
        GcReport r2 = h.gc().collect();
        StoreTestSupport.log("GC", "再永久删除 v3 后 GC：删除=%s，保留=%s",
                r2.deletedContentIds().stream().map(s -> s.substring(0, 8)).toList(),
                r2.retainedContentIds().stream().map(s -> s.substring(0, 8)).toList());
        assertEquals(1, r2.deleted());
        assertTrue(r2.deletedContentIds().contains(v1.contentId().sha256()));
        assertTrue(r2.retainedContentIds().contains(v2.contentId().sha256()));
        assertEquals(1, h.blobs().listAll().size());
        assertArrayEquals(b("middle"), h.store().getVersion("k", 2).data());

        // 再删 v2，最后一次 GC 清空
        h.store().purgeVersion("k", 2);
        GcReport r3 = h.gc().collect();
        StoreTestSupport.log("GC", "清空全部版本后 GC：删除=%d，剩余块=%d",
                r3.deleted(), h.blobs().listAll().size());
        assertEquals(1, r3.deleted());
        assertEquals(0, h.blobs().listAll().size());
    }

    @Test
    void sharedBlobAcrossDifferentObjectsIsReferenceCounted() {
        var h = StoreTestSupport.newHarness();
        ObjectVersion a1 = h.store().put("obj-a", b("shared-data"));
        ObjectVersion b1 = h.store().put("obj-b", b("shared-data"));
        assertEquals(a1.contentId(), b1.contentId());
        StoreTestSupport.log("GC", "跨对象共享：a.v1 与 b.v1 同址=%s，磁盘块数=%d",
                a1.contentId().sha256().substring(0, 8), h.blobs().listAll().size());
        assertEquals(1, h.blobs().listAll().size());

        h.store().purgeVersion("obj-a", 1);
        GcReport r = h.gc().collect();
        StoreTestSupport.log("GC", "删除 a.v1（b.v1 仍引用）后 GC 删除=%d（要求 0）", r.deleted());
        assertEquals(0, r.deleted());
        assertEquals(1, h.blobs().listAll().size());
        assertArrayEquals(b("shared-data"), h.store().get("obj-b").data());

        h.store().purgeVersion("obj-b", 1);
        GcReport r2 = h.gc().collect();
        StoreTestSupport.log("GC", "再删除 b.v1 后 GC 删除=%d（要求 1），剩余=%d",
                r2.deleted(), h.blobs().listAll().size());
        assertEquals(1, r2.deleted());
        assertFalse(h.blobs().listAll().contains(a1.contentId()));
    }
}
