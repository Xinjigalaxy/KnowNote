# KnowNote · 碎片笔记

[English](README.en.md) · **中文**

安卓记事本应用：Kotlin + Jetpack Compose + **Material 3**，Room(SQLite) + 全文检索（FTS5/FTS4/LIKE 三级降级），
MVVM + Repository，局域网同步（一主多从 + **定时自动同步**），另附一个**可以跑在 Termux 上的同步中心**（`server/`，纯 Python 标准库）。

应用名 **KnowNote**（中文「碎片笔记」/ 繁體「碎片筆記」），界面支持
**跟随系统 / 简体中文 / 繁體中文 / English / 日本語**，应用图标为「圆角卡片 + 三条笔记线」剪影，带单色层（Android 13+ 主题图标取色）。

- 包名：`com.xinjigalaxy.knownotes`（debug 变体带 `.debug` 后缀）
- minSdk 26 / targetSdk 36 / compileSdk 36
- 主题种子色 `#39C5BB`，可选 Android 12+ 动态取色
- 界面截图见 `docs/screenshots/`（模拟器 + 演示数据；同步页的密钥 / 设备标识 / 局域网地址已打码）

---

## 一、已实现（第一期：核心可用）

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
| 定时自动同步 | ✅ 两条互不依赖的路：**设备侧** WorkManager 周期任务（15 / 30 / 60 / 180 分钟可选；15 分钟是 Android 的下限，省电模式下还可能被推迟），应用不在前台也把变更推给主机；**服务器侧** `server/knownote_hub.py` 按 `interval_seconds` 主动去连各台设备（分钟级可控、不受手机省电策略影响）。同步页可开关、选间隔、看上次结果 |
| 同步中心（Termux） | ✅ `server/knownote_hub.py`：纯 Python 3 标准库、零依赖，说的就是 App 那套线协议（协议版本 2），所以 App 里填「地址 + 密钥」就能把它当主机用；它自己也会定时轮询设备。SQLite 存笔记（含墓碑）+ `images/` 存图片 + `change_log` 增量 + 同一套冲突裁决；共享密钥认证（常量时间比较）+ 同 IP 连错限流；没设密钥拒绝启动 |
| 同步日志 | ✅ `sync_log` 表记录每次同步（角色 / 对端 / 拉取 / 推送 / 冲突 / 失败原因），同步页展示、可清空 |
| 跨设备标识 | ✅ `notes.guid`（唯一索引，v2→v3 迁移用 SQLite 的 `randomblob(16)` 回填老数据）；分组 / 标签跨设备按**名字**对齐，不需要 id 映射表 |
| 删除的同步 | ✅ 「彻底删除」改为墓碑（`is_purged`）而不是删行 —— 删行的话对端下次同步会把这条笔记推回来 |
| Markdown 渲染 | ✅ 自研轻量渲染器：标题 / 粗体 / 斜体 / 删除线 / 行内代码 / 代码块 / 列表 / 引用 / 可点链接；编辑页「编辑 ↔ 预览」切换 |
| 搜索结果高亮 | ✅ 命中词在标题与正文里高亮加粗 |
| 搜索历史与筛选记忆 | ✅ 搜索历史（同词合并计数、最多 12 条、chip 展示、可单删/清空）；标签与分组筛选写入 SharedPreferences |
| 回收站 | ✅ 软删除笔记的恢复 / 彻底删除 / 清空（文档 5.1 没列，但软删除没有入口等于变相丢数据） |
| 设置页 | ✅ 主题模式（跟随系统 / 浅色 / 深色，改完立即整树换肤，不必重建 Activity）、主题色（默认初音绿 / **Android 12+ 动态取色**，低版本置灰并说明原因）、回收站定时清理（WorkManager 每天一次 + 保留天数 7/30/90 + 「立即清理一次」+ 上次清理结果） |
| 多语言 | ✅ 四语言界面（跟随系统 / 简中 / 繁中 / English / 日本語）：文案全部走 `res/values*`；ViewModel 侧用 `UiMessage`（资源 id + 参数）承载消息，界面再渲染。API 33+ 走系统 per-app language（`LocaleManager` + `locale_config`，与系统设置同步），低版本 `attachBaseContext` 包 Context 后重建 |
| 应用图标 | ✅ 自适应图标 + **单色层**：monochrome 必须是单色剪影，指向彩色前景的话主题图标模式下会渲染成一块实心方块 |
| 概览实时统计 | ✅ 「更多」页的在线笔记 / 标签分组 / 回收站 / 变更日志全部是 Room 的 Flow，任何写入自动重算（v1.4.0 修：以前是一次性查询，从回收站子页面回来数字不更新） |
| 列表展示形态 | ✅ 列表 ↔ 瀑布流（两列 LazyVerticalStaggeredGrid）循环切换并记忆；**笔记页 / 分组页 / 标签页 / 二级页面共用同一份偏好** |
| 二级页面 | ✅ 分组条目、标签条目点进去是独立页面（`group/{groupId}`、`tag/{tagId}` 路由）：左上返回、标题为分组名或 `#标签名`、常驻本页筛选框、状态行、与笔记页同款的卡片与列表 ↔ 瀑布流切换、点笔记进查看 / 长按进编辑 |
| 检索范围 | ✅ 检索时可单独勾选 **标题 / 正文 / 标签**，默认全选（一个都不选会自动回到全选）；范围收窄时候选集上限自动放宽，避免「过滤后只剩个位数」 |
| 检索大小写 | ✅ 索引侧与查询侧统一 `lowercase(Locale.ROOT)`：`Android` / `android` / `GRADLE` 命中同一条；搜索历史也按忽略大小写合并同词 |
| 阅读显示 | ✅ **字号**五档（小 / 标准 / 大 / 特大 / 超大，整棵 Typography 等比缩放，标题与正文的相对层级不变）；**文字颜色**七种（跟随主题 / 深墨 / 纯黑 / 暖褐 / 墨绿 / 深蓝 / 酒红，各带明暗两套取值） |
| 阅读页字号 | ✅ 阅读页底部滑块单独调字号（0.8×–1.8×，实时显示换算后的 sp 值）；存的是**倍率**不是绝对值，所以设置页的全局字号改了，这里仍按同一比例走，两处设置不打架 |
| 阅读模式 | ✅ MD 模式（渲染）/ 文本模式（原样显示，连 `<color>` / `<size>` 标记本身也看得见，方便手改原文） |
| 行内格式 | ✅ 编辑器**选中文字**后可加：**粗体** / *斜体* / 三档字号 / 六色（红黄绿青蓝紫）。标记存进纯文本正文：`<color=red>…</color>`、`<size=1.25>…</size>`；字号是**相对倍率**（em），因此能与阅读页滑块叠加；每种颜色都有明暗两套取值 |
| 正文插入图片 | ✅ 编辑器工具栏「插入图片」调系统相册选择器（**免读相册权限**）；图片拷进应用私有目录（等比缩到 1600px 内、重编码 JPEG，渲染时按需降采样 + 内存缓存），正文只存文件名 `![说明](img:文件名)`；渲染按「文字段 / 图片块」分块，图片**独占整行、按宽度铺满** —— 行内穿插的也一样单独成行；卡片摘要显示 `[说明]` 占位，全文索引保留说明文字 |
| 数据迁移 | ✅ `user_version` 1→2（新增 `search_history`）、2→3（`guid` + `is_purged` + `sync_log`）真实迁移 + `MigrationTest` 对着 schema JSON 逐版本校验，含 1→3 跨级路径 |

