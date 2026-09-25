package com.github.lisama569.objectstoreversioningjava.store;

import com.github.lisama569.objectstoreversioningjava.exception.StoreException;
import com.github.lisama569.objectstoreversioningjava.model.PutResult;
import org.junit.jupiter.api.RepeatedTest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 回收与并发读竞态测试。
 *
 * <p>可解释的线性化规则：
 * <ul>
 *   <li>读的引脚发生在 GC 物理删除<b>之前</b>：读必成功且字节完整（文件在引脚期间绝不允许被删）；</li>
 *   <li>读的引脚发生在物理删除<b>之后</b>：blob 已注销，pin 被拒，读以 {@code NO_SUCH_VERSION}
 *       （版本数据已回收）显式失败——绝不允许出现“文件被删但读半截/IO 错”这类不可解释结果；</li>
 *   <li>仍被任何版本引用的 blob，purge 永不删除。</li>
 * </ul>
 */
class PurgeReadRaceTest {

    private static final Logger log = LoggerFactory.getLogger(PurgeReadRaceTest.class);

    @RepeatedTest(30)
    void purgeRacingWithReads_eitherFullContentOrExplicitReclaim() throws Exception {
        // 保留 1 个版本：每次新写入都会让上一个 blob 引用归零成为待删候选
        ObjectStore store = TestSupport.newStore(1, false);
        String firstContent = "race-body-0-" + "z".repeat(128);
        PutResult first = store.put("race/k", TestSupport.bytes(firstContent));
        String oldBlobId = first.blobId();
        log.info("setup: first version={} blobId={} (will become unreferenced after next put)",
                first.versionId(), oldBlobId.substring(0, 12));

        int readers = 24;
        ExecutorService pool = Executors.newFixedThreadPool(readers + 2);
        CountDownLatch start = new CountDownLatch(1);
        AtomicInteger ok = new AtomicInteger();
        AtomicInteger reclaimed = new AtomicInteger();
        AtomicInteger corrupt = new AtomicInteger();
        AtomicInteger other = new AtomicInteger();

        for (int i = 0; i < readers; i++) {
            pool.submit(() -> {
                try {
                    start.await();
                    // 随机读旧版本（即将被回收）与最新版本
                    try {
                        String text = TestSupport.utf8(store.getVersion("race/k", first.versionId()));
                        if (firstContent.equals(text)) {
                            ok.incrementAndGet();
                        } else {
                            corrupt.incrementAndGet();
                            log.error("reader got WRONG content len={}", text.length());
                        }
                    } catch (StoreException e) {
                        if (e.getReason() == StoreException.Reason.NO_SUCH_VERSION
                                || e.getReason() == StoreException.Reason.NO_SUCH_OBJECT) {
                            reclaimed.incrementAndGet();
                        } else {
                            other.incrementAndGet();
                            log.error("reader unexpected reason={}", e.getReason());
                        }
                    }
                } catch (Throwable t) {
                    other.incrementAndGet();
                    log.error("reader unexpected throwable", t);
                }
            });
        }

        // 写者：让旧 blob 失去引用
        CountDownLatch writerDone = new CountDownLatch(1);
        pool.submit(() -> {
            try {
                start.await();
                store.put("race/k", TestSupport.bytes("race-body-1-" + "y".repeat(128)));
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            } finally {
                writerDone.countDown();
            }
        });

        // 回收者：反复物理回收
        CountDownLatch purgerDone = new CountDownLatch(1);
        pool.submit(() -> {
            try {
                start.await();
                int total = 0;
                for (int i = 0; i < 50; i++) {
                    total += store.purgeUnreferenced();
                }
                log.info("purger removed {} blob(s) in total", total);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            } finally {
                purgerDone.countDown();
            }
        });

        start.countDown();
        assertTrue(writerDone.await(30, TimeUnit.SECONDS));
        assertTrue(purgerDone.await(30, TimeUnit.SECONDS));
        pool.shutdown();
        assertTrue(pool.awaitTermination(30, TimeUnit.SECONDS));

        // 所有读者结束后再 drain 一次：回收者可能在旧引用释放前就跑完了循环，
        // 按“purge 只删除 purge 时刻已待删的 blob”这一语义，最终态由这次 drain 决定。
        int drained = store.purgeUnreferenced();
        log.info("final drain removed={}", drained);

        log.info("race verdict: readers ok={} reclaimed={} corrupt={} other={}",
                ok.get(), reclaimed.get(), corrupt.get(), other.get());
        assertEquals(0, corrupt.get(), "no reader may ever observe wrong/partial data");
        assertEquals(0, other.get(), "no IO errors or unexpected exception types allowed");
        assertEquals(readers, ok.get() + reclaimed.get(),
                "every read must either fully succeed or be explicitly rejected as reclaimed");

        // 最终状态：旧 blob 一定已被物理删除，新 blob 一定完好可读
        assertTrue(!store.blobStore().exists(oldBlobId), "old unreferenced blob must eventually be purged");
        String latest = TestSupport.utf8(store.get("race/k"));
        assertTrue(latest.startsWith("race-body-1-"), "newest version remains intact: " + latest.length());
        assertEquals(0, store.registry().pinCount(first.blobId()));
        log.info("final state: oldBlob purged, newest readable, pins leaked=0");
    }

