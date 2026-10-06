package com.github.lisama569.objectstoreversioningjava.store;

import com.github.lisama569.objectstoreversioningjava.exception.StoreException;
import com.github.lisama569.objectstoreversioningjava.model.ObjectVersion;
import com.github.lisama569.objectstoreversioningjava.model.PutResult;
import com.github.lisama569.objectstoreversioningjava.model.RestoreResult;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 删除恢复语义：
 * - 删除后可恢复到任一保留中的历史版本，恢复是追加新版本，get 立即可读；
 * - 恢复到已被裁剪但底层数据仍在的版本：从待删状态救回；
 * - 底层数据已被物理回收时明确报 VERSION_DATA_RECLAIMED，绝不返回“假成功”；
 * - 目标是删除标记 / 版本不存在 / key 不存在时分别给出可区分原因。
 */
class RestoreObjectTest {

    private static final Logger log = LoggerFactory.getLogger(RestoreObjectTest.class);

    @Test
    void restoreDeletedObjectToHistoricalVersion() throws Exception {
        ObjectStore store = TestSupport.newStore(0, false);
        PutResult v1 = store.put("doc", TestSupport.bytes("first-draft"));
        PutResult v2 = store.put("doc", TestSupport.bytes("second-draft"));
        ObjectVersion marker = store.delete("doc");
        log.info("inputs: v1={} v2={} deleteMarker={}", v1.versionId(), v2.versionId(),
                marker.versionId());

        // 删除后 get 被拒绝
        StoreException deleted = assertThrows(StoreException.class, () -> store.get("doc"));
        assertEquals(StoreException.Reason.OBJECT_DELETED, deleted.getReason());

        // 恢复到 v1：追加新版本，get 立即可读且内容与 v1 一致
        RestoreResult restored = store.restore("doc", v1.versionId());
        log.info("restored: newVersion={} fromVersion={} blobId={}",
                restored.versionId(), restored.restoredFromVersionId(), restored.blobId());
        assertEquals(v1.blobId(), restored.blobId());
        assertEquals("first-draft", TestSupport.utf8(store.get("doc")));
        // 历史版本链连续可查：v1/v2/删除标记/恢复版本都在
        assertEquals(4, store.listVersions("doc").size());
        assertEquals("second-draft", TestSupport.utf8(store.getVersion("doc", v2.versionId())));
        // 恢复版本与源版本共享底层 blob（引用计数 = 2）
        assertEquals(2, store.registry().referenceCount(v1.blobId()));
        log.info("after restore: get returns v1 content, history chain intact (4 versions), "
                + "shared blob refs=2");
    }

    @Test
    void restoreRescuesPrunedVersionWhoseBlobIsStillOnDisk() throws Exception {
        ObjectStore store = TestSupport.newStore(2, false); // 只留最近 2 个版本
        PutResult v1 = store.put("k", TestSupport.bytes("old-content"));
        PutResult v2 = store.put("k", TestSupport.bytes("mid-content"));
        PutResult v3 = store.put("k", TestSupport.bytes("new-content"));
        // v1 已被裁剪出活跃链（元数据进入归档，blob 待删但文件仍在，因为尚未 purge）
        StoreException pruned = assertThrows(StoreException.class,
                () -> store.getVersion("k", v1.versionId()));
        assertEquals(StoreException.Reason.NO_SUCH_VERSION, pruned.getReason());
        assertTrue(store.registry().isPending(v1.blobId()), "v1 blob pending delete, file still present");
        log.info("setup: v1={} pruned to archive, blob={} pending delete (not yet purged)",
                v1.versionId(), v1.blobId());

        store.delete("k");
        // 从归档恢复：blob 被“救回”，恢复成功
        RestoreResult restored = store.restore("k", v1.versionId());
        assertEquals("old-content", TestSupport.utf8(store.get("k")));
        assertEquals(1, store.registry().referenceCount(v1.blobId()));
        // 恢复触发的新一轮裁剪会把 v2/v3 的 blob 释放为待删，purge 只删它们；
        // 被救回的 v1 blob 有引用，必须保留
        int purged = store.purgeUnreferenced();
        assertTrue(store.blobStore().exists(v1.blobId()), "rescued blob must survive purge");
        assertTrue(purged >= 0);
        assertEquals("old-content", TestSupport.utf8(store.get("k")));
        log.info("restore from archive rescued blob: newVersion={} refs=1, "
                + "purge removed {} other pending blobs, rescued blob intact",
                restored.versionId(), purged);
    }

