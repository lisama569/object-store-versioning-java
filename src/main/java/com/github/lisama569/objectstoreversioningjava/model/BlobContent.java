package com.github.lisama569.objectstoreversioningjava.model;

import java.nio.ByteBuffer;

/**
 * 读取到的底层数据内容及其校验信息。
 */
public record BlobContent(ByteBuffer data, long size, String checksum) {
}
