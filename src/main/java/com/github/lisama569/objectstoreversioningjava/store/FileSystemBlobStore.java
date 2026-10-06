package com.github.lisama569.objectstoreversioningjava.store;

import com.github.lisama569.objectstoreversioningjava.exception.StoreException;
import com.github.lisama569.objectstoreversioningjava.model.BlobContent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.UUID;
import java.util.function.Consumer;

/**
 * 基于本地磁盘的内容寻址 blob 仓库。
 *
 * <p>目录布局：
 * <ul>
 *   <li>{@code blobs/<前2位指纹>/<完整指纹>}：正常数据，文件名即内容的 SHA-256 指纹（blobId）；</li>
 *   <li>{@code tmp/}：落盘暂存区，校验通过后以原子 rename 发布到 blobs 区；</li>
 *   <li>{@code quarantine/}：校验失败数据的隔离区，永不作为正常数据读取。</li>
 * </ul>
 *
 * <p>一致性要点：
 * <ol>
 *   <li>写入“先落临时文件 → 回读校验 → 原子发布”，任何读者都不可能看到半截文件；</li>
 *   <li>相同内容的并发写入最终只有一份正式文件（同名原子移动，已存在则去重）；</li>
 *   <li>读时再次校验，发现静默损坏（bit rot）立即把文件移入隔离区并抛出 {@code CHECKSUM_MISMATCH}；</li>
 *   <li>隔离与正常区域物理分离，损坏数据不会以任何正常路径返回。</li>
 * </ol>
 */
public class FileSystemBlobStore implements BlobStore {

    private static final Logger log = LoggerFactory.getLogger(FileSystemBlobStore.class);

    private final Path rootDir;
    private final Path blobsDir;
    private final Path tmpDir;
    private final Path quarantineDir;

    /**
     * 测试/故障注入钩子：临时文件写完、回读校验之前被调用（例如翻转字节模拟落盘损坏）。
     * 生产中始终为 null。一次性使用后自行置 null。
     */
    volatile Consumer<Path> beforeCommitCorrupter;

    public FileSystemBlobStore(Path rootDir) {
        this.rootDir = rootDir;
        this.blobsDir = rootDir.resolve("blobs");
        this.tmpDir = rootDir.resolve("tmp");
        this.quarantineDir = rootDir.resolve("quarantine");
        try {
            Files.createDirectories(blobsDir);
            Files.createDirectories(tmpDir);
            Files.createDirectories(quarantineDir);
        } catch (IOException e) {
            throw new UncheckedIOException("init blob store dirs failed under " + rootDir, e);
        }
        log.info("FileSystemBlobStore initialized, root={}", rootDir.toAbsolutePath());
    }

    @Override
    public String put(ByteBuffer content) {
        byte[] bytes = toByteArray(content);
        String expectedId = sha256Hex(bytes);
        Path target = blobPath(expectedId);

        // 内容去重：正式文件已存在则直接复用，不重复落盘。
        if (Files.isRegularFile(target)) {
            log.info("put dedup-hit blobId={} size={}", expectedId, bytes.length);
            return expectedId;
        }

        Path staging = null;
        try {
            Path shard = target.getParent();
            Files.createDirectories(shard);
            // 暂存文件与目标位于同一目录，保证原子 rename 可靠。
            staging = Files.createTempFile(shard, ".stage-" + expectedId + "-", ".tmp");
            try (FileChannel ch = FileChannel.open(staging, StandardOpenOption.WRITE)) {
                ch.write(ByteBuffer.wrap(bytes));
                ch.force(true);
            }

            if (beforeCommitCorrupter != null) {
                beforeCommitCorrupter.accept(staging);
                log.warn("fault-injection: corrupter applied to staging file {}", staging);
            }

            // 回读校验暂存文件，杜绝把半截/损坏数据发布为正式 blob。
            byte[] written = Files.readAllBytes(staging);
            String actualId = sha256Hex(written);
            if (!expectedId.equals(actualId)) {
                Path isolated = quarantine(staging, expectedId, actualId, "write-verify");
                log.error("write checksum FAILED expected={} actual={} size={} -> isolated at {}",
                        expectedId, actualId, bytes.length, isolated);
                throw new StoreException(StoreException.Reason.CHECKSUM_MISMATCH,
                        "write verification failed: expected=" + expectedId + " actual=" + actualId
                                + ", corrupted data isolated at " + isolated);
            }

            try {
                // 不使用 REPLACE_EXISTING：并发同内容写入时后到者走 FileAlreadyExistsException 去重分支。
                Files.move(staging, target, StandardCopyOption.ATOMIC_MOVE);
                log.info("put published blobId={} size={}", expectedId, bytes.length);
            } catch (java.nio.file.FileAlreadyExistsException dup) {
                Files.deleteIfExists(staging);
                log.info("put concurrent dedup blobId={} (published by another writer)", expectedId);
            }
            staging = null;
            return expectedId;
        } catch (IOException e) {
            throw new UncheckedIOException("blob put failed blobId=" + expectedId, e);
        } finally {
            if (staging != null) {
                try {
                    Files.deleteIfExists(staging);
                } catch (IOException ignore) {
                    // 暂存残留清理失败不影响正确性；tmp/ 可被运维清理。
                }
            }
        }
    }