界面页：笔记列表（常驻搜索框 + 标签 chips + 状态行）、笔记编辑（编辑/预览）、回收站、分组管理、标签管理、二级页面（分组内 / 标签内笔记）、更多（数据库概览）、导出、局域网同步。
底部导航 4 个条目：笔记 / 分组 / 标签 / 更多。

交互约定：列表里**点击 = 查看**（Markdown 预览），**长按 = 编辑**；新建走右下角「记一条」。

## 界面

<a href="docs/screenshots/01-notes-list.png"><img src="docs/screenshots/01-notes-list.png" width="200" alt="笔记列表"></a>
<a href="docs/screenshots/02-note-preview.png"><img src="docs/screenshots/02-note-preview.png" width="200" alt="Markdown 预览"></a>
<a href="docs/screenshots/03-search.png"><img src="docs/screenshots/03-search.png" width="200" alt="全文检索"></a>
<a href="docs/screenshots/07-settings.png"><img src="docs/screenshots/07-settings.png" width="200" alt="设置"></a>

<a href="docs/screenshots/04-groups.png"><img src="docs/screenshots/04-groups.png" width="200" alt="分组"></a>
<a href="docs/screenshots/05-group-detail.png"><img src="docs/screenshots/05-group-detail.png" width="200" alt="分组二级页面"></a>
<a href="docs/screenshots/08-settings-dark.png"><img src="docs/screenshots/08-settings-dark.png" width="200" alt="深色主题"></a>
<a href="docs/screenshots/10-english-settings.png"><img src="docs/screenshots/10-english-settings.png" width="200" alt="英文界面"></a>

