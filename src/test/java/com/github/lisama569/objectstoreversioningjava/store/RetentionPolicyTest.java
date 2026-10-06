package com.github.lisama569.objectstoreversioningjava.store;

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
 * per-key 保留规则：
 * - 非法规则（负值、空规则、与最低保留要求冲突）被明确拒绝，原因可区分；
 * - 数量规则：超出最近 N 个版本的历史版本被自动裁剪，保留范围内的版本按版本号可读且内容一致；
 * - 时间规则：保留时长内的版本即使超出数量上限也不被裁剪；
 * - 设置规则立即生效；清除规则回落到全局默认。
 */
class RetentionPolicyTest {

    private static final Logger log = LoggerFactory.getLogger(RetentionPolicyTest.class);

    @Test
    void invalidPoliciesAreRejectedWithDistinguishableReason() throws Exception {
        ObjectStore store = TestSupport.newStore(0, false);
        store.put("k", TestSupport.bytes("v1"));

        // 负值
        StoreException negative = assertThrows(StoreException.class,
                () -> store.setRetentionPolicy("k", new RetentionPolicy(-1, 0)));
        assertEquals(StoreException.Reason.INVALID_RETENTION_POLICY, negative.getReason());
        log.info("rejected negative maxVersions: {}", negative.getMessage());

        StoreException negativeTime = assertThrows(StoreException.class,
                () -> store.setRetentionPolicy("k", new RetentionPolicy(0, -5)));
        assertEquals(StoreException.Reason.INVALID_RETENTION_POLICY, negativeTime.getReason());
        log.info("rejected negative minRetentionMillis: {}", negativeTime.getMessage());

        // 空规则（两条都未设置，等价于不限制，无治理意义）
        StoreException empty = assertThrows(StoreException.class,
                () -> store.setRetentionPolicy("k", RetentionPolicy.unlimited()));
        assertEquals(StoreException.Reason.INVALID_RETENTION_POLICY, empty.getReason());
        log.info("rejected empty policy: {}", empty.getMessage());

        // 与最低保留要求冲突：守卫默认 minVersions=1，maxVersions 不能再低；
        // 构造守卫更严格的 store 验证冲突判定
        ObjectStore guarded = TestSupport.newStoreWithGuards(0, false, 3, 60_000);
        guarded.put("g", TestSupport.bytes("v1"));
        StoreException tooFew = assertThrows(StoreException.class,
                () -> guarded.setRetentionPolicy("g", new RetentionPolicy(2, 0)));
        assertEquals(StoreException.Reason.INVALID_RETENTION_POLICY, tooFew.getReason());
        log.info("rejected maxVersions=2 against guard minVersions=3: {}", tooFew.getMessage());

        StoreException tooShort = assertThrows(StoreException.class,
                () -> guarded.setRetentionPolicy("g", new RetentionPolicy(0, 1_000)));
        assertEquals(StoreException.Reason.INVALID_RETENTION_POLICY, tooShort.getReason());
        log.info("rejected minRetention=1000ms against guard 60000ms: {}", tooShort.getMessage());

        // 合法规则不受影响
        guarded.setRetentionPolicy("g", new RetentionPolicy(3, 60_000));
        assertEquals(3, guarded.retentionPolicy("g").maxVersions());
        log.info("valid policy accepted: maxVersions=3 minRetention=60000ms");
    }

    @Test
    void countRulePrunesOlderVersionsAndRetainedOnesStayReadable() throws Exception {
        ObjectStore store = TestSupport.newStore(0, false);
        store.setRetentionPolicy("k", new RetentionPolicy(2, 0));

        PutResult v1 = store.put("k", TestSupport.bytes("content-1"));
        PutResult v2 = store.put("k", TestSupport.bytes("content-2"));
        PutResult v3 = store.put("k", TestSupport.bytes("content-3"));
        log.info("inputs: v1={} v2={} v3={}, policy=maxVersions 2",
                v1.versionId(), v2.versionId(), v3.versionId());

        // 设置规则后前 3 个版本都在（裁剪发生在下一次变更/设置时），再写一次触发裁剪
        PutResult v4 = store.put("k", TestSupport.bytes("content-4"));
        List<ObjectVersion> retained = store.listVersions("k");
        log.info("after v4={} retained={}", v4.versionId(),
                retained.stream().map(ObjectVersion::versionId).toList());
        assertEquals(List.of(v3.versionId(), v4.versionId()),
                retained.stream().map(ObjectVersion::versionId).toList());

        // 保留范围内的版本按版本号读出且内容一致
        assertEquals("content-3", TestSupport.utf8(store.getVersion("k", v3.versionId())));
        assertEquals("content-4", TestSupport.utf8(store.getVersion("k", v4.versionId())));
        assertEquals("content-4", TestSupport.utf8(store.get("k")));

        // 被裁版本读取给出可区分原因
        StoreException prunedRead = assertThrows(StoreException.class,
                () -> store.getVersion("k", v1.versionId()));
        assertEquals(StoreException.Reason.NO_SUCH_VERSION, prunedRead.getReason());
        log.info("pruned v1 read rejected: {}", prunedRead.getMessage());
    }

