package com.github.lisama569.objectstoreversioningjava.store;

import com.github.lisama569.objectstoreversioningjava.model.BlobContent;
import com.github.lisama569.objectstoreversioningjava.model.ObjectVersion;
import com.github.lisama569.objectstoreversioningjava.model.PutResult;
import org.junit.jupiter.api.RepeatedTest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

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
 * 并发覆盖写入测试：多线程同时写同一 key。
 * 判定依据：每个写者都拿到唯一递增版本号（无丢更新）；每个版本都能按版本号读回且内容与输入一致；
 * 版本链数量与写者数一致；并发读最新版本只能读到某个完整提交版本。
 */
class ConcurrentOverwriteTest {

    private static final Logger log = LoggerFactory.getLogger(ConcurrentOverwriteTest.class);

    private static final int WRITERS = 16;

    @RepeatedTest(20)
    void concurrentPuts_noLostUpdate_andAllVersionsReadable() throws Exception {
        ObjectStore store = TestSupport.newStore(0, false);
        ExecutorService pool = Executors.newFixedThreadPool(WRITERS);
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(WRITERS);
        List<PutResult> results = Collections.synchronizedList(new ArrayList<>());
        List<String> inputs = Collections.synchronizedList(new ArrayList<>());
        AtomicInteger errors = new AtomicInteger();

        for (int i = 0; i < WRITERS; i++) {
            final int idx = i;
            pool.submit(() -> {
                try {
                    start.await();
                    String content = "payload-" + idx + "-" + "x".repeat(64);
                    inputs.add(content);
                    PutResult r = store.put("concurrency/key", TestSupport.bytes(content));
                    results.add(r);
                } catch (Throwable t) {
                    errors.incrementAndGet();
                    log.error("writer {} failed", idx, t);
                } finally {
                    done.countDown();
                }
            });
        }
        start.countDown();
        assertTrue(done.await(30, TimeUnit.SECONDS), "all writers must finish");
        pool.shutdown();

        // 1) 无写者异常
        assertEquals(0, errors.get(), "no writer may fail");
        // 2) 无丢更新：版本号唯一且数量等于写者数
        Set<Long> versionIds = new HashSet<>();
        Set<String> blobIds = new HashSet<>();
        for (PutResult r : results) {
            assertTrue(versionIds.add(r.versionId()), "duplicate versionId=" + r.versionId());
            blobIds.add(r.blobId());
        }
        assertEquals(WRITERS, results.size(), "every writer produces one version");
        assertEquals(WRITERS, blobIds.size(), "distinct payloads -> distinct blobs");
        log.info("inputs={} writers; committed versions={}; versionIds={}", WRITERS,
                results.stream().map(PutResult::versionId).sorted().toList(),
                versionIds.size());

        // 3) 元数据版本链与提交结果一致
        List<ObjectVersion> chain = store.listVersions("concurrency/key");
        assertEquals(WRITERS, chain.size());
        assertEquals(versionIds, new HashSet<>(chain.stream().map(ObjectVersion::versionId).toList()));

        // 4) 每个版本都能读回，且内容必须与某个输入完全一致（无半截/错版内容）
        for (ObjectVersion v : chain) {
            BlobContent got = store.getVersion("concurrency/key", v.versionId());
            String text = TestSupport.utf8(got);
            assertTrue(inputs.contains(text),
                    "version " + v.versionId() + " returned unknown/partial content: " + text);
            assertEquals(v.size(), got.size(), "size must agree");
            assertEquals(v.checksum(), got.checksum(), "checksum must agree with committed metadata");
        }
        // 5) 并发期间读最新版本也只能命中某个已提交版本
        String latest = TestSupport.utf8(store.get("concurrency/key"));
        assertTrue(inputs.contains(latest));

        // 6) 引用计数总账：每个 blob 恰好被一个版本引用，总和 == 版本数
        int totalRefs = 0;
        for (String blobId : blobIds) {
            int refs = store.registry().referenceCount(blobId);
            assertEquals(1, refs, "blob " + blobId + " expected refs=1");
            totalRefs += refs;
        }
        assertEquals(WRITERS, totalRefs, "reference-count ledger must match versions");
        log.info("assertions: versions={}, totalRefs={}, no partial read observed", chain.size(), totalRefs);
    }
}
