# KnowNote · 安卓零碎知识点记事本

需求文档 `Desktop/harness/需求文档安卓记事本.md` 的 **1.0 demo 实现**，覆盖第一期「核心可用」范围。
Kotlin + Jetpack Compose + **Material 3**，Room(SQLite) + 全文检索，MVVM + Repository。

- 包名：`com.xinjigalaxy.knownotes`（debug 变体带 `.debug` 后缀）
- minSdk 26 / targetSdk 36 / compileSdk 36
- 主题种子色 `#39C5BB`（初音绿），默认不吃 Android 12+ 动态取色

---

## 一、已实现（对应需求文档第 7 章第一阶段）

| 需求 | 实现 |
| --- | --- |
| 笔记 CRUD | 增 / 改 / 软删除（`is_deleted`）/ 恢复；列表长按可删，删除后有「撤销」 |
| 标签管理 | 增 / 改名 / 删除 / **合并到**；点条目 → **二级页面**看该标签下的笔记（左上返回） |
| 分组管理 | 增 / 改名 / 删除（可选择「保留笔记」或「一起删除」）/ 上移下移排序；点条目 → **二级页面**看组内笔记 |
| 全文检索 | FTS5 → FTS4 → LIKE 三级降级；**中文子串命中**；前缀匹配；标签进索引；结果高亮 |
| 组合筛选 | 关键词 + 多标签 + 分组（全部 / 未分组 / 指定） |
| 导出 | `.json` / `.csv`（带 BOM）/ `.db`（VACUUM INTO 一致性快照），SAF 保存免存储权限 |
| 升级兼容 | `user_version` + 增量 `Migration`（`AppDatabase.MIGRATIONS`，1→2→3 全部只用 ALTER / CREATE，不重建表） |
| 局域网同步 | ✅ 一主多从 + 手动触发：主机内嵌 HTTP 服务（默认 8765），从机填「地址 + 共享密钥」同步；**一次请求双向**（推自己的增量 + 拉主机的增量）；增量靠 `change_log` + 水位线；冲突按 `updated_at` 优先、打平按内容确定性收敛（详见第六节） |
| 同步日志 | ✅ `sync_log` 表记录每次同步（角色 / 对端 / 拉取 / 推送 / 冲突 / 失败原因），同步页展示、可清空 |
| 跨设备标识 | ✅ `notes.guid`（唯一索引，v2→v3 迁移用 SQLite 的 `randomblob(16)` 回填老数据）；分组 / 标签跨设备按**名字**对齐，不需要 id 映射表 |
| 删除的同步 | ✅ 「彻底删除」改为墓碑（`is_purged`）而不是删行 —— 删行的话对端下次同步会把这条笔记推回来 |
| Markdown 渲染 | ✅ 自研轻量渲染器：标题 / 粗体 / 斜体 / 删除线 / 行内代码 / 代码块 / 列表 / 引用 / 可点链接；编辑页「编辑 ↔ 预览」切换 |
| 搜索结果高亮 | ✅ 命中词在标题与正文里高亮加粗 |
| 搜索历史与筛选记忆 | ✅ 搜索历史（同词合并计数、最多 12 条、chip 展示、可单删/清空）；标签与分组筛选写入 SharedPreferences |
| 回收站 | ✅ 软删除笔记的恢复 / 彻底删除 / 清空（文档 5.1 没列，但软删除没有入口等于变相丢数据） |
| 列表展示形态 | ✅ 列表 ↔ 瀑布流（两列 LazyVerticalStaggeredGrid）循环切换并记忆；**笔记页 / 分组页 / 标签页 / 二级页面共用同一份偏好** |
| 二级页面 | ✅ 分组条目、标签条目点进去是独立页面（`group/{groupId}`、`tag/{tagId}` 路由）：左上返回、标题为分组名或 `#标签名`、常驻本页筛选框、状态行、与笔记页同款的卡片与列表 ↔ 瀑布流切换、点笔记进查看 / 长按进编辑 |
| 数据迁移 | ✅ `user_version` 1→2（新增 `search_history`）、2→3（`guid` + `is_purged` + `sync_log`）真实迁移 + `MigrationTest` 对着 schema JSON 逐版本校验，含 1→3 跨级路径 |

