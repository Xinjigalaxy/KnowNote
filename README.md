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
| 标签管理 | 增 / 改名 / 删除 / **合并到**；列表显示引用条数 |
| 分组管理 | 增 / 改名 / 删除（可选择「保留笔记」或「一起删除」）/ 上移下移排序 |
| 全文检索 | FTS5 → FTS4 → LIKE 三级降级；**中文子串命中**；前缀匹配；标签进索引；结果高亮 |
| 组合筛选 | 关键词 + 多标签 + 分组（全部 / 未分组 / 指定） |
| 导出 | `.json` / `.csv`（带 BOM）/ `.db`（VACUUM INTO 一致性快照），SAF 保存免存储权限 |
| 升级兼容 | `user_version` + `Migration` 机制已搭好（`AppDatabase.MIGRATIONS`，v1 暂无迁移） |
| 同步地基 | `sync_meta`（device_id / last_sync_at）+ `change_log`（每次增删改写一条）已建表并落数据；同步页是路标页，**未实现同步** |
| Markdown 渲染 | ✅ 自研轻量渲染器：标题 / 粗体 / 斜体 / 删除线 / 行内代码 / 代码块 / 列表 / 引用 / 可点链接；编辑页「编辑 ↔ 预览」切换 |
| 搜索结果高亮 | ✅ 命中词在标题与正文里高亮加粗 |
| 搜索历史与筛选记忆 | ✅ 搜索历史（同词合并计数、最多 12 条、chip 展示、可单删/清空）；标签与分组筛选写入 SharedPreferences |
| 回收站 | ✅ 软删除笔记的恢复 / 彻底删除 / 清空（文档 5.1 没列，但软删除没有入口等于变相丢数据） |
| 列表展示形态 | ✅ 列表 ↔ 瀑布流（两列 LazyVerticalStaggeredGrid）循环切换并记忆 |
| 数据迁移 | ✅ `user_version` 1→2 真实迁移（新增 `search_history` 表）+ `MigrationTest` 对着 schema JSON 校验 |

界面页：笔记列表（常驻搜索框 + 标签 chips + 状态行）、笔记编辑（编辑/预览）、回收站、分组管理、标签管理、更多（数据库概览）、导出、同步路标页。
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
data/model/Entities.kt      notes / tags / groups / note_tags / sync_meta / change_log + NoteWithTags
data/db/AppDatabase.kt      6 张实体表；FtsSchemaCallback 挂 FTS 建表与自愈
data/db/NoteDao.kt          笔记 CRUD、软删除、LIKE 兜底、分组/标签计数
data/db/MetaDaos.kt         标签（含合并）、分组（含排序）、变更日志、同步元数据
data/db/FtsStore.kt         虚拟表 DDL / 引擎探测 / MATCH 查询 / 索引重建
data/fts/FtsText.kt         分词与 MATCH 表达式构造（中文子串检索的关键）
data/repo/NoteRepository.kt 唯一写入口：事务内同时改主表 + 关联 + FTS 索引 + 变更日志
data/export/Exporter.kt     JSON / CSV / SQLite 三种导出
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
| 仪器化测试（Android 13 / SQLite 3.32.2） | 15/15 通过 |
| 仪器化测试（Android 15 / SQLite 3.44.3） | 15/15 通过 |
| 迁移测试 `MigrationTest` | 1→2 迁移后表结构经 schema 校验，笔记/标签关联原样保留 |
| 编辑页测试 `NoteEditViewModelTest` | 4/4（含「输入框待确认标签必须入库」的回归） |
| APK 元信息 | minSdk 26 / targetSdk 36 / 标签「知识点记事本」 |
| Room schema 导出 | `app/schemas/…/1.json`、`2.json` |
| 真机检索（LG G8 / Android 13） | 中文子串「检索」命中 2 条、前缀 `gradle*` 命中 1 条、多词元 AND 命中正确 |
| 索引自愈 | 灌库时索引 0 条 → 启动后 8 条，与在线笔记数一致 |
| **升级路径自愈（Android 13 / SQLite 3.32.2）** | v1.0.0 造出降级状态 → 覆盖装 v1.0.1 → 引擎恢复 FTS4，`FTS 索引条数对不上（0 → 8），已整体重建`，MATCH 查询全部命中 |
| **真机迁移（LG G8，原有 3 条笔记）** | 覆盖装 v1.1.0 → `user_version` 1 升 2、`search_history` 建出、3 条笔记与索引全部保留 |

界面截图见 `demo-shots/`。

## 五之二、版本记录

| 版本 | 说明 |
| --- | --- |
| v1.0.0 | 首个可用版本（第一阶段全部功能） |
| v1.0.1 | 修 FTS 引擎误判导致检索降级为 LIKE；新增「更多」页诊断信息，重复探测幂等 + 回归测试 |
| v1.1.0 | 第二阶段：Markdown 渲染、编辑/预览切换、搜索历史、筛选记忆、回收站；数据库 1→2 真实迁移 + 迁移测试 |
| v1.1.1 | 修「记一条」里标签输完直接保存会丢标签；点击=查看 / 长按=编辑；列表 ↔ 瀑布流切换 |

## 六、下一步建议

1. **第二阶段**：Markdown 渲染、搜索历史与筛选记忆、回收站页面（清空软删除）、导出范围选择
2. **检索**：数据量上来后可评估 FTS5 + 分词器（若自行打包 SQLite 或用 `requery/sqlite-android`）
3. **第三阶段**：局域网同步（一主多从 → mDNS）、时间戳冲突裁决、同步日志页
