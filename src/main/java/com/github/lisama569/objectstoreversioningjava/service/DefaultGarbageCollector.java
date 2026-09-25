package com.github.lisama569.objectstoreversioningjava.service;

import com.github.lisama569.objectstoreversioningjava.core.ContentId;
import com.github.lisama569.objectstoreversioningjava.core.GcReport;
import com.github.lisama569.objectstoreversioningjava.storage.BlobStorage;
import com.github.lisama569.objectstoreversioningjava.storage.MetadataStore;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * 默认空间回收器。
 *
 * <h3>回收规则（引用计数的集合论表达）</h3>
 * <ol>
 *   <li>取 GC 写锁：与所有业务读写互斥。因此下面两步观察到的是<b>同一个静止一致状态</b>，
 *       不存在“扫描时还有引用、删除时引用已变化”的竞态；</li>
 *   <li>{@code onDisk} = 磁盘上全部内容块；{@code live} = 全部存活版本引用的内容集合
 *       （删除标记不引用任何内容；共享块在 live 中天然只算一个）；</li>
 *   <li>仅删除 {@code onDisk - live} 差集。任何仍被至少一个版本引用的共享块
 *       都在 live 中，绝不被提前清除。</li>
 * </ol>
 *
 * <h3>与并发读写并存时的可预期结果</h3>
 * <ul>
 *   <li>业务读取持有 GC 读锁，故读取“定位版本→读块复验”整个过程中 GC 不可能删除其块；</li>
 *   <li>业务写入“先提交块后发布版本”。GC 持写锁期间写入在等待，不会产生半成品；
 *       写入先于 GC 完成时：已发布版本的块在 live 中被保留；未发布的块属无引用垃圾，可被回收，
 *       写入恢复后会重新提交，幂等无副作用；</li>
 *   <li>永久删除某版本后：若其内容仍被其他版本共享则保留；最后一个引用消失后，下次 GC 才回收。</li>
 * </ul>
 */
public class DefaultGarbageCollector implements GarbageCollector {

    private final BlobStorage blobs;
    private final MetadataStore metadata;
    private final StoreLocks locks;

    public DefaultGarbageCollector(BlobStorage blobs, MetadataStore metadata, StoreLocks locks) {
        this.blobs = Objects.requireNonNull(blobs, "blobs");
        this.metadata = Objects.requireNonNull(metadata, "metadata");
        this.locks = Objects.requireNonNull(locks, "locks");
    }

    @Override
    public GcReport collect() {
        locks.gcLock().writeLock().lock();
        try {
            Set<ContentId> onDisk = blobs.listAll();
            Set<ContentId> live = metadata.liveContentIds();

            List<String> deleted = new ArrayList<>();
            List<String> retained = new ArrayList<>();

            for (ContentId id : onDisk) {
                if (live.contains(id)) {
                    // 仍有版本引用（可能被多个版本共享）：保留。
                    retained.add(id.sha256());
                } else {
                    blobs.delete(id);
                    deleted.add(id.sha256());
                }
            }
            return new GcReport(onDisk.size(), deleted.size(),
                    List.copyOf(deleted), List.copyOf(retained));
        } finally {
            locks.gcLock().writeLock().unlock();
        }
    }
}
