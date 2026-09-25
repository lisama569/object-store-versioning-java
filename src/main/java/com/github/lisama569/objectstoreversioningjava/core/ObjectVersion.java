package com.github.lisama569.objectstoreversioningjava.core;

import java.util.Objects;

/**
 * 一个对象版本（版本链中的不可变节点）。
 *
 * <p>两种形态：
 * <ul>
 *   <li><b>数据版本</b>：{@code deleteMarker=false}，{@code contentId} 非空；</li>
 *   <li><b>删除标记版本（tombstone）</b>：{@code deleteMarker=true}，{@code contentId=null}。
 *       它本身不引用任何底层数据，因此不参与内容引用计数。</li>
 * </ul>
 *
 * <p>历史版本永久保留在元数据链中（逻辑删除不清除它们），
 * 只有显式调用永久删除才会移除单个版本节点。
 */
public record ObjectVersion(ObjectKey key, VersionId version, boolean deleteMarker, ContentId contentId) {

    public ObjectVersion {
        Objects.requireNonNull(key, "key");
        Objects.requireNonNull(version, "version");
        if (deleteMarker) {
            if (contentId != null) {
                throw new IllegalArgumentException("删除标记版本不能引用内容");
            }
        } else {
            Objects.requireNonNull(contentId, "data 版本必须引用内容");
        }
    }

    public static ObjectVersion data(ObjectKey key, VersionId v, ContentId c) {
        return new ObjectVersion(key, v, false, Objects.requireNonNull(c, "contentId"));
    }

    public static ObjectVersion tombstone(ObjectKey key, VersionId v) {
        return new ObjectVersion(key, v, true, null);
    }

    public String describe() {
        return "v" + version.value() + (deleteMarker ? "[DELETED]" : "[data@" + contentId.sha256().substring(0, 12) + "]");
    }
}
