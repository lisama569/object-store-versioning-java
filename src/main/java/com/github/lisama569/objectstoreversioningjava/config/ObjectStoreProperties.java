package com.github.lisama569.objectstoreversioningjava.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.nio.file.Path;

/**
 * 可配置项（前缀 object-store）。
 */
@ConfigurationProperties(prefix = "object-store")
public class ObjectStoreProperties {

    /** 本地数据根目录。 */
    private Path dataDir = Path.of("data", "object-store");

    /** 每个 key 最多保留多少个版本（含删除标记）；<=0 表示不限制。 */
    private int maxVersionsPerKey = 0;

    /** 是否在写操作触发版本裁剪后自动回收无引用 blob。 */
    private boolean autoPurge = false;

    /** 保留规则守卫：每 key 至少保留的版本数下限，per-key 规则不得更低。 */
    private int retentionMinVersions = 1;

    /** 保留规则守卫：版本最短保留时长下限（毫秒），per-key 规则不得更短。 */
    private long retentionMinRetentionMillis = 0;

    /** 自动回收阈值：blobs 区占用字节上限；<=0 表示不按容量触发。 */
    private long reclaimMaxDiskBytes = 0;

    /** 自动回收阈值：数据目录所在卷的占用比例上限（0,1]；0 表示不按比例触发。 */
    private double reclaimMaxUsageRatio = 0;

    public Path getDataDir() {
        return dataDir;
    }

    public void setDataDir(Path dataDir) {
        this.dataDir = dataDir;
    }

    public int getMaxVersionsPerKey() {
        return maxVersionsPerKey;
    }

    public void setMaxVersionsPerKey(int maxVersionsPerKey) {
        this.maxVersionsPerKey = maxVersionsPerKey;
    }

    public boolean isAutoPurge() {
        return autoPurge;
    }

    public void setAutoPurge(boolean autoPurge) {
        this.autoPurge = autoPurge;
    }

    public int getRetentionMinVersions() {
        return retentionMinVersions;
    }

    public void setRetentionMinVersions(int retentionMinVersions) {
        this.retentionMinVersions = retentionMinVersions;
    }

    public long getRetentionMinRetentionMillis() {
        return retentionMinRetentionMillis;
    }

    public void setRetentionMinRetentionMillis(long retentionMinRetentionMillis) {
        this.retentionMinRetentionMillis = retentionMinRetentionMillis;
    }

    public long getReclaimMaxDiskBytes() {
        return reclaimMaxDiskBytes;
    }

    public void setReclaimMaxDiskBytes(long reclaimMaxDiskBytes) {
        this.reclaimMaxDiskBytes = reclaimMaxDiskBytes;
    }

    public double getReclaimMaxUsageRatio() {
        return reclaimMaxUsageRatio;
    }

    public void setReclaimMaxUsageRatio(double reclaimMaxUsageRatio) {
        this.reclaimMaxUsageRatio = reclaimMaxUsageRatio;
    }
}
