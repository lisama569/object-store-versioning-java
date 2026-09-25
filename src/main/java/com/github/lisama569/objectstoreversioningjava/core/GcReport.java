package com.github.lisama569.objectstoreversioningjava.core;

import java.util.List;

/** 一次空间回收的结果说明。 */
public record GcReport(int scanned, int deleted, List<String> deletedContentIds, List<String> retainedContentIds) {
}
