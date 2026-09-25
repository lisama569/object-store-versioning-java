package com.github.lisama569.objectstoreversioningjava.storage;

import com.github.lisama569.objectstoreversioningjava.core.ContentId;
import com.github.lisama569.objectstoreversioningjava.core.ObjectKey;
import com.github.lisama569.objectstoreversioningjava.core.ObjectVersion;
import com.github.lisama569.objectstoreversioningjava.core.VersionId;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

/**
 * 进程内元数据实现。
 *
 * <p>结构：{@code ConcurrentHashMap<ObjectKey, 有序版本链>}，每个 key 维护
 * 自己的单调版本计数器。所有“读-改-写”复合动作（追加、移除、列举存活引用等）
 * 都在一把全局元数据锁 {@link #lock} 下完成，保证：
 * <ul>
 *   <li>版本号分配与链追加原子——并发写同一对象不会丢更新，版本号不复用不跳号可见；</li>
 *   <li>{@link #snapshot()} / {@link #liveContentIds()} 拿到的是某一瞬间一致的视图；</li>
 *   <li>读取方拿到的永远是不可变 {@link ObjectVersion} 节点或其列表副本，
 *       不会看到链被并发修改的中间状态。</li>
 * </ul>
 */
public class InMemoryMetadataStore implements MetadataStore {

    /** 一把元数据锁，守护 chains / counters 的所有复合操作。 */
    private final Object lock = new Object();

    private final Map<ObjectKey, List<ObjectVersion>> chains = new ConcurrentHashMap<>();
    private final Map<ObjectKey, long[]> counters = new ConcurrentHashMap<>();

    public InMemoryMetadataStore() {
    }

    private List<ObjectVersion> chainOf(ObjectKey key) {
        return chains.computeIfAbsent(key, k -> new ArrayList<>());
    }

    @Override
    public ObjectVersion appendData(ObjectKey key, ContentId contentId) {
        Objects.requireNonNull(key, "key");
        Objects.requireNonNull(contentId, "contentId");
        synchronized (lock) {
            long next = nextVersion(key);
            ObjectVersion v = ObjectVersion.data(key, VersionId.of(next), contentId);
            chainOf(key).add(v);
            return v;
        }
    }

    @Override
    public ObjectVersion appendTombstone(ObjectKey key) {
        Objects.requireNonNull(key, "key");
        synchronized (lock) {
            long next = nextVersion(key);
            ObjectVersion v = ObjectVersion.tombstone(key, VersionId.of(next));
            chainOf(key).add(v);
            return v;
        }
    }

    /** 计数器存在长为 1 的数组中，避免自动装箱；自增在元数据锁内完成。 */
    private long nextVersion(ObjectKey key) {
        return ++counters.computeIfAbsent(key, k -> new long[1])[0];
    }

    @Override
    public ObjectVersion removeVersion(ObjectKey key, VersionId version) {
        Objects.requireNonNull(key, "key");
        Objects.requireNonNull(version, "version");
        synchronized (lock) {
            List<ObjectVersion> chain = chains.get(key);
            if (chain == null) {
                return null;
            }
            ObjectVersion removed = null;
            for (int i = 0; i < chain.size(); i++) {
                if (chain.get(i).version().equals(version)) {
                    removed = chain.remove(i);
                    break;
                }
            }
            if (chain.isEmpty()) {
                chains.remove(key);
                counters.remove(key);
            }
            return removed;
        }
    }

    @Override
    public ObjectVersion getVersion(ObjectKey key, VersionId version) {
        synchronized (lock) {
            List<ObjectVersion> chain = chains.get(key);
            if (chain == null) {
                return null;
            }
            for (ObjectVersion v : chain) {
                if (v.version().equals(version)) {
                    return v;
                }
            }
            return null;
        }
    }

    @Override
    public ObjectVersion latest(ObjectKey key) {
        synchronized (lock) {
            List<ObjectVersion> chain = chains.get(key);
            if (chain == null || chain.isEmpty()) {
                return null;
            }
            return chain.get(chain.size() - 1);
        }
    }

    @Override
    public List<ObjectVersion> versions(ObjectKey key) {
        synchronized (lock) {
            List<ObjectVersion> chain = chains.get(key);
            if (chain == null) {
                return List.of();
            }
            return List.copyOf(chain);
        }
    }

    @Override
    public Map<ObjectKey, List<ObjectVersion>> snapshot() {
        synchronized (lock) {
            Map<ObjectKey, List<ObjectVersion>> copy = new LinkedHashMap<>();
            chains.forEach((k, chain) -> copy.put(k, List.copyOf(chain)));
            return Map.copyOf(copy);
        }
    }

    @Override
    public Set<ContentId> liveContentIds() {
        synchronized (lock) {
            return chains.values().stream()
                    .flatMap(List::stream)
                    .filter(v -> !v.deleteMarker())
                    .map(ObjectVersion::contentId)
                    .collect(Collectors.toUnmodifiableSet());
        }
    }
}
