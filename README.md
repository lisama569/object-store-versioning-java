# 本地对象存储（版本化 + 空间回收 + 治理能力）

用**本地磁盘**存放内容寻址的数据 blob，用**进程内元数据**维护 key→版本链，
并以**引用计数 + 读引脚**协调版本保留与空间回收。目标是让同一对象的并发写入、
版本读取与回收行为一致且结果可解释。在此之上提供一层治理能力：**对象级保留规则**、
**删除恢复**与**阈值触发的自动回收**。

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
| `BlobRegistry` | blob 引用计数、读引脚、待删登记、字节总账、全局读写门（purge 逐 blob 持写门） |
| `ObjectStore` | 对外门面：`put/get/getVersion/delete/listVersions/restore` + 治理接口 `setRetentionPolicy/retentionPolicy/reclaimNow/purgeUnreferenced` |

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
- **回收与并发读的可解释分界**：物理回收**逐 blob** 在独立的写门临界区内完成
  （“复核状态→删文件→注销”），临界区之间释放写门，因此回收只让并发读写在
  单个 blob 删除的瞬间排队，不会长时间卡住；读/写临界区持**读门**。
  - 读在物理删除**之前**取得引脚：该文件在读结束前绝不会被删，读必完整成功；
  - 读在物理删除**之后**取得引脚：blob 已注销，引脚被拒，读显式报 `NO_SUCH_VERSION`
    （“版本数据已回收”），不会出现“文件被删导致 IO 错/半截读”这类不可解释结果。
  - 写发布“文件→引用登记”的临界序列也在读门内，杜绝“文件已发布但引用未登记”时被 GC 误删。
  - 回收中途失败（如磁盘 IO 错）：该 blob 登记保留、本轮跳过，重跑 `purge` 幂等重试——
    只有删除成功才计数并注销，不重复计数、不误删仍有引用的数据。

## 3. 版本与删除语义

- 每次 `put` 产生一个新版本（`versionId` 全局递增），`get` 读最新版本，
  `getVersion(key, versionId)` 读指定版本。
- **删除是追加一个删除标记版本（delete marker）**，不是物理擦除：
  - 删除后 `get` 报 `OBJECT_DELETED`；对删除标记版本调用 `getVersion` 同样报 `OBJECT_DELETED`；
  - 删除标记**之前**的数据版本在保留窗口内仍可按版本号读取；
  - 重复删除报 `OBJECT_DELETED`；删除从未存在的 key 报 `NO_SUCH_OBJECT`；
  - 删除后重新 `put` 会追加新版本使对象“复活”，历史版本链连续可查。
- **删除恢复 `restore(key, versionId)`**：追加一个内容与目标历史版本一致的新版本，
  恢复后 `get` 立即读到该版本内容；版本链连续（历史 + 删除标记 + 恢复版本）。
  - 目标版本在活跃链上：直接恢复（未删除的对象调用 restore 等价于“回滚”到历史版本）；
  - 目标版本已被保留规则裁剪、但底层数据**尚未物理回收**：从裁剪归档中找到元数据并“救回”；
  - 目标版本底层数据**已被物理回收**：显式报 `VERSION_DATA_RECLAIMED`，
    绝不返回看似成功却读不出内容的结果；
  - 目标版本号从未存在：`NO_SUCH_VERSION`；目标是删除标记：`OBJECT_DELETED`；
    key 从未存在：`NO_SUCH_OBJECT`。所有拒绝都不产生任何副作用。

### 可区分的拒绝原因（`StoreException.Reason`）

| Reason | 触发条件 |
| --- | --- |
| `INVALID_KEY` | key 为 null/空白/超长(>256)/含控制字符；content 为 null |
| `NO_SUCH_OBJECT` | 读/删/恢复一个从未创建的 key |
| `NO_SUCH_VERSION` | 指定版本号从未存在、已被保留策略裁剪，或其底层数据已被回收 |
| `OBJECT_DELETED` | 最新版本是删除标记、读删除标记版本、对已删除对象重复删除、恢复目标是删除标记 |
| `CHECKSUM_MISMATCH` | 写入回读校验失败或读取时发现磁盘静默损坏；坏数据已被隔离 |
| `INVALID_RETENTION_POLICY` | 保留规则非法：空规则、`maxVersions<1`、`minRetentionMillis<0`、低于全店最低保留要求 |
| `VERSION_DATA_RECLAIMED` | 恢复目标版本的底层数据已被物理回收，恢复不可能 |