截图取自模拟器 + 演示数据；同步页的共享密钥、设备标识与局域网地址已做打码（见 `docs/screenshots/09-sync.png`）。

## 二、构建与运行

```bash
# 环境：JDK 17 + Android SDK（local.properties 指向本机 SDK 路径，不入库）
export JAVA_HOME="D:\\app\\java17"
./gradlew :app:assembleDebug          # 产物 app/build/outputs/apk/debug/app-debug.apk
./gradlew :app:installDebug           # 装到已连接设备
./gradlew :app:testDebugUnitTest      # 纯 JVM 单测（55 例）
./gradlew :app:connectedDebugAndroidTest  # 仪器化测试（47 例，需要设备/模拟器）

# 同步中心（Termux / PC 上的服务端，只用 Python 3 标准库，不需要任何依赖）
python -m unittest discover -s server/tests -t .   # 中心测试 29 例（真 socket）
python server/knownote_hub.py --init-config server/hub.conf.json   # 生成配置（含随机密钥）
python server/knownote_hub.py --config server/hub.conf.json        # 跑起来（同时定时轮询设备）
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
data/sync/SyncCoordinator.kt 一次「从机同步会话」：取增量 → 发送 → 落库 → 水位线 → 日志（会话串行闸）
data/sync/AutoSync.kt       定时自动同步：WorkManager 周期任务 + AutoSyncScheduler（排 / 撤）
server/knownote_hub.py      同步中心：协议 v2 主机端 + 定时轮询设备 + SQLite / 图片 / 变更日志（Termux 可跑）
server/start-hub.sh         Termux 启动脚本（wake-lock / 后台 / check / status）
server/tests/test_hub.py    同步中心的测试（真实 socket：协议 / 冲突收敛 / 图片 / 调度，28 例）
ui/…                        Compose 页面 + ViewModel（AppViewModelProvider 手工装配）
ui/components/UiMessage.kt  ViewModel 侧可本地化消息（资源 id + 参数），界面负责渲染
data/settings/AppSettings.kt 应用设置（主题 / 动态取色 / 回收站清理 / 语言），StateFlow 承载
data/settings/AppLocales.kt  语言落地：33+ 交系统 LocaleManager，低版本包 Configuration
data/settings/AppLanguage.kt 语言枚举（跟随系统 / 简中 / 繁中 / 英 / 日）
res/values{,-zh,-zh-rTW,-ja}/strings.xml  四语言文案
tools/                      辅助脚本：i18n 抽取与修复、截图打码、演示数据灌库
```

## 四、两个必须知道的 Android 平台坑（都已在真机上实测确认）

### 1. Android 系统 SQLite 没有 FTS5

