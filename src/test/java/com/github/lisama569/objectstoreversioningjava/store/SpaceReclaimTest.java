package com.github.lisama569.objectstoreversioningjava.store;

import com.github.lisama569.objectstoreversioningjava.exception.StoreException;
import com.github.lisama569.objectstoreversioningjava.model.ReclaimReport;
import com.github.lisama569.objectstoreversioningjava.model.RetentionPolicy;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 空间回收：
 * - 容量阈值越界才触发，未越界不做任何动作（报告含判定依据）；
 * - 触发后先按保留规则裁剪再物理回收，仍有引用的数据绝不被删；
 * - 回收可安全重入：重复执行不重复计数；删除中途失败后重试不多删、不漏删；
 * - 未配置阈值时自动回收整体关闭。
 */
class SpaceReclaimTest {

    private static final Logger log = LoggerFactory.getLogger(SpaceReclaimTest.class);

    @Test
    void reclaimTriggersOnlyWhenThresholdExceeded() throws Exception {
        // 阈值 1KB：先写小对象（不越界），再写大对象把占用推过阈值
        ObjectStore store = TestSupport.newStoreWithReclaim(0, false, 1024, 0);
        store.reclaimer().setAsyncTriggerEnabled(false); // 测试同步控制回收时机
        // 纯时间规则：版本至少保留 50ms，3 次写入在窗口内完成（不裁剪），回收时才到期
        store.setRetentionPolicy("big", new RetentionPolicy(0, 50));

        store.put("small", TestSupport.bytes("tiny"));
        ReclaimReport below = store.reclaimer().reclaimIfNeeded();
        log.info("below threshold: usage={}B triggered={}", below.usageBeforeBytes(),
                below.thresholdExceeded());
        assertFalse(below.thresholdExceeded(), "usage below 1KB must not trigger reclaim");
        assertEquals(0, below.blobsPurged());

        // 每次写入 2KB 新内容，3 个版本后占用 ~6KB > 1KB 阈值
        byte[] chunk = new byte[2048];
        for (int i = 0; i < 3; i++) {
            java.util.Arrays.fill(chunk, (byte) ('a' + i));
            store.put("big", java.nio.ByteBuffer.wrap(chunk.clone()));
        }
        long usage = store.reclaimer().currentUsageBytes();
        log.info("above threshold: usage={}B threshold=1024B", usage);
        assertTrue(usage > 1024);

        Thread.sleep(60); // 让 3 个版本都“老”于 50ms 的时间下限
        ReclaimReport report = store.reclaimer().reclaimIfNeeded();
        log.info("reclaim report: triggered={} pruned={} purged={} failures={} usage {}B -> {}B",
                report.thresholdExceeded(), report.versionsPruned(), report.blobsPurged(),
                report.purgeFailures(), report.usageBeforeBytes(), report.usageAfterBytes());
        assertTrue(report.thresholdExceeded());
        assertEquals(2, report.versionsPruned(), "reclaim-time pruning drops the 2 aged-out versions");
        assertEquals(2, report.blobsPurged(), "two superseded 2KB blobs physically reclaimed");
        assertEquals(0, report.purgeFailures());
        assertTrue(report.usageAfterBytes() < report.usageBeforeBytes());
        // 最新版本内容完好可读
        assertEquals(2048, store.get("big").size());
        assertEquals("tiny", TestSupport.utf8(store.get("small")));
        log.info("after reclaim: latest version and other keys still readable");
    }

    @Test
    void reclaimIsIdempotentAndNeverDeletesReferencedData() throws Exception {
        ObjectStore store = TestSupport.newStoreWithReclaim(2, false, 1, 0); // 阈值 1 字节：总是越界
        store.reclaimer().setAsyncTriggerEnabled(false);
        String shared = "shared-content-kept-alive";
        store.put("a", TestSupport.bytes(shared));
        store.put("b", TestSupport.bytes(shared)); // 两个 key 共享同一 blob
        store.put("a", TestSupport.bytes("a-v2"));
        store.put("a", TestSupport.bytes("a-v3")); // a 的 v1 被裁剪，但 b 仍引用 shared blob

        ReclaimReport first = store.reclaimer().reclaimIfNeeded();
        log.info("first reclaim: pruned={} purged={} usage {}B -> {}B",
                first.versionsPruned(), first.blobsPurged(),
                first.usageBeforeBytes(), first.usageAfterBytes());
        assertTrue(first.thresholdExceeded());
        // shared blob 仍被 b 引用：必须保留
        assertTrue(store.blobStore().exists(
                store.listVersions("b").get(0).blobId()), "referenced shared blob must survive");
        assertEquals(shared, TestSupport.utf8(store.get("b")));

        // 立即再跑两次：无可回收对象，删除计数必须为 0（不重复计数、不多删）
        ReclaimReport second = store.reclaimer().reclaimIfNeeded();
        ReclaimReport third = store.reclaimer().reclaimIfNeeded();
        log.info("re-run: second purged={} third purged={}", second.blobsPurged(),
                third.blobsPurged());
        assertEquals(0, second.blobsPurged(), "idempotent re-run must not double count");
        assertEquals(0, third.blobsPurged());
        assertEquals(shared, TestSupport.utf8(store.get("b")));
    }

