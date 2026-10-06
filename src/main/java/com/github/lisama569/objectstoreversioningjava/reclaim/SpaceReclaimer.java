package com.github.lisama569.objectstoreversioningjava.reclaim;

import com.github.lisama569.objectstoreversioningjava.config.ObjectStoreProperties;
import com.github.lisama569.objectstoreversioningjava.model.ReclaimReport;
import com.github.lisama569.objectstoreversioningjava.store.BlobRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.IntSupplier;
import java.util.stream.Stream;

/**
 * 空间回收器：当占用越过可配置阈值时，自动执行“保留策略裁剪 + 无引用 blob 物理回收”。
 *
 * <h3>触发条件</h3>
 * <ul>
 *   <li>{@code object-store.reclaim-max-disk-bytes}：blobs 区占用字节数超过该值即触发；</li>
 *   <li>{@code object-store.reclaim-max-usage-ratio}：数据目录所在卷的占用比例
 *       （已用/总量）达到该值即触发；</li>
 *   <li>两者都配置时任一越界即触发；都不配置（<=0）时自动回收整体关闭，
 *       只能手动调用 {@link #reclaimIfNeeded()}（此时也不会执行任何动作）。</li>
 * </ul>
 *
 * <h3>与并发读写的关系</h3>
 * 写/删路径只通过 {@link #triggerAsync()} 投递一个信号，阈值检查与回收在独立的后台
 * 单线程中执行，写入不会因回收而阻塞或失败；物理回收本身持注册表写门，只等待在途
 * 读/写临界区结束，被读引脚占住的 blob 会留到下一次回收。回收可安全重入：
 * 只统计真正删除成功的 blob，失败项保留待删状态，重试不会重复计数、不会多删，
 * 仍有引用的数据绝不在删除集合内。
 */