界面页：笔记列表（常驻搜索框 + 标签 chips + 状态行）、笔记编辑（编辑/预览）、回收站、分组管理、标签管理、二级页面（分组内 / 标签内笔记）、更多（数据库概览）、导出、局域网同步。
底部导航 4 个条目：笔记 / 分组 / 标签 / 更多。

交互约定：列表里**点击 = 查看**（Markdown 预览），**长按 = 编辑**；新建走右下角「记一条」。

## 二、构建与运行

```bash
# 环境：JDK 17（D:\app\java17）、Android SDK（local.properties 已指向）
export JAVA_HOME="D:\\app\\java17"
./gradlew :app:assembleDebug          # 产物 app/build/outputs/apk/debug/app-debug.apk
./gradlew :app:installDebug           # 装到已连接设备
./gradlew :app:testDebugUnitTest      # 分词逻辑单测（纯 JVM，8 例）
./gradlew :app:connectedDebugAndroidTest  # 真机/模拟器功能测试（7 例）
```

> **注意**：wrapper 的 `distributionUrl` 指向华为镜像
> `https://mirrors.huaweicloud.com/gradle/gradle-8.13-bin.zip`。
> `services.gradle.org` 在国内直连会超时，导致 `gradle wrapper` 报
> “Test of distribution url ... failed”。要重新生成时用：
> `gradle wrapper --gradle-distribution-url https://mirrors.huaweicloud.com/gradle/gradle-8.13-bin.zip`

### 灌演示数据（可选）

`tools/seed-demo-db.py` 直接往 SQLite 文件里写 8 条示例知识点。
Room 用 WAL 模式，**必须连 `-wal` 一起取回**再本地 checkpoint：

```bash
PKG=com.xinjigalaxy.knownotes.debug
adb shell am force-stop $PKG
mkdir -p seed/live && cd seed/live
adb exec-out run-as $PKG cat databases/knownotes.db     > knownotes.db
adb exec-out run-as $PKG cat databases/knownotes.db-wal > knownotes.db-wal
python -c "import sqlite3;c=sqlite3.connect('knownotes.db');c.execute('PRAGMA wal_checkpoint(TRUNCATE)');c.close()"
cd ../.. && python tools/seed-demo-db.py seed/live/knownotes.db
adb push seed/live/knownotes.db /data/local/tmp/knownote-seed.db
adb shell run-as $PKG rm -f databases/knownotes.db-wal databases/knownotes.db-shm
adb shell run-as $PKG cp /data/local/tmp/knownote-seed.db databases/knownotes.db
```

脚本刻意不写 FTS 索引表，App 下次启动时会因条数不一致**自动重建**（`FtsStore.repair()`）。

## 三、代码地图

```
data/model/Entities.kt      notes / tags / groups / note_tags / sync_meta / change_log / search_history / sync_log
data/db/AppDatabase.kt      8 张实体表（v3）；FtsSchemaCallback 挂 FTS 建表与自愈；MIGRATION_1_2 / 2_3
data/db/NoteDao.kt          笔记 CRUD、软删除、墓碑、LIKE 兜底、分组/标签计数
data/db/MetaDaos.kt         标签（含合并）、分组（含排序）、变更日志、同步元数据、同步日志
data/db/FtsStore.kt         虚拟表 DDL / 引擎探测 / MATCH 查询 / 索引重建
data/fts/FtsText.kt         分词与 MATCH 表达式构造（中文子串检索的关键）
data/repo/NoteRepository.kt 唯一写入口：事务内同时改主表 + 关联 + FTS 索引 + 变更日志
data/export/Exporter.kt     JSON / CSV / SQLite 三种导出
data/sync/SyncModels.kt     线格式（SyncNote / SyncRequest / SyncResponse）+ JSON 编解码
data/sync/SyncEngine.kt     收集本地增量 + 应用远端变更（时间戳优先 / 打平的确定性收敛）
data/sync/SyncServer.kt     主机侧手写 HTTP 服务（ServerSocket，无第三方依赖）+ 局域网地址枚举
data/sync/SyncClient.kt     从机侧 HttpURLConnection 客户端 + 地址解析
data/sync/SyncCoordinator.kt 一次「从机同步会话」：取增量 → 发送 → 落库 → 水位线 → 日志
ui/…                        Compose 页面 + ViewModel（AppViewModelProvider 手工装配）
```

