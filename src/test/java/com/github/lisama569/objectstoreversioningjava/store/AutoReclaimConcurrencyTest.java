package com.github.lisama569.objectstoreversioningjava.store;

import com.github.lisama569.objectstoreversioningjava.config.ObjectStoreProperties;
import com.github.lisama569.objectstoreversioningjava.exception.StoreException;
import com.github.lisama569.objectstoreversioningjava.model.ObjectVersion;
import com.github.lisama569.objectstoreversioningjava.model.PutResult;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 容量阈值触发的自动回收 + 并发读写：
 * - 越限即触发“保留裁剪 + 物理回收”，写入不失败、不长时间卡死；
 * - 并发读要么读到完整内容，要么得到可解释的显式拒绝；
 * - 回收中途失败后重试：不重复计数、不误删仍有引用的数据。
 */
class AutoReclaimConcurrencyTest {

    private static final Logger log = LoggerFactory.getLogger(AutoReclaimConcurrencyTest.class);

    private static long physicalBytes(ObjectStore store) throws Exception {
        try (Stream<Path> s = Files.walk(store.properties().getDataDir().resolve("blobs"))) {
            return s.filter(Files::isRegularFile).mapToLong(p -> p.toFile().length()).sum();
        }
    }

    @Test
    void capacityThresholdTriggersReclaimUnderConcurrentLoad() throws Exception {
        // 每 key 只留 2 个版本；已跟踪字节超过 4KB 即自动回收
        ObjectStoreProperties props = TestSupport.newProps();
        props.setMaxVersionsPerKey(2);
        props.setReclaimMaxBytes(4096);
        ObjectStore store = TestSupport.newStoreWithClock(props, new TestSupport.MutableClock(0));
        log.info("setup: maxVersionsPerKey=2 reclaimMaxBytes=4096 autoPurge=false");

        int writers = 8;
        int rounds = 25;
        int keys = 4;
        ExecutorService pool = Executors.newFixedThreadPool(writers + 4);
        CountDownLatch start = new CountDownLatch(1);
        AtomicInteger writeFailures = new AtomicInteger();
        AtomicInteger corruptReads = new AtomicInteger();
        AtomicInteger explicitRejects = new AtomicInteger();
        AtomicInteger okReads = new AtomicInteger();
        AtomicLong versionsCreated = new AtomicLong();

        for (int w = 0; w < writers; w++) {
            int writerId = w;
            pool.submit(() -> {
                try {
                    start.await();
                    for (int r = 0; r < rounds; r++) {
                        String key = "k" + (writerId % keys);
                        String body = "w" + writerId + "-r" + r + "-" + "x".repeat(200);
                        store.put(key, TestSupport.bytes(body));
                        versionsCreated.incrementAndGet();
                    }
                } catch (Throwable t) {
                    writeFailures.incrementAndGet();
                    log.error("writer {} failed", writerId, t);
                }
            });
        }
        // 读者：并发读各 key 最新版；内容要么完整匹配已知前缀，要么显式拒绝
        for (int i = 0; i < 4; i++) {
            pool.submit(() -> {
                try {
                    start.await();
                    for (int r = 0; r < 200; r++) {
                        String key = "k" + (r % keys);
                        try {
                            String text = TestSupport.utf8(store.get(key));
                            if (text.matches("w\\d+-r\\d+-x+")) {
                                okReads.incrementAndGet();
                            } else {
                                corruptReads.incrementAndGet();
                                log.error("reader got WRONG content: {}", text.substring(0, 20));
                            }
                        } catch (StoreException e) {
                            explicitRejects.incrementAndGet();
                        }
                    }
                } catch (Throwable t) {
                    log.error("reader failed", t);
                }
            });
        }

        long t0 = System.nanoTime();
        start.countDown();
        pool.shutdown();
        assertTrue(pool.awaitTermination(60, TimeUnit.SECONDS), "all tasks must finish");
        long elapsedMs = (System.nanoTime() - t0) / 1_000_000;
        log.info("load finished in {}ms: versions={} writeFailures={} reads ok={} rejected={} corrupt={}",
                elapsedMs, versionsCreated.get(), writeFailures.get(), okReads.get(),
                explicitRejects.get(), corruptReads.get());

        assertEquals(0, writeFailures.get(), "reclaim must never fail or wedge concurrent writes");
        assertEquals(0, corruptReads.get(), "no reader may observe wrong/partial content");
        assertTrue(elapsedMs < 30_000, "writes must not be blocked long by reclamation");

        // 终态对账：注册表字节账 == 磁盘实际字节；引用账 == 各 key 数据版本数
        long tracked = store.registry().trackedBytes();
        long onDisk = physicalBytes(store);
        int dataVersions = 0;
        int refSum = 0;
        for (int k = 0; k < keys; k++) {
            for (ObjectVersion v : store.listVersions("k" + k)) {
                if (!v.deleteMarker()) {
                    dataVersions++;
                    refSum += store.registry().referenceCount(v.blobId());
                }
            }
        }
        log.info("final ledger: trackedBytes={} onDiskBytes={} dataVersions={} refSum={}",
                tracked, onDisk, dataVersions, refSum);
        assertEquals(onDisk, tracked, "tracked bytes must equal physical bytes on disk");
        assertEquals(dataVersions, refSum,
                "every retained data version holds exactly one reference");
    }