最初选型是 FTS5，但 **Android 自带的 SQLite 不保证编译 FTS5**。
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
| 单元测试 | 55/55 通过（`FtsTextTest` 8 / `MarkdownMarkupTest` 10 / `MarkupEditTest` 22 / `NoteBlocksTest` 7 / `SearchScopeTest` 8） |
| 仪器化测试（Android 13 / SQLite 3.32.2，与真机同版本） | 47/47 通过（`tests="47" failures="0" errors="0"`；v1.9.0 时是 40 例） |
| 仪器化测试（Android 15 / SQLite 3.44.3） | 47/47 通过 |
| 设置与统计测试 `SettingsAndStatsTest` | 4/4：概览统计**真的会随操作推送新值**（订阅 flow 记录每次发射，而不是每次重查一遍）、定时清理只清够老的那条且留墓碑、保留期清理整空、设置读写往返 |
| 同步引擎测试 `SyncEngineTest` | 8/8：远端新建（分组按名字建 / 标签关联）、时间戳优先、**打平收敛**（两台设备互相同步后内容相同）、软删与墓碑传播、本地彻底删除留墓碑并可同步出去、增量取数、主机中转记日志而从机不记、本机无该条时忽略墓碑 |
| 同步真回环测试 `SyncLoopbackTest` | 3/3：**真 ServerSocket + 真 HttpURLConnection、两个独立库**跑双向同步（拉 4 推 1 再同步幂等）、错密钥 401 且错误原因透出、无密钥拒绝启动 |
| 迁移测试 `MigrationTest` | 3/3：1→2（`search_history`）、2→3（`guid` 回填 32 位十六进制且互不相同、`is_purged` 默认 0、`sync_log` 可写、**拿重复 guid 插入必须被唯一索引拒绝**）、1→3 跨级路径；三步都对 schema JSON 校验 |
| 编辑页测试 `NoteEditViewModelTest` | 7/7（含「输入框待确认标签必须入库」「只看不改返回不刷新 updated_at」两个回归） |
| APK 元信息 | minSdk 26 / targetSdk 36 / 标签「KnowNote / 碎片笔记」 |
| Room schema 导出 | `app/schemas/…/1.json`、`2.json`、`3.json` |
| 真机检索（Android 13 真机） | 中文子串「检索」命中 2 条、前缀 `gradle*` 命中 1 条、多词元 AND 命中正确 |
| 索引自愈 | 灌库时索引 0 条 → 启动后 8 条，与在线笔记数一致 |
| **升级路径自愈（Android 13 / SQLite 3.32.2）** | v1.0.0 造出降级状态 → 覆盖装 v1.0.1 → 引擎恢复 FTS4，`FTS 索引条数对不上（0 → 8），已整体重建`，MATCH 查询全部命中 |
| **真机迁移（Android 13 真机，原有 3 条笔记）** | 覆盖装 v1.1.0 → `user_version` 1 升 2、`search_history` 建出、3 条笔记与索引全部保留 |
| **真机二级页面（Android 13 真机 / v1.2.1）** | 分组条目、标签条目点进去都是独立页面：左上「返回」按钮、标题为分组名、本页筛选框、状态行「共 3 条笔记」、与笔记页同款卡片；两页的列表 ↔ 瀑布流切换都生效；页内点笔记直接进「查看」预览；返回链路（笔记 → 二级页面 → 上级列表）逐级正确 |
| **真机回归：只看不改不刷新时间（Android 13 真机 / v1.2.1）** | 点开笔记 → 直接返回，列表里三条笔记的时间文字与点开前 **完全一致**（修复前会被无条件保存刷成「刚刚」） |
| **真机迁移 v2→v3（Android 13 真机，原有 3 条笔记 / v1.3.0）** | 覆盖装 v1.3.0 → `user_version` 2 升 3；`guid` 回填 **3/3 且唯一**（32 位十六进制，SQLite `randomblob(16)`）、`is_purged` 默认 0、`sync_log` 建出；3 条笔记与 FTS 索引全部完好 |
| **真机同步·主机侧（Android 13 真机 / v1.3.0）** | 开启主机后从 PC 直连真机接口：`GET /ping` → 200 `{"protocol":1,"device_name":"<真机型号>"}`；错密钥 `POST /sync` → **401 共享密钥不匹配**；正确密钥推一条笔记 → 200（主机落库 1 条，并把 3 条真实笔记回给从机）。落库后：笔记数 3→4、分组「PC 测试分组」与标签「同步测试」按名字自动建出、FTS 索引 4 条、`sync_log` 记 `host | PC 侧测试 | 拉=3 推=1` |
| **真机同步·App ↔ App（真机当主机 / Android 13 模拟器当从机）** | 模拟器同步 → 拉到 4 条全部落库（含 PC 推的那条），分组 / 标签名字对齐、水位线写入 `sync_meta`；从机新建笔记后再次同步 → 「推过去 1 条，主机落库 1 条」，主机笔记数 3→5；第三次同步两边均无增量（水位线幂等） |
| **真机同步·日志（两侧）** | 主机侧 `host` 5 条、从机侧 `client` 4 条，字段（对端 / 拉取 / 推送 / 结果）逐条对得上实际行为 |
| **真机回归：概览实时更新（Android 13 真机 / v1.4.0）** | 长按笔记删除 → 「更多」页**立刻**显示 回收站 1 / 在线笔记 5→4 / 变更日志 +1，副标题变「1 条已删除笔记」；进回收站彻底删除 → 返回「更多」**计数自动变回 0**（修复前这里一直是旧数字）；库层面确认墓碑仍在（`is_purged=1`，为了同步），只是不再计入任何统计 |
| **真机主题与设置（Android 13 真机 / v1.4.0）** | 设置页「深色」选中后整个应用立即换肤（设置页与笔记页均为深色，未重建 Activity）；打开动态取色后配色明显变为壁纸取色（本机壁纸给出暖褐/红调）；「关于」显示 `1.4.0-demo`（版本号已改为读包信息）；回收站为空时「立即清理一次」正确置灰 |

