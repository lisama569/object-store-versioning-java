package com.github.lisama569.objectstoreversioningjava.service;

import com.github.lisama569.objectstoreversioningjava.core.GcReport;

/**
 * 空间回收：删除磁盘上无任何存活版本引用的内容块。
 * 共享数据在仍有版本引用时绝不删除；回收与业务读写通过全局读写锁串行化。
 */
public interface GarbageCollector {
    GcReport collect();
}
