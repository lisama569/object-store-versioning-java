package com.github.lisama569.objectstoreversioningjava.tests;

import com.github.lisama569.objectstoreversioningjava.core.ObjectStoreException;
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
 * 并发覆盖写入同一对象：
 * 1) 不丢更新——版本数=写入次数，版本号 1..N 连续且每次写入都被采纳为某一版本；
 * 2) 相同内容只保留一份底层数据——磁盘块数=去重后内容数；
 * 3) 并发读不到半截数据——读到的字节其 SHA-256 必须等于该版本声明的内容地址。
 */
class ConcurrentOverwriteTest {

    private static byte[] payload(int writer, int size) {
        StringBuilder sb = new StringBuilder(size);
        sb.append("writer-").append(writer).append(':');
        while (sb.length() < size) {
            sb.append('x');
        }
        return sb.substring(0, size).getBytes(StandardCharsets.UTF_8);
    }

    @Test
    void concurrentDistinctWrites_noLostUpdate_noPartialRead() throws Exception {
        var h = StoreTestSupport.newHarness();
        int writers = 16;
        int readers = 8;
        int rounds = 20;
        ExecutorService pool = Executors.newFixedThreadPool(writers + readers);
        CountDownLatch start = new CountDownLatch(1);
        AtomicInteger done = new AtomicInteger();

        List<ObjectVersion> allWrites = Collections.synchronizedList(new ArrayList<>());
        List<String> readChecks = Collections.synchronizedList(new ArrayList<>());
        AtomicInteger checksumReReads = new AtomicInteger();

        for (int w = 0; w < writers; w++) {
            final int id = w;
            pool.submit(() -> {
                try {
                    start.await();
                    for (int r = 0; r < rounds; r++) {
                        byte[] data = payload(id, 64 + r);
                        ObjectVersion v = h.store().put("obj/concurrent", data);
                        allWrites.add(v);
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            });
        }
        for (int r = 0; r < readers; r++) {
            pool.submit(() -> {
                try {
                    start.await();
                    while (done.get() < writers * rounds) {
                        try {
                            StoredContent c = h.store().get("obj/concurrent");
                            // 判定依据：对读到的字节重新摘要，必须与版本声明的内容地址一致，
                            // 否则就是读到了半截/损坏对象。
                            String actual = LocalBlobStorage.sha256Hex(c.data());
                            if (actual.equals(c.contentId().sha256())) {
                                readChecks.add("OK v" + c.version().value());
                            } else {
                                readChecks.add("CORRUPT v" + c.version().value());
                            }
                            checksumReReads.incrementAndGet();
                        } catch (ObjectStoreException notThereYet) {
                            // 对象尚未发布时读不到（空安全），不是半截数据。
                        }
                        if (allWrites.size() >= writers * rounds && done.get() == 0) {
                            // 写者即将结束
                        }
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            });
        }

        start.countDown();
        pool.shutdown();
        // 先等写者完成并标记
        for (int i = 0; i < 600; i++) {
            if (allWrites.size() >= writers * rounds) {
                done.set(writers * rounds);
                break;
            }
            Thread.sleep(10);
        }
        done.set(writers * rounds);
        assertTrue(pool.awaitTermination(30, TimeUnit.SECONDS), "线程未在时限内结束");

        StoreTestSupport.log("CONCURRENT", "写入次数=%d，返回版本数=%d，并发读复验次数=%d",
                writers * rounds, allWrites.size(), checksumReReads.get());

        // 断言 1：无丢更新——版本号恰好覆盖 1..N
        Set<Long> versionNumbers = new HashSet<>();
        Set<String> distinctContents = new HashSet<>();
        for (ObjectVersion v : allWrites) {
            versionNumbers.add(v.version().value());
            distinctContents.add(v.contentId().sha256());
        }
        assertEquals(writers * rounds, versionNumbers.size(), "版本号有丢失或重复");
        for (long i = 1; i <= writers * rounds; i++) {
            assertTrue(versionNumbers.contains(i), "缺失版本 v" + i);
        }
        StoreTestSupport.log("CONCURRENT", "版本号 1..%d 全部存在，无丢更新；不同内容数=%d",
                versionNumbers.size(), distinctContents.size());

        // 断言 2：磁盘块数 == 不同内容数（去重 + 无垃圾）
        int onDisk = h.blobs().listAll().size();
        StoreTestSupport.log("CONCURRENT", "判定：磁盘块数=%d，去重后内容数=%d（要求相等）",
                onDisk, distinctContents.size());
        assertEquals(distinctContents.size(), onDisk);

        // 断言 3：所有并发读都通过摘要复验（无 CORRUPT、无半截）
        long corrupt = readChecks.stream().filter(s -> s.startsWith("CORRUPT")).count();
        StoreTestSupport.log("CONCURRENT", "并发读校验：成功=%d，损坏=%d（要求损坏=0）",
                readChecks.size() - corrupt, corrupt);
        assertEquals(0, corrupt);
        assertTrue(readChecks.size() > 0, "读者应实际读到过对象");

        // 元数据链最终长度与最新版本一致
        List<ObjectVersion> chain = h.store().listVersions("obj/concurrent");
        assertEquals(writers * rounds, chain.size());
        StoreTestSupport.log("CONCURRENT", "最终版本链长度=%d，最新版本=%s",
                chain.size(), chain.get(chain.size() - 1).describe());
    }

    @Test
    void concurrentIdenticalWrites_dedupeToOneBlob() throws Exception {
        var h = StoreTestSupport.newHarness();
        int n = 12;
        ExecutorService pool = Executors.newFixedThreadPool(n);
        CountDownLatch start = new CountDownLatch(1);
        List<ObjectVersion> versions = Collections.synchronizedList(new ArrayList<>());
        byte[] same = "identical-content".getBytes(StandardCharsets.UTF_8);
        for (int i = 0; i < n; i++) {
            pool.submit(() -> {
                try {
                    start.await();
                    versions.add(h.store().put("dup", same));
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            });
        }
        start.countDown();
        pool.shutdown();
        assertTrue(pool.awaitTermination(20, TimeUnit.SECONDS));

        Set<String> contentIds = new HashSet<>();
        Set<Long> vn = new HashSet<>();
        versions.forEach(v -> {
            contentIds.add(v.contentId().sha256());
            vn.add(v.version().value());
        });
        StoreTestSupport.log("DEDUP", "输入=%d 次相同内容；版本数=%d；不同内容地址=%d；磁盘块数=%d",
                n, vn.size(), contentIds.size(), h.blobs().listAll().size());
        assertEquals(n, vn.size(), "相同内容的并发写也必须各自成为版本（不丢更新）");
        assertEquals(1, contentIds.size(), "相同内容只能映射到一个内容地址");
        assertEquals(1, h.blobs().listAll().size(), "底层数据只能保留一份");
    }
}