## 四、两个必须知道的 Android 平台坑（都已在真机上实测确认）

### 1. Android 系统 SQLite 没有 FTS5

需求文档 3.1 选了 FTS5，但 **Android 自带的 SQLite 不保证编译 FTS5**。
模拟器 Android 15（API 35，SQLite 3.44.3）实测：

```
sqlite=3.44.3 | FTS5=no such module: fts5 (code 1 SQLITE_ERROR) | FTS4=OK
```

因此实现改成**能力探测 + 三级降级**：FTS5 → FTS4 → LIKE，并把当前生效引擎显示在
列表状态行与「更多」页，检索降级不会静默发生。

顺带踩到的一个坑：**不能用 `CREATE VIRTUAL TABLE IF NOT EXISTS ... USING fts5` 探测模块是否可用**
——当同名表已存在（比如上一轮建成了 FTS4）时 SQLite 会跳过模块加载直接返回成功，
于是引擎被误判成 FTS5，再去用 FTS4 不支持的 `bm25()` 就会**一条都搜不到**。
现在改为先读 `sqlite_master` 里 `notes_fts` 的真实 DDL，再决定引擎。

### 2. FTS4 的标准查询语法不支持显式 `AND`

未编译 `SQLITE_ENABLE_FTS3_PARENTHESIS` 时，`AND` 会被当作普通词元，
`"检 索" AND "零 碎"` 永远搜不到东西。现在统一用**空格隐式 AND**
（`"检 索" "零 碎"`），FTS4 与 FTS5 都支持。

### 3. 中文分词策略（`FtsText`）

unicode61 与 simple 分词器都把**连续汉字当成一个 token**，于是「检索」搜不到「全文检索」，
只有前缀匹配有效。解决办法不依赖 jieba：

- **写入索引前**：CJK 逐字拆开（`全文检索` → `全 文 检 索`），拉丁词保持整词并小写
- **查询时**：连续 CJK 组成 phrase（`"检 索"`，位置相邻 ⇒ 子串命中），拉丁词用前缀（`sql*`）
- 索引里存的是分词副本，展示与导出永远用主表原文

索引条数与在线笔记数不一致时（升级、异常退出、外部灌库）自动整体重建。

### 4. 千万别靠 `sqlite_master` 判断"虚拟表是否已存在"（v1.0.0 的实际 bug）

Room 新建库时会**连续回调 `onCreate` 和 `onOpen`**，也就是同一个库里 `FtsStore.create()` 会被调用两次。
v1.0.0 在第二次调用时去 `sqlite_master` 里找已有表：

```kotlin
// ❌ v1.0.0 的写法
val ddl = db.query("SELECT sql FROM sqlite_master WHERE type='table' AND name=?", arrayOf("notes_fts"))
if (ddl == null) { /* 去建表 —— 但表其实已经存在 */ }
```

在 SQLite 3.32（Android 12 / 13）上这次查询拿不到值，于是：

1. 误判成"表不存在" → 重试建 FTS5 → 报 `table notes_fts already exists`
2. 再试 FTS4 → 同样 `already exists`
3. **已经建好的引擎被降级成 NONE，检索退化为 LIKE**

实测复现（Android 13 / SQLite 3.32.2 模拟器）：

```
W KnowNote: 全文检索引擎 FTS5 不可用: table notes_fts already exists ...
W KnowNote: 全文检索引擎 FTS4 不可用: table notes_fts already exists ...
```

