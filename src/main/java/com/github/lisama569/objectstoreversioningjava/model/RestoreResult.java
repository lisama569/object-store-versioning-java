package com.github.lisama569.objectstoreversioningjava.model;

/**
 * 一次删除恢复的结果。
 *
 * @param versionId             恢复产生的新版本号（恢复是追加新版本，不是回滚）
 * @param restoredFromVersionId 内容来源的历史版本号
 * @param blobId                底层数据指纹
 */
public record RestoreResult(long versionId, long restoredFromVersionId, String blobId) {
}
