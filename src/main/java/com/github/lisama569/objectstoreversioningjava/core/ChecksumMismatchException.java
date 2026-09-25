package com.github.lisama569.objectstoreversioningjava.core;

/** 内容校验失败：实际 SHA-256 与声明不一致。损坏数据必须隔离，不得作为正常数据返回。 */
public class ChecksumMismatchException extends ObjectStoreException {
    public ChecksumMismatchException(String message) { super(message); }
}
