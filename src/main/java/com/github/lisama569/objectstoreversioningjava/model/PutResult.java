package com.github.lisama569.objectstoreversioningjava.model;

/**
 * 一次写入的结果：版本号、底层 blobId 以及本次是否真正新建了底层数据（用于去重判定）。
 */
public record PutResult(long versionId, String blobId, boolean contentDeduplicated) {
}