### v1.5.0 真机验证（多语言与图标）

| 检查项 | 结果 |
| --- | --- |
| 语言切换（Android 13 真机，API 33） | 设置页五选一：点 **English** 后整树变英文，系统侧同步为 `Locales for …debug are [en]`；点「跟随系统」回到 `[]`（走 `LocaleManager`） |
| 应用名三语言 | 从 APK 读：`application-label:'KnowNote'`、`application-label-zh:'碎片笔记'`、`application-label-zh-TW:'碎片筆記'`，其余语言回落品牌名 |
| 设置共存 | 切换语言后主题模式 / 动态取色 / 回收站清理设置全部保留（设置在 `AppSettings`，语言只影响资源解析） |
| **真机发现并修复的插值 bug** | 切日语后设置页显示 `$days 日`、`${state.trashCount} 件` —— 抽取脚本只把**中文原文**的插值转成了 `%1$s`，四语言**译文**里的 `${...}` 却原样写进了资源，而 Android 资源不做模板展开。修法（`tools/i18n-fix-args.py`）：按**表达式文本**对齐编号而非出现顺序，日语把参数提到句首仍然正确（`同期完了：%1$s 件取得、この端末で %2$s 件反映`）。修后真机复验：日语 `0 件 / 7 日 / 前回のクリーンアップ 33 分前、0 件を削除`，英文 `0 items / 7 days / Last cleanup 33 minutes ago, removed 0` |
| 图标单色层 | 修前 monochrome 指向彩色前景，主题图标模式下是一块实心方块；改为单色剪影后跟随系统取色 |

### v1.10.0 验证（定时自动同步 + Termux 同步中心）

设备侧（App）与服务器侧（`server/knownote_hub.py`）分别验证，最后合到一条链上跑通。

