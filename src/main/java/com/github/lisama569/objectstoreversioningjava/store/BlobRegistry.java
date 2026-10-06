package com.github.lisama569.objectstoreversioningjava.store;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.locks.ReentrantReadWriteLock;

/**
 * blob 引用计数 + 读引脚注册表，是“版本保留 / 空间回收 / 并发读”三者一致性的协调点。
 *
 * <p>规则：
 * <ul>
 *   <li>每有一个数据版本引用某 blob，其引用计数 +1；版本被裁剪/删除时 -1。
 *       内容相同的不同版本各自计数，因此共享 blob 在仍有任一版本引用时绝不会被回收。</li>
 *   <li>引用计数归零只表示<b>登记为待删（PENDING_DELETE）</b>，物理删除由
 *       {@link #purgeUnreferenced()} 执行；若归零后同内容再次写入，会被重新 acquire “救回”。</li>
 *   <li>读取前先 pin：处于待删态的 blob 拒绝新读（调用方据此报“版本已被回收”）；
 *       已 pin 的 blob 即使计数归零，也要等读结束才会被物理删除。</li>
 *   <li>{@code gate} 是一把读写门：读路径与版本裁剪持有读门，物理回收持有写门，
 *       保证回收那一刻没有任何读者正在访问数据文件，删除结果对并发读严格可解释。</li>
 *   <li>物理回收<b>逐 blob 进行</b>：每个 blob 的“检查 + 删文件 + 注销”在独立的写门临界区内
 *       完成，临界区之间释放写门，因此回收不会长时间阻塞并发读写；回收中途失败后重跑，
 *       只会处理仍处于待删态的 blob，不重复计数、不会误删有引用的数据。</li>
 *   <li>注册表同时跟踪已登记 blob 的总字节数（{@link #trackedBytes()}），
 *       供容量阈值触发自动回收使用。</li>
 * </ul>
 */
public class BlobRegistry {

    private static final Logger log = LoggerFactory.getLogger(BlobRegistry.class);

    private final BlobStore blobStore;
    private final ReentrantReadWriteLock gate = new ReentrantReadWriteLock(true);
    private final ConcurrentHashMap<String, State> states = new ConcurrentHashMap<>();
    private final AtomicLong trackedBytes = new AtomicLong(0);

    private static final class State {
        final long size;
        int refs;
        int pins;
        boolean pending;

        State(long size) {
            this.size = size;
        }

        @Override
        public String toString() {
            return "State{refs=" + refs + ", pins=" + pins + ", pending=" + pending
                    + ", size=" + size + '}';
        }
    }

    public BlobRegistry(BlobStore blobStore) {
        this.blobStore = blobStore;
    }

    // ---- 读门：读路径、写发布路径、版本裁剪路径都在读门内完成临界动作 ----

    public void readLock() {
        gate.readLock().lock();
    }

    public void readUnlock() {
        gate.readLock().unlock();
    }

    // ---- 版本引用计数 ----

    /**
     * 新增一个版本引用。计数为 0/待删态的 blob 会在此被“救回”为活跃态
     * （调用方保证救回时 blob 文件已重新发布并持有读门，GC 无法在中间插入物理删除）。
     *
     * @param size blob 字节数，仅在首次登记时计入总账
     */
    public void acquire(String blobId, long size) {
        State s = states.computeIfAbsent(blobId, k -> {
            trackedBytes.addAndGet(size);
            return new State(size);
        });
        synchronized (s) {
            s.refs++;
            boolean rescued = s.pending;
            s.pending = false;
            log.info("acquire blobId={} refs={} trackedBytes={}{}",
                    blobId, s.refs, trackedBytes.get(), rescued ? " rescued-from-pending" : "");
        }
    }

    /**
     * 恢复专用：仅当 blob 的底层数据仍然可用时才新增一个引用。
     * <ul>
     *   <li>活跃态：直接计数 +1；</li>
     *   <li>待删态但文件仍在（尚未被 purge）：救回为活跃态；</li>
     *   <li>从未登记/已物理回收，或待删态但文件已不存在：返回 false，
     *       调用方据此报“版本数据已被回收”，绝不制造看似成功的恢复。</li>
     * </ul>
     * 必须在持有读门时调用（与 purge 的写门互斥，检查与计数之间不会被插入物理删除）。
     */
    public boolean tryAcquire(String blobId) {
        State s = states.get(blobId);
        if (s == null) {
            log.warn("tryAcquire rejected blobId={} reason=no-registry-state (already reclaimed)",
                    blobId);
            return false;
        }
        synchronized (s) {
            if (s.pending && !blobStore.exists(blobId)) {
                log.warn("tryAcquire rejected blobId={} state={} reason=file-already-reclaimed",
                        blobId, s);
                return false;
            }
            boolean rescued = s.pending;
            s.pending = false;
            s.refs++;
            log.info("tryAcquire ok blobId={} refs={}{}",
                    blobId, s.refs, rescued ? " rescued-from-pending" : "");
            return true;
        }
    }

    /** 释放一个版本引用；计数归零时登记待删，不立即物理删除。 */
    public void release(String blobId) {
        if (blobId == null) {
            return;
        }
        State s = states.get(blobId);
        if (s == null) {
            throw new IllegalStateException("release unknown blobId=" + blobId);
        }
        synchronized (s) {
            if (s.refs <= 0) {
                throw new IllegalStateException("over-release blobId=" + blobId + " " + s);
            }
            s.refs--;
            if (s.refs == 0) {
                s.pending = true;
                log.info("release blobId={} refs=0 -> PENDING_DELETE (awaiting purge)", blobId);
            } else {
                log.info("release blobId={} refs={} (still referenced, keep data)", blobId, s.refs);
            }
        }
    }

