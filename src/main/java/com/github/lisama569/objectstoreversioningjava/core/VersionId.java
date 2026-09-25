package com.github.lisama569.objectstoreversioningjava.core;

import java.util.Objects;

/**
 * 版本号：每个 key 独立计数，从 1 起单调递增，只增不复用。
 * 0 或负数非法——按版本读取时会以 {@link VersionNotFoundException} 被拒绝。
 */
public record VersionId(long value) implements Comparable<VersionId> {

    public VersionId {
        if (value <= 0) {
            throw new VersionNotFoundException("版本号必须为正整数，实际=" + value);
        }
    }

    public static VersionId of(long v) {
        return new VersionId(v);
    }

    @Override
    public int compareTo(VersionId o) {
        return Long.compare(this.value, Objects.requireNonNull(o).value);
    }
}
