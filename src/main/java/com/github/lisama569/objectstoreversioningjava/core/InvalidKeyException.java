package com.github.lisama569.objectstoreversioningjava.core;

/** 键非法（空、空白、超长、含控制字符）。 */
public class InvalidKeyException extends ObjectStoreException {
    public InvalidKeyException(String message) { super(message); }
}
