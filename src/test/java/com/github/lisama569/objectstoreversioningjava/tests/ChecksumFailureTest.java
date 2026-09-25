package com.github.lisama569.objectstoreversioningjava.tests;

import com.github.lisama569.objectstoreversioningjava.core.ChecksumMismatchException;
import com.github.lisama569.objectstoreversioningjava.core.ContentId;
import com.github.lisama569.objectstoreversioningjava.core.ObjectVersion;
import com.github.lisama569.objectstoreversioningjava.storage.LocalBlobStorage;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 校验失败必须被显式识别并隔离：
 * 1) 提交时声明地址与实际摘要不符 → 直接拒绝，磁盘不留正式块；
 * 2) 已提交的块在磁盘上被外力篡改 → 读取复验失败，文件移入 quarantine，绝不返回损坏字节；
 * 3) 被版本引用的共享块损坏被隔离后，读取持续显式失败，而不是静默返回脏数据。
 */
class ChecksumFailureTest {

    private static byte[] b(String s) {
        return s.getBytes(StandardCharsets.UTF_8);
    }

    @Test
    void commitWithWrongDeclaredIdIsRejected() {
        var h = StoreTestSupport.newHarness();
        ContentId good = ContentId.of(LocalBlobStorage.sha256Hex(b("real")));
        ContentId wrong = ContentId.of(LocalBlobStorage.sha256Hex(b("other")));
        StoreTestSupport.log("CHECKSUM", "真实内容地址=%s，声明地址=%s（不一致）",
                good.sha256(), wrong.sha256());

        ChecksumMismatchException e = assertThrows(ChecksumMismatchException.class,
                () -> h.blobs().commit(wrong, b("real")));
        StoreTestSupport.log("CHECKSUM", "提交被拒绝原因：%s", e.getMessage());

        // 正式目录里不得出现该块（连垃圾都不留）
        assertTrue(h.blobs().listAll().isEmpty(), "校验失败的提交不得留下正式块");
        StoreTestSupport.log("CHECKSUM", "判定：磁盘正式块数=%d（要求 0）", h.blobs().listAll().size());
    }

    @Test
    void tamperedOnDiskBlobIsDetectedQuarantinedAndNeverReturned() throws Exception {
        var h = StoreTestSupport.newHarness();
        ObjectVersion v = h.store().put("secure/doc", b("important-payload"));
        StoreTestSupport.log("CHECKSUM", "正常写入 %s", v.describe());

        // 外力篡改磁盘上的块文件（模拟比特腐坏）
        Path blob = h.dir().resolve("blobs-root/blobs").resolve(v.contentId().sha256());
        byte[] original = Files.readAllBytes(blob);
        byte[] tampered = ("TAMPERED-" + new String(original, StandardCharsets.UTF_8))
                .getBytes(StandardCharsets.UTF_8);
        Files.write(blob, tampered);
        StoreTestSupport.log("CHECKSUM", "已篡改磁盘块 %s：原长度=%d，篡改后长度=%d",
                v.contentId().sha256().substring(0, 12), original.length, tampered.length);

        // 读取必须复验失败并隔离，不得返回损坏字节
        ChecksumMismatchException e = assertThrows(ChecksumMismatchException.class,
                () -> h.store().get("secure/doc"));
        StoreTestSupport.log("CHECKSUM", "读取被拒绝原因：%s", e.getMessage());

        assertFalse(Files.exists(blob), "损坏块必须从正式目录移走");
        Path quarantine = h.dir().resolve("blobs-root/quarantine");
        long quarantined;
        try (var s = Files.list(quarantine)) {
            quarantined = s.count();
        }
        StoreTestSupport.log("CHECKSUM", "判定：正式目录已无该块，quarantine 中文件数=%d（要求 1）", quarantined);
        assertEquals(1, quarantined);

        // 再次读仍然显式失败（隔离后不会“自愈”为脏数据返回）
        assertThrows(ChecksumMismatchException.class, () -> h.store().get("secure/doc"));

        // 另一个正常对象不受影响
        h.store().put("other/doc", b("fine"));
        assertArrayEquals(b("fine"), h.store().get("other/doc").data());
        StoreTestSupport.log("CHECKSUM", "其他对象读取正常，损坏被隔离未扩散");
    }

    @Test
    void sharedBlobTamperedAffectsAllVersionsExplicitlyNotSilently() throws Exception {
        var h = StoreTestSupport.newHarness();
        // 三个版本，其中 v1 与 v3 内容相同 → 共享同一块
        ObjectVersion v1 = h.store().put("s", b("same"));
        h.store().put("s", b("different"));
        ObjectVersion v3 = h.store().put("s", b("same"));
        assertEquals(v1.contentId(), v3.contentId());
        StoreTestSupport.log("CHECKSUM", "v1 与 v3 共享块=%s（引用计数=2）",
                v1.contentId().sha256().substring(0, 12));
        assertEquals(2, h.blobs().listAll().size());

        Path blob = h.dir().resolve("blobs-root/blobs").resolve(v1.contentId().sha256());
        Files.write(blob, b("broken-on-disk"));

        // 共享块损坏：v1、v3 都显式失败；v2 完好可读
        assertThrows(ChecksumMismatchException.class, () -> h.store().getVersion("s", 1));
        assertThrows(ChecksumMismatchException.class, () -> h.store().getVersion("s", 3));
        assertArrayEquals(b("different"), h.store().getVersion("s", 2).data());
        StoreTestSupport.log("CHECKSUM", "判定：共享块损坏时引用它的版本全部显式失败，未引用的 v2 正常");
    }
}
