package com.github.lisama569.objectstoreversioningjava.core;

/** 本地磁盘读写失败。 */
public class StorageIOException extends ObjectStoreException {
    public StorageIOException(String message, Throwable cause) { super(message); }
}
