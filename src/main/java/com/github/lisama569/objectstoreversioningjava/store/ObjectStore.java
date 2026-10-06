package com.github.lisama569.objectstoreversioningjava.store;

import com.github.lisama569.objectstoreversioningjava.config.ObjectStoreProperties;
import com.github.lisama569.objectstoreversioningjava.exception.StoreException;
import com.github.lisama569.objectstoreversioningjava.model.BlobContent;
import com.github.lisama569.objectstoreversioningjava.model.ObjectVersion;
import com.github.lisama569.objectstoreversioningjava.model.PutResult;
import com.github.lisama569.objectstoreversioningjava.model.RetentionPolicy;
import com.github.lisama569.objectstoreversioningjava.model.RestoreResult;
import com.github.lisama569.objectstoreversioningjava.reclaim.SpaceReclaimer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.regex.Pattern;

/**
 * 本地对象存储门面。以本地磁盘保存内容寻址 blob，以进程内元数据表保存版本链，
 * 以引用计数 + 读引脚协调版本保留与空间回收。
 *
 * <h3>语义</h3>
 * <ul>
 *   <li><b>写入</b>：每次 put 产生一个全局单调递增的新版本；内容相同的写入共享同一 blob。
 *       同 key 并发写按 key 分段锁串行，无丢更新；blob 先校验后原子发布，读不到半截数据。</li>
 *   <li><b>读取</b>：{@link #get(String)} 读最新版本；{@link #getVersion(String, long)} 读指定版本。
 *       读取在元数据锁外取快照，再在“读门 + 读引脚”保护下读数据文件。</li>
 *   <li><b>删除</b>：删除即追加一个<b>删除标记版本</b>，历史数据版本仍可按版本号读取，
 *       直到超出版本保留上限被裁剪；删除后 get 报 {@code OBJECT_DELETED}，重新 put 可复活。</li>
 *   <li><b>恢复</b>：{@link #restore(String, long)} 以指定历史版本的内容追加新版本，
 *       恢复后 get 立即可读；底层数据已被物理回收时明确报 {@code VERSION_DATA_RECLAIMED}。</li>
 *   <li><b>保留策略</b>：{@link #setRetentionPolicy(String, RetentionPolicy)} 支持按数量
 *       （最多保留最近 N 版）与按时间（至少保留 T 毫秒）组合；不合法或与守卫下限冲突的规则
 *       明确报 {@code INVALID_RETENTION_POLICY}。</li>
 *   <li><b>回收</b>：版本裁剪使 blob 引用计数归零后只登记待删，{@link #purgeUnreferenced()} 持写门
 *       物理删除，期间不可能有在途读；被多个版本共享的 blob 只要仍被引用就保留。
 *       配置容量/比例阈值后由 {@code SpaceReclaimer} 在后台自动触发，写路径只投递信号不阻塞。</li>
 * </ul>
 */
public class ObjectStore {

    private static final Logger log = LoggerFactory.getLogger(ObjectStore.class);

    private static final int MAX_KEY_LENGTH = 256;
    private static final Pattern ILLEGAL_KEY = Pattern.compile(".*[\\p{Cntrl}].*", Pattern.DOTALL);

    private final ObjectStoreProperties properties;
    private final int maxVersionsPerKey;
    private final boolean autoPurge;
    private final int retentionMinVersions;
    private final long retentionMinRetentionMillis;
    private final BlobStore blobStore;
    private final BlobRegistry registry;
    private final MetadataTable table;
    private final ConcurrentHashMap<String, RetentionPolicy> policies = new ConcurrentHashMap<>();
    private final SpaceReclaimer reclaimer;
    private final AtomicLong versionSequence = new AtomicLong(0);