## 4. 保留与回收规则

### 4.1 保留规则（全局默认 + 对象级覆盖）

- 每个 key 的保留规则由两个维度组成（`RetentionPolicy`）：
  - `maxVersions`：最多保留最近多少个版本（含删除标记）；
  - `minRetentionMillis`：版本自创建起**至少**保留多长时间。
- **一个版本只有在两个维度同时越界时才允许被回收**（比它新的版本数已达上限 **且**
  年龄已满足最低保留时长）；任一维度仍在保留范围内的版本必须能按版本号正常读出，
  内容保证与写入时一致。**最新版本永不裁剪**，任何规则都不会让对象变得不可读。
- 对象级规则用 `setRetentionPolicy(key, policy)` 设置（传 `null` 清除），未设置的维度
  回落到全局默认（`max-versions-per-key`、`default-min-retention-millis`）；
  只设 `minRetentionMillis` 时数量维度视为不限制，只设 `maxVersions` 时时长维度视为不要求。
- 规则在写入/删除/恢复的同 key 锁内即时生效，`setRetentionPolicy` 也会立即按新规则
  补做一次裁剪；`reclaimNow()`/阈值自动回收会对全部 key 重估规则（时长维度因此
  即使不再写入也会最终生效）。
- **非法规则显式拒绝**（`INVALID_RETENTION_POLICY`），绝不悄悄套用默认值：
  空规则（两个维度都未指定）、`maxVersions < 1`、`minRetentionMillis < 0`、
  以及低于全店最低保留要求 `min-retention-floor-millis` 的规则。

### 4.2 引用计数与物理回收

- blob 引用计数 = 引用它的**数据版本数**（跨 key 累计）。
- 计数归零时 blob **只登记为待删（PENDING_DELETE），不立即删文件**；物理删除只发生在
  `purgeUnreferenced()`/`reclaimNow()`（手动，或由 `auto-purge`/容量阈值自动触发）。
- 因此**被多个版本（或多个 key）共享的底层数据，只要还有一个版本引用就绝不会被清除**；
  最后一个引用消失并经一次 purge 后才物理删除。
- purge **逐 blob** 持写门：等待与该 blob 相关的在途读/写结束，删除条件为
  `refs==0 && pending && pins==0`。被引脚占住的 blob 会等到读结束后再删；
  临界区之间释放写门，并发读写只在单个 blob 删除瞬间排队。
- 归零后若同内容再次写入（`put` 在读门内），blob 会被重新 `acquire` 从待删态“救回”，
  GC 无法在中间窗口物理删除它；`restore` 对已裁剪但未物理回收的版本走同一救回路径。
- 被裁版本的元数据会进入**裁剪归档**（进程内），供 `restore` 区分
  “从未存在 / 已裁可救回 / 已物理回收”三种情形。

### 4.3 自动回收的触发条件与边界

写/删/恢复之后按以下规则决定是否回收（判定依据会打进日志）：

| 配置 | 触发条件 | 关闭/含义变化边界 |
| --- | --- | --- |
| `auto-purge=true` | 每次写/删/恢复后都物理回收无引用 blob | `false`（默认）时不触发 |
| `reclaim-max-bytes` | 已跟踪 blob 总字节数 **>** 该值时，执行一轮完整治理回收（全部 key 保留裁剪 + 物理回收） | `<=0`（默认）关闭；字节账是进程内统计，**进程重启后从零计**（磁盘上遗留文件不计入） |
| `reclaim-max-usage-ratio` | 数据目录所在卷 `已用/总空间 >=` 该比例时触发同上回收 | 不在 `(0,1]` 内（如 `0`、负数、`>1`）视为关闭；卷信息读取失败时本轮按未越限处理 |

- 三者可叠加：任一条件满足即触发；`reclaimNow()` 与 `purgeUnreferenced()` 也可随时手动调用。
- 回收是**状态驱动、幂等可重试**的：中途失败后重跑不重复计数、不多删，
  仍有引用的数据绝不受影响。

## 5. 可配置项

前缀 `object-store`（`application.properties`）：

