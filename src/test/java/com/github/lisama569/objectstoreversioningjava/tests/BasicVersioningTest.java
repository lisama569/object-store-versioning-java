package com.github.lisama569.objectstoreversioningjava.tests;

import com.github.lisama569.objectstoreversioningjava.core.ObjectDeletedException;
import com.github.lisama569.objectstoreversioningjava.core.ObjectNotFoundException;
import com.github.lisama569.objectstoreversioningjava.core.ObjectVersion;
import com.github.lisama569.objectstoreversioningjava.core.StoredContent;
import com.github.lisama569.objectstoreversioningjava.core.VersionNotFoundException;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 基础版本与删除语义：覆盖写入、按版本/最新读取、逻辑删除后旧版本仍可读。 */
class BasicVersioningTest {

    private static byte[] b(String s) {
        return s.getBytes(StandardCharsets.UTF_8);
    }

    @Test
    void putCreatesMonotonicVersionsAndVersionedReadsWork() {
        var h = StoreTestSupport.newHarness();
        ObjectVersion v1 = h.store().put("docs/a", b("hello"));
        ObjectVersion v2 = h.store().put("docs/a", b("world"));
        StoreTestSupport.log("BASIC", "写入 v%d=%s, v%d=%s",
                v1.version().value(), v1.describe(), v2.version().value(), v2.describe());

        assertEquals(1, v1.version().value());
        assertEquals(2, v2.version().value());

        StoredContent latest = h.store().get("docs/a");
        StoredContent first = h.store().getVersion("docs/a", 1);
        StoreTestSupport.log("BASIC", "最新=v%d 内容='%s'；v1 内容='%s'",
                latest.version().value(), new String(latest.data(), StandardCharsets.UTF_8),
                new String(first.data(), StandardCharsets.UTF_8));

        assertArrayEquals(b("world"), latest.data());
        assertArrayEquals(b("hello"), first.data());
    }

    @Test
    void logicalDeleteAppendsMarkerButKeepsHistoryReadable() {
        var h = StoreTestSupport.newHarness();
        h.store().put("k1", b("one"));
        h.store().put("k1", b("two"));
        ObjectVersion tomb = h.store().delete("k1");
        StoreTestSupport.log("BASIC", "逻辑删除追加 %s，判定依据：最新版本 deleteMarker=%s",
                tomb.describe(), tomb.deleteMarker());
        assertEquals(3, tomb.version().value());
        assertTrue(tomb.deleteMarker());

        ObjectDeletedException gone = assertThrows(ObjectDeletedException.class, () -> h.store().get("k1"));
        StoreTestSupport.log("BASIC", "读最新版被拒绝原因：%s", gone.getMessage());

        // 历史版本仍可按版本读取
        assertArrayEquals(b("one"), h.store().getVersion("k1", 1).data());
        StoreTestSupport.log("BASIC", "删除后 v1 仍可读，内容='%s'", "one");

        // 对已删除对象再次逻辑删除 → 可区分原因
        ObjectDeletedException again = assertThrows(ObjectDeletedException.class, () -> h.store().delete("k1"));
        StoreTestSupport.log("BASIC", "重复逻辑删除被拒绝：%s", again.getMessage());
    }

    @Test
    void missingObjectVersionAndDeletedReasonsAreDistinct() {
        var h = StoreTestSupport.newHarness();
        assertThrows(ObjectNotFoundException.class, () -> h.store().get("nope"));
        assertThrows(ObjectNotFoundException.class, () -> h.store().delete("nope"));

        h.store().put("k2", b("x"));
        VersionNotFoundException e1 = assertThrows(VersionNotFoundException.class,
                () -> h.store().getVersion("k2", 99));
        StoreTestSupport.log("BASIC", "缺失版本被拒绝：%s", e1.getMessage());
        VersionNotFoundException e2 = assertThrows(VersionNotFoundException.class,
                () -> h.store().getVersion("k2", 0));
        StoreTestSupport.log("BASIC", "非法版本号被拒绝：%s", e2.getMessage());

        List<ObjectVersion> vs = h.store().listVersions("k2");
        StoreTestSupport.log("BASIC", "k2 版本链=%s", vs.stream().map(ObjectVersion::describe).toList());
        assertEquals(1, vs.size());
    }
}
