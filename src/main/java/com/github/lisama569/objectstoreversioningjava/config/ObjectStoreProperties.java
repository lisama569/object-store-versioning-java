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

    /** 全局默认的“版本至少保留多长时间”（毫秒）；对象级规则未指定时回落到此值；0 表示不要求。 */
    private long defaultMinRetentionMillis = 0;

    /** 最低保留要求（毫秒）：任何对象级规则的 minRetentionMillis 不得低于它，否则拒绝配置。 */
    private long minRetentionFloorMillis = 0;

    /** 自动回收的容量阈值（字节）：已跟踪 blob 总字节超过它时触发回收；<=0 表示关闭该阈值。 */
    private long reclaimMaxBytes = 0;

    /** 自动回收的占用比例阈值：数据目录所在卷 已用/总空间 >= 该比例时触发回收；不在 (0,1] 内表示关闭。 */
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

    public long getDefaultMinRetentionMillis() {
        return defaultMinRetentionMillis;
    }

    public void setDefaultMinRetentionMillis(long defaultMinRetentionMillis) {
        this.defaultMinRetentionMillis = defaultMinRetentionMillis;
    }

    public long getMinRetentionFloorMillis() {
        return minRetentionFloorMillis;
    }

    public void setMinRetentionFloorMillis(long minRetentionFloorMillis) {
        this.minRetentionFloorMillis = minRetentionFloorMillis;
    }

    public long getReclaimMaxBytes() {
        return reclaimMaxBytes;
    }

    public void setReclaimMaxBytes(long reclaimMaxBytes) {
        this.reclaimMaxBytes = reclaimMaxBytes;
    }

    public double getReclaimMaxUsageRatio() {
        return reclaimMaxUsageRatio;
    }

    public void setReclaimMaxUsageRatio(double reclaimMaxUsageRatio) {
        this.reclaimMaxUsageRatio = reclaimMaxUsageRatio;
    }
}
