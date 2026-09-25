package com.github.lisama569.objectstoreversioningjava.service;

import com.github.lisama569.objectstoreversioningjava.core.ObjectKey;
import com.github.lisama569.objectstoreversioningjava.core.ObjectVersion;
import com.github.lisama569.objectstoreversioningjava.core.StoredContent;
import com.github.lisama569.objectstoreversioningjava.core.VersionId;

import java.util.List;

/**
 * 对象存储门面：对外暴露版本化的增/读/删语义。
 */
public interface ObjectStore {

    /** 新增或覆盖写入（追加一个新版本）。相同内容共享底层数据。 */
    ObjectVersion put(String key, byte[] content);

    /** 读最新版本；对象已删除时抛 ObjectDeletedException。 */
    StoredContent get(String key);

    /** 读指定版本；删除标记版本抛 ObjectDeletedException。 */
    StoredContent getVersion(String key, long version);

    /** 逻辑删除：追加删除标记（旧版本仍可按版本号读取）。已删除对象再次逻辑删除被拒绝。 */
    ObjectVersion delete(String key);

    /** 永久删除指定版本（该版本内容引用被移除，随后可被回收）。 */
    void purgeVersion(String key, long version);

    /** 版本列表（调试/可解释性）。 */
    List<ObjectVersion> listVersions(String key);
}
