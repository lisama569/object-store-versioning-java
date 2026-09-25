# 本地版本化对象存储（Local Versioned Object Store）

用**本地磁盘**（内容寻址块文件）+ **进程内元数据**（每键版本链）模拟一个支持版本的对象仓库，
聚焦在同一对象的**并发写入**、**版本读取**与**空间回收**上，使三者在并发下得到
**一致且可解释**的结果。基于 Spring Boot 3 / Java 21，无需任何外部数据库。

---

## 1. 数据模型

```
对象键 key
  └─ 版本链（版本号 1,2,3,… 单调递增，只增不复用）
       ├─ 数据版本   → 引用一个 ContentId
       └─ 删除标记   → 不引用任何内容（tombstone）

ContentId = 内容字节的 SHA-256（64 位十六进制）
磁盘：data-dir/blobs-root/
        blobs/<sha256>        正式内容块（不可变，文件名即地址）
        tmp/<uuid>            提交中的临时文件
        quarantine/<sha256>.corrupt-<actual12>   校验失败被隔离的块
```

- **内容寻址去重**：相同字节 ⇒ 相同 `ContentId` ⇒ 磁盘上只有一份块文件，
  可被同一对象的多个版本、或不同对象的版本共享。
- 版本节点是**不可变对象**；元数据读取一律返回副本/快照，调用方看不到中间状态。

---

## 2. 一致性模型

### 2.1 写入协议（put：新增/覆盖）
1. 校验键与内容，计算 `ContentId = SHA-256(content)`；
2. 获取该 key 的**键级锁**——同一对象的写彼此串行，不同对象并行；
3. 在**全局 GC 读锁**保护下，严格按 **先数据、后元数据** 的顺序：
   `blobs.commit()`（临时文件 → 摘要复验 → `ATOMIC_MOVE` 落位）**之后**，
   才在元数据中追加版本并分配版本号。

由此得到的保证：

| 场景 | 保证 |
|---|---|
| 并发覆盖同一对象 | 每次写入各得到一个新版本，版本号 `1..N` 连续，**绝不丢更新** |
| 相同内容的并发重复写 | 块以原子 move 幂等落位，磁盘上**只保留一份**底层数据 |
| 写过程中并发读 | 块在 move 完成前不存在于正式目录、元数据也未发布，**读不到半截对象**；读只能看到「块已提交 + 版本已发布」的完整状态 |
| 提交时声明地址与实际摘要不符 | 在写临时文件前即被拒绝，**磁盘不留垃圾** |

### 2.2 读取协议（get / getVersion）
持 GC 读锁，从不可变元数据节点定位 `ContentId`，再读块并对字节**重新计算 SHA-256 复验**：

- 摘要一致 ⇒ 返回内容；
- 摘要不一致（磁盘比特腐坏/被篡改）⇒ 把块移入 `quarantine/` 并抛
  `ChecksumMismatchException`，**损坏字节绝不作为正常数据返回**；此后读该版本持续显式失败，
  不会静默返回脏数据，也不会与「被回收/缺失」混淆。

### 2.3 并发控制结构
- **键级锁**（`ConcurrentHashMap<key, ReentrantLock>`）：串行化同一对象的
  put / delete / purge，是「不丢更新」的关键；
- **元数据锁**：守护版本号分配与版本链的所有读-改-写，并提供某一瞬间一致的快照；
- **全局 GC 读写锁**（`ReentrantReadWriteLock`）：业务读写持**读锁**（彼此并发），
  回收器持**写锁**（与一切业务操作互斥）。这保证回收的「扫描引用集合 → 删除无引用块」
  是一个静止临界区，从根上消除「扫描时还有引用、删除时引用已变」的竞态。

---

## 3. 版本与删除语义

| 操作 | 语义 |
|---|---|
| `put` | 追加一个数据版本（新增或覆盖）。空字节内容是合法对象。 |
| `get` | 读**最新**版本。 |
| `getVersion(n)` | 读**指定**版本（历史版本永久保留）。 |
| `delete` | **逻辑删除**：追加一个删除标记版本。**历史版本链原样保留，仍可按版本号读取。** |
| `purgeVersion(n)` | **永久删除单个版本**：只移除元数据节点，不直接删块；块是否回收交给 GC 依引用集合判定。 |

### 失败原因必须可区分
所有错误都是 `ObjectStoreException` 的独立子类，HTTP 层映射为不同状态码：

| 情况 | 异常 | HTTP |
|---|---|---|
| 键为 null / 空白 / 超长 / 含控制字符 | `InvalidKeyException` | 400 `INVALID_KEY` |
| 对象从未创建 | `ObjectNotFoundException` | 404 `OBJECT_NOT_FOUND` |
| 对象存在但指定版本号不存在（含非正数） | `VersionNotFoundException` | 404 `VERSION_NOT_FOUND` |
| 最新版本是删除标记（读最新版 / 重复逻辑删除 / 读到的版本本身是标记） | `ObjectDeletedException` | 410 `OBJECT_DELETED` |
| 内容 SHA-256 复验失败 | `ChecksumMismatchException` | 422 `CHECKSUM_MISMATCH` |

要点：
- 逻辑删除后 `get` 报「已删除」，但 `getVersion(旧版本号)` 仍成功；
- 对一个**已删除**对象再次 `delete` 会被拒绝（`OBJECT_DELETED`），避免无意义的连续标记；
- 对**不存在**的对象 `delete` 报 `OBJECT_NOT_FOUND`，与上面区分。

---

## 4. 版本保留与空间回收规则