    @Override
    public BlobContent get(String blobId) {
        validateBlobId(blobId);
        Path target = blobPath(blobId);
        if (!Files.isRegularFile(target)) {
            return null;
        }
        byte[] bytes;
        try {
            bytes = Files.readAllBytes(target);
        } catch (IOException e) {
            throw new UncheckedIOException("blob read failed blobId=" + blobId, e);
        }
        String actualId = sha256Hex(bytes);
        if (!blobId.equals(actualId)) {
            // 读时校验发现静默损坏：立即隔离，绝不返回坏数据。
            Path isolated;
            try {
                isolated = quarantine(target, blobId, actualId, "read-verify");
            } catch (IOException q) {
                throw new UncheckedIOException("quarantine corrupt blob failed: " + blobId, q);
            }
            log.error("read checksum FAILED blobId={} actual={} size={} -> isolated at {}",
                    blobId, actualId, bytes.length, isolated);
            throw new StoreException(StoreException.Reason.CHECKSUM_MISMATCH,
                    "stored blob corrupted: name=" + blobId + " actual=" + actualId
                            + ", data isolated at " + isolated);
        }
        return new BlobContent(ByteBuffer.wrap(bytes), bytes.length, actualId);
    }

    /**
     * 测试/故障注入钩子：物理删除某个 blob 之前被调用（可抛出异常模拟删除失败，
     * 验证回收中途失败后的可重试性）。生产中始终为 null。
     */
    volatile Consumer<String> beforeDeleteHook;

    @Override
    public void delete(String blobId) {
        validateBlobId(blobId);
        if (beforeDeleteHook != null) {
            beforeDeleteHook.accept(blobId);
        }
        try {
            boolean removed = Files.deleteIfExists(blobPath(blobId));
            log.info("blob physical-delete blobId={} removed={}", blobId, removed);
        } catch (IOException e) {
            throw new UncheckedIOException("blob delete failed blobId=" + blobId, e);
        }
    }

    @Override
    public boolean exists(String blobId) {
        validateBlobId(blobId);
        return Files.isRegularFile(blobPath(blobId));
    }

    /**
     * 故障注入：直接破坏正式区里某个 blob 的若干字节，模拟磁盘静默损坏（bit rot）。
     * 下次读校验即应识别并隔离。仅供测试使用。
     */
    public void corruptOnDisk(String blobId) {
        Path target = blobPath(blobId);
        try (FileChannel ch = FileChannel.open(target, StandardOpenOption.WRITE)) {
            ByteBuffer buf = ByteBuffer.wrap(new byte[]{(byte) 0xFF});
            ch.position(0);
            ch.write(buf);
            ch.force(true);
        } catch (IOException e) {
            throw new UncheckedIOException("inject corruption failed blobId=" + blobId, e);
        }
        log.warn("fault-injection: corrupted blob bytes on disk blobId={}", blobId);
    }

    private Path quarantine(Path corruptFile, String expectedId, String actualId, String phase)
            throws IOException {
        Path isolated = quarantineDir.resolve(expectedId + "-" + phase + "-" + UUID.randomUUID());
        try {
            Files.move(corruptFile, isolated, StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException atomicFail) {
            Files.copy(corruptFile, isolated, StandardCopyOption.REPLACE_EXISTING);
            Files.deleteIfExists(corruptFile);
        }
        return isolated;
    }

    private Path blobPath(String blobId) {
        return blobsDir.resolve(blobId.substring(0, 2)).resolve(blobId);
    }

    private static void validateBlobId(String blobId) {
        if (blobId == null || !blobId.matches("[0-9a-f]{64}")) {
            throw new IllegalArgumentException("illegal blobId: " + blobId);
        }
    }

    private static byte[] toByteArray(ByteBuffer buffer) {
        ByteBuffer dup = buffer.slice();
        byte[] bytes = new byte[dup.remaining()];
        dup.get(bytes);
        return bytes;
    }

    static String sha256Hex(byte[] bytes) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(bytes));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }
}
