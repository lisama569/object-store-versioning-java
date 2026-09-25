package com.github.lisama569.objectstoreversioningjava.store;

import com.github.lisama569.objectstoreversioningjava.exception.StoreException;
import com.github.lisama569.objectstoreversioningjava.model.ObjectVersion;
import com.github.lisama569.objectstoreversioningjava.model.PutResult;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 版本保留与空间回收：
 * - 超出保留上限的旧版本被裁剪，按版本号读返回 NO_SUCH_VERSION；
 * - 计数归零先登记待删，purge 才物理删除；
 * - 被多个版本共享的 blob 在任一版本仍引用时保留，最后一个引用消失后才回收。
 */
class RetentionReclamationTest {

    private static final Logger log = LoggerFactory.getLogger(RetentionReclamationTest.class);

    private static long physicalBlobs(ObjectStore store) throws Exception {
        try (Stream<Path> s = Files.walk(store.properties().getDataDir().resolve("blobs"))) {
            return s.filter(Files::isRegularFile).count();
        }
    }

    @Test
    void retentionPrunesOldVersionsAndPurgeDeletesOnlyUnreferencedBlobs() throws Exception {
        ObjectStore store = TestSupport.newStore(3, false); // 每 key 只保留最新 3 个版本

        PutResult a = store.put("k", TestSupport.bytes("content-a"));
        PutResult b = store.put("k", TestSupport.bytes("content-b"));
        PutResult c = store.put("k", TestSupport.bytes("content-c"));
        log.info("inputs: a={} b={} c={}", a.versionId(), b.versionId(), c.versionId());

        // 第四次写入触发裁剪：版本 a 被裁掉
        PutResult d = store.put("k", TestSupport.bytes("content-d"));
        List<ObjectVersion> retained = store.listVersions("k");
        assertEquals(List.of(b.versionId(), c.versionId(), d.versionId()),
                retained.stream().map(ObjectVersion::versionId).toList());
        log.info("after 4th put retained versions={}, a({}) pruned",
                retained.stream().map(ObjectVersion::versionId).toList(), a.versionId());

        // 被裁版本读取被拒绝，原因可区分
        StoreException missing = assertThrows(StoreException.class,
                () -> store.getVersion("k", a.versionId()));
        assertEquals(StoreException.Reason.NO_SUCH_VERSION, missing.getReason());
        assertEquals("content-b", TestSupport.utf8(store.getVersion("k", b.versionId())));

        // a 的 blob 引用归零：登记待删但文件仍在（purge 之前）
        assertEquals(0, store.registry().referenceCount(a.blobId()));
        assertTrue(store.registry().isPending(a.blobId()));
        assertTrue(store.blobStore().exists(a.blobId()), "pending blob kept until purge");
        int removed = store.purgeUnreferenced();
        assertEquals(1, removed);
        assertFalse(store.blobStore().exists(a.blobId()), "unreferenced blob physically deleted");
        // b/c/d 仍被引用，purge 不得动它们
        assertTrue(store.blobStore().exists(b.blobId()));
        assertEquals(3, physicalBlobs(store));
        log.info("purge removed a's blob; kept b/c/d, physicalFiles={}", physicalBlobs(store));
    }

    @Test
    void sharedBlobSurvivesUntilLastReferenceDrops() throws Exception {
        ObjectStore store = TestSupport.newStore(0, false); // 不限版本，手工制造共享
        String sharedContent = "shared-by-many";
        PutResult v1 = store.put("k", TestSupport.bytes(sharedContent));
        PutResult v2 = store.put("k", TestSupport.bytes(sharedContent));
        PutResult v3 = store.put("other-key", TestSupport.bytes(sharedContent));
        log.info("shared blob versions: k#{} k#{} other#{}", v1.versionId(), v2.versionId(), v3.versionId());
        assertEquals(v1.blobId(), v2.blobId());
        assertEquals(v1.blobId(), v3.blobId());
        assertEquals(3, store.registry().referenceCount(v1.blobId()));
        assertEquals(1, physicalBlobs(store));

        // 重写 k 两次新内容并用小保留策略手工裁剪不现实；直接通过删除/重写验证计数递减：
        // 用有限保留的第二个 store 做等价验证。
        ObjectStore limited = TestSupport.newStore(2, false);
        PutResult s1 = limited.put("k", TestSupport.bytes(sharedContent));
        PutResult s2 = limited.put("k", TestSupport.bytes(sharedContent));
        assertEquals(2, limited.registry().referenceCount(s1.blobId()));
        // 第三次写不同内容：s1 版本被裁剪，但 s2 仍引用同一 blob -> 必须保留
        PutResult s3 = limited.put("k", TestSupport.bytes("different-content"));
        log.info("limited store: s1={} pruned, s2={} still references shared blob",
                s1.versionId(), s2.versionId());
        assertEquals(1, limited.registry().referenceCount(s1.blobId()),
                "one surviving version still references the shared blob");
        assertFalse(limited.registry().isPending(s1.blobId()), "shared blob must not be pending while referenced");
        assertEquals(0, limited.purgeUnreferenced(), "GC must skip referenced shared blob");
        assertTrue(limited.blobStore().exists(s1.blobId()));
        assertEquals(sharedContent, TestSupport.utf8(limited.getVersion("k", s2.versionId())));
        log.info("shared blob kept with refs=1, readable via surviving version {}", s2.versionId());

        // 第四次写：s2 也被裁剪 -> 最后引用消失 -> 登记待删 -> purge 物理删除
        PutResult s4 = limited.put("k", TestSupport.bytes("yet-another"));
        assertEquals(0, limited.registry().referenceCount(s1.blobId()));
        assertTrue(limited.registry().isPending(s1.blobId()));
        assertEquals(1, limited.purgeUnreferenced());
        assertFalse(limited.blobStore().exists(s1.blobId()), "blob deleted only after last reference gone");
        // 新内容 blob 完好
        assertTrue(limited.blobStore().exists(s4.blobId()));
        log.info("last reference dropped -> blob purged; newest blobId={} intact", s4.blobId());
    }
}