    public ObjectStore(ObjectStoreProperties properties) {
        this.properties = properties;
        this.maxVersionsPerKey = properties.getMaxVersionsPerKey();
        this.autoPurge = properties.isAutoPurge();
        this.retentionMinVersions = properties.getRetentionMinVersions();
        this.retentionMinRetentionMillis = properties.getRetentionMinRetentionMillis();
        if (retentionMinVersions < 1) {
            throw new IllegalArgumentException(
                    "object-store.retention-min-versions must be >= 1, got " + retentionMinVersions);
        }
        if (retentionMinRetentionMillis < 0) {
            throw new IllegalArgumentException(
                    "object-store.retention-min-retention-millis must be >= 0, got "
                            + retentionMinRetentionMillis);
        }
        this.blobStore = new FileSystemBlobStore(properties.getDataDir());
        this.registry = new BlobRegistry(blobStore);
        this.table = new MetadataTable();
        this.reclaimer = new SpaceReclaimer(properties, registry, this::enforceRetentionNow);
        log.info("ObjectStore ready, dataDir={} maxVersionsPerKey={} autoPurge={} "
                        + "retentionGuards={}versions/{}ms reclaimThresholds={}",
                properties.getDataDir().toAbsolutePath(),
                maxVersionsPerKey <= 0 ? "UNLIMITED" : maxVersionsPerKey, autoPurge,
                retentionMinVersions, retentionMinRetentionMillis,
                reclaimer.thresholdsEnabled() ? "ENABLED" : "DISABLED");
    }

    /**
     * 新增或覆盖对象。
     *
     * @return 新版本号、底层 blobId 以及是否命中内容去重（未产生新底层文件）
     * @throws StoreException 键非法（INVALID_KEY）或写入校验失败（CHECKSUM_MISMATCH）
     */
    public PutResult put(String key, ByteBuffer content) {
        validateKey(key);
        if (content == null) {
            throw new StoreException(StoreException.Reason.INVALID_KEY, "content must not be null");
        }

        // 持读门完成“发布 blob 文件 + 提交元数据 + 引用计数”临界序列：
        // GC 物理回收持写门，无法在文件已发布但引用尚未登记的窗口里删除文件（含待删 blob 被救回的情形）。
        registry.readLock();
        PutResult result;
        try {
            boolean existed = blobStore.exists(calculateBlobId(content));
            String blobId = blobStore.put(content);
            long versionId = versionSequence.incrementAndGet();
            long now = System.currentTimeMillis();

            result = table.mutate(key, current -> {
                List<ObjectVersion> next = new ArrayList<>(current == null ? List.of() : current);
                ObjectVersion version = new ObjectVersion(versionId, blobId, content.remaining(),
                        blobId, now, false);
                next.add(version);
                // 新版本占用一个 blob 引用。
                registry.acquire(blobId);
                List<ObjectVersion> pruned = prune(key, next);
                boolean dedup = existed;
                log.info("put key={} version={} blobId={} size={} dedup={} retainedVersions={}",
                        key, versionId, blobId, content.remaining(), dedup, next.size());
                return new MetadataTable.Mutation<>(next, new PutResult(versionId, blobId, dedup));
            });
        } finally {
            registry.readUnlock();
        }
        // 物理回收持写门，必须在读门之外执行（读锁不可升级为写锁，门内调用会自死锁）。
        if (autoPurge) {
            int removed = registry.purgeUnreferenced().removed();
            if (removed > 0) {
                log.info("auto-purge after put key={} removed={}", key, removed);
            }
        }
        reclaimer.triggerAsync();
        return result;
    }

    /**
     * 读取最新版本。
     *
     * @throws StoreException INVALID_KEY / NO_SUCH_OBJECT / OBJECT_DELETED / CHECKSUM_MISMATCH
     */
    public BlobContent get(String key) {
        validateKey(key);
        List<ObjectVersion> versions = table.snapshot(key);
        if (versions == null || versions.isEmpty()) {
            log.warn("get rejected key={} reason=NO_SUCH_OBJECT", key);
            throw new StoreException(StoreException.Reason.NO_SUCH_OBJECT,
                    "object does not exist: " + key);
        }
        ObjectVersion latest = versions.get(versions.size() - 1);
        if (latest.deleteMarker()) {
            log.warn("get rejected key={} reason=OBJECT_DELETED atVersion={}", key, latest.versionId());
            throw new StoreException(StoreException.Reason.OBJECT_DELETED,
                    "object has been deleted: " + key + " (delete marker version " + latest.versionId()
                            + "); a new put can recreate it");
        }
        log.info("get key={} resolvedVersion={} blobId={}", key, latest.versionId(), latest.blobId());
        return readBlob(key, latest);
    }

