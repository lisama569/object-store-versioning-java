package com.github.lisama569.objectstoreversioningjava.store;

import com.github.lisama569.objectstoreversioningjava.config.ObjectStoreProperties;
import com.github.lisama569.objectstoreversioningjava.exception.StoreException;
import com.github.lisama569.objectstoreversioningjava.model.BlobContent;
import com.github.lisama569.objectstoreversioningjava.model.ObjectVersion;
import com.github.lisama569.objectstoreversioningjava.model.PutResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;
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

    public ObjectStore(ObjectStoreProperties properties) {
        this.properties = properties;
        this.maxVersionsPerKey = properties.getMaxVersionsPerKey();
        this.autoPurge = properties.isAutoPurge();
        this.blobStore = new FileSystemBlobStore(properties.getDataDir());
        this.registry = new BlobRegistry(blobStore);
        this.table = new MetadataTable();
        log.info("ObjectStore ready, dataDir={} maxVersionsPerKey={} autoPurge={}",
                properties.getDataDir().toAbsolutePath(),
                maxVersionsPerKey <= 0 ? "UNLIMITED" : maxVersionsPerKey, autoPurge);
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
        try {
            boolean existed = blobStore.exists(calculateBlobId(content));
            String blobId = blobStore.put(content);
            long versionId = versionSequence.incrementAndGet();
            long now = System.currentTimeMillis();

            PutResult result = table.mutate(key, current -> {
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

            if (autoPurge) {
                int removed = registry.purgeUnreferenced();
                if (removed > 0) {
                    log.info("auto-purge after put key={} removed={}", key, removed);
                }
            }
            return result;
        } finally {
            registry.readUnlock();
        }
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
        try {
            ObjectVersion marker = table.mutate(key, current -> {
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
            if (autoPurge) {
                registry.purgeUnreferenced();
            }
            return marker;
        } finally {
            registry.readUnlock();
        }
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
     * 按保留上限裁剪版本链（原地删除 list 头部多余元素），并同步释放被裁版本的 blob 引用。
     * 必须在该 key 的元数据分段锁内调用。
     */
    private List<ObjectVersion> prune(String key, List<ObjectVersion> versions) {
        List<ObjectVersion> pruned = List.of();
        if (maxVersionsPerKey > 0 && versions.size() > maxVersionsPerKey) {
            int drop = versions.size() - maxVersionsPerKey;
            pruned = List.copyOf(versions.subList(0, drop));
            versions.subList(0, drop).clear();
            for (ObjectVersion v : pruned) {
                if (!v.deleteMarker()) {
                    registry.release(v.blobId());
                }
            }
            log.info("retention prune key={} droppedVersions={} maxVersions={}",
                    key, pruned.stream().map(ObjectVersion::versionId).toList(), maxVersionsPerKey);
        }
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