| 检查项 | 结果 |
| --- | --- |
| 纯 JVM 单测 | 55/55 通过（本版改动集中在 Android 侧，未新增 JVM 单测） |
| 仪器化测试（Android 13 / SQLite 3.32.2 模拟器） | **47/47** 通过（v1.9.0 是 40 例，本版新增 `AutoSyncTest` 7 例） |
| 仪器化测试（Android 15 / SQLite 3.44.3 模拟器） | **47/47** 通过 |
| `AutoSyncTest` | 7/7：关闭开关时什么都不做；**自动同步真的把中心上的笔记拉到本机**（真 ServerSocket + 真 HttpURLConnection）并写下「上次自动同步」；本机改动推上中心；地址没填 / 密钥没设时返回 success 而不是 failure（见 6.7 的坑）；中心没开机时返回 retry 并记下原因；密钥不对时把 401 的原因透出来；`AutoSyncScheduler` 真的排上 / 撤掉唯一周期任务 |
| 同步中心测试 `server/tests/test_hub.py` | **29/29** 通过：协议层（真 HTTP 服务 + 真客户端：401 / 404 / 429 节流 / 坏 JSON / 协议版本）、引擎层（增量、冲突收敛、墓碑、图片只传一次、多轮）、调度层（`poll_device` 真连设备：双向、幂等、失败原因、水位线取 min）、设备状态落库 |
| 跨实现①：**中心主动拉 App**（PC 上跑中心 / Android 13 模拟器跑 App） | 模拟器开启主机模式后，中心 `--check` 探测到 `✓ 通（对端自称 Android 模拟器，协议 2）`；`--once` 一轮把 App 侧的 **8 条笔记拉回中心**、把中心的 **2 条推给 App**（`拉 8 / 推 2 落库，冲突 0`）。回读 App 库：8 → **10 条**，其中「中心上的笔记 A/B」两条分组 / 标签都按名字对齐 |
| 跨实现②：**App 主动同步中心**（点开「定时自动同步」开关） | 在 PC 侧中心新建第 3 条笔记后，点开开关 → 中心日志 `[host] … 接入：给它 11 条 / 它推来 0 条落库`，App 库 10 → **11 条**且新笔记在位；界面上「上次自动同步 刚刚 · 成功」。**幂等性顺带验到**：App 把 10 条推过去、中心落库 0 条（内容完全相同） |
| 同步中心测试**在真 Termux 上跑**（Android 11 手机 / Termux 自带 Python 3.14.6 / arm64） | **29/29 通过** —— 零依赖、零编译，`pkg install python` 之后直接 `python3 -m unittest discover -s tests -t .`，不用 root、不用装任何第三方包 |
| 跨实现③：**Termux 上的中心 ⇄ PC 上的中心** | 两台中心互认协议 2；Termux 侧一轮从 PC 拉回 **12 条**，紧接着第二轮 `拉 0 推 0`（幂等）；`--status` 显示「已同步过（2026-10-05 14:49，拉 0 / 推 0）」 |
| 跨实现④：**Termux 上的中心 ⇄ 模拟器里的真 App** | 手机 Termux 里的中心 `--check` 认到 App（`Android 模拟器，协议 2`）；第一轮从 App 拉 10 条，在 Termux 里新建一条后第二轮**把它推给 App**（App 12 → 13 条，App 侧同步日志出现「接客（主机）Termux Hub (Android 11 手机) 拉取 0 条 · 推送 1 条」）。整条链路 = 真手机的 Termux Python ⇄ 真 Android App，靠的就是同一套协议 |
| 设备状态落库（`--status` 是另一个进程） | `--status` 输出 `曾接入 Android 模拟器（127.0.0.1）：已同步过（2026-10-05 14:41，拉 11 / 推 0）` —— 修复前这里永远是「还没成功同步过」，因为状态只在内存里 |
| 界面 | 「更多 → 局域网同步」新增**定时自动同步**卡片：开关 + 「已开启 · 每 30 分钟」+ 「上次自动同步 刚刚 · 成功」+ 间隔四档（15 / 30 / 60 / 180 分钟）+ 一行说明；失败时才显示诊断详情（成功时不留那串英文统计，免得占地方）；四语言文案齐全 |
| APK 元信息 | `versionCode 14` / `versionName 1.10.0`（`dumpsys package` 读回确认）；minSdk 26 / targetSdk 36 |

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
| v1.4.0 | 修「更多」页概览不刷新（一次性查询 → Room Flow 实时统计，回收站 / 变更日志等数字随写入自动更新）；新增**设置页**：主题模式（跟随系统 / 浅色 / 深色）、主题色（默认初音绿 / Android 12+ 动态取色）、回收站定时清理（WorkManager 每天一次 + 保留天数 + 立即清理）；`AppSettings` 用 StateFlow 承载设置，改主题立即换肤；顺带修「彻底删除」确认文案（v1.3.0 起不再物理删行，旧文案说"从数据库中永久移除"已不准确） |

| v1.5.0 | **多语言 + 品牌化**：四语言界面（254 条去重文案抽成资源，API 33+ 用系统 per-app language）；改名 KnowNote / 碎片笔记 / 碎片筆記；图标补单色层（主题图标动态取色）；FTS 判定依据与同步日志等**诊断信息统一英文**；修一个只有上真机才会暴露的 bug —— 译文里的 Kotlin 模板被原样写进资源，日语界面显示出 `$days 日` 这类字面量（见第五节） |
| v1.6.0 | **可调阅读显示 + 检索更可控**：字号五档（整棵 Typography 等比缩放，层级关系不变）、文字颜色七种；检索范围可单独勾选标题 / 正文 / 标签（默认全选，收窄时自动放宽候选集）；检索与搜索历史统一忽略大小写（`Locale.ROOT`）。文字颜色改的是**主题色角色**而不是 `LocalContentColor` —— 页面里 60 多处 `Text` 显式写了颜色，只有改角色才真正生效 |
| v1.7.0 | **阅读页可调 + 行内格式**：阅读页底部滑块单独调字号（倍率制，与全局字号解耦）+ MD / 文本模式切换；编辑器选中文字可加粗体 / 斜体 / 三档字号 / 六色，标记以 `<color>` / `<size>` 存进纯文本。顺带修一个真机才暴露的 bug：渲染器原先不递归解析行内内容，「粗体在外、颜色在内」会把标记当普通文字吐出来。单测 16 → **38** |
| v1.8.0 | **正文插入图片**：相册选择 → 拷进私有目录（缩小重编码）→ 正文存 `![说明](img:文件名)`；渲染分块进行，图片独占整行（行内插入的同样单独成行）；摘要显示 `[说明]`。图片随**局域网同步**一起传输（同一张只传一次，超量自动分批多轮传完）；导出仍只带引用、不带图片文件 |
| v1.9.0 | **图片参与局域网同步**：协议升到 2；一次同步里图片与笔记一起走（base64 承载，单批最多 6 张 / 4MB），超量自动多轮传完；每台设备记住「对端已有哪些图」，同一张只传一次；同步日志里带上图片收发张数 |
| v1.10.0 | **定时自动同步 + Termux 同步中心**：同步页新增「定时自动同步」卡片（开关 / 间隔四档 / 上次结果），设备侧用 WorkManager 周期任务在后台把变更推给主机；新增 `server/knownote_hub.py` —— 纯 Python 3 标准库的同步中心，说的就是 App 那套协议（协议 2），既能当主机被 App 同步，也能按 `interval_seconds` **主动去连各台设备**（定时调度放在服务器侧，手机不用常驻后台），可长期跑在 Termux 上；一份 `start-hub.sh` 管启动 / 后台 / wake-lock / 状态 / 日志；同步会话加串行闸（手动与定时不会互相打水位线）；设备状态落库，`--status` 换进程也看得见 |

