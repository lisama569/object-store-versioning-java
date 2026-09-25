package com.github.lisama569.objectstoreversioningjava.store;

import com.github.lisama569.objectstoreversioningjava.model.ObjectVersion;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Function;

/**
 * 进程内对象元数据表：key -> 版本链（按版本号升序的不可变 List）。
 *
 * <p>并发模型：
 * <ul>
 *   <li>同一 key 的所有变更（put / delete / 裁剪）在<b>同一把分段锁</b>内串行执行，
 *       杜绝同 key 丢更新；不同 key 互不阻塞。</li>
 *   <li>读取在锁外拿到版本链的不可变快照，读到的永远是某个完整提交过的版本链，
 *       不会读到“写到一半”的元数据。</li>
 *   <li>版本链采用“整体替换”（copy-on-write），快照持有者不受后续变更影响。</li>
 * </ul>
 */
public class MetadataTable {

    private static final Logger log = LoggerFactory.getLogger(MetadataTable.class);

    private static final int STRIPES = 64;

    private final ConcurrentHashMap<String, List<ObjectVersion>> table = new ConcurrentHashMap<>();
    private final ReentrantLock[] locks;

    public MetadataTable() {
        this.locks = new ReentrantLock[STRIPES];
        for (int i = 0; i < STRIPES; i++) {
            locks[i] = new ReentrantLock(true);
        }
    }

    private ReentrantLock lockFor(String key) {
        return locks[Math.floorMod(key.hashCode(), STRIPES)];
    }

    /**
     * 在 key 的分段锁内对当前版本链执行一次原子变更并发布新链。
     *
     * @param mutation 入参为当前版本链（key 不存在时为 null），返回 {@link Mutation}；
     *                 若返回的新版本链为 null 则移除该 key。
     */
    public <R> R mutate(String key, Function<List<ObjectVersion>, Mutation<R>> mutation) {
        ReentrantLock lock = lockFor(key);
        lock.lock();
        try {
            List<ObjectVersion> current = table.get(key);
            Mutation<R> result = mutation.apply(current);
            if (result.newVersions == null) {
                table.remove(key);
                log.debug("mutate key={} removed", key);
            } else {
                table.put(key, List.copyOf(result.newVersions));
                log.debug("mutate key={} versions={}", key, result.newVersions.size());
            }
            return result.result;
        } finally {
            lock.unlock();
        }
    }

    /** 取 key 当前版本链的不可变快照（按版本号升序）；key 不存在返回 null。 */
    public List<ObjectVersion> snapshot(String key) {
        return table.get(key);
    }

    /** 是否存在该 key（含只有删除标记的 key）。 */
    public boolean containsKey(String key) {
        return table.containsKey(key);
    }

    /** 遍历所有 key（GC 扫描引用用）。 */
    public Iterable<String> keys() {
        return new ArrayList<>(table.keySet());
    }

    /** 当前 key 数量（测试/日志用）。 */
    public int size() {
        return table.size();
    }

    /** 变更结果：新版本链 + 要带出临界区的返回值。 */
    public record Mutation<R>(List<ObjectVersion> newVersions, R result) {
    }
}