    /**
     * 读取指定版本。
     *
     * @throws StoreException INVALID_KEY / NO_SUCH_OBJECT / NO_SUCH_VERSION /
     *                        OBJECT_DELETED / CHECKSUM_MISMATCH
     */
    public BlobContent getVersion(String key, long versionId) {
        validateKey(key);
        List<ObjectVersion> versions = table.snapshot(key);
        if (versions == null || versions.isEmpty()) {
            log.warn("getVersion rejected key={} version={} reason=NO_SUCH_OBJECT", key, versionId);
            throw new StoreException(StoreException.Reason.NO_SUCH_OBJECT,
                    "object does not exist: " + key);
        }
        ObjectVersion found = null;
        for (ObjectVersion v : versions) {
            if (v.versionId() == versionId) {
                found = v;
                break;
            }
        }
        if (found == null) {
            log.warn("getVersion rejected key={} version={} reason=NO_SUCH_VERSION available={}",
                    key, versionId, versions.stream().map(ObjectVersion::versionId).toList());
            throw new StoreException(StoreException.Reason.NO_SUCH_VERSION,
                    "version " + versionId + " does not exist for key: " + key
                            + " (it was never created or already pruned by retention)");
        }
        if (found.deleteMarker()) {
            log.warn("getVersion rejected key={} version={} reason=OBJECT_DELETED (delete marker)",
                    key, versionId);
            throw new StoreException(StoreException.Reason.OBJECT_DELETED,
                    "version " + versionId + " is a delete marker of key: " + key);
        }
        log.info("getVersion key={} version={} blobId={}", key, versionId, found.blobId());
        return readBlob(key, found);
    }

    /**
     * 删除对象：追加删除标记版本。
     *
     * @return 删除标记版本
     * @throws StoreException INVALID_KEY / NO_SUCH_OBJECT / OBJECT_DELETED（重复删除）
     */
    public ObjectVersion delete(String key) {
        validateKey(key);
        registry.readLock();
        ObjectVersion marker;
        try {
            marker = table.mutate(key, current -> {
                if (current == null || current.isEmpty()) {
                    log.warn("delete rejected key={} reason=NO_SUCH_OBJECT", key);
                    throw new StoreException(StoreException.Reason.NO_SUCH_OBJECT,
                            "cannot delete non-existing object: " + key);
                }
                ObjectVersion latest = current.get(current.size() - 1);
                if (latest.deleteMarker()) {
                    log.warn("delete rejected key={} reason=OBJECT_DELETED atVersion={}",
                            key, latest.versionId());
                    throw new StoreException(StoreException.Reason.OBJECT_DELETED,
                            "object already deleted: " + key + " (delete marker version "
                                    + latest.versionId() + ")");
                }
                long versionId = versionSequence.incrementAndGet();
                ObjectVersion m = new ObjectVersion(versionId, null, 0, null,
                        System.currentTimeMillis(), true);
                List<ObjectVersion> next = new ArrayList<>(current);
                next.add(m);
                List<ObjectVersion> pruned = prune(key, next);
                log.info("delete key={} markerVersion={} prunedVersions={} retainedVersions={}",
                        key, versionId, pruned.size(), next.size());
                return new MetadataTable.Mutation<>(next, m);
            });
        } finally {
            registry.readUnlock();
        }
        // 物理回收持写门，必须在读门之外执行（读锁不可升级为写锁，门内调用会自死锁）。
        if (autoPurge) {
            registry.purgeUnreferenced();
        }
        reclaimer.triggerAsync();
        return marker;
    }

