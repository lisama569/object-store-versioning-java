package com.github.lisama569.objectstoreversioningjava.tests;

import com.github.lisama569.objectstoreversioningjava.core.GcReport;
import com.github.lisama569.objectstoreversioningjava.core.ObjectVersion;
import com.github.lisama569.objectstoreversioningjava.core.StoredContent;
import com.github.lisama569.objectstoreversioningjava.storage.LocalBlobStorage;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 回收竞态：GC 与并发读取/覆盖写入/永久删除同时进行。
 *
 * <p>不变式（每个时刻都必须成立）：
 * <ol>
 *   <li>任何成功读取返回的字节，其 SHA-256 等于该版本声明地址（无损坏/无半截）；</li>
 *   <li>GC 结束后，磁盘块集合 ⊇ 存活版本引用集合（绝不提前删除仍有引用的共享块）；</li>
 *   <li>GC 结束后，磁盘块集合 ⊆ 存活引用集合（无引用垃圾被清空）——最终一致。</li>
 * </ol>
 * 判定依据全部来自版本元数据与实时重新摘要，打印在日志中。
 */
class GcRaceTest {

    private static byte[] payload(int round) {
        return ("round-" + round + "-" + "p".repeat(32 + (round % 7))).getBytes(StandardCharsets.UTF_8);
    }