    /**
     * 读引脚。仅当 blob 处于活跃态（至少一个版本引用、未登记待删）时成功。
     * 必须在持有读门时调用；返回 false 表示该版本数据已被回收/正在回收。
     */
    public boolean pinForRead(String blobId) {
        State s = states.get(blobId);
        if (s == null) {
            return false;
        }
        synchronized (s) {
            if (s.pending || s.refs == 0) {
                log.warn("pin rejected blobId={} state={} (version data no longer retained)", blobId, s);
                return false;
            }
            s.pins++;
            return true;
        }
    }

    public void unpin(String blobId) {
        State s = states.get(blobId);
        if (s == null) {
            return;
        }
        synchronized (s) {
            if (s.pins > 0) {
                s.pins--;
            }
            log.info("unpin blobId={} state={}", blobId, s);
        }
    }

    /**
     * 物理回收：逐个 blob 在独立的写门临界区内完成“复核状态 + 删文件 + 注销”，
     * 临界区之间释放写门，让并发读写可以插入，避免回收长时间阻塞读写。
     *
     * <p>可重入/可重试：某个 blob 删除失败（IO 异常）时其登记保留，本次回收继续处理其余
     * blob 并正常返回；重新执行只会重试仍处于待删态的 blob。删除成功才计数并注销，
     * 因此不会重复计数，也不会误删仍有引用的数据。
     *
     * @return 本次实际物理删除的 blob 数量
     */
    public int purgeUnreferenced() {
        int removed = 0;
        Set<String> failedThisRound = new HashSet<>();
        while (true) {
            String candidate = findCandidate(failedThisRound);
            if (candidate == null) {
                break;
            }
            try {
                if (purgeOne(candidate)) {
                    removed++;
                }
                // purgeOne 返回 false：该 blob 在临界区前被救回/重新引用，不再是候选，继续扫描。
            } catch (RuntimeException e) {
                // 删除失败：登记保留，本轮跳过它避免死循环；下一轮 purge 幂等重试。
                failedThisRound.add(candidate);
                log.error("purge failed blobId={} (kept registered, retryable next round): {}",
                        candidate, e.toString());
            }
        }
        if (removed > 0 || !failedThisRound.isEmpty()) {
            log.info("purge finished removed={} failed={} trackedBytes={} trackedBlobs={}",
                    removed, failedThisRound.size(), trackedBytes.get(), states.size());
        } else {
            log.info("purge: nothing to reclaim (trackedBytes={} trackedBlobs={})",
                    trackedBytes.get(), states.size());
        }
        return removed;
    }

    /**
     * 找一个待删候选（不写门内，仅做预筛；最终判定在 purgeOne 的写门临界区内复核）。
     * 预筛不检查引脚：被引脚占住的候选会让 purgeOne 在写门上等待在途读结束，
     * 读结束后再完成删除——这与“引脚保护读”的语义一致。
     * {@code excluded} 中的 blob 本轮跳过（通常是刚刚删除失败的），其登记仍然保留，
     * 下一轮 purge 会重试。
     */
    private String findCandidate(Set<String> excluded) {
        for (var entry : states.entrySet()) {
            if (excluded.contains(entry.getKey())) {
                continue;
            }
            State s = entry.getValue();
            synchronized (s) {
                if (s.refs == 0 && s.pending) {
                    return entry.getKey();
                }
            }
        }
        return null;
    }


    /**
     * 在写门临界区内复核并物理删除单个 blob。
     *
     * @return true=已删除并注销；false=临界区内发现已被救回/仍有引脚，未动它
     */
    private boolean purgeOne(String blobId) {
        gate.writeLock().lock();
        try {
            State s = states.get(blobId);
            if (s == null) {
                return false;
            }
            synchronized (s) {
                if (!(s.refs == 0 && s.pending && s.pins == 0)) {
                    log.info("purge skip blobId={} state={} (rescued or pinned meanwhile)", blobId, s);
                    return false;
                }
            }
            // 持有写门：此刻不存在任何在途读/写/裁剪，删文件与注销之间状态不可能再变化。
            // 删除失败会抛出，state 保留在册，下一轮 purge 幂等重试（deleteIfExists 语义）。
            blobStore.delete(blobId);
            states.remove(blobId, s);
            trackedBytes.addAndGet(-s.size);
            log.info("purge removed blobId={} size={} trackedBytes={} (unreferenced, no in-flight reads)",
                    blobId, s.size, trackedBytes.get());
            return true;
        } finally {
            gate.writeLock().unlock();
        }
    }

    /** 当前引用计数（测试断言与日志用）。 */
    public int referenceCount(String blobId) {
        State s = states.get(blobId);
        return s == null ? 0 : s.refs;
    }

    /** 当前读引脚数（测试断言用）。 */
    public int pinCount(String blobId) {
        State s = states.get(blobId);
        return s == null ? 0 : s.pins;
    }

    /** 是否处于待删态（测试断言用）。 */
    public boolean isPending(String blobId) {
        State s = states.get(blobId);
        return s != null && s.pending;
    }

    /** 已登记 blob 的总字节数（含待删未清的），容量阈值触发与测试断言用。 */
    public long trackedBytes() {
        return trackedBytes.get();
    }

    /** 已登记 blob 数量（测试断言用）。 */
    public int trackedBlobCount() {
        return states.size();
    }
}
