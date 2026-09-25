package com.github.lisama569.objectstoreversioningjava.core;

/** 读取返回的内容：数据字节 + 所属内容地址 + 版本号。 */
public record StoredContent(VersionId version, ContentId contentId, byte[] data) {
}