    /** 返回某 key 的版本链（不可变快照，含删除标记）。 */
    public List<ObjectVersion> listVersions(String key) {
        validateKey(key);
        List<ObjectVersion> versions = table.snapshot(key);
        if (versions == null) {
            throw new StoreException(StoreException.Reason.NO_SUCH_OBJECT,
                    "object does not exist: " + key);
        }
        return versions;
    }

    /** 物理回收所有无引用 blob，返回删除数量。 */
    public int purgeUnreferenced() {
        int removed = registry.purgeUnreferenced().removed();
        log.info("manual purge removed={} blobs, trackedKeys={}", removed, table.size());
        return removed;
    }

    // ---- 保留策略 ----

    /**
     * 为某个对象设置保留规则。规则立即生效：已超出规则的旧版本会被就地裁剪。
     * 规则不合法或与最低保留要求冲突时抛 {@code INVALID_RETENTION_POLICY}，不套用任何默认值。
     */
    public void setRetentionPolicy(String key, RetentionPolicy policy) {
        validateKey(key);
        if (policy == null) {
            throw new StoreException(StoreException.Reason.INVALID_RETENTION_POLICY,
                    "retention policy must not be null; use clearRetentionPolicy to remove it");
        }
        policy.validate(retentionMinVersions, retentionMinRetentionMillis);
        policies.put(key, policy);
        log.info("retention policy set key={} maxVersions={} minRetention={}ms "
                        + "(guards: {}versions/{}ms)",
                key, policy.maxVersions() <= 0 ? "UNLIMITED" : policy.maxVersions(),
                policy.minRetentionMillis(), retentionMinVersions, retentionMinRetentionMillis);
        // 立即按新规则裁剪已有版本。
        registry.readLock();
        try {
            table.mutate(key, current -> {
                if (current == null) {
                    return new MetadataTable.Mutation<>(null, null);
                }
                List<ObjectVersion> next = new ArrayList<>(current);
                prune(key, next);
                return new MetadataTable.Mutation<>(next, null);
            });
        } finally {
            registry.readUnlock();
        }
    }

    /** 返回某 key 当前生效的保留规则（per-key 覆盖优先，否则为全局默认）。 */
    public RetentionPolicy retentionPolicy(String key) {
        validateKey(key);
        return effectivePolicy(key);
    }

    /** 移除某 key 的 per-key 保留规则，回落到全局默认。 */
    public void clearRetentionPolicy(String key) {
        validateKey(key);
        if (policies.remove(key) != null) {
            log.info("retention policy cleared key={}, falling back to global default", key);
        }
    }

    private RetentionPolicy effectivePolicy(String key) {
        RetentionPolicy policy = policies.get(key);
        if (policy != null) {
            return policy;
        }
        return new RetentionPolicy(Math.max(maxVersionsPerKey, 0), 0L);
    }

    // ---- 删除恢复 ----

