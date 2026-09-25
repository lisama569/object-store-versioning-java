package com.github.lisama569.objectstoreversioningjava.tests;

import com.github.lisama569.objectstoreversioningjava.service.DefaultGarbageCollector;
import com.github.lisama569.objectstoreversioningjava.service.DefaultObjectStore;
import com.github.lisama569.objectstoreversioningjava.service.GarbageCollector;
import com.github.lisama569.objectstoreversioningjava.service.ObjectStore;
import com.github.lisama569.objectstoreversioningjava.service.StoreLocks;
import com.github.lisama569.objectstoreversioningjava.storage.InMemoryMetadataStore;
import com.github.lisama569.objectstoreversioningjava.storage.LocalBlobStorage;

import java.nio.file.Path;
import java.util.function.Supplier;

/** 为每个测试构造独立临时目录的真实存储组件（非 mock）。 */
final class StoreTestSupport {

    private StoreTestSupport() {
    }

    record Harness(ObjectStore store, GarbageCollector gc, LocalBlobStorage blobs,
                   InMemoryMetadataStore metadata, StoreLocks locks, Path dir) {
    }

    static Harness newHarness() {
        Path dir = Path.of("target", "test-data", String.valueOf(System.nanoTime()));
        StoreLocks locks = new StoreLocks();
        LocalBlobStorage blobs = new LocalBlobStorage(dir.resolve("blobs-root"));
        InMemoryMetadataStore metadata = new InMemoryMetadataStore();
        DefaultObjectStore store = new DefaultObjectStore(blobs, metadata, locks);
        DefaultGarbageCollector gc = new DefaultGarbageCollector(blobs, metadata, locks);
        return new Harness(store, gc, blobs, metadata, locks, dir);
    }

    static void log(String phase, String fmt, Object... args) {
        System.out.println("[" + phase + "] " + String.format(fmt, args));
    }

    static void log(String phase, Supplier<String> msg) {
        System.out.println("[" + phase + "] " + msg.get());
    }
}