## 六、局域网同步（v1.3.0 起，v1.10.0 加定时）

### 6.1 模式：一主多从，手动或定时

一台设备开「主机模式」（内嵌 HTTP 服务，默认端口 8765），其他设备填「主机地址 + 共享密钥」点「开始同步」。
同一台设备两个角色都能当（同步页里两块都在），个人场景下经常是手机和平板互相轮换。

v1.10.0 起**触发方式**有两种，可以只开一种也可以都开（详见 6.7）：

- **手动**：点「开始同步」。
- **定时**：设备侧 WorkManager 周期任务；或者把「主机」换成 Termux 上的同步中心（`server/`），
  由**服务器侧**按固定间隔主动来拉 —— 手机不用常驻后台，也不用管 Android 的省电策略。

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

### 6.4 冲突：时间戳优先，打平按内容确定性收敛（设计决策）

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

### 6.7 定时自动同步：两条路，各有各的边界

「到点自动同步」这件事有两个不同的实现位置，本版**两个都做了**，因为它们各自有一半解决不了的问题：

| | 设备侧（App 里的 `data/sync/AutoSync.kt`） | 服务器侧（`server/knownote_hub.py`） |
| --- | --- | --- |
| 谁发起 | 手机（WorkManager 到点拉起进程） | 中心（按 `interval_seconds` 主动连手机） |
| 最短节拍 | **15 分钟**（Android 的硬下限），省电 / 息屏时还会再推迟 | 自定义（默认 60 秒，想多密都行） |
| 需要什么 | 只要「主机地址 + 密钥」填对，不需要对端在跑 | 手机上得开着**主机模式**、App 进程还活着（6.6 那条限制一样适用） |
| 开关在哪 | App 同步页「定时自动同步」卡片 | 配置文件 `interval_seconds` |

两个一起开最稳：手机醒来就推一次，中心到点就拉一次，谁先到算谁 —— 反正同步是幂等的
（验收时实测：App 把 10 条推给中心、中心落库 0 条，因为内容完全一样）。

**实现上踩到的两个坑（都有回归测试钉住）**：

1. **配置不全时不能返回 `failure`**。WorkManager 的规则是 Worker 返回 `failure` 就**把周期任务取消掉**，
   于是用户第二天填好地址、任务却永远不会再跑，而且界面上什么异常都看不到。
   所以「地址没填 / 密钥没设」按 `success` 处理（下一个周期再试），只有真的连不上才 `retry`（指数退避）。
2. **手动同步和定时同步会撞水位线**。两者读的是同一份 `sync_meta.last_sync_at`，并行跑会各自读到同一个旧水位线、
   把同一批变更推两遍（幂等所以数据不会坏，但日志重复、水位线互相覆盖）。
   所以 `SyncCoordinator` 上加了一把互斥锁，会话排成队。

另外，打开开关时会**立刻按同一条路跑一次**（调的就是 Worker 调的那个函数），
这样地址填错能当场看到，而不用等 15 分钟才发现。

