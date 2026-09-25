package com.github.lisama569.objectstoreversioningjava.core;

/** 对象存储领域异常基类，每种失败原因有独立子类，便于调用方区分。 */
public abstract class ObjectStoreException extends RuntimeException {
    protected ObjectStoreException(String message) { super(message); }
}
