package com.github.lisama569.objectstoreversioningjava.service;

import java.util.concurrent.locks.ReentrantReadWriteLock;

/**
 * 存储内部共享锁集合，由 Spring 单例装配，保证 ObjectStore 与 GarbageCollector 是同一把锁。
 *
 * <ul>
 *   <li>{@link #gcLock}：全局读写锁。业务读写持<b>读锁</b>（彼此并发），
 *       回收器持<b>写锁</b>（与一切业务操作互斥）。这样回收扫描与删除之间，
 *       存活引用集合与磁盘集合都不会发生变化，“扫描到删除”是一个串行化临界区，
 *       回收与并发读取的结果因此可预期：读进行中不会开始 GC；GC 期间新读排队等待。</li>
 * </ul>
 *
 * <p>注：键级写串行化锁由 {@link DefaultObjectStore} 内部维护。
 */
public class StoreLocks {

    private final ReentrantReadWriteLock gcLock = new ReentrantReadWriteLock(true);

    public ReentrantReadWriteLock gcLock() {
        return gcLock;
    }
}
