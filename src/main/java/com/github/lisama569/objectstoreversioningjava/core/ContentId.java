package com.github.lisama569.objectstoreversioningjava.core;

import java.util.Locale;
import java.util.Objects;

/**
 * 内容地址：内容字节的 SHA-256 摘要（64 位小写十六进制）。
 * 相同内容必然得到相同 ContentId——这是“相同内容只保留一份底层数据”的去重键。
 */
public record ContentId(String sha256) {

    public static final int HEX_LENGTH = 64;

    public ContentId {
        Objects.requireNonNull(sha256, "sha256");
        if (sha256.length() != HEX_LENGTH) {
            throw new IllegalArgumentException("内容地址必须是 64 位十六进制，实际长度=" + sha256.length());
        }
        for (int i = 0; i < sha256.length(); i++) {
            char c = sha256.charAt(i);
            boolean hex = (c >= '0' && c <= '9') || (c >= 'a' && c <= 'f');
            if (!hex) {
                throw new IllegalArgumentException("内容地址含非小写十六进制字符: " + c);
            }
        }
    }

    public static ContentId of(String hex) {
        return new ContentId(Objects.requireNonNull(hex).toLowerCase(Locale.ROOT));
    }
}
