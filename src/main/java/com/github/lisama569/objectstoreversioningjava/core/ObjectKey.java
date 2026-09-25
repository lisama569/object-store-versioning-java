package com.github.lisama569.objectstoreversioningjava.core;

import java.util.Objects;

/**
 * 对象键。
 *
 * <p>合法规则：非 null、去空白后非空、长度不超过 maxKeyLength、不含控制字符。
 * 校验在构造时完成，因此任何走到存储层的 ObjectKey 都已合法——
 * 非法键在边界处即以 {@link InvalidKeyException} 被拒绝。
 */
public record ObjectKey(String value) {

    /** 默认最大键长度，可被配置覆盖。 */
    public static final int DEFAULT_MAX_LENGTH = 1024;

    public ObjectKey {
        Objects.requireNonNull(value, "key");
        if (value.isBlank()) {
            throw new InvalidKeyException("对象键不能为空或纯空白");
        }
        if (value.length() > DEFAULT_MAX_LENGTH) {
            throw new InvalidKeyException("对象键长度 " + value.length()
                    + " 超过上限 " + DEFAULT_MAX_LENGTH);
        }
        for (int i = 0; i < value.length(); i++) {
            if (Character.isISOControl(value.charAt(i))) {
                throw new InvalidKeyException("对象键不能包含控制字符，位置=" + i);
            }
        }
    }

    public static ObjectKey of(String raw) {
        if (raw == null) {
            throw new InvalidKeyException("对象键不能为 null");
        }
        return new ObjectKey(raw);
    }

    /** 结构合法性（非空白、无控制字符），长度上限可配置。 */
    public static ObjectKey of(String raw, int maxLength) {
        ObjectKey k = of(raw);
        if (k.value().length() > maxLength) {
            throw new InvalidKeyException("对象键长度 " + k.value().length()
                    + " 超过配置上限 " + maxLength);
        }
        return k;
    }
}
