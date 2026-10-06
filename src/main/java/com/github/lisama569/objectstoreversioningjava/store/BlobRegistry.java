package com.github.lisama569.objectstoreversioningjava.store;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
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
 * </ul>
 */
public class BlobRegistry {

    private static final Logger log = LoggerFactory.getLogger(BlobRegistry.class);

    private final BlobStore blobStore;
    private final ReentrantReadWriteLock gate = new ReentrantReadWriteLock(true);
    private final ConcurrentHashMap<String, State> states = new ConcurrentHashMap<>();

    private static final class State {
        int refs;
        int pins;
        boolean pending;

        @Override
        public String toString() {
            return "State{refs=" + refs + ", pins=" + pins + ", pending=" + pending + '}';
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
     */
    public void acquire(String blobId) {
        State s = states.computeIfAbsent(blobId, k -> new State());
        synchronized (s) {
            s.refs++;
            boolean rescued = s.pending;
            s.pending = false;
            log.info("acquire blobId={} refs={}{}", blobId, s.refs, rescued ? " rescued-from-pending" : "");
        }
    }

    /**
     * 仅当 blob 仍在登记表中（活跃或待删）才新增一个引用，用于“恢复已裁剪版本”时把
     * 待删 blob 救回。必须在持有读门时调用（GC 持写门，无法在中间窗口物理删除）。
     *
     * @return false 表示该 blob 已被物理回收（登记表中不存在），调用方应报“数据已回收”
     */
    public boolean tryAcquireIfPresent(String blobId) {
        State s = states.get(blobId);
        if (s == null) {
            return false;
        }
        synchronized (s) {
            s.refs++;
            boolean rescued = s.pending;
            s.pending = false;
            log.info("tryAcquireIfPresent blobId={} refs={}{}", blobId, s.refs,
                    rescued ? " rescued-from-pending" : "");
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

    /** 一次物理回收的结果：removed 只计真正删除成功的，failures 为可重试的失败数。 */
    public record PurgeOutcome(int removed, int failures) {
    }

    /**
     * 物理回收：持有写门（等待所有在途读/写/裁剪结束，并阻止新的进入），
     * 删除所有引用计数为 0 且处于待删态的 blob 文件。
     *
     * <p>可重入/可重试：单个 blob 删除失败只记录失败并保留其待删状态，不影响其余
     * blob，也不会中断本次回收；只有真正删除成功的才从登记表移除并计数，因此
     * 中途失败后重新执行不会重复计数、不会多删，仍有引用的 blob 绝不在删除集合内。
     */
    public PurgeOutcome purgeUnreferenced() {
        gate.writeLock().lock();
        try {
            List<String> dead = new ArrayList<>();
            states.forEach((id, s) -> {
                synchronized (s) {
                    if (s.refs == 0 && s.pending && s.pins == 0) {
                        dead.add(id);
                    }
                }
            });
            int removed = 0;
            int failures = 0;
            for (String id : dead) {
                try {
                    blobStore.delete(id);
                    states.remove(id);
                    removed++;
                    log.info("purge removed blobId={} (unreferenced, no in-flight reads)", id);
                } catch (RuntimeException e) {
                    // 保留待删状态，下次 purge 可安全重试；本次不计数。
                    failures++;
                    log.error("purge failed blobId={} kept as PENDING_DELETE for retry: {}",
                            id, e.toString());
                }
            }
            if (dead.isEmpty()) {
                log.info("purge: nothing to reclaim");
            } else if (failures > 0) {
                log.warn("purge partial: removed={} failures={} (retryable)", removed, failures);
            }
            return new PurgeOutcome(removed, failures);
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
}