public class SpaceReclaimer implements AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(SpaceReclaimer.class);

    private final long maxDiskBytes;
    private final double maxUsageRatio;
    private final Path dataDir;
    private final BlobRegistry registry;
    private final IntSupplier retentionEnforcer;
    private final ExecutorService executor;
    private final AtomicBoolean pending = new AtomicBoolean(false);
    /** 有未消化的触发信号（回收任务以此判断是否需要在退出前再跑一轮，避免合并丢触发）。 */
    private final AtomicBoolean dirty = new AtomicBoolean(false);
    /** 测试钩子：为 false 时写/删路径不再异步触发，测试可同步精确控制回收时机。 */
    private volatile boolean asyncTriggerEnabled = true;

    /**
     * @param retentionEnforcer 对全部 key 执行一次保留策略裁剪，返回裁剪版本数
     */
    public SpaceReclaimer(ObjectStoreProperties properties, BlobRegistry registry,
                          IntSupplier retentionEnforcer) {
        this.maxDiskBytes = properties.getReclaimMaxDiskBytes();
        this.maxUsageRatio = properties.getReclaimMaxUsageRatio();
        if (maxDiskBytes < 0) {
            throw new IllegalArgumentException(
                    "object-store.reclaim-max-disk-bytes must be >= 0, got " + maxDiskBytes);
        }
        if (maxUsageRatio < 0 || maxUsageRatio > 1) {
            throw new IllegalArgumentException(
                    "object-store.reclaim-max-usage-ratio must be within [0,1], got "
                            + maxUsageRatio);
        }
        this.dataDir = properties.getDataDir();
        this.registry = registry;
        this.retentionEnforcer = retentionEnforcer;
        this.executor = Executors.newSingleThreadExecutor(r -> {
            Thread t = new Thread(r, "space-reclaimer");
            t.setDaemon(true);
            return t;
        });
        log.info("SpaceReclaimer ready, maxDiskBytes={} maxUsageRatio={} -> auto-reclaim {}",
                maxDiskBytes <= 0 ? "OFF" : maxDiskBytes,
                maxUsageRatio <= 0 ? "OFF" : maxUsageRatio,
                thresholdsEnabled() ? "ENABLED" : "DISABLED");
    }

    /** 是否配置了任一有效阈值（未配置时自动回收整体关闭）。 */
    public boolean thresholdsEnabled() {
        return maxDiskBytes > 0 || maxUsageRatio > 0;
    }

    /** 测试钩子：关闭/恢复写路径的异步触发。 */
    public void setAsyncTriggerEnabled(boolean enabled) {
        this.asyncTriggerEnabled = enabled;
    }

    /**
     * 写/删路径的异步触发入口：只投递信号立即返回，不阻塞调用方。
     * 执行中的回收会消化所有在途信号，保证“最后一次写入之后总有一轮回收”，
     * 不会因信号合并而漏掉末尾的待删数据。
     */
    public void triggerAsync() {
        if (!thresholdsEnabled() || !asyncTriggerEnabled) {
            return;
        }
        dirty.set(true);
        if (pending.compareAndSet(false, true)) {
            executor.submit(this::drain);
        }
    }

    private void drain() {
        try {
            while (dirty.getAndSet(false)) {
                try {
                    reclaimIfNeeded();
                } catch (RuntimeException e) {
                    // 回收失败不影响写入路径；待删状态保留，下次触发可重试。
                    log.error("async reclaim failed (will retry on next trigger): {}",
                            e.toString());
                }
            }
        } finally {
            pending.set(false);
            // 退出前又有新信号到达（且没来得及自己提交任务）：补交一轮，杜绝漏触发。
            if (dirty.get() && pending.compareAndSet(false, true)) {
                executor.submit(this::drain);
            }
        }
    }

    /**
     * 同步执行一次“检查阈值 -> 越界则裁剪 + 物理回收”。未越界时不做任何动作。
     * 返回的报告包含全部判定依据（占用、阈值、裁剪数、删除数、失败数）。
     */
    public ReclaimReport reclaimIfNeeded() {
        long before = currentUsageBytes();
        if (!thresholdsEnabled()) {
            log.info("reclaim check: thresholds DISABLED, usage={}B -> no action", before);
            return new ReclaimReport(false, before, before, 0, 0, 0);
        }
        double volumeRatio = volumeUsageRatio();
        boolean bytesExceeded = maxDiskBytes > 0 && before > maxDiskBytes;
        boolean ratioExceeded = maxUsageRatio > 0 && volumeRatio >= maxUsageRatio;
        log.info("reclaim check: usage={}B (threshold={}), volumeRatio={} (threshold={}) -> {}",
                before, maxDiskBytes <= 0 ? "OFF" : maxDiskBytes,
                String.format("%.4f", volumeRatio), maxUsageRatio <= 0 ? "OFF" : maxUsageRatio,
                (bytesExceeded || ratioExceeded) ? "EXCEEDED" : "within limits");
        if (!bytesExceeded && !ratioExceeded) {
            return new ReclaimReport(false, before, before, 0, 0, 0);
        }

        int pruned = retentionEnforcer.getAsInt();
        BlobRegistry.PurgeOutcome outcome = registry.purgeUnreferenced();
        long after = currentUsageBytes();
        log.info("reclaim done: prunedVersions={} purgedBlobs={} purgeFailures={} usage {}B -> {}B",
                pruned, outcome.removed(), outcome.failures(), before, after);
        if (isStillExceeded(after)) {
            log.warn("reclaim finished but usage {}B still exceeds thresholds; "
                    + "remaining data is still referenced or pinned, nothing more to reclaim", after);
        }
        return new ReclaimReport(true, before, after, pruned, outcome.removed(), outcome.failures());
    }

    /** 当前 blobs 区占用字节数。 */
    public long currentUsageBytes() {
        Path blobs = dataDir.resolve("blobs");
        if (!Files.isDirectory(blobs)) {
            return 0;
        }
        try (Stream<Path> s = Files.walk(blobs)) {
            return s.filter(Files::isRegularFile).mapToLong(p -> {
                try {
                    return Files.size(p);
                } catch (IOException e) {
                    return 0;
                }
            }).sum();
        } catch (IOException e) {
            throw new UncheckedIOException("failed to measure blob usage under " + blobs, e);
        }
    }

    private boolean isStillExceeded(long usageBytes) {
        return (maxDiskBytes > 0 && usageBytes > maxDiskBytes)
                || (maxUsageRatio > 0 && volumeUsageRatio() >= maxUsageRatio);
    }

    /** 数据目录所在卷的占用比例（已用/总量）；读取失败时记 0 并记日志，不影响写路径。 */
    private double volumeUsageRatio() {
        try {
            var store = Files.getFileStore(dataDir);
            long total = store.getTotalSpace();
            if (total <= 0) {
                return 0;
            }
            return (double) (total - store.getUsableSpace()) / (double) total;
        } catch (IOException e) {
            log.warn("cannot read volume usage for {}: {}", dataDir, e.toString());
            return 0;
        }
    }

    @Override
    public void close() {
        executor.shutdown();
        try {
            if (!executor.awaitTermination(5, TimeUnit.SECONDS)) {
                executor.shutdownNow();
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            executor.shutdownNow();
        }
    }
}