**v1.0.1 的修法：完全按行为判定，不解析 DDL 文本，且探测/建表必须幂等**

- 表已存在 → 用 `MATCH` 能不能过判断"模块在不在"，再用 `bm25()` 是否存在区分 FTS5 / FTS4
- 模块可用性探测走 `temp.` 临时表，**绝不拿真表名试错**（失败的 CREATE 在部分版本会留下同名残留）
- `create()` 可重复调用，第二次不得改变第一次的结论（回归测试 `repeatedProbeMustNotDowngradeTheEngine` 守住）
- 索引条数与在线笔记数不一致就整体重建，兜住升级 / 崩溃 / 外部灌库等各种状态

> 诚实说明：3.32 上"第一次建好的表为何没落盘"的 SQLite 内部原因我没能完全钉死（同连接立刻查
> `sqlite_master` 在两版 SQLite 上都是可见的）。但 v1.0.1 已不依赖任何这些假设 ——
> 不解析 DDL、不重试建表、模块探测隔离在 temp、条数不符即重建，所以各种中间状态都能收敛。

## 五、验证记录

| 检查项 | 结果 |
| --- | --- |
| `:app:assembleDebug` / `assembleRelease` | BUILD SUCCESSFUL |
| 单元测试 `FtsTextTest` | 8/8 通过 |
| 仪器化测试（Android 13 / SQLite 3.32.2） | 18/18 通过（`tests="18" failures="0" errors="0"`） |
| 仪器化测试（Android 15 / SQLite 3.44.3） | 18/18 通过 |
| 迁移测试 `MigrationTest` | 1→2 迁移后表结构经 schema 校验，笔记/标签关联原样保留 |
| 编辑页测试 `NoteEditViewModelTest` | 7/7（含「输入框待确认标签必须入库」「只看不改返回不刷新 updated_at」两个回归） |
| APK 元信息 | minSdk 26 / targetSdk 36 / 标签「知识点记事本」 |
| Room schema 导出 | `app/schemas/…/1.json`、`2.json` |
| 真机检索（LG G8 / Android 13） | 中文子串「检索」命中 2 条、前缀 `gradle*` 命中 1 条、多词元 AND 命中正确 |
| 索引自愈 | 灌库时索引 0 条 → 启动后 8 条，与在线笔记数一致 |
| **升级路径自愈（Android 13 / SQLite 3.32.2）** | v1.0.0 造出降级状态 → 覆盖装 v1.0.1 → 引擎恢复 FTS4，`FTS 索引条数对不上（0 → 8），已整体重建`，MATCH 查询全部命中 |
| **真机迁移（LG G8，原有 3 条笔记）** | 覆盖装 v1.1.0 → `user_version` 1 升 2、`search_history` 建出、3 条笔记与索引全部保留 |
| **真机二级页面（LG G8 / v1.2.1）** | 分组条目、标签条目点进去都是独立页面：左上「返回」按钮、标题为分组名、本页筛选框、状态行「共 3 条笔记」、与笔记页同款卡片；两页的列表 ↔ 瀑布流切换都生效；页内点笔记直接进「查看」预览；返回链路（笔记 → 二级页面 → 上级列表）逐级正确 |
| **真机回归：只看不改不刷新时间（LG G8 / v1.2.1）** | 点开笔记 → 直接返回，列表里三条笔记的时间文字与点开前 **完全一致**（修复前会被无条件保存刷成「刚刚」） |
| **真机迁移 v2→v3（LG G8，原有 3 条笔记 / v1.3.0）** | 覆盖装 v1.3.0 → `user_version` 2 升 3；`guid` 回填 **3/3 且唯一**（32 位十六进制，SQLite `randomblob(16)`）、`is_purged` 默认 0、`sync_log` 建出；3 条笔记与 FTS 索引全部完好 |
| **真机同步·主机侧（LG G8 / v1.3.0）** | 开启主机后从 PC 直连真机接口：`GET /ping` → 200 `{"protocol":1,"device_name":"Android 13 真机"}`；错密钥 `POST /sync` → **401 共享密钥不匹配**；正确密钥推一条笔记 → 200（主机落库 1 条，并把 3 条真实笔记回给从机）。落库后：笔记数 3→4、分组「PC 测试分组」与标签「同步测试」按名字自动建出、FTS 索引 4 条、`sync_log` 记 `host | PC 侧测试 | 拉=3 推=1` |
| **真机同步·App ↔ App（LG 当主机 / Android 13 模拟器当从机）** | 模拟器同步 → 拉到 4 条全部落库（含 PC 推的那条），分组 / 标签名字对齐、水位线写入 `sync_meta`；从机新建笔记后再次同步 → 「推过去 1 条，主机落库 1 条」，主机笔记数 3→5；第三次同步两边均无增量（水位线幂等） |
| **真机同步·日志（两侧）** | 主机侧 `host` 5 条、从机侧 `client` 4 条，字段（对端 / 拉取 / 推送 / 结果）逐条对得上实际行为 |

