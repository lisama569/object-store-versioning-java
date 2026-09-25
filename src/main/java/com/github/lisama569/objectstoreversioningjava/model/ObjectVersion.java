package com.github.lisama569.objectstoreversioningjava.model;

/**
 * 某对象在某版本上的不可变元数据。deleteMarker=true 表示删除标记版本（无底层数据）。
 */
public record ObjectVersion(long versionId, String blobId, long size, String checksum, long createdAt,
                            boolean deleteMarker) {
}
