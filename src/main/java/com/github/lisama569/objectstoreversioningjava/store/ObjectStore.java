package com.github.lisama569.objectstoreversioningjava.store;

import com.github.lisama569.objectstoreversioningjava.config.ObjectStoreProperties;
import com.github.lisama569.objectstoreversioningjava.exception.StoreException;
import com.github.lisama569.objectstoreversioningjava.model.BlobContent;
import com.github.lisama569.objectstoreversioningjava.model.ObjectVersion;
import com.github.lisama569.objectstoreversioningjava.model.PutResult;
import com.github.lisama569.objectstoreversioningjava.model.RetentionPolicy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.FileStore;
import java.time.Clock;
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
 *   <li><b>回收</b>：版本裁剪使 blob 引用计数归零后只登记待删，{@link #purgeUnreferenced()} 持写门
 *       物理删除，期间不可能有在途读；被多个版本共享的 blob 只要仍被引用就保留。</li>
 * </ul>
 */
public class ObjectStore {

    private static final Logger log = LoggerFactory.getLogger(ObjectStore.class);

    private static final int MAX_KEY_LENGTH = 256;
    private static final Pattern ILLEGAL_KEY = Pattern.compile(".*[\\p{Cntrl}].*", Pattern.DOTALL);

    private final ObjectStoreProperties properties;
    private final int maxVersionsPerKey;
    private final boolean autoPurge;
    private final BlobStore blobStore;
    private final BlobRegistry registry;
    private final MetadataTable table;
    private final AtomicLong versionSequence = new AtomicLong(0);
    private final ConcurrentHashMap<String, RetentionPolicy> retentionPolicies = new ConcurrentHashMap<>();
    /**
     * 已裁剪版本的归档（key -> 被裁版本的元数据，按版本号升序）。
     * 仅保留元数据用于恢复判定：被裁版本不再可读，但只要其 blob 尚未物理回收，
     * {@link #restore} 仍能把它救回；已物理回收的则明确报 VERSION_DATA_RECLAIMED。
     */
    private final ConcurrentHashMap<String, List<ObjectVersion>> archive = new ConcurrentHashMap<>();
    private final Clock clock;

    public ObjectStore(ObjectStoreProperties properties) {
        this(properties, Clock.systemUTC());
    }

    /** 测试专用：注入时钟以确定性地控制版本年龄（保留规则的“至少保留时长”维度）。 */
    ObjectStore(ObjectStoreProperties properties, Clock clock) {
        this.properties = properties;
        this.clock = clock;
        this.maxVersionsPerKey = properties.getMaxVersionsPerKey();
        this.autoPurge = properties.isAutoPurge();
        this.blobStore = new FileSystemBlobStore(properties.getDataDir());
        this.registry = new BlobRegistry(blobStore);
        this.table = new MetadataTable();
        log.info("ObjectStore ready, dataDir={} maxVersionsPerKey={} autoPurge={} "
                        + "defaultMinRetentionMillis={} minRetentionFloorMillis={} "
                        + "reclaimMaxBytes={} reclaimMaxUsageRatio={}",
                properties.getDataDir().toAbsolutePath(),
                maxVersionsPerKey <= 0 ? "UNLIMITED" : maxVersionsPerKey, autoPurge,
                properties.getDefaultMinRetentionMillis(), properties.getMinRetentionFloorMillis(),
                properties.getReclaimMaxBytes() <= 0 ? "DISABLED" : properties.getReclaimMaxBytes(),
                ratioEnabled() ? properties.getReclaimMaxUsageRatio() : "DISABLED");
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
        PutResult result;
        registry.readLock();
        try {
            boolean existed = blobStore.exists(calculateBlobId(content));
            String blobId = blobStore.put(content);
            long versionId = versionSequence.incrementAndGet();
            long now = clock.millis();

            result = table.mutate(key, current -> {
                List<ObjectVersion> next = new ArrayList<>(current == null ? List.of() : current);
                ObjectVersion version = new ObjectVersion(versionId, blobId, content.remaining(),
                        blobId, now, false);
                next.add(version);
                // 新版本占用一个 blob 引用。
                registry.acquire(blobId, content.remaining());
                List<ObjectVersion> pruned = prune(key, next);
                boolean dedup = existed;
                log.info("put key={} version={} blobId={} size={} dedup={} retainedVersions={}",
                        key, versionId, blobId, content.remaining(), dedup, next.size());
                return new MetadataTable.Mutation<>(next, new PutResult(versionId, blobId, dedup));
            });
        } finally {
            registry.readUnlock();
        }
        // 回收触发必须在读门之外：物理回收要获取写门，持读门等写门会自我死锁。
        maybeAutoReclaim("put key=" + key);
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
        ObjectVersion marker;
        registry.readLock();
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
                        clock.millis(), true);
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
        // 读门之外触发回收（避免读门->写门升级死锁）。
        maybeAutoReclaim("delete key=" + key);
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
        int removed = registry.purgeUnreferenced();
        log.info("manual purge removed={} blobs, trackedKeys={}", removed, table.size());
        return removed;
    }

    // ---- 治理能力 ----

    /**
     * 为对象设置保留规则；传 null 表示清除对象级规则（回落到全局默认）。
     * 设置后立即对该 key 按新规则做一次裁剪（只裁规则允许裁的，绝不提前裁）。
     *
     * @throws StoreException INVALID_KEY / INVALID_RETENTION_POLICY
     */
    public void setRetentionPolicy(String key, RetentionPolicy policy) {
        validateKey(key);
        if (policy == null) {
            retentionPolicies.remove(key);
            log.info("retention-policy cleared key={} (fall back to global defaults)", key);
            return;
        }
        validatePolicy(key, policy);
        retentionPolicies.put(key, policy);
        log.info("retention-policy set key={} maxVersions={} minRetentionMillis={}",
                key, policy.maxVersions(), policy.minRetentionMillis());
        // 立即按新规则裁剪一次（在 key 锁内，与写/删串行）。
        registry.readLock();
        try {
            table.mutate(key, current -> {
                if (current == null) {
                    return new MetadataTable.Mutation<>(null, null);
                }
                List<ObjectVersion> next = new ArrayList<>(current);
                List<ObjectVersion> pruned = prune(key, next);
                log.info("retention-policy applied key={} prunedNow={} retained={}",
                        key, pruned.size(), next.size());
                return new MetadataTable.Mutation<>(next, null);
            });
        } finally {
            registry.readUnlock();
        }
    }

    /** 返回该 key 当前生效的对象级保留规则；未配置返回 {@link RetentionPolicy#NONE}。 */
    public RetentionPolicy retentionPolicy(String key) {
        validateKey(key);
        return retentionPolicies.getOrDefault(key, RetentionPolicy.NONE);
    }

    /**
     * 把（通常已删除的）对象恢复到指定历史版本：追加一个内容与目标版本一致的新版本，
     * 恢复后 {@link #get(String)} 即可读到该版本内容。目标版本的底层数据仍在保留期内
     * （含“已裁剪但尚未物理回收”的待删数据，会被救回）才能恢复；已被物理回收的，
     * 显式报 {@code VERSION_DATA_RECLAIMED}，绝不返回看似成功却读不出内容的结果。
     *
     * @return 恢复产生的新版本
     * @throws StoreException INVALID_KEY / NO_SUCH_OBJECT / NO_SUCH_VERSION /
     *                        OBJECT_DELETED（目标是删除标记）/ VERSION_DATA_RECLAIMED
     */
    public ObjectVersion restore(String key, long versionId) {
        validateKey(key);
        ObjectVersion restored;
        registry.readLock();
        try {
            restored = table.mutate(key, current -> {
                if (current == null || current.isEmpty()) {
                    log.warn("restore rejected key={} version={} reason=NO_SUCH_OBJECT", key, versionId);
                    throw new StoreException(StoreException.Reason.NO_SUCH_OBJECT,
                            "cannot restore non-existing object: " + key);
                }
                ObjectVersion target = null;
                for (ObjectVersion v : current) {
                    if (v.versionId() == versionId) {
                        target = v;
                        break;
                    }
                }
                if (target == null) {
                    // 活跃链上没有：查裁剪归档。归档里有，说明它曾被保留规则裁掉——
                    // 底层数据若尚未物理回收仍可救回，已回收则报可区分的 VERSION_DATA_RECLAIMED。
                    ObjectVersion archived = findArchived(key, versionId);
                    if (archived == null) {
                        log.warn("restore rejected key={} version={} reason=NO_SUCH_VERSION available={}",
                                key, versionId,
                                current.stream().map(ObjectVersion::versionId).toList());
                        throw new StoreException(StoreException.Reason.NO_SUCH_VERSION,
                                "version " + versionId + " does not exist for key: " + key
                                        + " (it was never created)");
                    }
                    target = archived;
                }
                if (target.deleteMarker()) {
                    log.warn("restore rejected key={} version={} reason=OBJECT_DELETED "
                                    + "(target is a delete marker, no data to restore)", key, versionId);
                    throw new StoreException(StoreException.Reason.OBJECT_DELETED,
                            "version " + versionId + " is a delete marker of key: " + key
                                    + "; restore target must be a data version");
                }
                // 关键判定：目标版本的底层数据必须仍然可用（活跃或待删未清可救回）。
                if (!registry.tryAcquire(target.blobId())) {
                    log.error("restore rejected key={} version={} blobId={} "
                                    + "reason=VERSION_DATA_RECLAIMED", key, versionId, target.blobId());
                    throw new StoreException(StoreException.Reason.VERSION_DATA_RECLAIMED,
                            "data of version " + versionId + " for key " + key
                                    + " has been physically reclaimed (blobId=" + target.blobId()
                                    + "); restore is impossible");
                }
                long newVersionId = versionSequence.incrementAndGet();
                ObjectVersion restoredVersion = new ObjectVersion(newVersionId, target.blobId(),
                        target.size(), target.checksum(), clock.millis(), false);
                List<ObjectVersion> next = new ArrayList<>(current);
                next.add(restoredVersion);
                prune(key, next);
                log.info("restore ok key={} fromVersion={} newVersion={} blobId={} retained={}",
                        key, versionId, newVersionId, target.blobId(), next.size());
                return new MetadataTable.Mutation<>(next, restoredVersion);
            });
        } finally {
            registry.readUnlock();
        }
        // 读门之外触发回收（避免读门->写门升级死锁）。
        maybeAutoReclaim("restore key=" + key);
        return restored;
    }

    /**
     * 立即执行一轮治理回收：先按保留规则（对象级规则优先，回落全局默认）裁剪全部 key
     * 的过期版本，再物理回收无引用 blob。自动回收（容量/比例阈值触发）与手动调用共用
     * 此路径：只依据当前状态做判定，可安全重入、可重复执行，不重复计数、不误删有引用数据。
     *
     * @return 物理删除的 blob 数量
     */
    public int reclaimNow() {
        // 第一阶段：保留规则裁剪（读门内，与并发读写并行不悖，只阻塞 purge 的写门）。
        registry.readLock();
        try {
            for (String key : table.keys()) {
                table.mutate(key, current -> {
                    if (current == null) {
                        return new MetadataTable.Mutation<>(null, null);
                    }
                    List<ObjectVersion> next = new ArrayList<>(current);
                    List<ObjectVersion> pruned = prune(key, next);
                    if (!pruned.isEmpty()) {
                        log.info("reclaim sweep key={} prunedVersions={} retained={}",
                                key, pruned.stream().map(ObjectVersion::versionId).toList(),
                                next.size());
                    }
                    return new MetadataTable.Mutation<>(next, null);
                });
            }
        } finally {
            registry.readUnlock();
        }
        // 第二阶段：物理回收无引用 blob（逐 blob 写门，读写只在单个 blob 删除瞬间被排队）。
        int removed = registry.purgeUnreferenced();
        log.info("reclaim finished removed={} trackedBytes={} trackedBlobs={}",
                removed, registry.trackedBytes(), registry.trackedBlobCount());
        return removed;
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
     * 按保留规则裁剪版本链（原地删除 list 头部可裁元素），并同步释放被裁版本的 blob 引用。
     * 必须在该 key 的元数据分段锁内、且持有读门时调用。
     *
     * <p>判定（对象级规则优先，未配置的维度回落到全局默认）：版本 i 可裁，当且仅当
     * <ul>
     *   <li>它不是链上最新版本（任何规则都不会裁掉最新版本，保证对象始终可读）；</li>
     *   <li>数量维度：已配置 maxVersions 时，比它新的版本数必须达到 maxVersions；
     *       未配置 maxVersions 时，该维度只在“至少保留时长 &gt; 0”时放行（否则等于不限制）；</li>
     *   <li>时长维度：已配置 minRetentionMillis(&gt;0) 时，版本年龄必须已达到该时长；
     *       未配置（或 &lt;=0）时该维度直接放行。</li>
     * </ul>
     * 即两个维度同时越界才允许回收；任一维度仍在保留范围内的版本必须能正常按版本读出。
     */
    private List<ObjectVersion> prune(String key, List<ObjectVersion> versions) {
        RetentionPolicy policy = retentionPolicies.getOrDefault(key, RetentionPolicy.NONE);
        int maxVersions = policy.maxVersions() != null ? policy.maxVersions() : maxVersionsPerKey;
        long minRetention = policy.minRetentionMillis() != null
                ? policy.minRetentionMillis() : properties.getDefaultMinRetentionMillis();

        int size = versions.size();
        long now = clock.millis();
        int drop = 0;
        for (int i = 0; i < size - 1; i++) { // 永不裁剪最新版本
            ObjectVersion v = versions.get(i);
            boolean countOk = maxVersions > 0
                    ? (size - 1 - i) >= maxVersions   // 比它新的版本数已达到上限
                    : minRetention > 0;               // 未限数量时，仅时长维度生效
            boolean ageOk = minRetention <= 0 || (now - v.createdAt()) >= minRetention;
            if (countOk && ageOk) {
                drop++;
            } else {
                break; // 版本链按时间升序，第一个不可裁的版本之后都更新，必然也不满足数量维度
            }
        }
        if (drop == 0) {
            return List.of();
        }
        List<ObjectVersion> pruned = List.copyOf(versions.subList(0, drop));
        versions.subList(0, drop).clear();
        for (ObjectVersion v : pruned) {
            if (!v.deleteMarker()) {
                registry.release(v.blobId());
            }
        }
        // 归档被裁版本的元数据，供 restore 区分“从未存在 / 已裁可救回 / 已物理回收”。
        archive.merge(key, pruned, (oldList, added) -> {
            List<ObjectVersion> merged = new ArrayList<>(oldList);
            merged.addAll(added);
            return List.copyOf(merged);
        });
        log.info("retention prune key={} droppedVersions={} maxVersions={} minRetentionMillis={}",
                key, pruned.stream().map(ObjectVersion::versionId).toList(),
                maxVersions <= 0 ? "UNLIMITED" : maxVersions, minRetention);
        return pruned;
    }

    /** 在裁剪归档中查找某 key 的指定版本（元数据）；找不到返回 null。 */
    private ObjectVersion findArchived(String key, long versionId) {
        List<ObjectVersion> archived = archive.get(key);
        if (archived == null) {
            return null;
        }
        for (ObjectVersion v : archived) {
            if (v.versionId() == versionId) {
                return v;
            }
        }
        return null;
    }

    /** 校验对象级保留规则；不合理（含与最低保留要求冲突）一律显式拒绝，绝不悄悄套用默认值。 */
    private void validatePolicy(String key, RetentionPolicy policy) {
        long floor = properties.getMinRetentionFloorMillis();
        if (policy.maxVersions() == null && policy.minRetentionMillis() == null) {
            log.warn("retention-policy rejected key={} reason=empty-policy", key);
            throw new StoreException(StoreException.Reason.INVALID_RETENTION_POLICY,
                    "retention policy for key " + key + " must specify at least one rule"
                            + " (maxVersions and/or minRetentionMillis); to remove the policy,"
                            + " call setRetentionPolicy(key, null)");
        }
        if (policy.maxVersions() != null && policy.maxVersions() < 1) {
            log.warn("retention-policy rejected key={} reason=maxVersions<1 value={}",
                    key, policy.maxVersions());
            throw new StoreException(StoreException.Reason.INVALID_RETENTION_POLICY,
                    "maxVersions must be >= 1 (got " + policy.maxVersions() + "): the latest"
                            + " version of an object must always be retained");
        }
        if (policy.minRetentionMillis() != null && policy.minRetentionMillis() < 0) {
            log.warn("retention-policy rejected key={} reason=minRetentionMillis<0 value={}",
                    key, policy.minRetentionMillis());
            throw new StoreException(StoreException.Reason.INVALID_RETENTION_POLICY,
                    "minRetentionMillis must be >= 0 (got " + policy.minRetentionMillis() + ")");
        }
        if (policy.minRetentionMillis() != null && policy.minRetentionMillis() < floor) {
            log.warn("retention-policy rejected key={} reason=below-min-retention-floor value={} floor={}",
                    key, policy.minRetentionMillis(), floor);
            throw new StoreException(StoreException.Reason.INVALID_RETENTION_POLICY,
                    "minRetentionMillis " + policy.minRetentionMillis() + " conflicts with the"
                            + " minimum retention requirement of this store (" + floor
                            + " ms); refusing to apply a weaker policy");
        }
    }

    // ---- 自动回收触发 ----

    /**
     * 写/删/恢复之后按配置决定是否回收：
     * <ul>
     *   <li>{@code auto-purge=true}：每次都物理回收无引用 blob（历史行为，等价于阈值恒为 0）；</li>
     *   <li>配置了容量/比例阈值且当前已越限：执行一轮完整治理回收（保留裁剪 + 物理回收）。</li>
     * </ul>
     * 两者都未开启时不做任何事（只能手动 {@link #purgeUnreferenced()}/{@link #reclaimNow()}）。
     */
    private void maybeAutoReclaim(String trigger) {
        if (autoPurge) {
            int removed = registry.purgeUnreferenced();
            if (removed > 0) {
                log.info("auto-purge after {} removed={}", trigger, removed);
            }
        }
        String exceeded = thresholdExceededBy();
        if (exceeded != null) {
            log.info("auto-reclaim triggered by {} : {}", trigger, exceeded);
            reclaimNow();
        }
    }

    /** 返回越限原因的可读描述；未越限（或阈值关闭）返回 null。 */
    private String thresholdExceededBy() {
        long maxBytes = properties.getReclaimMaxBytes();
        if (maxBytes > 0) {
            long used = registry.trackedBytes();
            if (used > maxBytes) {
                return "trackedBytes " + used + " > reclaimMaxBytes " + maxBytes;
            }
        }
        if (ratioEnabled()) {
            double ratio = properties.getReclaimMaxUsageRatio();
            try {
                FileStore fs = Files.getFileStore(properties.getDataDir());
                long total = fs.getTotalSpace();
                if (total > 0) {
                    double usedRatio = (double) (total - fs.getUsableSpace()) / total;
                    if (usedRatio >= ratio) {
                        return "volume usage ratio " + String.format("%.4f", usedRatio)
                                + " >= reclaimMaxUsageRatio " + ratio;
                    }
                }
            } catch (IOException e) {
                log.warn("auto-reclaim ratio check failed (treated as not exceeded): {}", e.toString());
            }
        }
        return null;
    }

    private boolean ratioEnabled() {
        double ratio = properties.getReclaimMaxUsageRatio();
        return ratio > 0 && ratio <= 1;
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