界面截图见 `demo-shots/`（模拟器）与 `lg-shots/`（LG G8 真机）。

## 五之二、版本记录

| 版本 | 说明 |
| --- | --- |
| v1.0.0 | 首个可用版本（第一阶段全部功能） |
| v1.0.1 | 修 FTS 引擎误判导致检索降级为 LIKE；新增「更多」页诊断信息，重复探测幂等 + 回归测试 |
| v1.1.0 | 第二阶段：Markdown 渲染、编辑/预览切换、搜索历史、筛选记忆、回收站；数据库 1→2 真实迁移 + 迁移测试 |
| v1.1.1 | 修「记一条」里标签输完直接保存会丢标签；点击=查看 / 长按=编辑；列表 ↔ 瀑布流切换 |
| v1.2.0 | 「分组」「标签」页条目点击可看组内 / 带该标签的笔记；两页复用笔记页的卡片样式与列表 ↔ 瀑布流循环切换；卡片组件抽成共享 `ui/components/NoteCards.kt`，展示形态枚举移到 `ui/NoteLayout.kt`。**（该版做成的是「原地展开」，v1.2.1 已改成二级页面）** |
| v1.2.1 | 按反馈把「原地展开」改为**独立二级页面**（`group/{groupId}`、`tag/{tagId}` 路由 + `NoteCollectionScreen`）：左上返回、本页筛选框、与笔记页同款卡片与形态切换；顺带修真机验证时发现的 bug —— 只是点开看了一眼就返回，会被无条件保存刷新 `updated_at`、把笔记顶到列表最前（加 `dirty` 判定 + 3 个回归测试） |
| v1.3.0 | 第三阶段：**局域网同步**落地 —— 一主多从 + 手动触发、主机内嵌 HTTP 服务（手写 ServerSocket，无第三方依赖）、协议 `GET /ping` + `POST /sync`、一次请求双向增量、时间戳优先冲突裁决（打平按内容确定性收敛）、共享密钥认证；数据库 2→3 迁移（`notes.guid` 唯一索引 + `is_purged` 墓碑 + `sync_log`）；「彻底删除」由删行改墓碑；同步页从路标页换成真页面；顺带修「更多」页版本号写死的问题 |

## 六、局域网同步（v1.3.0，需求文档 4 + 7 第三阶段）

### 6.1 模式：一主多从 + 手动触发

一台设备开「主机模式」（内嵌 HTTP 服务，默认端口 8765），其他设备填「主机地址 + 共享密钥」点「开始同步」。
同一台设备两个角色都能当（同步页里两块都在），个人场景下经常是手机和平板互相轮换。

### 6.2 协议：HTTP + JSON，两个接口

| 接口 | 说明 |
| --- | --- |
| `GET /ping` | 连通性探测，只回 `{protocol, device_name}`，**不需要密钥** —— 用来区分「地址填错了 / 主机没开」和「密钥不对」这两种失败 |
| `POST /sync` | 一次请求完成双向同步：请求头 `X-KnowNote-Key`，请求体带从机水位线 + 自己的变更，响应带主机的变更 + 落库统计 |

