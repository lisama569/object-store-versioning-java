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
| `MetadataTable` | key→版本链 + 已裁剪版本归档；同 key 写操作按 64 段分段锁串行；读操作取不可变快照 |
| `BlobRegistry` | blob 引用计数、读引脚、待删登记、全局读写门（purge 持写门）、可重试的物理回收 |
| `SpaceReclaimer` | 容量/占用比例阈值检查，越界时异步执行“保留裁剪 + 物理回收” |
| `ObjectStore` | 对外门面：`put/get/getVersion/delete/restore/listVersions/setRetentionPolicy/purgeUnreferenced` |

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
- **删除恢复 `restore(key, versionId)`**：以指定历史版本的内容**追加一个新版本**
  （不是回滚，历史链保持连续），恢复后 `get` 立即读到该内容：
  - 目标版本仍在活跃链中：直接共享其 blob（引用计数 +1）；
  - 目标版本已被保留策略裁剪、但底层数据仍在磁盘（被其他版本引用或待删未清）：
    从归档元数据找到它并把 blob 从待删态“救回”；
  - 目标版本的底层数据**已被物理回收**：明确报 `VERSION_DATA_RECLAIMED`，
    绝不返回“看起来成功、实际读不出内容”的假结果，对象维持删除态；
  - 目标是删除标记报 `OBJECT_DELETED`；版本从未存在（或归档已溢出）报 `NO_SUCH_VERSION`；
    key 不存在报 `NO_SUCH_OBJECT`。

### 可区分的拒绝原因（`StoreException.Reason`）

| Reason | 触发条件 |
| --- | --- |
| `INVALID_KEY` | key 为 null/空白/超长(>256)/含控制字符；content 为 null |
| `NO_SUCH_OBJECT` | 读/删/恢复一个从未创建的 key |
| `NO_SUCH_VERSION` | 指定版本号从未存在、已被保留策略裁剪（读），或恢复目标的归档元数据已溢出 |
| `OBJECT_DELETED` | 最新版本是删除标记、读删除标记版本、重复删除、恢复目标是删除标记 |
| `CHECKSUM_MISMATCH` | 写入回读校验失败或读取时发现磁盘静默损坏；坏数据已被隔离 |
| `INVALID_RETENTION_POLICY` | 保留规则为负值/空规则，或与最低保留要求（守卫下限）冲突 |
| `VERSION_DATA_RECLAIMED` | 恢复目标的元数据尚在归档，但底层数据已被物理回收，无法恢复 |

## 4. 保留与回收规则

### 4.1 保留策略（RetentionPolicy）

- 每个对象可通过 `setRetentionPolicy(key, policy)` 配置保留规则，两条规则可独立或组合：
  - `maxVersions`：最多保留最近 N 个版本（含删除标记），`0` = 不按数量限制；
  - `minRetentionMillis`：版本至少保留的时长（毫秒），`0` = 不按时间限制。
- **判定规则**：一个历史版本只有在“数量规则”和“时间规则”都允许回收时才会被裁剪
  （超出最近 N 个 **且** 年龄已达保留时长）；任一规则还在保护它，就必须能按版本号正常读出，
  内容绝不走样。链尾**最新版本永不裁剪**，保证对象当前状态始终可解释。
- 未配置 per-key 规则的对象回落到全局默认 `max-versions-per-key`；
  `clearRetentionPolicy(key)` 移除 per-key 规则。设置规则时立即对已有版本执行一次裁剪。
- **守卫下限（最低保留要求）**：`retention-min-versions`（默认 1）与
  `retention-min-retention-millis`（默认 0）是全店最低保留要求。per-key 规则若更宽松
  （如 `maxVersions` 低于下限、保留时长短于下限），或规则本身为负值/空规则，
  一律抛 `INVALID_RETENTION_POLICY` 并说明原因，**不会悄悄套用默认值**。
- 被裁剪的版本：blob 引用立即 `release`（登记待删），版本元数据进入**归档**（每 key 上限
  256 条，仅元数据不占数据空间），供 `restore` 区分“版本从未存在”与“数据已被回收”。

### 4.2 引用计数与物理回收

- blob 引用计数 = 引用它的**数据版本数**（跨 key 累计）。
- 计数归零时 blob **只登记为待删（PENDING_DELETE），不立即删文件**；物理删除只发生在
  `purgeUnreferenced()`（手动、开启 `auto-purge` 后由写/删触发、或回收器阈值越界时）。
- 因此**被多个版本（或多个 key）共享的底层数据，只要还有一个版本引用就绝不会被清除**；
  最后一个引用消失并经一次 purge 后才物理删除。