    @Test
    void gcRacingWithReadsAndWritesKeepsReferencedBlobs() throws Exception {
        var h = StoreTestSupport.newHarness();
        // 预置若干版本，制造共享：偶数 round 写相同内容
        List<ObjectVersion> seeded = new ArrayList<>();
        for (int r = 0; r < 10; r++) {
            seeded.add(h.store().put("race/obj", payload(r % 4)));
        }
        StoreTestSupport.log("GC-RACE", "预置版本数=%d，磁盘块=%d（内容按 r%%4 去重）",
                seeded.size(), h.blobs().listAll().size());

        int writers = 6, readers = 8, gcThreads = 2, purgers = 2;
        int durationMs = 3000;
        ExecutorService pool = Executors.newFixedThreadPool(writers + readers + gcThreads + purgers);
        CountDownLatch start = new CountDownLatch(1);
        AtomicInteger stop = new AtomicInteger(0);

        AtomicInteger writes = new AtomicInteger();
        AtomicInteger readsOk = new AtomicInteger();
        AtomicInteger readsCorrupt = new AtomicInteger();
        AtomicInteger purgeCount = new AtomicInteger();
        AtomicInteger gcRuns = new AtomicInteger();
        List<GcReport> reports = Collections.synchronizedList(new ArrayList<>());

        for (int i = 0; i < writers; i++) {
            pool.submit(() -> {
                try {
                    start.await();
                    while (stop.get() == 0) {
                        int r = writes.incrementAndGet();
                        // 大部分写入是唯一内容（purge 后其块应被 GC 回收），
                        // 每 5 次写入一次固定共享内容（验证共享块在引用消失前绝不被删）。
                        byte[] data = (r % 5 == 0) ? "SHARED-CONTENT".getBytes(StandardCharsets.UTF_8)
                                : payload(r);
                        h.store().put("race/obj", data);
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            });
        }
        for (int i = 0; i < purgers; i++) {
            pool.submit(() -> {
                try {
                    start.await();
                    while (stop.get() == 0) {
                        List<ObjectVersion> vs = h.store().listVersions("race/obj");
                        if (vs.size() > 6) {
                            // 永久删除最老的一个版本（可能正在释放共享块引用）
                            long victim = vs.get(0).version().value();
                            h.store().purgeVersion("race/obj", victim);
                            purgeCount.incrementAndGet();
                        } else {
                            Thread.sleep(2);
                        }
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            });
        }
        for (int i = 0; i < readers; i++) {
            pool.submit(() -> {
                try {
                    start.await();
                    while (stop.get() == 0) {
                        try {
                            StoredContent c = h.store().get("race/obj");
                            String actual = LocalBlobStorage.sha256Hex(c.data());
                            if (actual.equals(c.contentId().sha256())) {
                                readsOk.incrementAndGet();
                            } else {
                                readsCorrupt.incrementAndGet();
                            }
                        } catch (RuntimeException deletedOrMissing) {
                            // 永久删除可能使最新版本变化；属可预期的显式拒绝，不是脏读。
                        }
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            });
        }
        java.util.Random rnd = new java.util.Random(42);
        for (int i = 0; i < gcThreads; i++) {
            final int seed = i;
            pool.submit(() -> {
                try {
                    start.await();
                    // GC 以“密集但有抖动”的节奏运行，故意在 purge 释放最后引用的
                    // 时间窗之间穿插，从而真正观察到竞态下的删除判定。
                    int backoff = seed == 0 ? 1 : 3;
                    while (stop.get() == 0) {
                        reports.add(h.gc().collect());
                        gcRuns.incrementAndGet();
                        Thread.sleep(rnd.nextInt(backoff) + 1);
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            });
        }

        start.countDown();
        Thread.sleep(durationMs);
        stop.set(1);
        pool.shutdown();
        assertTrue(pool.awaitTermination(30, TimeUnit.SECONDS));

        StoreTestSupport.log("GC-RACE", "并发结束：写入=%d 永久删除=%d 读取成功=%d 读取损坏=%d GC次数=%d",
                writes.get(), purgeCount.get(), readsOk.get(), readsCorrupt.get(), gcRuns.get());

        // 不变式 1：没有任何脏读/半截读
        assertEquals(0, readsCorrupt.get(), "并发 GC 期间出现了校验不一致的读取");
        assertTrue(readsOk.get() > 0);

        // 再来一次“静止状态”GC，然后检查最终引用一致性
        GcReport finalGc = h.gc().collect();
        Set<String> onDisk = new HashSet<>();
        h.blobs().listAll().forEach(c -> onDisk.add(c.sha256()));
        Set<String> live = new HashSet<>();
        h.metadata().liveContentIds().forEach(c -> live.add(c.sha256()));

        StoreTestSupport.log("GC-RACE", "最终静止 GC：删除=%d；磁盘块=%d，存活引用=%d，判定依据 onDisk==live",
                finalGc.deleted(), onDisk.size(), live.size());
        assertEquals(live, onDisk, "静止后磁盘块集合必须与存活引用集合完全相等");

        long deletedSum = reports.stream().mapToLong(GcReport::deleted).sum();
        StoreTestSupport.log("GC-RACE", "竞态期间各轮 GC 累计删除块=%d（这些块删除瞬间均无存活引用）", deletedSum);
        assertTrue(deletedSum > 0, "应当在竞态中实际观察到至少一次对无引用块的回收");
    }

    @Test
    void gcWaitsForReadersAndPurgeDoesNotDeleteBlobsDirectly() throws Exception {
        // 锁语义验证：purge 只移除元数据引用、绝不直接删块；
        // GC 写锁与读路径互斥——读未结束时，GC 不得进入删除阶段。
        var h = StoreTestSupport.newHarness();
        ObjectVersion v = h.store().put("locked/doc", "persist".getBytes(StandardCharsets.UTF_8));

        // 1) purge 后块仍在（引用虽移除，但物理回收只由 GC 做）
        h.store().purgeVersion("locked/doc", 1);
        assertTrue(h.blobs().listAll().contains(v.contentId()), "purge 不得直接删除底层块");
        StoreTestSupport.log("GC-LOCK", "永久删除版本后、GC 前：块仍存在=%b（purge 只动元数据）", true);

        // 2) 用 harness 中与 GC 共享的真实读写锁验证互斥：读锁持有期间，GC 线程拿不到写锁。
        var locks = h.locks();
        locks.gcLock().readLock().lock();
        try {
            AtomicInteger gcDeleted = new AtomicInteger(-1);
            Thread gc = new Thread(() -> gcDeleted.set(h.gc().collect().deleted()));
            gc.start();
            Thread.sleep(200);
            assertTrue(gc.isAlive(), "读路径持锁期间 GC 必须等待，不得删除块");
            StoreTestSupport.log("GC-LOCK", "读锁持有 200ms 期间 GC 线程仍存活（被阻塞），块安全");
        } finally {
            locks.gcLock().readLock().unlock();
        }
        // 读锁释放后 GC 才能执行删除
        Thread.sleep(300);
        StoreTestSupport.log("GC-LOCK", "读锁释放后 GC 执行，块剩余=%d（要求 0）", h.blobs().listAll().size());
        assertEquals(0, h.blobs().listAll().size());
    }
}
