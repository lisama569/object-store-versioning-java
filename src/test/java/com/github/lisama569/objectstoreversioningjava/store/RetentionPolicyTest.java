package com.github.lisama569.objectstoreversioningjava.store;

import com.github.lisama569.objectstoreversioningjava.config.ObjectStoreProperties;
import com.github.lisama569.objectstoreversioningjava.exception.StoreException;
import com.github.lisama569.objectstoreversioningjava.model.ObjectVersion;
import com.github.lisama569.objectstoreversioningjava.model.PutResult;
import com.github.lisama569.objectstoreversioningjava.model.RetentionPolicy;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 对象级保留规则：配置校验（非法规则显式拒绝）与双维度裁剪边界。
 * 时钟可推进，版本年龄完全确定。
 */
class RetentionPolicyTest {

    private static final Logger log = LoggerFactory.getLogger(RetentionPolicyTest.class);

    private static List<Long> versionIds(ObjectStore store, String key) {
        return store.listVersions(key).stream().map(ObjectVersion::versionId).toList();
    }

    @Test
    void invalidPoliciesAreRejectedWithDistinguishableReason() throws Exception {
        ObjectStoreProperties props = TestSupport.newProps();
        props.setMinRetentionFloorMillis(60_000); // 全店最低保留要求：至少保留 60s
        ObjectStore store = TestSupport.newStoreWithClock(props, new TestSupport.MutableClock(0));
        store.put("k", TestSupport.bytes("v1"));

        // 空规则：两个维度都未指定
        StoreException empty = assertThrows(StoreException.class,
                () -> store.setRetentionPolicy("k", new RetentionPolicy(null, null)));
        assertEquals(StoreException.Reason.INVALID_RETENTION_POLICY, empty.getReason());
        log.info("rejected empty policy: {}", empty.getMessage());

        // maxVersions < 1：最新版本必须始终保留
        StoreException zero = assertThrows(StoreException.class,
                () -> store.setRetentionPolicy("k", new RetentionPolicy(0, null)));
        assertEquals(StoreException.Reason.INVALID_RETENTION_POLICY, zero.getReason());
        log.info("rejected maxVersions=0: {}", zero.getMessage());

        // 负的保留时长
        StoreException negative = assertThrows(StoreException.class,
                () -> store.setRetentionPolicy("k", new RetentionPolicy(null, -1L)));
        assertEquals(StoreException.Reason.INVALID_RETENTION_POLICY, negative.getReason());
        log.info("rejected negative minRetention: {}", negative.getMessage());

        // 与最低保留要求冲突：10s < 60s floor，必须拒绝而不是悄悄套用默认值
        StoreException conflict = assertThrows(StoreException.class,
                () -> store.setRetentionPolicy("k", new RetentionPolicy(null, 10_000L)));
        assertEquals(StoreException.Reason.INVALID_RETENTION_POLICY, conflict.getReason());
        assertTrue(conflict.getMessage().contains("minimum retention requirement"),
                "message must explain the conflict: " + conflict.getMessage());
        log.info("rejected below-floor policy: {}", conflict.getMessage());

        // 以上拒绝都不能留下任何已生效的规则
        assertEquals(RetentionPolicy.NONE, store.retentionPolicy("k"));

        // 合法规则：恰好等于 floor 可以接受
        store.setRetentionPolicy("k", new RetentionPolicy(5, 60_000L));
        assertEquals(new RetentionPolicy(5, 60_000L), store.retentionPolicy("k"));
        // 传 null 清除规则，回落全局默认
        store.setRetentionPolicy("k", null);
        assertEquals(RetentionPolicy.NONE, store.retentionPolicy("k"));
        log.info("verdict: invalid policies rejected with INVALID_RETENTION_POLICY, "
                + "valid policy accepted, null clears");
    }

    @Test
    void minRetentionProtectsYoungVersionsBeyondCountLimit() throws Exception {
        ObjectStoreProperties props = TestSupport.newProps();
        ObjectStore store = TestSupport.newStoreWithClock(props, new TestSupport.MutableClock(1_000_000));
        // 规则：最多 2 个版本，且每个版本至少保留 100s
        store.setRetentionPolicy("k", new RetentionPolicy(2, 100_000L));

        PutResult v1 = store.put("k", TestSupport.bytes("content-1"));
        PutResult v2 = store.put("k", TestSupport.bytes("content-2"));
        PutResult v3 = store.put("k", TestSupport.bytes("content-3"));
        log.info("inputs: v1={} v2={} v3={} (maxVersions=2, minRetention=100s, all young)",
                v1.versionId(), v2.versionId(), v3.versionId());

        // 虽然数量已超（3 > 2），但所有版本年龄都 < 100s：一个都不许裁
        assertEquals(List.of(v1.versionId(), v2.versionId(), v3.versionId()), versionIds(store, "k"));
        assertEquals("content-1", TestSupport.utf8(store.getVersion("k", v1.versionId())),
                "retained-by-age version must remain readable with exact content");
        log.info("judgement: count exceeded but all versions younger than minRetention -> nothing pruned");
    }

