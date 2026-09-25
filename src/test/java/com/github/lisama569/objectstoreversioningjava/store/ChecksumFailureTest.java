package com.github.lisama569.objectstoreversioningjava.store;

import com.github.lisama569.objectstoreversioningjava.exception.StoreException;
import com.github.lisama569.objectstoreversioningjava.model.PutResult;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 校验失败必须显式识别并隔离，绝不能当正常数据返回。
 * 1) 写入校验失败：抛 CHECKSUM_MISMATCH，坏数据进 quarantine/，对象无新版本、无残留引用；
 * 2) 读时发现静默损坏（bit rot）：抛 CHECKSUM_MISMATCH，坏文件移入 quarantine/，不会返回坏字节。
 */
class ChecksumFailureTest {

    private static final Logger log = LoggerFactory.getLogger(ChecksumFailureTest.class);

    @Test
    void writeTimeCorruption_isRejectedIsolatedAndCreatesNoVersion() throws Exception {
        ObjectStore store = TestSupport.newStore(0, false);
        FileSystemBlobStore fs = (FileSystemBlobStore) store.blobStore();

        // 正常写入建立基线
        PutResult good = store.put("obj/a", TestSupport.bytes("good-content"));
        log.info("baseline put: {}", good);

        // 注入：暂存文件发布前翻转首字节，模拟写入路径落盘损坏
        fs.beforeCommitCorrupter = path -> {
            try (var ch = Files.newByteChannel(path,
                    java.nio.file.StandardOpenOption.WRITE)) {
                ch.position(0);
                ch.write(java.nio.ByteBuffer.wrap(new byte[]{(byte) 0x7E}));
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        };

        StoreException ex = assertThrows(StoreException.class,
                () -> store.put("obj/a", TestSupport.bytes("corrupt-on-write")));
        assertEquals(StoreException.Reason.CHECKSUM_MISMATCH, ex.getReason());
        log.info("write-time corruption rejected, reason={} msg={}", ex.getReason(), ex.getMessage());

        // 判定：没有产生新版本（版本链只有基线版本），最新读仍是基线内容
        assertEquals(1, store.listVersions("obj/a").size(), "failed write must not create a version");
        assertEquals("good-content", TestSupport.utf8(store.get("obj/a")),
                "failed corrupt write must not affect readable data");
        assertEquals(1, store.registry().referenceCount(good.blobId()));

        // 判定：坏数据出现在隔离区，正常区没有以其名义发布的文件，物理正常文件仍为 1
        Path quarantine = store.properties().getDataDir().resolve("quarantine");
        long isolated;
        try (Stream<Path> s = Files.walk(quarantine)) {
            isolated = s.filter(Files::isRegularFile).count();
        }
        assertTrue(isolated >= 1, "corrupt data must be quarantined");
        Path blobs = store.properties().getDataDir().resolve("blobs");
        long physical;
        try (Stream<Path> s = Files.walk(blobs)) {
            physical = s.filter(Files::isRegularFile).count();
        }
        assertEquals(1, physical, "only the good baseline blob may exist in healthy area");
        log.info("quarantine files={}, healthy physical files={}", isolated, physical);

        // 注入钩子只生效一次（实现中未自动清空，这里显式清掉）后系统恢复正常服务
        fs.beforeCommitCorrupter = null;
        PutResult recovered = store.put("obj/a", TestSupport.bytes("after-recovery"));
        assertEquals("after-recovery", TestSupport.utf8(store.get("obj/a")));
        log.info("service recovered after isolation, newVersion={}", recovered.versionId());
    }

    @Test
    void readTimeBitRot_isDetectedQuarantinedAndNeverReturned() throws Exception {
        ObjectStore store = TestSupport.newStore(0, false);
        FileSystemBlobStore fs = (FileSystemBlobStore) store.blobStore();
        PutResult v = store.put("obj/b", TestSupport.bytes("bit-rot-target-data"));
        assertEquals("bit-rot-target-data", TestSupport.utf8(store.get("obj/b")));

        // 直接破坏磁盘字节，模拟静默损坏
        fs.corruptOnDisk(v.blobId());
        log.info("injected bit-rot blobId={}", v.blobId());

        StoreException ex = assertThrows(StoreException.class, () -> store.get("obj/b"));
        assertEquals(StoreException.Reason.CHECKSUM_MISMATCH, ex.getReason());
        log.info("read-time corruption detected, reason={} msg={}", ex.getReason(), ex.getMessage());

        // 坏文件已被移走：正常区不存在
        assertFalse(store.blobStore().exists(v.blobId()),
                "corrupt file must be removed from healthy area after detection");
        Path quarantine = store.properties().getDataDir().resolve("quarantine");
        long isolated;
        try (Stream<Path> s = Files.walk(quarantine)) {
            isolated = s.filter(Files::isRegularFile).count();
        }
        assertTrue(isolated >= 1, "corrupt data must be moved to quarantine");

        // 元数据版本仍在（引用计数不变，说明这是数据层损坏而非正常回收），读取始终显式失败而非坏数据
        assertEquals(1, store.registry().referenceCount(v.blobId()),
                "detection/isolation does not alter version references");
        StoreException again = assertThrows(StoreException.class, () -> store.get("obj/b"));
        // 文件已隔离，再次读走显式的缺失/校验拒绝路径，不允许返回损坏内容
        assertTrue(again.getReason() == StoreException.Reason.CHECKSUM_MISMATCH
                        || again.getReason() == StoreException.Reason.NO_SUCH_VERSION,
                "repeat read must fail explicitly, got " + again.getReason());
        log.info("repeat read rejected with reason={} (no corrupt bytes ever returned)", again.getReason());
    }
}
