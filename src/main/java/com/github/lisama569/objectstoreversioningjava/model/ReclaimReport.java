package com.github.lisama569.objectstoreversioningjava.model;

/**
 * 一次空间回收执行的报告（无论是否真正触发，都给出判定依据，便于日志与测试断言）。
 *
 * @param thresholdExceeded 本次检查时占用是否越过阈值（未越过则不执行任何回收动作）
 * @param usageBeforeBytes  回收前 blobs 区占用字节数
 * @param usageAfterBytes   回收后 blobs 区占用字节数
 * @param versionsPruned    本次因保留规则裁剪掉的版本数
 * @param blobsPurged       本次物理删除的 blob 数（只计实际删除成功的，重复执行不重复计数）
 * @param purgeFailures     本次物理删除失败的 blob 数（失败项保留待删状态，可安全重试）
 */
public record ReclaimReport(boolean thresholdExceeded, long usageBeforeBytes, long usageAfterBytes,
                            int versionsPruned, int blobsPurged, int purgeFailures) {
}
