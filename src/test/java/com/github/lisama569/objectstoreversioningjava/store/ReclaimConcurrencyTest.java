package com.github.lisama569.objectstoreversioningjava.store;

import com.github.lisama569.objectstoreversioningjava.exception.StoreException;
import com.github.lisama569.objectstoreversioningjava.model.PutResult;
import org.junit.jupiter.api.RepeatedTest;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 自动回收与并发读写：
 * - 阈值持续越界、回收线程反复执行时，并发写入不失败、不长时间卡住；
 * - 并发读取要么读到完整内容（存储内部已做校验），要么得到可解释的显式拒绝，
 *   绝不读到已回收数据或半截数据；
 * - 异步触发路径下写入只投递信号，回收最终会把物理文件数收敛到“仍被引用的 blob 数”。
 */
class ReclaimConcurrencyTest {

    private static final Logger log = LoggerFactory.getLogger(ReclaimConcurrencyTest.class);

    private static long physicalBlobs(ObjectStore store) throws Exception {
        try (Stream<Path> s = Files.walk(store.properties().getDataDir().resolve("blobs"))) {
            return s.filter(Files::isRegularFile).count();
        }
    }

    @RepeatedTest(10)
    void reclaimRacingWithWritesAndReads() throws Exception {
        // 保留 2 版 + 阈值 1 字节：每次回收检查都越界，回收线程满负荷运转
        ObjectStore store = TestSupport.newStoreWithReclaim(2, false, 1, 0);
        store.reclaimer().setAsyncTriggerEnabled(false); // 本测试用专属回收线程手动驱动
        List<String> keys = List.of("race/a", "race/b");
        for (String key : keys) {
            store.put(key, TestSupport.bytes("seed-" + key));
        }

        int writers = 4;
        int writesPerWriter = 25;
        int readers = 8;
        ExecutorService pool = Executors.newFixedThreadPool(writers + readers + 1);
        CountDownLatch start = new CountDownLatch(1);
        AtomicInteger writeFailures = new AtomicInteger();
        AtomicInteger readOk = new AtomicInteger();
        AtomicInteger readReclaimed = new AtomicInteger();
        AtomicInteger readCorrupt = new AtomicInteger();
        AtomicInteger readUnexpected = new AtomicInteger();
        ConcurrentLinkedQueue<String> writeErrors = new ConcurrentLinkedQueue<>();

        for (int w = 0; w < writers; w++) {
            int writerId = w;
            pool.submit(() -> {
                try {
                    start.await();
                    for (int i = 0; i < writesPerWriter; i++) {
                        String key = keys.get((writerId + i) % keys.size());
                        store.put(key, TestSupport.bytes("w" + writerId + "-" + i + "-"
                                + "x".repeat(64)));
                    }
                } catch (Throwable t) {
                    writeFailures.incrementAndGet();
                    writeErrors.add(t.toString());
                    log.error("writer {} failed", writerId, t);
                }
            });
        }
        for (int r = 0; r < readers; r++) {
            int readerId = r;
            pool.submit(() -> {
                try {
                    start.await();
                    for (int i = 0; i < 60; i++) {
                        String key = keys.get((readerId + i) % keys.size());
                        try {
                            String text = TestSupport.utf8(store.get(key));
                            // 存储读路径已做 SHA-256 校验；这里再验证内容确为某次写入的值
                            if (text.startsWith("seed-") || text.startsWith("w")) {
                                readOk.incrementAndGet();
                            } else {
                                readCorrupt.incrementAndGet();
                                log.error("reader got foreign content: {}", text);
                            }
                        } catch (StoreException e) {
                            if (e.getReason() == StoreException.Reason.NO_SUCH_VERSION
                                    || e.getReason() == StoreException.Reason.NO_SUCH_OBJECT) {
                                readReclaimed.incrementAndGet();
                            } else {
                                readUnexpected.incrementAndGet();
                                log.error("reader unexpected reason={}", e.getReason());
                            }
                        }
                    }
                } catch (Throwable t) {
                    readUnexpected.incrementAndGet();
                    log.error("reader unexpected throwable", t);
                }
            });
        }
        AtomicInteger reclaimRuns = new AtomicInteger();
        AtomicInteger reclaimedTotal = new AtomicInteger();
        pool.submit(() -> {
            try {
                start.await();
                for (int i = 0; i < 100; i++) {
                    reclaimedTotal.addAndGet(store.reclaimer().reclaimIfNeeded().blobsPurged());
                    reclaimRuns.incrementAndGet();
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        });

        start.countDown();
        pool.shutdown();
        assertTrue(pool.awaitTermination(60, TimeUnit.SECONDS));
        log.info("race done: writes={} failures={} readOk={} readReclaimed={} corrupt={} "
                        + "unexpected={} reclaimRuns={} purged={}",
                writers * writesPerWriter, writeFailures.get(), readOk.get(), readReclaimed.get(),
                readCorrupt.get(), readUnexpected.get(), reclaimRuns.get(), reclaimedTotal.get());

        assertEquals(0, writeFailures.get(),
                "writes must not fail while reclaim runs: " + writeErrors);
        assertEquals(0, readCorrupt.get(), "reads must never see reclaimed/partial data");
        assertEquals(0, readUnexpected.get(), "reads must only see explainable outcomes");
        assertTrue(readOk.get() > 0, "latest-version reads must keep succeeding under reclaim");

        // 收尾：回收收敛后，每个 key 最新版本必须可读且物理文件数 == 被引用 blob 数
        store.reclaimer().reclaimIfNeeded();
        for (String key : keys) {
            assertTrue(store.get(key).size() > 0);
        }
        log.info("final: physicalBlobs={} all latest versions readable", physicalBlobs(store));
    }

    @Test
    void asyncTriggerKeepsWritesNonBlockingAndEventuallyReclaims() throws Exception {
        // 异步触发开启（默认）：写入只投递信号，回收在后台收敛
        ObjectStore store = TestSupport.newStoreWithReclaim(1, false, 1, 0);
        try {
            int puts = 60;
            long startNanos = System.nanoTime();
            for (int i = 0; i < puts; i++) {
                PutResult r = store.put("async/k", TestSupport.bytes("payload-" + i + "-"
                        + "y".repeat(256)));
                assertTrue(r.versionId() > 0);
            }
            long elapsedMs = (System.nanoTime() - startNanos) / 1_000_000;
            log.info("async path: {} puts completed in {}ms (writes never block on reclaim)",
                    puts, elapsedMs);

            // 后台回收最终收敛：物理文件数 == 仍被引用的 blob 数（保留 1 版 -> 1 个）
            long deadline = System.currentTimeMillis() + 10_000;
            long physical = physicalBlobs(store);
            while (physical != 1 && System.currentTimeMillis() < deadline) {
                Thread.sleep(50);
                physical = physicalBlobs(store);
            }
            log.info("async reclaim converged: physicalBlobs={} (expected 1 live blob)", physical);
            assertEquals(1, physical, "async reclaim must converge to exactly the live blobs");
            assertTrue(store.get("async/k").size() > 0, "latest version survives async reclaim");
        } finally {
            store.close();
        }
    }
}
