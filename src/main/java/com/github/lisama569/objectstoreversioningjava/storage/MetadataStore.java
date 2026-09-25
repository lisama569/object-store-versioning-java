package com.github.lisama569.objectstoreversioningjava.storage;

import com.github.lisama569.objectstoreversioningjava.core.ContentId;
import com.github.lisama569.objectstoreversioningjava.core.ObjectKey;
import com.github.lisama569.objectstoreversioningjava.core.ObjectVersion;
import com.github.lisama569.objectstoreversioningjava.core.VersionId;

import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 进程内版本元数据：key -> 有序版本链。
 * 所有方法均在元数据锁保护下执行，提供一致性快照视图（copy-on-read）。
 */
public interface MetadataStore {

    /** 追加一个数据版本，分配下一个单调递增版本号；返回新版本。 */
    ObjectVersion appendData(ObjectKey key, ContentId contentId);

    /** 追加删除标记版本；返回新版本。 */
    ObjectVersion appendTombstone(ObjectKey key);

    /** 物理移除指定版本（永久删除），返回被移除的版本；不存在返回 null。 */
    ObjectVersion removeVersion(ObjectKey key, VersionId version);

    /** 读取指定版本；不存在返回 null。 */
    ObjectVersion getVersion(ObjectKey key, VersionId version);

    /** 最新版本；无任何版本返回 null。 */
    ObjectVersion latest(ObjectKey key);

    /** 全部版本（按版本号升序，快照副本）。 */
    List<ObjectVersion> versions(ObjectKey key);

    /** 快照：key -> 版本链。 */
    Map<ObjectKey, List<ObjectVersion>> snapshot();

    /** 当前仍被版本引用的内容地址集合（删除标记不引用内容）。 */
    Set<ContentId> liveContentIds();
}
