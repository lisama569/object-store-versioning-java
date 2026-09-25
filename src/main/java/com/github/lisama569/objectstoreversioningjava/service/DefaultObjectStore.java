package com.github.lisama569.objectstoreversioningjava.service;

import com.github.lisama569.objectstoreversioningjava.core.ContentId;
import com.github.lisama569.objectstoreversioningjava.core.ObjectDeletedException;
import com.github.lisama569.objectstoreversioningjava.core.ObjectKey;
import com.github.lisama569.objectstoreversioningjava.core.ObjectNotFoundException;
import com.github.lisama569.objectstoreversioningjava.core.ObjectVersion;
import com.github.lisama569.objectstoreversioningjava.core.StoredContent;
import com.github.lisama569.objectstoreversioningjava.core.VersionId;
import com.github.lisama569.objectstoreversioningjava.storage.BlobStorage;
import com.github.lisama569.objectstoreversioningjava.storage.LocalBlobStorage;
import com.github.lisama569.objectstoreversioningjava.storage.MetadataStore;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReentrantLock;

/**
 * 默认对象存储门面，协调块存储与版本元数据，并承载全部一致性协议。
 *
 * <h3>写入协议（put）</h3>
 * <ol>
 *   <li>校验键与内容，计算内容地址；</li>
 *   <li>取该 key 的键级锁（{@link #keyLocks}，按 key 分段），同一对象的写入彼此串行，
 *       不同对象可并行；</li>
 *   <li>在全局 GC 读锁保护下，先 {@code blobs.commit}（临时文件 + 复验 + 原子落位），
 *       再 {@code metadata.appendData} 分配新版本号。顺序固定为<b>先数据后元数据</b>：
 *       元数据一旦发布，块必然已存在且校验通过；未发布的块只是无引用垃圾，GC 可安全清理。</li>
 * </ol>
 * 因此并发覆盖同一对象时：每次写入各得到一个新版本，绝不丢更新；
 * 内容相同的写入映射到同一 ContentId，块存储原子幂等落位，磁盘上只有一份；
 * 读取方只会看到“已提交块 + 已发布版本”的完整状态。
 *
 * <h3>读取协议（get / getVersion）</h3>
 * 持 GC 读锁（GC 不会在读取过程中介入删除），从不可变元数据节点定位内容地址，
 * 再从块存储读取并复验。块复验失败时抛出校验异常且损坏块被隔离，绝不返回脏数据。
 *
 * <h3>删除语义</h3>
 * <ul>
 *   <li>{@code delete}：<b>逻辑删除</b>，追加一个删除标记版本。历史版本链原样保留，
 *       仍可按版本号读取；最新版读取抛 {@link ObjectDeletedException}；对已删除对象再次
 *       逻辑删除被拒绝（避免无意义的连续标记，原因可区分）。</li>
 *   <li>{@code purgeVersion}：<b>永久删除单个版本</b>，移除元数据节点。
 *       其内容块仅在不再被任何版本引用时，才会被之后的 GC 回收。</li>
 * </ul>
 */
public class DefaultObjectStore implements ObjectStore {

    private final BlobStorage blobs;
    private final MetadataStore metadata;
    private final StoreLocks locks;
    private final int maxKeyLength;

    /** 键级写锁：同一 key 的 put/delete/purge 串行。惰性创建，不同 key 互不阻塞。 */
    private final ConcurrentHashMap<ObjectKey, ReentrantLock> keyLocks = new ConcurrentHashMap<>();

    public DefaultObjectStore(BlobStorage blobs, MetadataStore metadata, StoreLocks locks) {
        this(blobs, metadata, locks, ObjectKey.DEFAULT_MAX_LENGTH);
    }

    public DefaultObjectStore(BlobStorage blobs, MetadataStore metadata, StoreLocks locks, int maxKeyLength) {
        this.blobs = Objects.requireNonNull(blobs, "blobs");
        this.metadata = Objects.requireNonNull(metadata, "metadata");
        this.locks = Objects.requireNonNull(locks, "locks");
        if (maxKeyLength <= 0) {
            throw new IllegalArgumentException("maxKeyLength 必须为正数");
        }
        this.maxKeyLength = maxKeyLength;
    }

    private ReentrantLock lockFor(ObjectKey key) {
        return keyLocks.computeIfAbsent(key, k -> new ReentrantLock());
    }

    @Override
    public ObjectVersion put(String key, byte[] content) {
        ObjectKey k = ObjectKey.of(key, maxKeyLength);
        if (content == null) {
            throw new IllegalArgumentException("写入内容不能为 null");
        }
        ContentId id = ContentId.of(LocalBlobStorage.sha256Hex(content));

        ReentrantLock keyLock = lockFor(k);
        keyLock.lock();
        try {
            locks.gcLock().readLock().lock();
            try {
                // 先落数据（原子、幂等去重），再发布元数据版本。
                blobs.commit(id, content);
                return metadata.appendData(k, id);
            } finally {
                locks.gcLock().readLock().unlock();
            }
        } finally {
            keyLock.unlock();
        }
    }

