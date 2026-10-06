package com.github.lisama569.objectstoreversioningjava.model;

/**
 * 对象级保留规则（不可变）。两个维度可同时配置，版本只有在<b>两个维度都越界</b>时才允许被回收：
 * <ul>
 *   <li>{@code maxVersions}：最多保留最近多少个版本（含删除标记）；null 表示不限制；</li>
 *   <li>{@code minRetentionMillis}：版本至少保留多长时间（自创建起算）；null 表示不要求。</li>
 * </ul>
 * 即：一个版本被裁剪，当且仅当“比它新的版本数已达到 maxVersions”且“它的年龄已不小于
 * minRetentionMillis”。任一维度仍在保留范围内，该版本就必须能按版本号正常读出。
 */
public record RetentionPolicy(Integer maxVersions, Long minRetentionMillis) {

    /** 不附加任何对象级规则（全部回落到全局默认）。 */
    public static final RetentionPolicy NONE = new RetentionPolicy(null, null);
}