回收（GC）算法是引用计数的集合论表达，在 GC 写锁下完成：

1. `onDisk` = 磁盘上所有块；
2. `live`   = 所有**存活数据版本**引用的 `ContentId` 集合（删除标记不引用内容；
   共享块在集合中天然只出现一次）；
3. **只删除 `onDisk − live` 差集**。

规则推论：
- **逻辑删除不回收任何块**——历史版本仍引用数据；
- 块被多个版本/对象共享时，只要**还有一个**引用就在 `live` 中，**绝不提前清除**；
- 永久删除版本只是移除一个引用；最后一个引用消失后，**下一次 GC** 才回收该块；
- 写路径「先块后元数据」：未被版本引用的块即便先落盘也只是无引用垃圾，可被安全回收，
  写者重试时块提交是幂等的。

**与并发读取并存时的结果是确定的**：读取在整个「定位版本 → 读块复验」期间持有 GC 读锁，
GC 不可能在中途删除其目标块；GC 进行中的新读会排队等待。因此不会出现
「版本还在、块却被删」导致的引用计数错误。`GcReport` 记录扫描数、删除/保留集合，
使每次判定都可解释。

---

## 5. 可配置项

`src/main/resources/application.properties`：

| 键 | 默认值 | 说明 |
|---|---|---|
| `object-store.data-dir` | `target/object-store-data` | 块数据根目录，其下自动建 `blobs/ tmp/ quarantine/` |
| `object-store.max-key-length` | `1024` | 对象键最大长度，超出按非法键拒绝 |

元数据为进程内实现，**重启进程后版本元数据清空**（块文件仍在磁盘，会被首次 GC 当作无引用垃圾回收）。
这是「本地模拟」的预期边界。

---

## 6. HTTP 验证入口

启动后（`server.port=0` 表示随机端口，看日志中的实际端口）：

```bash
# 写入（body 即内容字节）
curl -s -X POST --data-binary 'hello' http://localhost:$PORT/objects/docs/a
curl -s -X POST --data-binary 'world' http://localhost:$PORT/objects/docs/a

# 读最新版 / 指定版本（版本与内容地址在响应头）
curl -i http://localhost:$PORT/objects/docs/a
curl -i "http://localhost:$PORT/objects/docs/a?version=1"

# 版本链
curl -s "http://localhost:$PORT/objects/docs/a?versions=true"

# 逻辑删除（追加标记，旧版本仍可读）
curl -s -X DELETE http://localhost:$PORT/objects/docs/a
curl -i "http://localhost:$PORT/objects/docs/a?version=1"   # 仍 200
curl -i http://localhost:$PORT/objects/docs/a               # 410

# 永久删除单个版本 + 触发回收
curl -s -X DELETE "http://localhost:$PORT/objects/docs/a?purgeVersion=2"
curl -s -X POST http://localhost:$PORT/internal/gc          # 返回扫描/删除/保留明细
```

---

## 7. 自动化验证

```bash
mvn test
```

测试以**并发覆盖写入、校验失败隔离、回收竞态**为主，日志中打印输入、关键状态与判定依据
（`[CONCURRENT] [DEDUP] [CHECKSUM] [GC] [GC-RACE] [GC-LOCK] [KEY]`）：

| 测试类 | 覆盖内容 |
|---|---|
| `BasicVersioningTest` | 单调版本、按版本/最新读取、逻辑删除保留历史、三类失败原因可区分 |
| `ConcurrentOverwriteTest` | 16 写者 × 20 轮并发覆盖：版本号 1..320 不丢；数千次并发读重新摘要复验 0 损坏；相同内容 12 路并发写只落 1 份块 |
| `ChecksumFailureTest` | 提交时地址不符即拒；磁盘块被篡改后读复验失败并隔离、不返回脏字节；共享块损坏时引用它的版本全部显式失败 |
| `GarbageCollectionTest` | 逻辑删除零回收；共享块在最后引用消失前保留；跨对象共享引用计数正确 |
| `GcRaceTest` | GC 与数千~上万次写/读/永久删除并发：0 脏读、竞态中实际回收无引用块、静止后磁盘集合≡存活引用集合；并直接验证读锁持有期间 GC 被阻塞 |
| `InvalidKeyAndEdgeTest` | 各类非法键在边界被拒；空内容合法 |

典型竞态测试输出（数值随机器调度变化）：

```
[GC-RACE] 并发结束：写入=2312 永久删除=168 读取成功=31081 读取损坏=0 GC次数=1271
[GC-RACE] 竞态期间各轮 GC 累计删除块=127（这些块删除瞬间均无存活引用）
[GC-RACE] 最终静止 GC：...磁盘块=1725，存活引用=1725，判定依据 onDisk==live
[GC-LOCK] 读锁持有 200ms 期间 GC 线程仍存活（被阻塞），块安全
```

---

## 8. 代码结构

```
core/       值类型（ObjectKey/VersionId/ContentId/ObjectVersion/StoredContent/GcReport）
            + 可区分的领域异常层级
storage/    BlobStorage（LocalBlobStorage：原子提交/读取复验/隔离）
            MetadataStore（InMemoryMetadataStore：版本链 + 元数据锁 + 快照）
service/    ObjectStore 门面（DefaultObjectStore：键级锁 + GC 读锁 + 先数据后元数据）
            GarbageCollector（DefaultGarbageCollector：差集回收），StoreLocks
config/     StoreProperties + Spring 装配
web/        ObjectController / GcController（HTTP 手工验证入口，异常→状态码映射）
```