    @Test
    void failedReclaimRetriesWithoutDoubleCountingOrTouchingReferencedData() throws Exception {
        ObjectStore store = TestSupport.newStore(1, false);
        PutResult live = store.put("live", TestSupport.bytes("live-content"));
        PutResult doomed1 = store.put("tmp", TestSupport.bytes("doomed-content"));
        store.put("tmp", TestSupport.bytes("tmp-replacement")); // doomed1 被裁，引用归零
        String doomedBlob = doomed1.blobId();
        assertTrue(store.registry().isPending(doomedBlob));
        long bytesBefore = store.registry().trackedBytes();
        log.info("setup: live blob={} doomed blob={} pending, trackedBytes={}",
                live.blobId().substring(0, 12), doomedBlob.substring(0, 12), bytesBefore);

        // 注入删除失败：本轮回收对 doomed 失败，但绝不能碰还有引用的 live
        FileSystemBlobStore fs = (FileSystemBlobStore) store.blobStore();
        AtomicInteger deleteAttempts = new AtomicInteger();
        fs.beforeDeleteHook = id -> {
            deleteAttempts.incrementAndGet();
            throw new IllegalStateException("injected delete failure");
        };
        int removed = store.purgeUnreferenced();
        assertEquals(0, removed, "failed delete must not be counted");
        assertTrue(store.blobStore().exists(doomedBlob), "failed delete leaves file in place");
        assertTrue(store.registry().isPending(doomedBlob), "failed delete keeps registration");
        assertEquals(bytesBefore, store.registry().trackedBytes(), "no byte accounting drift");
        assertTrue(store.blobStore().exists(live.blobId()), "referenced blob untouched");
        assertEquals(1, deleteAttempts.get(), "only the pending blob was a delete candidate");
        log.info("judgement: failed purge counted 0, doomed still registered, live intact");

        // 再跑两次失败回收：依然 0，不重复计数
        assertEquals(0, store.purgeUnreferenced());
        fs.beforeDeleteHook = null;

        // 故障解除后重试：精确删除 1 个，字节账归零到 live 的大小
        int retried = store.purgeUnreferenced();
        assertEquals(1, retried, "retry reclaims exactly the one pending blob");
        assertEquals(0, store.purgeUnreferenced(), "third run has nothing left to do");
        assertTrue(!store.blobStore().exists(doomedBlob));
        assertEquals("live-content", TestSupport.utf8(store.get("live")),
                "referenced data survives the whole failure/retry cycle");
        assertEquals(store.registry().trackedBytes(), physicalBytes(store));
        log.info("verdict: retry removed=1, no double count, referenced data intact, ledger balanced");
    }

    @Test
    void usageRatioThresholdSemanticsAndDisabledBoundaries() throws Exception {
        // 比例阈值：极小正数 => 磁盘只要有占用就触发（确定性地验证“会触发”）
        ObjectStoreProperties props = TestSupport.newProps();
        props.setMaxVersionsPerKey(1);
        props.setReclaimMaxUsageRatio(1e-9);
        ObjectStore store = TestSupport.newStoreWithClock(props, new TestSupport.MutableClock(0));

        PutResult old = store.put("k", TestSupport.bytes("old-content"));
        store.put("k", TestSupport.bytes("new-content")); // 裁掉 old，触发比例回收
        log.info("ratio=1e-9: old blob={} pending after prune", old.blobId().substring(0, 12));
        assertTrue(!store.blobStore().exists(old.blobId()),
                "tiny ratio must trigger reclaim right after the mutating put");
        assertEquals("new-content", TestSupport.utf8(store.get("k")));

        // 关闭边界：ratio=0 与 ratio>1 都表示关闭，只能手动回收
        for (double disabled : new double[]{0.0, 1.5}) {
            ObjectStoreProperties p2 = TestSupport.newProps();
            p2.setMaxVersionsPerKey(1);
            p2.setReclaimMaxUsageRatio(disabled);
            ObjectStore s2 = TestSupport.newStoreWithClock(p2, new TestSupport.MutableClock(0));
            PutResult o = s2.put("k", TestSupport.bytes("old"));
            s2.put("k", TestSupport.bytes("new"));
            assertTrue(s2.blobStore().exists(o.blobId()),
                    "ratio=" + disabled + " must disable auto reclaim");
            assertEquals(1, s2.purgeUnreferenced(), "manual purge still works");
            log.info("ratio={} -> auto reclaim disabled, manual purge reclaimed 1", disabled);
        }
        log.info("verdict: ratio threshold triggers when exceeded; 0 and >1 disable it");
    }
}
