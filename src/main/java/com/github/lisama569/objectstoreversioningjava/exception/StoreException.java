package com.github.lisama569.objectstoreversioningjava.exception;

/**
 * 对所有可拒绝操作给出可区分原因的异常。
 */
public class StoreException extends RuntimeException {

    public enum Reason {
        INVALID_KEY,
        NO_SUCH_OBJECT,
        NO_SUCH_VERSION,
        OBJECT_DELETED,
        CHECKSUM_MISMATCH
    }

    private final Reason reason;

    public StoreException(Reason reason, String message) {
        super(message);
        this.reason = reason;
    }

    public Reason getReason() {
        return reason;
    }
}