    @Test
    void agedOutVersionsArePrunedOnlyAfterMinRetentionPasses() throws Exception {
        TestSupport.MutableClock clock = new TestSupport.MutableClock(1_000_000);
        ObjectStore store = TestSupport.newStoreWithClock(TestSupport.newProps(), clock);
        store.setRetentionPolicy("k", new RetentionPolicy(3, 100_000L));

        PutResult v1 = store.put("k", TestSupport.bytes("content-1"));
        PutResult v2 = store.put("k", TestSupport.bytes("content-2"));
        PutResult v3 = store.put("k", TestSupport.bytes("content-3"));

        // 推进 101s 后再写 v4：v1 年龄 101s >= 100s 且比它新的有 3 个（达到上限）-> 可裁；
        // v2 同龄但比它新的只有 2 个（未达上限 3）-> 仍受数量维度保护
        clock.advanceBy(101_000);
        PutResult v4 = store.put("k", TestSupport.bytes("content-4"));
        log.info("inputs: v1..v4={},{},{},{} clock advanced 101s before v4",
                v1.versionId(), v2.versionId(), v3.versionId(), v4.versionId());

        assertEquals(List.of(v2.versionId(), v3.versionId(), v4.versionId()), versionIds(store, "k"));
        StoreException gone = assertThrows(StoreException.class,
                () -> store.getVersion("k", v1.versionId()));
        assertEquals(StoreException.Reason.NO_SUCH_VERSION, gone.getReason());
        assertEquals("content-2", TestSupport.utf8(store.getVersion("k", v2.versionId())),
                "v2 retained by count dimension, content must match");
        log.info("judgement: v1 pruned (both dimensions exceeded), v2 kept (count dimension), "
                + "v2 content verified");
    }

    @Test
    void ageOnlyPolicyPrunesOldVersionsButNeverTheLatest() throws Exception {
        TestSupport.MutableClock clock = new TestSupport.MutableClock(1_000_000);
        ObjectStore store = TestSupport.newStoreWithClock(TestSupport.newProps(), clock);
        // 只配时长维度：版本至少保留 50s，超龄即允许回收
        store.setRetentionPolicy("k", new RetentionPolicy(null, 50_000L));

        PutResult v1 = store.put("k", TestSupport.bytes("content-1"));
        clock.advanceBy(60_000); // v1 超龄
        PutResult v2 = store.put("k", TestSupport.bytes("content-2"));
        log.info("inputs: v1={} (age 60s > 50s) v2={} (newborn)", v1.versionId(), v2.versionId());

        // v1 超龄被裁；v2 是最新版本，即使未来超龄也永不裁
        assertEquals(List.of(v2.versionId()), versionIds(store, "k"));
        assertEquals("content-2", TestSupport.utf8(store.get("k")));

        clock.advanceBy(3600_000); // v2 也远远超龄
        PutResult v3 = store.put("k", TestSupport.bytes("content-3"));
        assertEquals(List.of(v3.versionId()), versionIds(store, "k"));
        // 最新版本永远可读，无论年龄
        assertEquals("content-3", TestSupport.utf8(store.get("k")));
        log.info("judgement: age-only policy prunes aged versions, latest version always retained");
    }

    @Test
    void policyAppliesRetroactivelyAndPerKeyIndependently() throws Exception {
        TestSupport.MutableClock clock = new TestSupport.MutableClock(1_000_000);
        ObjectStore store = TestSupport.newStoreWithClock(TestSupport.newProps(), clock);

        PutResult a1 = store.put("a", TestSupport.bytes("a-1"));
        PutResult a2 = store.put("a", TestSupport.bytes("a-2"));
        PutResult a3 = store.put("a", TestSupport.bytes("a-3"));
        PutResult b1 = store.put("b", TestSupport.bytes("b-1"));
        log.info("inputs: key a has 3 versions {}, key b has 1 version",
                List.of(a1.versionId(), a2.versionId(), a3.versionId()));

        // 给 a 配规则：立即按新规则裁剪（a1 出局）；b 无规则不受影响
        store.setRetentionPolicy("a", new RetentionPolicy(2, null));
        assertEquals(List.of(a2.versionId(), a3.versionId()), versionIds(store, "a"));
        assertEquals(List.of(b1.versionId()), versionIds(store, "b"));
        assertEquals("a-2", TestSupport.utf8(store.getVersion("a", a2.versionId())));
        log.info("judgement: policy applied retroactively on set; other keys untouched");
    }
}