**线格式里不带任何本地 id**：笔记用 `guid`（32 位十六进制）认人；分组和标签直接传**名字**，
对端按名字解析、没有就建 —— 于是完全不需要 id 映射表。

```json
{"guid":"a4e5f331...","title":"...","content":"...","group":"零散知识点","tags":["标签1"],
 "created_at":1700000000000,"updated_at":1700000001000,"is_deleted":0,"is_purged":0}
```

### 6.3 增量：change_log + 水位线

- 每次增删改（含软删除、彻底删除）都写一条变更日志；
- 同步时请求方带上自己的 `last_sync_at`，只发「这条水位线之后我改过的东西」；主机同理只回它水位线之后的变更；
- 同步成功后水位线取 **min(主机时间, 本机时间)**：宁可下次多要一点（重复应用是幂等的），
  也不因为两台设备时钟有偏差而漏掉变更。
- 主机收到从机的变更时会**记一条变更日志**（`at` 用主机收到的时刻），
  否则第三个从机永远拉不到「第二个从机改的内容」；从机侧则**不记**，免得把自己的库当新变更推回去。

### 6.4 冲突：时间戳优先，打平按内容确定性收敛（需求文档 4.3）

1. `updated_at` 大的那版整体覆盖（标题 / 正文 / 分组 / 标签 / 软删除 / 墓碑一次全换）；
2. 本机更新就不动 —— 对端下次同步会拿到本机这版，按同样规则认输；
3. **时间戳打平但内容不同**：两端都取「规范串较大」的那版。这样一定收敛到同一份，
   而不是各留各的、每次同步互相打回。**这条有专门的回归测试**：两台设备各自应用对方的版本，断言最终内容相同。

### 6.5 「彻底删除」为什么改成墓碑

原来「彻底删除」是 `DELETE FROM notes`。有了同步之后这会出事：对端不知道你删了，
下次同步会把这条笔记**再推回来**。所以 v1.3.0 起彻底删除 = 保留一行 `is_deleted=1 + is_purged=1` 的墓碑，
所有列表 / 检索 / 计数都过滤 `is_purged=1`，删除意图靠时间戳传出去。

### 6.6 安全边界（不承诺做不到的事）

- **认证**：`X-KnowNote-Key` 共享密钥，常量时间比较，错密钥 401。密钥是应用生成的 8 位可读随机串
  （去掉了 `0/O/1/I/l` 这类容易看错的字符，因为要念着敲到另一台设备上），可在同步页重新生成。
- **保密**：**没有**。局域网内明文 HTTP，同网段抓包能看到笔记内容。密钥解决的是「谁能同步」，
  不是「中途看不看得见」——需要保密要等后续上 TLS 或自建预共享密钥加密。
- **暴露面**：主机绑定 `0.0.0.0`，但**没设密钥会拒绝启动** —— 一个谁都能读走全部笔记的服务
  比「忘记开同步」更危险。开启状态会记进偏好，下次进应用时恢复监听（只有用户明确选过才恢复）。
- **生命周期**：主机服务挂在应用作用域上，切页面不掉线；但**没做前台 Service**，进程被系统回收就停。
  同步页里直接写明了这一点，不假装能后台常驻。

## 七、下一步建议

1. **同步加固**：主机改用前台 Service（带常驻通知）以便长期待命；上 TLS 或预共享密钥加密解决局域网窃听；
   跨网段 / 异地场景可考虑把「主机」做成可自选的静态地址而非手动输入
2. **设备发现**：接 Android NSD / mDNS 自动发现主机，省掉手填 IP（本版刻意先做「地址 + 密钥」这条更可靠的路）
3. **冲突体验**：现在是自动裁决，可以在同步日志里列出被裁决的条目，并提供「查看对端版本」入口
4. **检索**：数据量上来后可评估自带 SQLite（`requery/sqlite-android`）以启用 FTS5 与自定义分词器
5. **导出**：导出范围选择（按分组 / 标签 / 时间），以及导入（目前只导出）
