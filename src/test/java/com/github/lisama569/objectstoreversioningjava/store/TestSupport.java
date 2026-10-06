package com.github.lisama569.objectstoreversioningjava.store;

import com.github.lisama569.objectstoreversioningjava.config.ObjectStoreProperties;
import com.github.lisama569.objectstoreversioningjava.model.BlobContent;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.concurrent.atomic.AtomicLong;

/** 测试工具：临时目录构造 ObjectStore 及常用编解码。 */
final class TestSupport {

    private TestSupport() {
    }

    static ObjectStore newStore(int maxVersions, boolean autoPurge) throws IOException {
        Path dir = Files.createTempDirectory("ostore-test-");
        ObjectStoreProperties props = new ObjectStoreProperties();
        props.setDataDir(dir);
        props.setMaxVersionsPerKey(maxVersions);
        props.setAutoPurge(autoPurge);
        return new ObjectStore(props);
    }

    /** 以可推进的时钟构造 store，用于确定性地控制版本年龄（保留规则的时长维度）。 */
    static ObjectStore newStoreWithClock(ObjectStoreProperties props, MutableClock clock)
            throws IOException {
        if (props.getDataDir() == null) {
            props.setDataDir(Files.createTempDirectory("ostore-test-"));
        }
        return new ObjectStore(props, clock);
    }

    static ObjectStoreProperties newProps() throws IOException {
        ObjectStoreProperties props = new ObjectStoreProperties();
        props.setDataDir(Files.createTempDirectory("ostore-test-"));
        return props;
    }

    /** 可手动推进的时钟。 */
    static final class MutableClock extends Clock {
        private final AtomicLong millis;

        MutableClock(long initialMillis) {
            this.millis = new AtomicLong(initialMillis);
        }

        void advanceBy(long deltaMillis) {
            millis.addAndGet(deltaMillis);
        }

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return Instant.ofEpochMilli(millis.get());
        }
    }

    static ByteBuffer bytes(String s) {
        return ByteBuffer.wrap(s.getBytes(StandardCharsets.UTF_8));
    }

    static String utf8(BlobContent content) {
        ByteBuffer dup = content.data().duplicate();
        byte[] arr = new byte[dup.remaining()];
        dup.get(arr);
        return new String(arr, StandardCharsets.UTF_8);
    }
}
