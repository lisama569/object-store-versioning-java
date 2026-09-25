package com.github.lisama569.objectstoreversioningjava.storage;

import com.github.lisama569.objectstoreversioningjava.core.ContentId;

import java.util.Set;

/**
 * 内容寻址的块存储：以内容 SHA-256 作为去重键。
 * 同一份内容在磁盘上只落一份文件，被多个对象版本共享。
 */
public interface BlobStorage {

    /**
     * 原子提交一份内容：先写临时文件并校验，再原子移动到内容目录。
     * 内容已存在时为幂等空操作。提交未完成期间，任何读取都看不到它。
     */
    void commit(ContentId id, byte[] data);

    /**
     * 读取并校验内容；字节实际摘要与内容地址不符时抛 ChecksumMismatchException。
     */
    byte[] read(ContentId id);

    /** 删除底层文件（仅在确认无版本引用后由回收器调用）。 */
    void delete(ContentId id);

    /** 磁盘上当前存在的全部内容地址。 */
    Set<ContentId> listAll();
}