    @Test
    void failedDeletionKeepsStateAndRetryCompletesWithoutDoubleCounting() throws Exception {
        ObjectStore store = TestSupport.newStoreWithReclaim(1, false, 1, 0);
        store.reclaimer().setAsyncTriggerEnabled(false);
        store.put("k", TestSupport.bytes("doomed-1"));
        store.put("k", TestSupport.bytes("doomed-2"));
        store.put("k", TestSupport.bytes("survivor"));
        // 两个旧版本已裁剪为待删；注入故障：第一次回收时所有删除都失败
        AtomicInteger deleteCalls = new AtomicInteger();
        ((FileSystemBlobStore) store.blobStore()).beforeDeleteHook = id -> {
            deleteCalls.incrementAndGet();
            throw new RuntimeException("injected disk failure");
        };

        ReclaimReport failed = store.reclaimer().reclaimIfNeeded();
        log.info("first reclaim with injected failures: purged={} failures={} deleteCalls={}",
                failed.blobsPurged(), failed.purgeFailures(), deleteCalls.get());
        assertEquals(0, failed.blobsPurged(), "nothing counted when deletions fail");
        assertEquals(2, failed.purgeFailures());
        // 失败不丢状态：两个 blob 仍处于待删，文件还在
        assertEquals(2, deleteCalls.get());

        // 解除故障后重试：恰好删除这 2 个，不多不少
        ((FileSystemBlobStore) store.blobStore()).beforeDeleteHook = null;
        ReclaimReport retried = store.reclaimer().reclaimIfNeeded();
        log.info("retry after clearing fault: purged={} failures={}",
                retried.blobsPurged(), retried.purgeFailures());
        assertEquals(2, retried.blobsPurged(), "retry deletes exactly the two pending blobs");
        assertEquals(0, retried.purgeFailures());

        // 第三次：无可回收，计数为 0
        ReclaimReport stable = store.reclaimer().reclaimIfNeeded();
        assertEquals(0, stable.blobsPurged(), "no double counting on subsequent runs");
        assertEquals("survivor", TestSupport.utf8(store.get("k")));
        log.info("third run purged=0, survivor still readable");
    }

    @Test
    void disabledThresholdsTurnReclaimIntoNoOp() throws Exception {
        ObjectStore store = TestSupport.newStore(1, false); // 未配置任何阈值
        assertFalse(store.reclaimer().thresholdsEnabled());
        store.put("k", TestSupport.bytes("v1"));
        store.put("k", TestSupport.bytes("v2")); // v1 被裁剪为待删

        ReclaimReport report = store.reclaimer().reclaimIfNeeded();
        log.info("thresholds disabled: triggered={} purged={}", report.thresholdExceeded(),
                report.blobsPurged());
        assertFalse(report.thresholdExceeded());
        assertEquals(0, report.blobsPurged(), "disabled reclaim must not delete anything");

        // 非法阈值配置在启动时即被拒绝
        StoreException unused = null;
        IllegalArgumentException badRatio = assertThrows(IllegalArgumentException.class,
                () -> TestSupport.newStoreWithReclaim(0, false, 0, 1.5));
        log.info("invalid ratio rejected at startup: {}", badRatio.getMessage());
        IllegalArgumentException badBytes = assertThrows(IllegalArgumentException.class,
                () -> TestSupport.newStoreWithReclaim(0, false, -1, 0));
        log.info("negative bytes rejected at startup: {}", badBytes.getMessage());
    }
}