| 配置 | 默认值 | 说明 |
| --- | --- | --- |
| `object-store.data-dir` | `${java.io.tmpdir}/object-store-versioning` | 数据根目录（blobs/tmp/quarantine） |
| `object-store.max-versions-per-key` | `0` | 全局默认每 key 保留版本数（含删除标记）；`0`/负数为不限制 |
| `object-store.default-min-retention-millis` | `0` | 全局默认“版本至少保留时长”（毫秒）；`0` 为不要求 |
| `object-store.min-retention-floor-millis` | `0` | 全店最低保留要求：对象级规则的 `minRetentionMillis` 不得低于它，否则拒绝配置 |
| `object-store.auto-purge` | `false` | 是否在写/删/恢复后自动物理回收无引用 blob |
| `object-store.reclaim-max-bytes` | `0` | 容量阈值（字节）：已跟踪 blob 总字节超过它时触发治理回收；`<=0` 关闭 |
| `object-store.reclaim-max-usage-ratio` | `0` | 占用比例阈值：卷 已用/总空间 ≥ 该比例时触发治理回收；不在 `(0,1]` 关闭 |

`ObjectStore` 注册为 Spring 单例 Bean，可直接注入使用。

> 说明：元数据为进程内状态，进程重启后版本链不保留（blob 文件仍在磁盘但不再可达）；
> 本实现定位为单机/单进程语义演示。

## 6. 验证方法

```bash
mvn test                                            # 全部用例
mvn test -Dtest=RetentionPolicyTest                 # 只看保留规则边界
mvn test -Dtest=RestoreTest                         # 只看删除恢复
mvn test -Dtest=AutoReclaimConcurrencyTest          # 只看自动回收与并发读写
```

测试位于 `src/test/.../store/`，均以并发与故障场景为主，日志打印输入、关键状态与判定依据
（`inputs:` / `judgement:` / `verdict:` 行可直接看到每个断言的依据）：

| 测试类 | 场景与断言 |
| --- | --- |
| `SequentialSemanticsTest` | 版本读/最新读、去重计数、删除标记、复活、四类拒绝原因 |
| `ConcurrentOverwriteTest` | 16 线程 × 20 轮并发覆盖：版本号唯一、无丢更新、每版可读且校验一致、引用总账平衡 |
| `ConcurrentDedupTest` | 24 线程 × 10 轮同内容并发写：物理文件仅一份、引用计数精确等于版本数、有引用时 purge 不删 |
| `ChecksumFailureTest` | 写时落盘损坏（隔离 + 拒绝 + 不产生版本/引用）与读时 bit rot（识别 + 隔离 + 绝不返回坏字节） |
| `RetentionReclamationTest` | 保留裁剪、待删登记、共享 blob 直到最后引用消失才回收 |
| `PurgeReadRaceTest` | 24 读者 + 写者 + 回收者 × 30 轮：读要么完整成功要么显式报“已回收”，无坏数据/IO 错；引脚保护 purge 必须等待在途读结束（×10 轮） |
| `RetentionPolicyTest` | 保留规则边界：非法规则（空规则/上限<1/负时长/低于全店最低保留要求）全部显式拒绝；时长维度保护超限年轻版本；超龄才裁剪；只配时长时最新版本永不裁；规则设置即追溯生效且各 key 独立 |
| `RestoreTest` | 删除后按历史版本恢复、已裁未回收可救回、物理回收后报 `VERSION_DATA_RECLAIMED`、非法目标（未存在 key/版本、删除标记）全部可区分拒绝且无副作用、未删除对象 restore 等价回滚 |
| `AutoReclaimConcurrencyTest` | 容量阈值触发自动回收 + 8 写者 4 读者并发：写不失败不卡死、读无坏数据、字节账与磁盘对平；删除失败注入后重试不重复计数、不误删有引用数据；比例阈值触发与 `0`/`>1` 关闭边界 |

手动验证治理能力的最小路径（单元测试同款语义）：

```java
ObjectStore store = new ObjectStore(props);          // props 见第 5 节配置
store.setRetentionPolicy("doc", new RetentionPolicy(3, 86_400_000L)); // 最近3版且至少留1天
store.put("doc", data);                              // 超阈值的旧版本在写/删/恢复时被裁剪
store.delete("doc");
store.restore("doc", versionId);                     // 恢复到指定历史版本
store.reclaimNow();                                  // 手动触发一轮“裁剪 + 物理回收”
```
