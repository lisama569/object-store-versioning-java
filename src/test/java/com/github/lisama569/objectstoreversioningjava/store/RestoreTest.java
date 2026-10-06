package com.github.lisama569.objectstoreversioningjava.store;

import com.github.lisama569.objectstoreversioningjava.exception.StoreException;
import com.github.lisama569.objectstoreversioningjava.model.ObjectVersion;
import com.github.lisama569.objectstoreversioningjava.model.PutResult;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 删除恢复：把已删除对象恢复到指定历史版本。
 * 覆盖：正常恢复、已裁剪未回收（救回）、已物理回收（可区分报错）、各类非法目标。
 */
class RestoreTest {

    private static final Logger log = LoggerFactory.getLogger(RestoreTest.class);

    private static List<Long> versionIds(ObjectStore store, String key) {
        return store.listVersions(key).stream().map(ObjectVersion::versionId).toList();
    }

    @Test
    void restoreDeletedObjectToHistoricalVersion() throws Exception {
        ObjectStore store = TestSupport.newStore(0, false);
        PutResult v1 = store.put("doc", TestSupport.bytes("revision-1"));
        PutResult v2 = store.put("doc", TestSupport.bytes("revision-2"));
        ObjectVersion marker = store.delete("doc");
        log.info("inputs: v1={} v2={} deleteMarker={}", v1.versionId(), v2.versionId(), marker.versionId());

        // 删除后最新读被拒
        StoreException deleted = assertThrows(StoreException.class, () -> store.get("doc"));
        assertEquals(StoreException.Reason.OBJECT_DELETED, deleted.getReason());

        // 恢复到 v1：产生一个新版本，内容等于 v1
        ObjectVersion restored = store.restore("doc", v1.versionId());
        log.info("restored: newVersion={} fromVersion={} blobId={}",
                restored.versionId(), v1.versionId(), restored.blobId());
        assertEquals("revision-1", TestSupport.utf8(store.get("doc")),
                "after restore, latest read must return the restored content");
        assertEquals(v1.blobId(), restored.blobId(), "restore reuses the same underlying blob (dedup)");
        // 版本链连续：历史 + 删除标记 + 恢复版本都可见
        assertEquals(List.of(v1.versionId(), v2.versionId(), marker.versionId(), restored.versionId()),
                versionIds(store, "doc"));
        // 恢复后继续正常写
        PutResult v5 = store.put("doc", TestSupport.bytes("revision-3"));
        assertEquals("revision-3", TestSupport.utf8(store.get("doc")));
        assertEquals("revision-1", TestSupport.utf8(store.getVersion("doc", restored.versionId())));
        log.info("verdict: restore resurrected object, chain continuous, subsequent puts work (v={})",
                v5.versionId());
    }

    @Test
    void restorePrunedVersionRescuesPendingBlobButFailsAfterPhysicalReclaim() throws Exception {
        ObjectStore store = TestSupport.newStore(1, false); // 只保留 1 个版本
        PutResult v1 = store.put("k", TestSupport.bytes("doomed-content"));
        store.put("k", TestSupport.bytes("replacement")); // v1 被裁剪出活跃链
        log.info("inputs: v1={} pruned by retention; blob pending but not yet purged", v1.versionId());

        // 活跃链上已没有 v1
        StoreException pruned = assertThrows(StoreException.class,
                () -> store.getVersion("k", v1.versionId()));
        assertEquals(StoreException.Reason.NO_SUCH_VERSION, pruned.getReason());

        // 但底层数据尚未物理回收：restore 能把 v1 救回
        ObjectVersion rescued = store.restore("k", v1.versionId());
        assertEquals("doomed-content", TestSupport.utf8(store.get("k")),
                "rescued version must be readable as latest");
        assertEquals(1, store.registry().referenceCount(v1.blobId()),
                "rescued blob holds a reference again");
        log.info("judgement: pruned-but-not-purged version rescued as version={}", rescued.versionId());

        // 再把救回的版本裁掉并物理回收：这次真的救不回来了
        store.put("k", TestSupport.bytes("final-content")); // rescued 版本被裁
        int removed = store.purgeUnreferenced();
        assertTrue(removed >= 1, "purge must physically reclaim the rescued blob");
        log.info("purge removed={} blobs; v1 data physically gone", removed);

        StoreException reclaimed = assertThrows(StoreException.class,
                () -> store.restore("k", v1.versionId()));
        assertEquals(StoreException.Reason.VERSION_DATA_RECLAIMED, reclaimed.getReason(),
                "must be a distinguishable reason, not a generic failure");
        assertTrue(reclaimed.getMessage().contains("reclaimed"), reclaimed.getMessage());
        log.info("judgement: restore after physical reclaim rejected: {}", reclaimed.getMessage());

        // 对象本身不受影响，最新版仍正常
        assertEquals("final-content", TestSupport.utf8(store.get("k")));
    }

    @Test
    void restoreRejectsInvalidTargetsWithDistinguishableReasons() throws Exception {
        ObjectStore store = TestSupport.newStore(0, false);

        // 从未存在的 key
        StoreException noObject = assertThrows(StoreException.class, () -> store.restore("ghost", 1));
        assertEquals(StoreException.Reason.NO_SUCH_OBJECT, noObject.getReason());
        log.info("rejected restore of unknown key: {}", noObject.getMessage());

        PutResult v1 = store.put("k", TestSupport.bytes("content-1"));
        ObjectVersion marker = store.delete("k");

        // 从未存在的版本号
        StoreException noVersion = assertThrows(StoreException.class, () -> store.restore("k", 9999));
        assertEquals(StoreException.Reason.NO_SUCH_VERSION, noVersion.getReason());
        log.info("rejected restore of unknown version: {}", noVersion.getMessage());

        // 目标是删除标记：没有数据可恢复
        StoreException toMarker = assertThrows(StoreException.class,
                () -> store.restore("k", marker.versionId()));
        assertEquals(StoreException.Reason.OBJECT_DELETED, toMarker.getReason());
        log.info("rejected restore to delete marker: {}", toMarker.getMessage());

        // 非法 key
        StoreException badKey = assertThrows(StoreException.class, () -> store.restore("  ", 1));
        assertEquals(StoreException.Reason.INVALID_KEY, badKey.getReason());

        // 全部拒绝都不产生副作用：版本链不变
        assertEquals(List.of(v1.versionId(), marker.versionId()), versionIds(store, "k"));
        log.info("verdict: all invalid restore targets rejected with distinguishable reasons, "
                + "no side effects");
    }

    @Test
    void restoreOfLiveObjectRollsBackToOlderVersion() throws Exception {
        ObjectStore store = TestSupport.newStore(0, false);
        PutResult v1 = store.put("cfg", TestSupport.bytes("config-good"));
        PutResult v2 = store.put("cfg", TestSupport.bytes("config-bad"));
        log.info("inputs: live object v1={} v2={} (not deleted)", v1.versionId(), v2.versionId());

        // 未删除也允许恢复：等价于回滚到历史版本
        ObjectVersion rolledBack = store.restore("cfg", v1.versionId());
        assertEquals("config-good", TestSupport.utf8(store.get("cfg")));
        assertEquals(List.of(v1.versionId(), v2.versionId(), rolledBack.versionId()),
                versionIds(store, "cfg"));
        log.info("verdict: restore on live object acts as rollback to version={}, new version={}",
                v1.versionId(), rolledBack.versionId());
    }
}
