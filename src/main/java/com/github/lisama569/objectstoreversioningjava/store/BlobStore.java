package com.github.lisama569.objectstoreversioningjava.store;

import com.github.lisama569.objectstoreversioningjava.model.BlobContent;

import java.nio.ByteBuffer;

/**
 * 内容寻址的底层数据仓库：写入即返回内容指纹 blobId；损坏数据写入隔离区；物理删除不可再读。
 */
public interface BlobStore {

    /**
     * 原子写入一份数据并校验，返回内容指纹（SHA-256 hex）。
     * 校验失败的数据必须被隔离（quarantine），不得作为正常数据保留。
     */
    String put(ByteBuffer content);

    /**
     * 按 blobId 读取并校验；文件不存在返回 null；校验失败抛 CHECKSUM_MISMATCH。
     */
    BlobContent get(String blobId);

    /**
     * 将 blobId 对应数据从正常区域物理删除。删除后 get 必须返回 null。
     */
    void delete(String blobId);

    /**
     * 正常区域是否存在该 blobId 的数据文件。
     */
    boolean exists(String blobId);
}