- purge 持全局写门：等待所有在途读/写结束并阻止新进入，删除条件为
  `refs==0 && pending && pins==0`。被引脚占住的 blob 即使计数归零也会留到读结束后的下一次 purge。
- 归零后若同内容再次写入（`put` 在读门内），blob 会被重新 `acquire` 从待删态“救回”，
  GC 无法在中间窗口物理删除它。
- **回收可安全重试**：purge 只统计真正删除成功的 blob；单个 blob 删除失败只记录失败、
  保留其待删状态并继续处理其余 blob。中途失败后重新执行不会重复计数、不会多删，
  仍有引用的数据绝不在删除集合内。

### 4.3 空间自动回收（SpaceReclaimer）

- **触发条件**（任一越界即触发，都未配置则自动回收整体关闭）：
  - `reclaim-max-disk-bytes`：blobs 区占用字节数超过该值；
  - `reclaim-max-usage-ratio`：数据目录**所在卷**的占用比例（已用/总量）达到该值，
    取值范围 `(0,1]`，超出范围启动即拒绝。
- 触发后执行：先对全部 key 按保留策略裁剪（时间规则随时间推移才会裁出新版本），
  再物理回收无引用 blob。每次检查与执行都输出 `ReclaimReport`
  （占用、阈值、裁剪数、删除数、失败数），日志可直接看到判定依据。
- **与并发读写的关系**：写/删路径只投递一个异步信号立即返回，阈值检查与回收在后台
  单线程执行——**写入不会因回收而阻塞或失败**；信号合并保证“最后一次写入之后总有
  一轮回收”，不漏末尾的待删数据。物理回收本身持写门，只等待在途读/写临界区结束；
  读到的要么是完整校验通过的内容，要么是显式 `NO_SUCH_VERSION`，绝不读到已回收数据。
- **边界语义**：
  - 阈值为 `0`/未配置 = 该条件关闭；两个都关闭 = 自动回收关闭，只能手动
    `purgeUnreferenced()` / `reclaimer().reclaimIfNeeded()`；
  - 占用比例是**整卷**口径（同卷其他应用也会推高比例），不是本仓库目录占比；
  - 回收后占用仍越界时只告警不再强清：说明剩余数据仍被版本引用或被读引脚占住，
    属于“该留的”，不会为了压阈值去删活数据；
  - 时间类保留规则只在“写/删/设置策略/回收触发”这些裁剪点上生效，没有独立定时器。

## 5. 可配置项

前缀 `object-store`（`application.properties`）：

| 配置 | 默认值 | 说明 |
| --- | --- | --- |
| `object-store.data-dir` | `${java.io.tmpdir}/object-store-versioning` | 数据根目录（blobs/tmp/quarantine） |
| `object-store.max-versions-per-key` | `0` | 全局默认保留版本数（含删除标记）；`0`/负数为不限制，可被 per-key 规则覆盖 |
| `object-store.auto-purge` | `false` | 是否在写/删裁剪后自动物理回收；`false` 时手动调用 `purgeUnreferenced()` |
| `object-store.retention-min-versions` | `1` | 保留规则守卫：每 key 至少保留版本数下限，per-key 规则不得更低（必须 >=1） |
| `object-store.retention-min-retention-millis` | `0` | 保留规则守卫：版本最短保留时长下限（毫秒），per-key 规则不得更短 |
| `object-store.reclaim-max-disk-bytes` | `0` | 自动回收阈值：blobs 区占用字节上限；`0` = 不按容量触发 |
| `object-store.reclaim-max-usage-ratio` | `0` | 自动回收阈值：数据目录所在卷占用比例上限 `(0,1]`；`0` = 不按比例触发 |

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
| `RetentionPolicyTest` | 非法/冲突保留规则明确拒绝（`INVALID_RETENTION_POLICY`）；数量规则裁剪、时间下限保护年轻版本、保留范围内版本按版本号可读且内容一致、最新版本永不裁剪、per-key 覆盖与清除回落 |
| `RestoreObjectTest` | 删除后恢复到历史版本（追加新版本、get 立即可读、链连续）；从归档救回待删 blob；数据已回收报 `VERSION_DATA_RECLAIMED` 且对象维持删除态；删除标记/未知版本/未知 key 各有可区分原因 |
| `SpaceReclaimTest` | 容量阈值未越界不动作、越界才回收（报告含判定依据）；重复执行不重复计数；删除中途失败后重试恰好补删、不多删；有引用数据绝不被动；阈值未配置时回收关闭、非法阈值启动即拒绝 |
| `ReclaimConcurrencyTest` | 回收线程满负荷 + 4 写者 × 25 写 + 8 读者 × 10 轮：写不失败、读无坏数据只有可解释结果；异步触发路径下写入不阻塞、物理文件数最终收敛到存活 blob 数 |