    @Test
    void restoreFailsDistinguishablyWhenDataAlreadyReclaimed() throws Exception {
        ObjectStore store = TestSupport.newStore(1, false); // 只留最新 1 个版本
        PutResult v1 = store.put("k", TestSupport.bytes("doomed-content"));
        PutResult v2 = store.put("k", TestSupport.bytes("surviving-content"));
        // v1 被裁剪；物理回收后底层数据消失
        int removed = store.purgeUnreferenced();
        assertEquals(1, removed);
        assertTrue(!store.blobStore().exists(v1.blobId()));
        log.info("setup: v1={} pruned and purged (blob physically gone), v2={} retained",
                v1.versionId(), v2.versionId());

        store.delete("k");
        // 元数据归档里还找得到 v1，但底层数据已回收：必须明确报 VERSION_DATA_RECLAIMED
        StoreException reclaimed = assertThrows(StoreException.class,
                () -> store.restore("k", v1.versionId()));
        assertEquals(StoreException.Reason.VERSION_DATA_RECLAIMED, reclaimed.getReason());
        log.info("restore to reclaimed version rejected: {}", reclaimed.getMessage());

        // 对象仍处于删除态：恢复失败不能产生“看起来成功”的假版本
        StoreException stillDeleted = assertThrows(StoreException.class, () -> store.get("k"));
        assertEquals(StoreException.Reason.OBJECT_DELETED, stillDeleted.getReason());

        // 恢复到仍保留的 v2 则成功
        RestoreResult ok = store.restore("k", v2.versionId());
        assertEquals("surviving-content", TestSupport.utf8(store.get("k")));
        log.info("restore to surviving v2 succeeded: newVersion={}", ok.versionId());
    }

    @Test
    void restoreRejectsInvalidTargetsWithDistinguishableReasons() throws Exception {
        ObjectStore store = TestSupport.newStore(0, false);

        // key 不存在
        StoreException noObject = assertThrows(StoreException.class,
                () -> store.restore("ghost", 1));
        assertEquals(StoreException.Reason.NO_SUCH_OBJECT, noObject.getReason());
        log.info("restore on missing key rejected: {}", noObject.getMessage());

        PutResult v1 = store.put("k", TestSupport.bytes("data"));
        ObjectVersion marker = store.delete("k");

        // 目标是删除标记
        StoreException toMarker = assertThrows(StoreException.class,
                () -> store.restore("k", marker.versionId()));
        assertEquals(StoreException.Reason.OBJECT_DELETED, toMarker.getReason());
        log.info("restore to delete marker rejected: {}", toMarker.getMessage());

        // 版本从未存在
        StoreException noVersion = assertThrows(StoreException.class,
                () -> store.restore("k", 9999));
        assertEquals(StoreException.Reason.NO_SUCH_VERSION, noVersion.getReason());
        log.info("restore to unknown version rejected: {}", noVersion.getMessage());

        // 正常恢复仍然可用
        RestoreResult ok = store.restore("k", v1.versionId());
        assertEquals("data", TestSupport.utf8(store.get("k")));
        log.info("restore to live version succeeded: newVersion={}", ok.versionId());
    }

    @Test
    void restoredObjectKeepsWorkingUnderFurtherWritesAndRetention() throws Exception {
        ObjectStore store = TestSupport.newStore(2, true); // 保留 2 版 + 自动回收
        PutResult v1 = store.put("k", TestSupport.bytes("original"));
        store.delete("k");
        RestoreResult restored = store.restore("k", v1.versionId());
        assertEquals("original", TestSupport.utf8(store.get("k")));

        // 恢复后继续正常写入：版本链继续增长，保留策略照常裁剪
        PutResult v3 = store.put("k", TestSupport.bytes("after-restore-1"));
        PutResult v4 = store.put("k", TestSupport.bytes("after-restore-2"));
        assertEquals("after-restore-2", TestSupport.utf8(store.get("k")));
        assertEquals("after-restore-1", TestSupport.utf8(store.getVersion("k", v3.versionId())));
        log.info("post-restore writes work: restored={} v3={} v4={} retained={}",
                restored.versionId(), v3.versionId(), v4.versionId(),
                store.listVersions("k").stream().map(ObjectVersion::versionId).toList());
        assertEquals(2, store.listVersions("k").size(), "retention still prunes after restore");
    }
}