    @RepeatedTest(10)
    void blobPinnedByReader_isNeverDeletedUnderPurge() throws Exception {
        // 真实模拟：读者线程持有“读门 + 读引脚”期间，另一线程尝试物理回收——必须被阻塞到读结束；
        // 读结束后该 blob 已无引用，回收才生效。全程不允许读到坏数据/文件缺失错误。
        ObjectStore store = TestSupport.newStore(1, false);
        PutResult v = store.put("pin/k", TestSupport.bytes("pinned-content-need-survive"));
        String blobId = v.blobId();

        CountDownLatch pinned = new CountDownLatch(1);
        CountDownLatch releasePin = new CountDownLatch(1);
        AtomicInteger readFailures = new AtomicInteger();

        Thread reader = new Thread(() -> {
            store.registry().readLock();
            boolean ok = false;
            try {
                if (store.registry().pinForRead(blobId)) {
                    ok = true;
                    pinned.countDown();
                    releasePin.await();
                    // 引脚持有期间文件必须完好可读
                    try {
                        String text = TestSupport.utf8(store.blobStore().get(blobId));
                        if (!"pinned-content-need-survive".equals(text)) {
                            readFailures.incrementAndGet();
                        }
                    } catch (Throwable t) {
                        readFailures.incrementAndGet();
                        log.error("pinned read failed", t);
                    }
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            } finally {
                if (ok) {
                    store.registry().unpin(blobId);
                }
                store.registry().readUnlock();
            }
        }, "pinned-reader");
        reader.start();
        assertTrue(pinned.await(10, TimeUnit.SECONDS), "reader must acquire pin");

        // 在读者持有引脚时，从另一线程裁剪掉唯一版本（计数归零）并尝试 purge。
        // 用一个更新版本触发保留裁剪，使旧 blob 引用归零。
        PutResult v2 = store.put("pin/k", TestSupport.bytes("replacement-content"));
        assertEquals(0, store.registry().referenceCount(blobId), "old version pruned -> refs=0");
        assertTrue(store.registry().isPending(blobId));

        Thread purger = new Thread(() -> store.purgeUnreferenced(), "purger");
        purger.start();
        // 给 purger 时间去竞争写门：它应当被读者的读门挡住，文件此刻必须还在
        Thread.sleep(50);
        assertTrue(purger.isAlive(), "purger must be blocked while reader holds the read gate");
        assertTrue(store.blobStore().exists(blobId), "pinned blob cannot be deleted mid-read");
        log.info("purger blocked by in-flight reader as expected; old blob still on disk");

        releasePin.countDown();
        reader.join(10_000);
        purger.join(10_000);
        assertEquals(0, readFailures.get(), "pinned reader must always observe intact data");
        assertTrue(!store.blobStore().exists(blobId), "blob reclaimed only after read finished");
        assertTrue(store.blobStore().exists(v2.blobId()), "replacement blob intact");
        assertEquals("replacement-content", TestSupport.utf8(store.get("pin/k")));
        assertEquals(0, store.registry().pinCount(blobId));
        log.info("verdict: purge waited for reader; file deleted only after read release; no leaks");
    }
}
