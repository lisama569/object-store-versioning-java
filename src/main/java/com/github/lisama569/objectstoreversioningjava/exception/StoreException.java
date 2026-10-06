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
        CHECKSUM_MISMATCH,
        /** 保留规则本身不合法或与最低保留要求冲突。 */
        INVALID_RETENTION_POLICY,
        /** 恢复目标版本的元数据尚在，但底层数据已被物理回收，无法恢复。 */
        VERSION_DATA_RECLAIMED
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