    /**
     * 把对象恢复到指定历史版本：以该版本的内容追加一个<b>新版本</b>（不是回滚，
     * 历史版本链保持连续），恢复后 {@link #get(String)} 即可读到该内容。
     *
     * @throws StoreException NO_SUCH_OBJECT（key 不存在）/
     *                        NO_SUCH_VERSION（版本从未存在或归档已溢出）/
     *                        OBJECT_DELETED（目标版本是删除标记）/
     *                        VERSION_DATA_RECLAIMED（版本元数据尚在归档，但底层数据已被物理回收）
     */
    public RestoreResult restore(String key, long targetVersionId) {
        validateKey(key);
        registry.readLock();
        RestoreResult result;
        try {
            result = table.mutate(key, current -> {
                if (current == null || current.isEmpty()) {
                    log.warn("restore rejected key={} targetVersion={} reason=NO_SUCH_OBJECT",
                            key, targetVersionId);
                    throw new StoreException(StoreException.Reason.NO_SUCH_OBJECT,
                            "object does not exist: " + key);
                }
                ObjectVersion target = null;
                for (ObjectVersion v : current) {
                    if (v.versionId() == targetVersionId) {
                        target = v;
                        break;
                    }
                }
                boolean fromArchive = false;
                if (target == null) {
                    for (ObjectVersion v : table.archivedSnapshot(key)) {
                        if (v.versionId() == targetVersionId) {
                            target = v;
                            fromArchive = true;
                            break;
                        }
                    }
                }
                if (target == null) {
                    log.warn("restore rejected key={} targetVersion={} reason=NO_SUCH_VERSION",
                            key, targetVersionId);
                    throw new StoreException(StoreException.Reason.NO_SUCH_VERSION,
                            "version " + targetVersionId + " does not exist for key: " + key
                                    + " (it was never created or its archived metadata has expired)");
                }
                if (target.deleteMarker()) {
                    log.warn("restore rejected key={} targetVersion={} reason=OBJECT_DELETED "
                                    + "(cannot restore to a delete marker)", key, targetVersionId);
                    throw new StoreException(StoreException.Reason.OBJECT_DELETED,
                            "version " + targetVersionId + " is a delete marker of key: " + key
                                    + "; restore target must be a data version");
                }
                if (fromArchive) {
                    // 已裁剪版本：blob 可能仍在（被其他版本引用或待删未清），也可能已被物理回收。
                    if (!registry.tryAcquireIfPresent(target.blobId())) {
                        log.warn("restore rejected key={} targetVersion={} blobId={} "
                                        + "reason=VERSION_DATA_RECLAIMED",
                                key, targetVersionId, target.blobId());
                        throw new StoreException(StoreException.Reason.VERSION_DATA_RECLAIMED,
                                "cannot restore key " + key + " to version " + targetVersionId
                                        + ": underlying blob " + target.blobId()
                                        + " has been physically reclaimed");
                    }
                } else {
                    // 新版本与目标版本共享同一 blob。
                    registry.acquire(target.blobId());
                }
                long newVersionId = versionSequence.incrementAndGet();
                ObjectVersion restored = new ObjectVersion(newVersionId, target.blobId(),
                        target.size(), target.checksum(), System.currentTimeMillis(), false);
                List<ObjectVersion> next = new ArrayList<>(current);
                next.add(restored);
                prune(key, next);
                log.info("restore key={} fromVersion={} newVersion={} blobId={} fromArchive={}",
                        key, targetVersionId, newVersionId, target.blobId(), fromArchive);
                return new MetadataTable.Mutation<>(next,
                        new RestoreResult(newVersionId, targetVersionId, target.blobId()));
            });
        } finally {
            registry.readUnlock();
        }
        // 物理回收持写门，必须在读门之外执行（读锁不可升级为写锁，门内调用会自死锁）。
        if (autoPurge) {
            registry.purgeUnreferenced();
        }
        reclaimer.triggerAsync();
        return result;
    }

    /**
     * 对所有 key 强制执行一次保留策略裁剪（时间规则随时间推移才会裁出新版本）。
     * 由空间回收器在阈值越界时调用，也可手动触发。返回裁剪掉的版本总数。
     */
    public int enforceRetentionNow() {
        int total = 0;
        for (String key : table.keys()) {
            registry.readLock();
            try {
                total += table.mutate(key, current -> {
                    if (current == null) {
                        return new MetadataTable.Mutation<>(null, 0);
                    }
                    List<ObjectVersion> next = new ArrayList<>(current);
                    List<ObjectVersion> pruned = prune(key, next);
                    return new MetadataTable.Mutation<>(next, pruned.size());
                });
            } finally {
                registry.readUnlock();
            }
        }
        if (total > 0) {
            log.info("enforceRetentionNow pruned={} versions across all keys", total);
        }
        return total;
    }

    /** 空间回收器入口（阈值检查 + 必要时回收），供测试与运维手动触发。 */
    public SpaceReclaimer reclaimer() {
        return reclaimer;
    }

    /** 关闭后台回收线程。 */
    public void close() {
        reclaimer.close();
    }

    // ---- internals ----

