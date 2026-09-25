package com.github.lisama569.objectstoreversioningjava.core;

/** 对象最新版本为删除标记（读最新版 / 对已删除对象做逻辑删除）。 */
public class ObjectDeletedException extends ObjectStoreException {
    public ObjectDeletedException(String message) { super(message); }
}
