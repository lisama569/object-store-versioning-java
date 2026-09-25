package com.github.lisama569.objectstoreversioningjava.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/** 可配置项：object-store.* */
@ConfigurationProperties(prefix = "object-store")
public class StoreProperties {

    /** 块数据目录。 */
    private String dataDir = "target/object-store-data";

    /** 最大键长度。 */
    private int maxKeyLength = 1024;

    public String getDataDir() { return dataDir; }
    public void setDataDir(String dataDir) { this.dataDir = dataDir; }
    public int getMaxKeyLength() { return maxKeyLength; }
    public void setMaxKeyLength(int maxKeyLength) { this.maxKeyLength = maxKeyLength; }
}
