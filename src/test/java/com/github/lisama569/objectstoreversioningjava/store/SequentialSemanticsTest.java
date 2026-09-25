package com.github.lisama569.objectstoreversioningjava.store;

import com.github.lisama569.objectstoreversioningjava.exception.StoreException;
import com.github.lisama569.objectstoreversioningjava.model.ObjectVersion;
import com.github.lisama569.objectstoreversioningjava.model.PutResult;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 顺序场景下的基础语义：版本读、最新读、删除语义、非法键、去重、引用计数。
 */
class SequentialSemanticsTest {

    private static final Logger log = LoggerFactory.getLogger(SequentialSemanticsTest.class);

    @Test
    void versionedPutGetDeleteAndDedup() throws Exception {
        ObjectStore store = TestSupport.newStore(0, false);

        PutResult v1 = store.put("users/u1", TestSupport.bytes("alice-v1"));
        PutResult v2 = store.put("users/u1", TestSupport.bytes("alice-v2"));
        log.info("input: v1={}, v2={}", v1, v2);
        assertEquals(1L, v1.versionId());
        assertEquals(2L, v2.versionId());
        assertEquals("alice-v2", TestSupport.utf8(store.get("users/u1")));
        assertEquals("alice-v1", TestSupport.utf8(store.getVersion("users/u1", v1.versionId())));
        assertEquals("alice-v2", TestSupport.utf8(store.getVersion("users/u1", v2.versionId())));

        // 相同内容重复写入：保留新版本，但底层数据去重，引用计数为 2。
        PutResult v3 = store.put("users/u1", TestSupport.bytes("alice-v2"));
        log.info("input: v3 same content as v2 -> {}", v3);
        assertEquals(v2.blobId(), v3.blobId());
        assertTrue(v3.contentDeduplicated(), "identical content must be deduplicated");
        assertEquals(2, store.registry().referenceCount(v2.blobId()), "shared blob referenced by 2 versions");
        assertEquals(1, store.registry().referenceCount(v1.blobId()));

        // 非法键可区分拒绝。
        StoreException badKey = assertThrows(StoreException.class, () -> store.put("  ", TestSupport.bytes("x")));
        assertEquals(StoreException.Reason.INVALID_KEY, badKey.getReason());
        log.info("invalid key rejected: reason={} msg={}", badKey.getReason(), badKey.getMessage());

        // 缺失对象 / 缺失版本。
        assertEquals(StoreException.Reason.NO_SUCH_OBJECT,
                assertThrows(StoreException.class, () -> store.get("missing")).getReason());
        assertEquals(StoreException.Reason.NO_SUCH_VERSION,
                assertThrows(StoreException.class,
                        () -> store.getVersion("users/u1", 999L)).getReason());

        // 删除：追加删除标记；最新读被拒，旧版本仍可读。
        ObjectVersion marker = store.delete("users/u1");
        log.info("deleted marker={}", marker);
        assertTrue(marker.deleteMarker());
        assertEquals(StoreException.Reason.OBJECT_DELETED,
                assertThrows(StoreException.class, () -> store.get("users/u1")).getReason());
        assertEquals(StoreException.Reason.OBJECT_DELETED,
                assertThrows(StoreException.class,
                        () -> store.getVersion("users/u1", marker.versionId())).getReason());
        assertEquals("alice-v1", TestSupport.utf8(store.getVersion("users/u1", v1.versionId())),
                "historical data version remains readable after delete marker");
        // 重复删除以 OBJECT_DELETED 区分。
        assertEquals(StoreException.Reason.OBJECT_DELETED,
                assertThrows(StoreException.class, () -> store.delete("users/u1")).getReason());
        // 删除不存在的对象是 NO_SUCH_OBJECT。
        assertEquals(StoreException.Reason.NO_SUCH_OBJECT,
                assertThrows(StoreException.class, () -> store.delete("ghost")).getReason());

        // 重新 put 可复活，最新读恢复。
        PutResult v4 = store.put("users/u1", TestSupport.bytes("alice-v4"));
        log.info("recreated v4={}", v4);
        assertEquals("alice-v4", TestSupport.utf8(store.get("users/u1")));
        assertFalse(v4.blobId().equals(v1.blobId()));

        List<ObjectVersion> versions = store.listVersions("users/u1");
        log.info("final versions: {}", versions.stream().map(ObjectVersion::versionId).toList());
        assertEquals(List.of(1L, 2L, 3L, 4L, 5L),
                versions.stream().map(ObjectVersion::versionId).toList());
    }
}
