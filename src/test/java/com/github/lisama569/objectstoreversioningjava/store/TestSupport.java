package com.github.lisama569.objectstoreversioningjava.store;

import com.github.lisama569.objectstoreversioningjava.config.ObjectStoreProperties;
import com.github.lisama569.objectstoreversioningjava.model.BlobContent;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

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

    static ObjectStore newStoreWithGuards(int maxVersions, boolean autoPurge,
                                          int retentionMinVersions,
                                          long retentionMinRetentionMillis) throws IOException {
        Path dir = Files.createTempDirectory("ostore-test-");
        ObjectStoreProperties props = new ObjectStoreProperties();
        props.setDataDir(dir);
        props.setMaxVersionsPerKey(maxVersions);
        props.setAutoPurge(autoPurge);
        props.setRetentionMinVersions(retentionMinVersions);
        props.setRetentionMinRetentionMillis(retentionMinRetentionMillis);
        return new ObjectStore(props);
    }

    static ObjectStore newStoreWithReclaim(int maxVersions, boolean autoPurge,
                                           long reclaimMaxDiskBytes,
                                           double reclaimMaxUsageRatio) throws IOException {
        Path dir = Files.createTempDirectory("ostore-test-");
        ObjectStoreProperties props = new ObjectStoreProperties();
        props.setDataDir(dir);
        props.setMaxVersionsPerKey(maxVersions);
        props.setAutoPurge(autoPurge);
        props.setReclaimMaxDiskBytes(reclaimMaxDiskBytes);
        props.setReclaimMaxUsageRatio(reclaimMaxUsageRatio);
        return new ObjectStore(props);
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
