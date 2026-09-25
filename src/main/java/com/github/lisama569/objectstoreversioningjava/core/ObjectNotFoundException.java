package com.github.lisama569.objectstoreversioningjava.core;

/** 对象不存在（从未创建，或最后一个版本是删除标记）。 */
public class ObjectNotFoundException extends ObjectStoreException {
    public ObjectNotFoundException(String message) { super(message); }
}
