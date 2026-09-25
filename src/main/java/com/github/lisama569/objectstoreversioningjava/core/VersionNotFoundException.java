package com.github.lisama569.objectstoreversioningjava.core;

/** 指定版本号不存在。 */
public class VersionNotFoundException extends ObjectStoreException {
    public VersionNotFoundException(String message) { super(message); }
}
