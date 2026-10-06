package com.github.lisama569.objectstoreversioningjava.model;

import com.github.lisama569.objectstoreversioningjava.exception.StoreException;

/**
 * 单个对象的保留规则。
 *
 * @param maxVersions        最多保留的最近版本数（含删除标记）；0 表示不按数量限制
 * @param minRetentionMillis 版本至少保留的时长（毫秒）；0 表示不按时间限制
 */
public record RetentionPolicy(int maxVersions, long minRetentionMillis) {

    /** 不施加任何限制（等价于未配置）。 */
    public static RetentionPolicy unlimited() {
        return new RetentionPolicy(0, 0L);
    }

    public boolean isUnlimited() {
        return maxVersions <= 0 && minRetentionMillis <= 0;
    }

    /**
     * 校验规则本身的合法性及其与最低保留要求（守卫下限）是否冲突。
     * 不合法时抛出 {@code INVALID_RETENTION_POLICY} 并说明原因，绝不悄悄套用默认值。
     *
     * @param minVersionsFloor        每 key 至少保留版本数下限（>=1）
     * @param minRetentionMillisFloor 版本最短保留时长下限（毫秒，>=0）
     */
    public void validate(int minVersionsFloor, long minRetentionMillisFloor) {
        if (maxVersions < 0) {
            throw invalid("maxVersions must be >= 0 (0 = unlimited), got " + maxVersions);
        }
        if (minRetentionMillis < 0) {
            throw invalid("minRetentionMillis must be >= 0 (0 = no time floor), got "
                    + minRetentionMillis);
        }
        if (isUnlimited()) {
            throw invalid("policy is empty: neither maxVersions nor minRetentionMillis is set; "
                    + "use clearRetentionPolicy to remove a policy instead");
        }
        if (maxVersions > 0 && maxVersions < minVersionsFloor) {
            throw invalid("maxVersions=" + maxVersions + " conflicts with the minimum retention "
                    + "requirement: at least " + minVersionsFloor + " version(s) must be kept");
        }
        if (minRetentionMillis < minRetentionMillisFloor) {
            throw invalid("minRetentionMillis=" + minRetentionMillis
                    + " conflicts with the minimum retention requirement: at least "
                    + minRetentionMillisFloor + " ms must be kept");
        }
    }

    private static StoreException invalid(String detail) {
        return new StoreException(StoreException.Reason.INVALID_RETENTION_POLICY,
                "invalid retention policy: " + detail);
    }
}