    private BlobContent readBlob(String key, ObjectVersion version) {
        String blobId = version.blobId();
        registry.readLock();
        boolean pinned = false;
        try {
            if (!registry.pinForRead(blobId)) {
                // 正常流程下版本链中保留的版本必仍有引用；走到这里意味着数据已被回收，显式拒绝。
                log.error("read rejected key={} version={} blobId={} reason=version data reclaimed",
                        key, version.versionId(), blobId);
                throw new StoreException(StoreException.Reason.NO_SUCH_VERSION,
                        "data of version " + version.versionId() + " for key " + key
                                + " has been reclaimed (blobId=" + blobId + ")");
            }
            pinned = true;
            BlobContent content = blobStore.get(blobId);
            if (content == null) {
                log.error("read inconsistency key={} version={} blobId={}: pin granted but file missing",
                        key, version.versionId(), blobId);
                throw new StoreException(StoreException.Reason.NO_SUCH_VERSION,
                        "data file missing unexpectedly for version " + version.versionId());
            }
            log.info("read ok key={} version={} blobId={} size={} checksumVerified=true",
                    key, version.versionId(), blobId, content.size());
            return content;
        } finally {
            if (pinned) {
                registry.unpin(blobId);
            }
            registry.readUnlock();
        }
    }

    /**
     * 按有效保留策略裁剪版本链：一个历史版本只有在“数量规则”和“时间规则”都允许回收时
     * 才会被裁掉；链尾最新版本永不裁剪。被裁版本的 blob 引用立即释放（登记待删），
     * 版本元数据进入归档，供 restore 给出可区分的失败原因。
     * 必须在该 key 的元数据分段锁内调用。
     *
     * @return 被裁剪的版本列表
     */
    private List<ObjectVersion> prune(String key, List<ObjectVersion> versions) {
        RetentionPolicy policy = effectivePolicy(key);
        if (policy.isUnlimited() || versions.size() <= 1) {
            return List.of();
        }
        long now = System.currentTimeMillis();
        int size = versions.size();
        List<ObjectVersion> pruned = new ArrayList<>();
        // 链尾（最新版本）不参与裁剪，保证对象当前状态始终可解释。
        for (int i = 0; i < size - 1; i++) {
            ObjectVersion v = versions.get(i);
            boolean keptByCount = policy.maxVersions() > 0 && i >= size - policy.maxVersions();
            boolean keptByTime = policy.minRetentionMillis() > 0
                    && now - v.createdAt() < policy.minRetentionMillis();
            if (!keptByCount && !keptByTime) {
                pruned.add(v);
            }
        }
        if (pruned.isEmpty()) {
            return List.of();
        }
        versions.removeAll(pruned);
        for (ObjectVersion v : pruned) {
            if (!v.deleteMarker()) {
                registry.release(v.blobId());
            }
        }
        table.archive(key, pruned);
        log.info("retention prune key={} policy=[maxVersions={}, minRetention={}ms] "
                        + "prunedVersions={} retainedVersions={}",
                key, policy.maxVersions() <= 0 ? "UNLIMITED" : policy.maxVersions(),
                policy.minRetentionMillis(),
                pruned.stream().map(ObjectVersion::versionId).toList(),
                versions.stream().map(ObjectVersion::versionId).toList());
        return pruned;
    }

    private void validateKey(String key) {
        if (key == null || key.isEmpty() || key.isBlank() || key.length() > MAX_KEY_LENGTH
                || ILLEGAL_KEY.matcher(key).matches()) {
            throw new StoreException(StoreException.Reason.INVALID_KEY,
                    "illegal key: " + (key == null ? "null" : "'" + key + "'")
                            + " (must be non-blank, length 1.." + MAX_KEY_LENGTH
                            + ", no control characters)");
        }
    }

    private static String calculateBlobId(ByteBuffer content) {
        byte[] bytes = new byte[content.remaining()];
        content.duplicate().get(bytes);
        return FileSystemBlobStore.sha256Hex(bytes);
    }

    // ---- 测试/诊断可见性 ----

    BlobRegistry registry() {
        return registry;
    }

    MetadataTable metadata() {
        return table;
    }

    BlobStore blobStore() {
        return blobStore;
    }

    ObjectStoreProperties properties() {
        return properties;
    }
}