    @Test
    void timeFloorProtectsYoungVersionsBeyondCountLimit() throws Exception {
        ObjectStore store = TestSupport.newStore(0, false);
        // 数量上只留 1 个，但时间上要至少保留 1 小时：刚写入的版本全部受时间规则保护
        store.setRetentionPolicy("k", new RetentionPolicy(1, 3_600_000));

        PutResult v1 = store.put("k", TestSupport.bytes("young-1"));
        PutResult v2 = store.put("k", TestSupport.bytes("young-2"));
        PutResult v3 = store.put("k", TestSupport.bytes("young-3"));
        log.info("inputs: v1={} v2={} v3={}, policy=maxVersions 1 + minRetention 1h",
                v1.versionId(), v2.versionId(), v3.versionId());

        List<ObjectVersion> retained = store.listVersions("k");
        assertEquals(3, retained.size(), "time floor must protect young versions beyond count limit");
        assertEquals("young-1", TestSupport.utf8(store.getVersion("k", v1.versionId())));
        assertEquals("young-2", TestSupport.utf8(store.getVersion("k", v2.versionId())));
        log.info("all 3 young versions retained and readable despite maxVersions=1");

        // 把时间规则缩到 0 时长（即取消时间保护）：立即裁剪到只剩最新 1 个
        store.setRetentionPolicy("k", new RetentionPolicy(1, 1));
        Thread.sleep(5); // 让所有版本都“老”于 1ms
        store.enforceRetentionNow();
        retained = store.listVersions("k");
        log.info("after shrinking time floor retained={}",
                retained.stream().map(ObjectVersion::versionId).toList());
        assertEquals(List.of(v3.versionId()),
                retained.stream().map(ObjectVersion::versionId).toList());
        assertEquals("young-3", TestSupport.utf8(store.get("k")));
    }

    @Test
    void perKeyPolicyOverridesGlobalDefaultAndClearFallsBack() throws Exception {
        ObjectStore store = TestSupport.newStore(5, false); // 全局默认留 5 个
        assertEquals(5, store.retentionPolicy("k").maxVersions());

        store.setRetentionPolicy("k", new RetentionPolicy(1, 0));
        assertEquals(1, store.retentionPolicy("k").maxVersions());
        assertEquals(5, store.retentionPolicy("other").maxVersions(),
                "other keys still use the global default");

        PutResult v1 = store.put("k", TestSupport.bytes("a"));
        PutResult v2 = store.put("k", TestSupport.bytes("b"));
        assertEquals(List.of(v2.versionId()),
                store.listVersions("k").stream().map(ObjectVersion::versionId).toList());
        log.info("per-key policy pruned k to newest 1: v1={} pruned, v2={} kept",
                v1.versionId(), v2.versionId());

        store.clearRetentionPolicy("k");
        assertEquals(5, store.retentionPolicy("k").maxVersions(), "cleared -> back to global default");
        PutResult v3 = store.put("k", TestSupport.bytes("c"));
        PutResult v4 = store.put("k", TestSupport.bytes("d"));
        assertEquals(3, store.listVersions("k").size(),
                "global default (5) no longer prunes at 3 versions");
        log.info("after clear: k retains {} versions (v2={} v3={} v4={})",
                store.listVersions("k").size(), v2.versionId(), v3.versionId(), v4.versionId());
    }

    @Test
    void latestVersionIsNeverPrunedEvenWhenAgedOut() throws Exception {
        ObjectStore store = TestSupport.newStore(0, false);
        // 纯时间规则：版本保留 1ms，超时即可回收——但最新版本必须始终保留
        store.setRetentionPolicy("k", new RetentionPolicy(0, 1));
        PutResult v1 = store.put("k", TestSupport.bytes("only-version"));
        Thread.sleep(5);
        int pruned = store.enforceRetentionNow();
        log.info("time-only policy on single-version object: pruned={}", pruned);
        assertEquals(0, pruned, "latest version must never be pruned");
        assertEquals("only-version", TestSupport.utf8(store.get("k")));
        assertTrue(store.listVersions("k").size() == 1);
    }
}