    @Override
    public StoredContent get(String key) {
        ObjectKey k = ObjectKey.of(key, maxKeyLength);
        locks.gcLock().readLock().lock();
        try {
            ObjectVersion latest = metadata.latest(k);
            if (latest == null) {
                throw new ObjectNotFoundException("对象不存在，从未创建过: " + k.value());
            }
            if (latest.deleteMarker()) {
                throw new ObjectDeletedException(
                        "对象已被逻辑删除，最新版本 v" + latest.version().value()
                                + " 是删除标记；可用历史版本号读取旧版本: " + k.value());
            }
            return readVersion(latest);
        } finally {
            locks.gcLock().readLock().unlock();
        }
    }

    @Override
    public StoredContent getVersion(String key, long version) {
        ObjectKey k = ObjectKey.of(key, maxKeyLength);
        VersionId v = VersionId.of(version); // 非正数在此被拒绝
        locks.gcLock().readLock().lock();
        try {
            ObjectVersion ov = metadata.getVersion(k, v);
            if (ov == null) {
                // 区分“整个对象不存在”与“版本缺失”，给出可区分原因。
                ObjectVersion latest = metadata.latest(k);
                if (latest == null) {
                    throw new ObjectNotFoundException("对象不存在: " + k.value());
                }
                throw new com.github.lisama569.objectstoreversioningjava.core.VersionNotFoundException(
                        "版本 v" + version + " 不存在（当前最高版本 v"
                                + latest.version().value() + "）: " + k.value());
            }
            if (ov.deleteMarker()) {
                throw new ObjectDeletedException(
                        "版本 v" + version + " 是删除标记，不含数据: " + k.value());
            }
            return readVersion(ov);
        } finally {
            locks.gcLock().readLock().unlock();
        }
    }

    /** 调用方已持 GC 读锁；从块存储读取并由其完成摘要复验与损坏隔离。 */
    private StoredContent readVersion(ObjectVersion ov) {
        byte[] data = blobs.read(ov.contentId());
        return new StoredContent(ov.version(), ov.contentId(), data);
    }

    @Override
    public ObjectVersion delete(String key) {
        ObjectKey k = ObjectKey.of(key, maxKeyLength);
        ReentrantLock keyLock = lockFor(k);
        keyLock.lock();
        try {
            locks.gcLock().readLock().lock();
            try {
                ObjectVersion latest = metadata.latest(k);
                if (latest == null) {
                    throw new ObjectNotFoundException(
                            "不能删除不存在的对象（从未创建）: " + k.value());
                }
                if (latest.deleteMarker()) {
                    throw new ObjectDeletedException(
                            "对象已处于删除状态（最新为删除标记 v"
                                    + latest.version().value() + "），拒绝重复逻辑删除: " + k.value());
                }
                return metadata.appendTombstone(k);
            } finally {
                locks.gcLock().readLock().unlock();
            }
        } finally {
            keyLock.unlock();
        }
    }

    @Override
    public void purgeVersion(String key, long version) {
        ObjectKey k = ObjectKey.of(key, maxKeyLength);
        VersionId v = VersionId.of(version);
        ReentrantLock keyLock = lockFor(k);
        keyLock.lock();
        try {
            locks.gcLock().readLock().lock();
            try {
                ObjectVersion removed = metadata.removeVersion(k, v);
                if (removed == null) {
                    ObjectVersion latest = metadata.latest(k);
                    if (latest == null) {
                        throw new ObjectNotFoundException("对象不存在: " + k.value());
                    }
                    throw new com.github.lisama569.objectstoreversioningjava.core.VersionNotFoundException(
                            "版本 v" + version + " 不存在，无法永久删除: " + k.value());
                }
                // 不在这里直接删块：是否还有其他版本引用同一内容，交由 GC 依据全局引用集合判定。
            } finally {
                locks.gcLock().readLock().unlock();
            }
        } finally {
            keyLock.unlock();
        }
    }

    @Override
    public List<ObjectVersion> listVersions(String key) {
        ObjectKey k = ObjectKey.of(key, maxKeyLength);
        locks.gcLock().readLock().lock();
        try {
            List<ObjectVersion> versions = metadata.versions(k);
            if (versions.isEmpty()) {
                throw new ObjectNotFoundException("对象不存在: " + k.value());
            }
            return new ArrayList<>(versions);
        } finally {
            locks.gcLock().readLock().unlock();
        }
    }
}