### 6.8 Termux 同步中心（`server/`）

`server/knownote_hub.py` 是一个能长期跑着的「同步中心」：它同时干两件事 ——

1. **当主机**：`GET /ping` + `POST /sync`，与 App 的线格式**完全一致**（协议版本 2），
   所以手机上填「中心地址 + 密钥」就能把它当普通主机用，一行 App 代码都不用改；
2. **当轮询者**：按 `interval_seconds` 依次去连配置里的每台设备（设备那边开着主机模式），
   把「谁也记不清该同步了」这件事交给中心 —— 这就是「定时调度放在服务器侧」。

只用 **Python 3 标准库**（`http.server` / `sqlite3` / `threading` / `base64`），零依赖、零编译。
Termux 上 `pkg install python` 就能跑，不需要 root，也不需要常驻前台服务：

```bash
cd server
python knownote_hub.py --init-config hub.conf.json   # 生成配置（密钥是随机生成的，可以直接用）
vim hub.conf.json                                    # 填 devices：每台手机的局域网 IP + 端口 + 密钥
bash start-hub.sh bg                                 # 后台跑（nohup + 写 hub.pid / hub.log）
bash start-hub.sh status                             # 中心存了多少条、每台设备上次同步结果
bash start-hub.sh check                              # 只探测各设备通不通（不动数据）
bash start-hub.sh log                                # 跟着看日志
bash start-hub.sh stop                               # 停
```

其他常用参数：`--once`（只跑一轮轮询就退出，方便放 cron / Termux:Boot）、`--status`、`--check`、
`--port`、`--key`、`--print-key`。`hub.conf.json`、`knownote-hub.db`、`images/` 都在 `.gitignore` 里 ——
里面有密钥和真实笔记，不进公开仓库（样例配置是 `hub.conf.example.json`）。

几条边界，说清楚免得误会：

- **中心不存「对端状态」**：谁缺哪些图片完全由对端 `advertise` 的集合算出来，
  所以中心挂了重启、换机器、数据库被清空，都不会让设备之间「记错对方有什么」；
- **安全**：和 App 主机同一套 —— 共享密钥（常量时间比较）+ 同一 IP 连续错密钥限流；
  **没设密钥拒绝启动**（退出码 2），不给自己留一个谁都能读走全部笔记的口子；
  传输仍是**明文 HTTP**（和 6.6 一样，局域网内可控、跨网段不要用）；
- **它不是「云端」**：数据只落在运行它的那台机器上（默认就是 Termux 的 app 私有目录），
  没有账号、没有上游服务；想异地用请自己套一层隧道 / VPN；
- **单进程够用**：`http.server` 的 `ThreadingHTTPServer` + 一把 SQLite 写锁，
  个人量级（几台设备、几千条笔记）绰绰有余，但如果真要给几十台设备当中转，得换正经 WSGI + 连接池。

## 七、下一步建议

1. **同步加固**：主机改用前台 Service（带常驻通知）以便长期待命 —— v1.10.0 有了服务器侧定时拉取之后，
   这条的紧迫性下降了一些（手机不必常驻，中心到点来拉即可），但「让手机一直当主机」还是得做；
   上 TLS 或预共享密钥加密解决局域网窃听（现在 App 主机与同步中心都还是明文 HTTP）；
   跨网段 / 异地场景可考虑把「主机」做成可自选的静态地址而非手动输入
2. **设备发现**：接 Android NSD / mDNS 自动发现主机，省掉手填 IP（本版刻意先做「地址 + 密钥」这条更可靠的路）
3. **冲突体验**：现在是自动裁决，可以在同步日志里列出被裁决的条目，并提供「查看对端版本」入口
4. **检索**：数据量上来后可评估自带 SQLite（`requery/sqlite-android`）以启用 FTS5 与自定义分词器
5. **导出**：导出范围选择（按分组 / 标签 / 时间），以及导入（目前只导出）
6. **墓碑增长**：`is_purged=1` 的行会一直留着（这是删除能同步的前提）。个人数据量下无感，但严格来说需要一个「墓碑保留期」——
   比如超过一年且各设备水位线都早已越过它的墓碑可以真删。做之前要先确认所有从机都已同步过该时间点

## 八、许可

采用 [MIT 协议](LICENSE)：可自由使用、修改、分发，保留版权声明即可。
