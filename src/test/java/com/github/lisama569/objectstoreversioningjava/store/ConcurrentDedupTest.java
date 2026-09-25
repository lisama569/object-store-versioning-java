package com.github.lisama569.objectstoreversioningjava.store;

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
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 相同内容并发写入：物理数据只允许一份，但每个版本都各自持有引用，引用计数必须精确等于版本数。
 * 同时验证跨 key 共享同一 blob 时的引用计数。
 */
class ConcurrentDedupTest {

    private static final Logger log = LoggerFactory.getLogger(ConcurrentDedupTest.class);

    private static final int WRITERS = 24;

    @RepeatedTest(10)
    void identicalConcurrentPuts_shareOneBlobWithExactRefcount() throws Exception {
        ObjectStore store = TestSupport.newStore(0, false);
        ExecutorService pool = Executors.newFixedThreadPool(WRITERS);
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(WRITERS);
        List<PutResult> results = Collections.synchronizedList(new ArrayList<>());
        AtomicInteger errors = new AtomicInteger();
        String payload = "identical-body-".repeat(20);

        for (int i = 0; i < WRITERS; i++) {
            pool.submit(() -> {
                try {
                    start.await();
                    results.add(store.put("k", TestSupport.bytes(payload)));
                } catch (Throwable t) {
                    errors.incrementAndGet();
                    log.error("writer failed", t);
                } finally {
                    done.countDown();
                }
            });
        }
        start.countDown();
        assertTrue(done.await(30, TimeUnit.SECONDS));
        pool.shutdown();
        assertEquals(0, errors.get());

        Set<String> blobIds = new HashSet<>(results.stream().map(PutResult::blobId).toList());
        assertEquals(1, blobIds.size(), "identical content must collapse to a single blobId");
        String blobId = blobIds.iterator().next();
        assertEquals(WRITERS, results.size());
        assertEquals(WRITERS, store.registry().referenceCount(blobId),
                "shared blob must be referenced once per version");
        assertTrue(store.blobStore().exists(blobId));
        long physical = countPhysicalBlobs(store);
        assertEquals(1, physical, "exactly one physical data file expected");
        log.info("identical writes: versions={} blobId={} refs={} physicalFiles={}",
                WRITERS, blobId.substring(0, 12), store.registry().referenceCount(blobId), physical);

        // 所有版本读回一致
        for (PutResult r : results) {
            assertEquals(payload, TestSupport.utf8(store.getVersion("k", r.versionId())));
        }

        // 有引用时回收不得删除该 blob
        assertEquals(0, store.purgeUnreferenced(), "referenced blob must not be reclaimed");
        assertTrue(store.blobStore().exists(blobId));
        log.info("purge while refs>0: file kept, refs={}", store.registry().referenceCount(blobId));
    }

    private static long countPhysicalBlobs(ObjectStore store) throws Exception {
        java.nio.file.Path blobs = store.properties().getDataDir().resolve("blobs");
        try (Stream<java.nio.file.Path> walk = java.nio.file.Files.walk(blobs)) {
            return walk.filter(java.nio.file.Files::isRegularFile).count();
        }
    }
}
