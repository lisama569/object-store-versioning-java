package com.github.lisama569.objectstoreversioningjava.tests;

import com.github.lisama569.objectstoreversioningjava.core.InvalidKeyException;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;

/** 非法键在边界处被拒绝，原因与“缺失/已删除”可区分；空内容是合法对象。 */
class InvalidKeyAndEdgeTest {

    @Test
    void invalidKeysAreRejectedAtBoundary() {
        var h = StoreTestSupport.newHarness();

        for (String bad : new String[]{null, "", "   ", "\t", "with\nnewline", "ctrl\u0000here"}) {
            String shown = String.valueOf(bad).replace("\n", "\\n").replace("\t", "\\t").replace("\u0000", "\\0");
            InvalidKeyException e = assertThrows(InvalidKeyException.class,
                    () -> h.store().put(bad, new byte[]{1}),
                    "应拒绝键: " + shown);
            StoreTestSupport.log("KEY", "拒绝非法键 '%s'，原因：%s", shown, e.getMessage());
        }

        InvalidKeyException tooLong = assertThrows(InvalidKeyException.class,
                () -> h.store().put("x".repeat(2000), new byte[]{1}));
        StoreTestSupport.log("KEY", "拒绝超长键，原因：%s", tooLong.getMessage());

        // 读/删路径同样校验
        assertThrows(InvalidKeyException.class, () -> h.store().get(""));
        assertThrows(InvalidKeyException.class, () -> h.store().delete(" "));
    }

    @Test
    void emptyContentIsAValidObject() {
        var h = StoreTestSupport.newHarness();
        assertDoesNotThrow(() -> {
            h.store().put("empty", new byte[0]);
            var c = h.store().get("empty");
            if (c.data().length != 0) {
                throw new AssertionError("空内容应读回 0 字节");
            }
        });
        StoreTestSupport.log("KEY", "空字节内容可正常写入与读取（内容地址=空串的 SHA-256）");
    }
}
