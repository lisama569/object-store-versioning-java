package com.github.lisama569.objectstoreversioningjava.storage;

import com.github.lisama569.objectstoreversioningjava.core.ChecksumMismatchException;
import com.github.lisama569.objectstoreversioningjava.core.ContentId;
import com.github.lisama569.objectstoreversioningjava.core.StorageIOException;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Set;
import java.util.stream.Stream;

/**
 * 基于本地磁盘的内容寻址块存储。
 *
 * <p>目录布局：
 * <pre>
 *   blobDir/
 *     blobs/&lt;sha256&gt;        —— 已提交的不可变内容块（文件名即内容地址）
 *     tmp/&lt;uuid&gt;            —— 提交过程中的临时文件（外部不可见）
 *     quarantine/&lt;sha256&gt;   —— 读取时复验失败、被隔离的损坏块
 * </pre>
 *
 * <p><b>原子性</b>：写入永远先落 {@code tmp/}，在临时文件上校验摘要一致后，
 * 通过 {@code ATOMIC_MOVE} 移入 {@code blobs/}。移动完成之前不存在于正式目录，
 * 因此任何并发读取都不可能看到“半截”内容；move 完成后内容立即可见。
 *
 * <p><b>去重</b>：文件名即 SHA-256 内容地址。相同内容并发提交时，
 * 只有一个 move 生效，其余走 {@code REPLACE_EXISTING} 幂等落位——磁盘上只有一份。
 *
 * <p><b>损坏隔离</b>：读取时对字节重新计算 SHA-256 并与内容地址比对；
 * 不符则把文件移入 {@code quarantine/} 并抛 {@link ChecksumMismatchException}，
 * 损坏字节绝不会作为正常数据返回。
 */
public class LocalBlobStorage implements BlobStorage {

    private final Path blobDir;
    private final Path tmpDir;
    private final Path quarantineDir;

    public LocalBlobStorage(Path baseDir) {
        try {
            this.blobDir = Files.createDirectories(baseDir.resolve("blobs"));
            this.tmpDir = Files.createDirectories(baseDir.resolve("tmp"));
            this.quarantineDir = Files.createDirectories(baseDir.resolve("quarantine"));
        } catch (IOException e) {
            throw new StorageIOException("初始化块存储目录失败: " + baseDir, e);
        }
    }

    private static MessageDigest sha256() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 不可用", e);
        }
    }

    public static String sha256Hex(byte[] data) {
        return HexFormat.of().formatHex(sha256().digest(data));
    }

    private Path blobPath(ContentId id) {
        return blobDir.resolve(id.sha256());
    }

    @Override
    public void commit(ContentId id, byte[] data) {
        if (data == null) {
            throw new StorageIOException("提交内容不能为 null", null);
        }
        // 1) 先在内存比对：声明的地址必须与实际摘要一致，不一致直接拒绝，磁盘不留垃圾。
        String actual = sha256Hex(data);
        if (!actual.equals(id.sha256())) {
            throw new ChecksumMismatchException(
                    "提交被拒绝：声明地址 " + id.sha256() + " 与内容实际摘要 " + actual + " 不一致");
        }

        Path target = blobPath(id);
        // 幂等快路径：内容已存在即不重复落盘（去重）。
        if (Files.exists(target)) {
            return;
        }

        // 2) 写临时文件：此阶段任何读取方在 blobs/ 下都看不到它。
        Path tmp;
        try {
            tmp = Files.createTempFile(tmpDir, "put-", ".tmp");
            Files.write(tmp, data);
        } catch (IOException e) {
            throw new StorageIOException("写临时文件失败: " + id.sha256(), e);
        }

        // 3) 原子移动到正式目录。同名已存在（并发相同内容提交）时幂等替换，磁盘仍只有一份。
        try {
            try {
                Files.move(tmp, target, StandardCopyOption.ATOMIC_MOVE);
            } catch (AtomicMoveNotSupportedException e) {
                Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (IOException e) {
            try {
                Files.deleteIfExists(tmp);
            } catch (IOException ignore) {
                // 清理失败不影响主错误传播
            }
            throw new StorageIOException("原子提交失败: " + id.sha256(), e);
        }
    }

    @Override
    public byte[] read(ContentId id) {
        Path path = blobPath(id);
        if (!Files.exists(path)) {
            // 块缺失：若能找到对应隔离文件，说明此前已被识别为损坏，仍以校验异常显式失败，
            // 而不是退化为含义模糊的 IO 错误——调用方据此可区分“损坏”与“被回收”。
            if (hasQuarantineCopy(id)) {
                throw new ChecksumMismatchException(
                        "内容块此前已校验失败并被隔离: " + id.sha256());
            }
            throw new StorageIOException("底层内容块缺失（可能已被回收）: " + id.sha256(), null);
        }
        byte[] data;
        try {
            data = Files.readAllBytes(path);
        } catch (IOException e) {
            throw new StorageIOException("读取内容块失败: " + id.sha256(), e);
        }
        // 复验：磁盘内容必须与其地址自洽，否则显式识别并隔离。
        String actual = sha256Hex(data);
        if (!actual.equals(id.sha256())) {
            quarantine(id, path, actual);
            throw new ChecksumMismatchException(
                    "内容校验失败并已隔离：地址 " + id.sha256() + "，磁盘实际摘要 " + actual);
        }
        return data;
    }

    private void quarantine(ContentId id, Path path, String actual) {
        try {
            Path q = quarantineDir.resolve(id.sha256() + ".corrupt-" + actual.substring(0, 12));
            Files.move(path, q, StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException e) {
            // 隔离失败也要保证损坏数据不外泄；保留原文件但仍然抛出校验异常。
            throw new ChecksumMismatchException(
                    "内容校验失败，且隔离移动失败: " + id.sha256());
        }
    }

    private boolean hasQuarantineCopy(ContentId id) {
        String prefix = id.sha256() + ".corrupt-";
        try (Stream<Path> files = Files.list(quarantineDir)) {
            return files.anyMatch(p -> p.getFileName().toString().startsWith(prefix));
        } catch (IOException e) {
            return false;
        }
    }

    @Override
    public void delete(ContentId id) {
        try {
            Files.deleteIfExists(blobPath(id));
        } catch (IOException e) {
            throw new StorageIOException("删除内容块失败: " + id.sha256(), e);
        }
    }

    @Override
    public Set<ContentId> listAll() {
        try (Stream<Path> files = Files.list(blobDir)) {
            return files
                    .filter(Files::isRegularFile)
                    .map(p -> {
                        try {
                            return ContentId.of(p.getFileName().toString());
                        } catch (IllegalArgumentException badName) {
                            return null;
                        }
                    })
                    .filter(java.util.Objects::nonNull)
                    .collect(java.util.stream.Collectors.toUnmodifiableSet());
        } catch (IOException | UncheckedIOException e) {
            throw new StorageIOException("列举内容块失败", e);
        }
    }
}
