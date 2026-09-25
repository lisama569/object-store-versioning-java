# 本地对象存储（版本化 + 空间回收）

用**本地磁盘**存放内容寻址的数据 blob，用**进程内元数据**维护 key→版本链，
并以**引用计数 + 读引脚**协调版本保留与空间回收。目标是让同一对象的并发写入、
版本读取与回收行为一致且结果可解释。

## 1. 目录与组件

```
<data-dir>/
├── blobs/<指纹前2位>/<SHA-256>   # 正式数据区：文件名即内容指纹（blobId）
├── tmp/…（实现使用同目录暂存文件） # 落盘暂存
└── quarantine/<指纹>-<阶段>-<uuid> # 校验失败数据的隔离区，永不作为正常数据读取
```

| 组件 | 职责 |
| --- | --- |
| `FileSystemBlobStore` | 内容寻址写入（暂存→回读校验→原子发布）、读时校验、损坏隔离、物理删除 |
| `MetadataTable` | key→版本链；同 key 写操作按 64 段分段锁串行；读操作取不可变快照 |
| `BlobRegistry` | blob 引用计数、读引脚、待删登记、全局读写门（purge 持写门） |
| `ObjectStore` | 对外门面：`put/get/getVersion/delete/listVersions/purgeUnreferenced` |

## 2. 一致性模型

- **单次操作原子可见**：写入先写同目录暂存文件并 `force`，回读做 SHA-256 校验，
  校验通过后用**原子 rename** 发布。任何读者在任何时刻只能看到“不存在”或“完整且校验通过”
  的正式文件，读不到半截数据；元数据版本链采用 copy-on-write 整体替换，读到的永远是
  某个完整提交的版本链。
- **同 key 写入线性化**：同一 key 的 put/delete/裁剪在同一把分段锁内串行，每次写入获得
  全局单调递增的唯一版本号——并发覆盖**无丢更新**；不同 key 互不阻塞。
- **读已提交 + 校验**：读在元数据锁外取快照定位版本，再在“全局读门 + 读引脚”保护下读文件并
  重新校验 SHA-256；读到的内容要么与提交时完全一致，要么得到显式异常。
- **内容去重**：blobId = 内容 SHA-256。相同内容（即便来自并发写、不同 key）只落一份物理文件；
  每个版本各自持有一个引用，去重不影响版本独立性。
- **回收与并发读的可解释分界**：物理回收持全局**写门**，读/写临界区持**读门**。
  - 读在物理删除**之前**取得引脚：该文件在读结束前绝不会被删，读必完整成功；
  - 读在物理删除**之后**取得引脚：blob 已注销，引脚被拒，读显式报 `NO_SUCH_VERSION`
    （“版本数据已回收”），不会出现“文件被删导致 IO 错/半截读”这类不可解释结果。
  - 写发布“文件→引用登记”的临界序列也在读门内，杜绝“文件已发布但引用未登记”时被 GC 误删。

## 3. 版本与删除语义

- 每次 `put` 产生一个新版本（`versionId` 全局递增），`get` 读最新版本，
  `getVersion(key, versionId)` 读指定版本。
- **删除是追加一个删除标记版本（delete marker）**，不是物理擦除：
  - 删除后 `get` 报 `OBJECT_DELETED`；对删除标记版本调用 `getVersion` 同样报 `OBJECT_DELETED`；
  - 删除标记**之前**的数据版本在保留窗口内仍可按版本号读取；
  - 重复删除报 `OBJECT_DELETED`；删除从未存在的 key 报 `NO_SUCH_OBJECT`；
  - 删除后重新 `put` 会追加新版本使对象“复活”，历史版本链连续可查。

### 可区分的拒绝原因（`StoreException.Reason`）

| Reason | 触发条件 |
| --- | --- |
| `INVALID_KEY` | key 为 null/空白/超长(>256)/含控制字符；content 为 null |
| `NO_SUCH_OBJECT` | 读/删一个从未创建的 key |
| `NO_SUCH_VERSION` | 指定版本号从未存在、已被保留策略裁剪，或其底层数据已被回收 |
| `OBJECT_DELETED` | 最新版本是删除标记、读删除标记版本、对已删除对象重复删除 |
| `CHECKSUM_MISMATCH` | 写入回读校验失败或读取时发现磁盘静默损坏；坏数据已被隔离 |

## 4. 保留与回收规则

- 保留上限 `max-versions-per-key`（含删除标记，0/负数=不限制）。超出上限时，写入/删除
  在同 key 锁内裁剪最旧版本，并对被裁版本引用的 blob 执行 `release`。
- blob 引用计数 = 引用它的**数据版本数**（跨 key 累计）。
- 计数归零时 blob **只登记为待删（PENDING_DELETE），不立即删文件**；物理删除只发生在
  `purgeUnreferenced()`（手动，或开启 `auto-purge` 后由写/删触发）。
- 因此**被多个版本（或多个 key）共享的底层数据，只要还有一个版本引用就绝不会被清除**；
  最后一个引用消失并经一次 purge 后才物理删除。
- purge 持全局写门：等待所有在途读/写结束并阻止新进入，删除条件为
  `refs==0 && pending && pins==0`。被引脚占住的 blob 即使计数归零也会留到读结束后的下一次 purge。
- 归零后若同内容再次写入（`put` 在读门内），blob 会被重新 `acquire` 从待删态“救回”，
  GC 无法在中间窗口物理删除它。

## 5. 可配置项

前缀 `object-store`（`application.properties`）：

| 配置 | 默认值 | 说明 |
| --- | --- | --- |
| `object-store.data-dir` | `${java.io.tmpdir}/object-store-versioning` | 数据根目录（blobs/tmp/quarantine） |
| `object-store.max-versions-per-key` | `0` | 每 key 保留版本数（含删除标记）；`0`/负数为不限制 |
| `object-store.auto-purge` | `false` | 是否在写/删裁剪后自动物理回收；`false` 时手动调用 `purgeUnreferenced()` |

`ObjectStore` 注册为 Spring 单例 Bean，可直接注入使用。

> 说明：元数据为进程内状态，进程重启后版本链不保留（blob 文件仍在磁盘但不再可达）；
> 本实现定位为单机/单进程语义演示。

## 6. 验证方法

```bash
mvn test
```

测试位于 `src/test/.../store/`，均以并发与故障场景为主，日志打印输入、关键状态与判定依据：

| 测试类 | 场景与断言 |
| --- | --- |
| `SequentialSemanticsTest` | 版本读/最新读、去重计数、删除标记、复活、四类拒绝原因 |
| `ConcurrentOverwriteTest` | 16 线程 × 20 轮并发覆盖：版本号唯一、无丢更新、每版可读且校验一致、引用总账平衡 |
| `ConcurrentDedupTest` | 24 线程 × 10 轮同内容并发写：物理文件仅一份、引用计数精确等于版本数、有引用时 purge 不删 |
| `ChecksumFailureTest` | 写时落盘损坏（隔离 + 拒绝 + 不产生版本/引用）与读时 bit rot（识别 + 隔离 + 绝不返回坏字节） |
| `RetentionReclamationTest` | 保留裁剪、待删登记、共享 blob 直到最后引用消失才回收 |
| `PurgeReadRaceTest` | 24 读者 + 写者 + 回收者 × 30 轮：读要么完整成功要么显式报“已回收”，无坏数据/IO 错；引脚保护 purge 必须等待在途读结束（×10 轮） |
