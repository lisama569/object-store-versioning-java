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
}
