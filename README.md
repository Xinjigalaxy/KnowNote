# KnowNote · 碎片笔记

[English](README.en.md) · **中文**

Android 笔记应用。Kotlin + Jetpack Compose + Material 3，Room(SQLite) 全文检索（FTS5 / FTS4 / LIKE 三级降级），
MVVM + Repository；多设备在同一局域网内互相同步，另附可跑在 Termux / PC 的同步中心（`server/`，纯 Python 3 标准库）。

| 项目 | 值 |
| --- | --- |
| 包名 | `com.xinjigalaxy.knownotes`（debug 变体带 `.debug` 后缀） |
| SDK | minSdk 26 / targetSdk 36 / compileSdk 36 |
| 界面语言 | 跟随系统 / 简体中文 / 繁體中文 / English / 日本語 |
| 主题 | 种子色 `#39C5BB`，支持 Android 12+ 动态取色 |
| 同步协议 | **3**（App 与中心须同版本升级） |

## 项目简介

本地优先的安卓笔记应用：笔记、标签、分组、Markdown 渲染、全文检索、正文插图、回收站全部离线可用。
同步走局域网内的内嵌 HTTP 服务，无需账号、无需云端；可选装一个纯 Python 3 标准库实现的同步中心，
把定时调度放到常驻机器上。

## 核心特点

- **本地优先**：数据存在应用私有目录，无账号、无上游服务
- **检索三级降级**：FTS5 → FTS4 → LIKE，按系统 SQLite 能力自动选择，生效引擎可见
- **中文子串命中**：写入时 CJK 逐字切分、查询时组 phrase，不依赖 jieba
- **双向库存比对同步**：按「两边各有什么」算差集，不依赖水位线；正文只在被点名时传输
- **同步中心**：零依赖零编译，可当主机、也可主动轮询各设备
- **仅局域网**：从机、主机、中心三处均在唯一出入口拦截非局域网来源
- **可调阅读**：字号五档、文字颜色七种、行内格式（粗体 / 斜体 / 字号 / 六色）、正文插图
- **四语言 + Material 3**：动态取色、深浅主题、per-app language

## 界面

<a href="docs/screenshots/01-notes-list.png"><img src="docs/screenshots/01-notes-list.png" width="200" alt="笔记列表"></a>
<a href="docs/screenshots/02-note-preview.png"><img src="docs/screenshots/02-note-preview.png" width="200" alt="Markdown 预览"></a>
<a href="docs/screenshots/03-search.png"><img src="docs/screenshots/03-search.png" width="200" alt="全文检索"></a>
<a href="docs/screenshots/07-settings.png"><img src="docs/screenshots/07-settings.png" width="200" alt="设置"></a>

<a href="docs/screenshots/04-groups.png"><img src="docs/screenshots/04-groups.png" width="200" alt="分组"></a>
<a href="docs/screenshots/05-group-detail.png"><img src="docs/screenshots/05-group-detail.png" width="200" alt="分组二级页面"></a>
<a href="docs/screenshots/08-settings-dark.png"><img src="docs/screenshots/08-settings-dark.png" width="200" alt="深色主题"></a>
<a href="docs/screenshots/10-english-settings.png"><img src="docs/screenshots/10-english-settings.png" width="200" alt="英文界面"></a>

截图取自模拟器与演示数据；同步页的共享密钥、设备标识、局域网地址已打码。

## 主要功能

### 笔记

- 增 / 改 / 软删除 / 恢复 / 彻底删除（墓碑）；列表长按删除后可撤销
- Markdown 渲染：标题 / 粗体 / 斜体 / 删除线 / 行内代码 / 代码块 / 列表 / 引用 / 可点链接；编辑 ↔ 预览切换
- 交互：点击 = 查看，长按 = 编辑，右下角新建
- 正文插图：相册选择（免读相册权限）→ 拷入私有目录（1600px 内等比缩放、JPEG 重编码）→ 正文存 `![说明](img:文件名)`；图片独占整行
- 列表 ↔ 瀑布流切换，笔记页 / 分组页 / 标签页 / 二级页面共用同一偏好

### 检索

- 引擎：FTS5 → FTS4 → LIKE 三级降级；中文子串命中、前缀匹配、标签进索引、结果高亮
- 范围：可单独勾选 标题 / 正文 / 标签，默认全选（全不选时回到全选）
- 组合：关键词 + 多标签 + 分组（全部 / 未分组 / 指定）
- 大小写：索引侧与查询侧统一 `lowercase(Locale.ROOT)`，搜索历史同词合并
- 搜索历史最多 12 条，可单删 / 清空；筛选条件写入 SharedPreferences
- 索引条数与在线笔记数不一致时自动整体重建

### 组织

- 标签：增 / 改名 / 删除 / 合并到；条目点击进二级页面
- 分组：增 / 改名 / 删除（保留笔记或一起删除）/ 上移下移排序；条目点击进二级页面
- 二级页面：`group/{groupId}`、`tag/{tagId}` 路由，常驻本页筛选框、状态行、与笔记页同款卡片
- 回收站：恢复 / 彻底删除 / 清空；定时清理（WorkManager 每天一次，保留 7 / 30 / 90 天）

### 同步

- 一主多从：主机内嵌 HTTP 服务（默认 8765），从机填「地址 + 共享密钥」；一次请求双向
- 定时两条路：设备侧 WorkManager（15 / 30 / 60 / 180 分钟）、服务器侧中心主动轮询
- 同步日志：`sync_log` 记录角色 / 对端 / 拉取 / 推送 / 冲突 / 失败原因，同步页展示并可清空
- 跨设备标识：`notes.guid`；分组与标签按名字对齐，不需要 id 映射表
- 删除传播：彻底删除改为墓碑（`is_purged`），否则对端会把笔记推回来

### 外观与设置

- 主题模式跟随系统 / 浅色 / 深色，切换立即整树换肤；动态取色（Android 12+，低版本置灰并说明）
- 阅读字号五档（整棵 Typography 等比缩放）、文字颜色七种；阅读页底部滑块单独调字号（0.8×–1.8×，倍率制）
- 阅读模式 MD / 文本切换（文本模式显示 `<color>` / `<size>` 标记原文）
- 编辑器选中文字可加粗体 / 斜体 / 三档字号 / 六色，标记存进纯文本正文
- 多语言：API 33+ 走系统 per-app language，低版本包 `Configuration`

### 工程

- 数据库 `user_version` 1→2→3 增量迁移（全部 ALTER / CREATE，不重建表），`MigrationTest` 对着 schema JSON 校验
- 导出 `.json` / `.csv`（带 BOM）/ `.db`（`VACUUM INTO` 一致性快照），SAF 保存免存储权限
- 「更多」页概览统计走 Room Flow，任何写入自动重算
- 应用图标为自适应图标 + 单色层（Android 13+ 主题图标取色）

## 快速开始

| 项目 | 说明 |
| --- | --- |
| 系统要求 | Android 8.0（API 26）或更高 |
| 构建环境 | JDK 17 + Android SDK；`local.properties` 指向本机 SDK 路径（不入库） |
| 安装 | 从 [Releases](../../releases) 下载 APK，或 `./gradlew :app:installDebug` |
| 同步中心 | `server/knownote_hub.py`，只用 Python 3 标准库 |

```bash
export JAVA_HOME="/path/to/jdk17"
./gradlew :app:assembleDebug               # 产物 app/build/outputs/apk/debug/app-debug.apk
./gradlew :app:installDebug                # 装到已连接设备

python server/knownote_hub.py --init-config server/hub.conf.json   # 生成配置（含随机密钥）
python server/knownote_hub.py --config server/hub.conf.json        # 启动（同时定时轮询设备）
```

Gradle wrapper 的 `distributionUrl` 指向华为镜像：`services.gradle.org` 国内直连超时。重新生成时：

```bash
gradle wrapper --gradle-distribution-url https://mirrors.huaweicloud.com/gradle/gradle-8.13-bin.zip
```

**本仓库只放程序源码**：开发期脚本、单元 / 仪器化测试、演示数据灌库脚本都不在库内
（`tools/`、`app/src/test`、`app/src/androidTest`、`app/schemas`、`server/tests`
已从仓库与历史中移除，本机保留）。下文「验证记录」是这些测试跑出来的结果。

## 代码地图

```
data/model/Entities.kt       notes / tags / groups / note_tags / sync_meta / change_log / search_history / sync_log
data/db/AppDatabase.kt       8 张实体表（v3）；FtsSchemaCallback 挂 FTS 建表与自愈；MIGRATION_1_2 / 2_3
data/db/NoteDao.kt           笔记 CRUD、软删除、墓碑、LIKE 兜底、分组 / 标签计数
data/db/MetaDaos.kt          标签（含合并）、分组（含排序）、变更日志、同步元数据、同步日志
data/db/FtsStore.kt          虚拟表 DDL / 引擎探测 / MATCH 查询 / 索引重建
data/fts/FtsText.kt          分词与 MATCH 表达式构造
data/repo/NoteRepository.kt  唯一写入口：事务内同时改主表 + 关联 + FTS 索引 + 变更日志
data/export/Exporter.kt      JSON / CSV / SQLite 三种导出
data/sync/SyncModels.kt      线格式 + JSON 编解码
data/sync/SyncDiff.kt        库存比对的纯函数（协议 3 差集）
data/sync/SyncEngine.kt      本机库存 / 增量，应用远端变更
data/sync/SyncServer.kt      主机侧手写 HTTP 服务（ServerSocket）+ 局域网地址枚举
data/sync/SyncClient.kt      从机侧 HttpURLConnection 客户端 + 地址解析
data/sync/SyncCoordinator.kt 一次同步会话：比差集 → 传正文 → 落库 → 日志（串行闸）
data/sync/AutoSync.kt        定时自动同步：WorkManager 周期任务 + 排 / 撤
data/sync/LanGuard.kt        局域网边界：地址分类 + 网络类型判定（可注入给测试）
server/knownote_hub.py       同步中心：协议 3 主机端 + 定时轮询设备 + SQLite / 图片
server/start-hub.sh          Termux 启动脚本（后台 / wake-lock / check / status / log）
ui/…                         Compose 页面 + ViewModel（AppViewModelProvider 手工装配）
ui/components/UiMessage.kt   ViewModel 侧可本地化消息（资源 id + 参数）
data/settings/AppSettings.kt 应用设置（主题 / 动态取色 / 回收站清理 / 语言），StateFlow 承载
data/settings/AppLocales.kt  语言落地：API 33+ 交系统 LocaleManager，低版本包 Configuration
res/values{,-zh,-zh-rTW,-ja}/strings.xml  四语言文案
```

## Android 平台要点

**系统 SQLite 不含 FTS5。** Android 15（SQLite 3.44.3）实测：

```
sqlite=3.44.3 | FTS5=no such module: fts5 (code 1 SQLITE_ERROR) | FTS4=OK
```

因此实现为能力探测 + 三级降级，并把生效引擎显示在列表状态行与「更多」页。

探测不能用 `CREATE VIRTUAL TABLE IF NOT EXISTS ... USING fts5`：同名表已存在时 SQLite 会跳过模块加载直接成功，
引擎被误判为 FTS5，再用 FTS4 不支持的 `bm25()` 会一条都搜不到。改为先读 `sqlite_master` 里 `notes_fts` 的真实 DDL。

**FTS4 不支持显式 `AND`。** 未编译 `SQLITE_ENABLE_FTS3_PARENTHESIS` 时 `AND` 被当作普通词元，
`"检 索" AND "零 碎"` 永远无结果。统一使用空格隐式 AND（`"检 索" "零 碎"`），FTS4 / FTS5 均支持。

**中文分词策略（`FtsText`）。** unicode61 与 simple 都把连续汉字视为一个 token，「检索」搜不到「全文检索」，只有前缀匹配有效。

- 写入索引前：CJK 逐字拆开（`全文检索` → `全 文 检 索`），拉丁词保持整词并小写
- 查询时：连续 CJK 组 phrase（`"检 索"`，位置相邻即子串命中），拉丁词用前缀（`sql*`）
- 索引存分词副本，展示与导出永远用主表原文

**不要用 `sqlite_master` 判断虚拟表是否存在（v1.0.0 的实际 bug）。** Room 建库时会连续回调 `onCreate` 与 `onOpen`，
`FtsStore.create()` 被调用两次；SQLite 3.32（Android 12 / 13）上第二次查询拿不到 DDL，于是误判「表不存在」→
重试建 FTS5 / FTS4 均报 `table notes_fts already exists` → 已建好的引擎被降级为 NONE，检索退化为 LIKE。

v1.0.1 的修法：

- 表已存在时用 `MATCH` 判断模块可用性，用 `bm25()` 是否存在区分 FTS5 / FTS4
- 模块可用性探测走 `temp.` 临时表，不拿真表名试错
- `create()` 幂等：第二次调用不得改变第一次的结论（回归测试 `repeatedProbeMustNotDowngradeTheEngine`）
- 索引条数与在线笔记数不一致即整体重建，覆盖升级 / 崩溃 / 外部灌库

## 局域网同步

### 拓扑与协议

一台设备开「主机模式」（内嵌 HTTP 服务，默认端口 8765），其他设备填「主机地址 + 共享密钥」同步；
同一台设备两种角色都能当。

| 接口 | 说明 |
| --- | --- |
| `GET /ping` | 连通性探测，返回 `{protocol, device_name}`，**不需要密钥**（用于区分地址填错、主机没开、密钥不对） |
| `POST /sync` | 一次请求完成双向同步；请求头 `X-KnowNote-Key`；请求体带本机全量库存 + 本机变更 + 水位线，响应带主机库存 + `want_guids` + 主机变更 + 落库统计 |

线格式不带任何本地 id：笔记用 `guid`（32 位十六进制）认人，分组与标签传名字，对端按名字解析或新建 —— 不需要 id 映射表。

```json
{"guid":"a4e5f331...","title":"...","content":"...","group":"零散知识点","tags":["标签1"],
 "created_at":1700000000000,"updated_at":1700000001000,"is_deleted":0,"is_purged":0}
```

协议 3 起，每轮附带库存条目（只有身份与指纹，不含正文）：

```json
{"g":"a4e5f331...","u":1700000001000,"p":0,"h":"551d9a74"}
```

### 差集：双向库存比对（协议 3）

- 请求与响应都带**全量库存**：每条笔记的 `guid` + `updated_at` + `is_purged` + `hash`（内容规范串的 FNV-1a 32 位指纹）
- 两端使用同一个纯函数（App 的 `SyncDiff` 与中心的 `SyncDiff`，Kotlin 与 Python 逐位一致）：

| 比对结果 | 动作 |
| --- | --- |
| 对方没有 / 我更新 / 时间戳打平但指纹不同 | 我发 |
| 我没有 / 对方更新 | 我要 |
| 完全相同（含两边都持有的同一枚墓碑） | 不动（幂等的来源） |

- 正文只在确实需要时传输：命中的条目由对方在响应里用 `want_guids` 点名，下一轮按 guid 取正文发送；一次同步最多 5 轮
- `last_sync_at` 保留，只作「省一轮往返的快路径」与界面显示，**正确性不依赖它**
- 主机收到从机变更时记一条变更日志（`at` 取接收时刻），否则第三个从机拉不到第二个从机的改动；从机侧不记

协议 2 → 3 是断点：v1.10.1 的 App 与协议 3 的中心会互相返回 426，两侧须一起升级。

### 冲突裁决

1. `updated_at` 大者整体覆盖（标题 / 正文 / 分组 / 标签 / 软删除 / 墓碑）
2. 本机更新则不动，对端下次同步取得本机版本
3. 时间戳打平但内容不同：两端都取「规范串较大」的版本，保证收敛（有回归测试断言两端最终内容相同）

### 彻底删除 = 墓碑

保留一行 `is_deleted=1 + is_purged=1`，不删行；否则对端下次同步会把笔记推回来。
所有列表 / 检索 / 计数过滤 `is_purged=1`，删除意图靠时间戳传播。

### 安全边界

| 项 | 说明 |
| --- | --- |
| 认证 | `X-KnowNote-Key` 共享密钥，常量时间比较，错密钥 401；密钥为 8 位可读随机串（去掉 `0/O/1/I/l`），可在同步页重新生成 |
| 保密 | **无**。局域网内明文 HTTP，同网段抓包可见笔记内容；保密需后续 TLS 或预共享密钥加密 |
| 暴露面 | 主机绑定 `0.0.0.0`，但**未设密钥拒绝启动**；开启状态记入偏好，下次进入应用恢复监听 |
| 来源限制 | 仅局域网（v1.10.1），见下节；共享密钥管「谁有权限」，来源限制管「从哪儿来」 |
| 生命周期 | 主机服务挂在应用作用域，切页面不掉线；未做前台 Service，进程被回收即停止 |

### 定时自动同步

| | 设备侧（`data/sync/AutoSync.kt`） | 服务器侧（`server/knownote_hub.py`） |
| --- | --- | --- |
| 谁发起 | 手机（WorkManager 到点拉起进程） | 中心（按 `interval_seconds` 主动连接设备） |
| 最短节拍 | 15 分钟（Android 下限），省电 / 息屏时推迟 | 自定义，默认 60 秒 |
| 前提 | 主机地址与密钥填对，对端不必在跑 | 设备开着主机模式且 App 进程存活 |
| 开关 | 同步页「定时自动同步」卡片 | 配置项 `interval_seconds` |
| 可见性 | 卡片显示「后台任务：已排程（每 N 分钟）/ 正在执行 / 未排程」；距上次执行超过两个间隔时提示可能被省电或后台限制压住 | `start-hub.sh status` 显示每台设备上次结果与最近接入过的地址 |

两者可同时开启（同步幂等）。两个实现要点：

- 配置不全时返回 `success` 而非 `failure`：`failure` 会取消周期任务，用户之后填好地址也不会再跑
- 手动与定时共用同一水位线，`SyncCoordinator` 加互斥锁串行化会话

打开开关时会立即按同一路径执行一次，地址填错可当场发现。

对端与本机**不在同一网段**时（v1.10.3），失败原因写明两边各自的网段，而不是系统的
`failed to connect to /192.168.1.7 ... after 4000ms`：校园网的 `10.x` 与家里的 `192.168.x`
都算私有地址，只看地址分不出来。判定只在能确定时下结论（域名、枚举不到本机网卡时不猜）。

### 仅局域网（v1.10.1）

| 位置 | 拦在哪 | 规则 |
| --- | --- | --- |
| App 从机 | `SyncCoordinator.syncSession` 入口（手动与定时共用） | 当前网络非局域网或对端地址非局域网 → 不发请求，记日志并说明原因 |
| App 主机 | `SyncServer.handle` 首步 | 来源非局域网 → 403（含 `/ping`），计数并记日志 |
| 同步中心 | `Hub.lan_check` | 来源非局域网 → 403；带代理头时按 `CF-Connecting-IP` / `X-Forwarded-For` 的真实来源判断；`lan_only` 默认开 |

局域网口径（App 与中心一致）：RFC1918（10/8、172.16/12、192.168/16）+ 环回（127/8）+ 链路本地（169.254/16），
IPv6 的 `::1` / `fc00::/7` / `fe80::/10`。运营商大内网 `100.64.0.0/10` **刻意不计入**（手机在移动数据上即该网段）。

设备侧**不设网络类型约束**（v1.10.3）：v1.10.2 要求 `UNMETERED`（不计费网络），而手机热点、
被系统标成计费的 Wi-Fi 全都不满足 —— 周期任务长期停在「等约束满足」，一次都不执行
（`WorkSpec.period_count = 0`、JobScheduler 报 `Unsatisfied constraints: CONNECTIVITY`），界面上只留一个
停在开关那一刻的时间。局域网直连本就不走流量，该拦的是「对端不在同一个局域网」，由下面的判定负责。
约束在排任务时确定，改约束须用 `ExistingPeriodicWorkPolicy.UPDATE`，`KEEP` 不会更新已有任务。

被拦按「跳过」处理：记 `skipped:` 并返回 `success`；返回 `retry` 会让手机在外面退避重试，返回 `failure` 会取消周期任务。
**失败同样不退避**（v1.10.3）：`retry()` 的指数退避会把下一次执行推到几小时以后（v1.10.2 的行为），
周期任务本身到下个周期会自己再来，返回 `success` 反而更准时。

中心另有启动守卫：`bind` 解析结果全为公网地址、或本机无局域网地址 → 打印原因并以退出码 2 结束；判断不出时只警告。

该限制只覆盖来源网段与本地网络类型，不防中间人；跨网段使用需关闭 `lan_only`，并接受仅共享密钥把关。

### Termux 同步中心（`server/`）

两个角色：

1. **主机**：`GET /ping` + `POST /sync`，与 App 线格式完全一致（协议 3），App 填「中心地址 + 密钥」即可当普通主机用
2. **轮询者**：按 `interval_seconds` 依次连接配置中的设备（设备侧需开主机模式）
3. **地址自学习**（v1.10.3）：配置里的地址连不上时，按「该设备最近接入过」的地址依次试（最多 3 个）。
   优先用上次成功同步时记下的自称名精确匹配，其次配置名一致，再次「只剩一个没被别的设备占用」的候选；
   都对不上就按最近接入顺序试 —— 名字对不上是常态（配置里写的是「卧室那台」，设备自报的却是型号名），
   候选又都是已配对过的自家设备，试错代价只是几秒。配置地址不在本机网段时直接跳过它，不白等连接超时；
   每轮试了哪几个地址、最后连上谁，日志与 `--status` 都写明。启动横幅会点出不在本机网段的配置地址，
   同一原因的连续失败不再每轮刷日志（前 3 次照记，之后每 12 次一条）

只用 Python 3 标准库（`http.server` / `sqlite3` / `threading` / `base64`），零依赖零编译，Termux 上 `pkg install python` 即可运行。

```bash
cd server
python knownote_hub.py --init-config hub.conf.json   # 生成配置（随机密钥，可直接用）
vim hub.conf.json                                    # 填 devices：设备局域网 IP + 端口 + 密钥
bash start-hub.sh                                    # 前台运行（Ctrl+C 停止）
bash start-hub.sh bg                                 # 后台运行（nohup，写 hub.pid / hub.log）
bash start-hub.sh status                             # 中心条数与各设备上次同步结果
bash start-hub.sh check                              # 只探测各设备连通性，不动数据
bash logs.sh                                         # 跟随日志
bash stop-hub.sh                                     # 停止后台进程
```

其他参数：`--once`（只跑一轮轮询，适合 cron / Termux:Boot）、`--status`、`--check`、`--port`、`--set-key`、`--print-key`。
`hub.conf.json`、`knownote-hub.db`、`images/` 均在 `.gitignore` 内（含密钥与真实笔记）；样例配置为 `hub.conf.example.json`。

边界：

- **不存对端状态**：所需图片由对端每轮 `advertise` 的集合算出，中心重启 / 换机 / 清库不影响设备间判断（库里的水位线与图片缓存只是省一轮传输的提示）
- **安全**：同 App 主机（共享密钥 + 同 IP 错密钥限流 + 未设密钥拒绝启动 + `lan_only` 默认开），传输为明文 HTTP
- **非云端**：数据只落在运行它的机器上，无账号与上游服务；异地使用需自行加隧道 / VPN
- **单进程**：`ThreadingHTTPServer` + 一把 SQLite 写锁，适合个人量级；几十台设备需换 WSGI + 连接池

## 验证记录

### 功能与迁移（v1.0.0 – v1.9.0）

| 检查项 | 结果 |
| --- | --- |
| 构建 | `:app:assembleDebug` / `assembleRelease` BUILD SUCCESSFUL |
| 单元测试 | 55/55（`FtsTextTest` 8 / `MarkdownMarkupTest` 10 / `MarkupEditTest` 22 / `NoteBlocksTest` 7 / `SearchScopeTest` 8） |
| 仪器化测试 | Android 13（SQLite 3.32.2）47/47；Android 15（SQLite 3.44.3）47/47 |
| 同步引擎 `SyncEngineTest` | 8/8：远端新建（分组按名字建、标签关联）、时间戳优先、打平收敛、软删与墓碑传播、本地彻底删除留墓碑并同步出去、增量取数、主机记日志而从机不记、本机无该条时忽略墓碑 |
| 真回环 `SyncLoopbackTest` | 3/3：真 ServerSocket + 真 HttpURLConnection + 两个独立库双向同步（拉 4 推 1，再同步幂等）、错密钥 401、无密钥拒绝启动 |
| 迁移 `MigrationTest` | 3/3：1→2、2→3（`guid` 回填 32 位十六进制且互不相同、`is_purged` 默认 0、`sync_log` 可写、重复 guid 被唯一索引拒绝）、1→3 跨级；三步均对 schema JSON 校验 |
| 真机检索 | 中文子串「检索」命中 2 条、前缀 `gradle*` 命中 1 条、多词元 AND 命中正确 |
| 索引自愈 | 灌库时索引 0 条 → 启动后 8 条，与在线笔记数一致 |
| 引擎自愈 | v1.0.0 制造降级态 → 覆盖安装 v1.0.1 → 恢复 FTS4 并整体重建，MATCH 全部命中 |
| 真机迁移 | 覆盖安装后 `user_version` 逐级升级，既有笔记与索引保留；v2→v3 时 `guid` 3/3 唯一 |
| 真机同步（主机侧 / App↔App） | `/ping` 200；错密钥 401；正确密钥双向落库；分组与标签按名字对齐；水位线幂等 |
| 概览实时统计 | 删除 / 彻底删除后「更多」页计数即时变化，墓碑仍保留 |
| 多语言 | API 33 真机：切 English 后系统侧 `[en]`，跟随系统回 `[]`；应用名三语言从 APK 读回确认；切换语言不影响主题等设置 |
| 主题与设置 | 深色立即换肤（不重建 Activity）；动态取色生效；「关于」版本号读包信息 |

### v1.10.0（定时自动同步 + Termux 同步中心）

| 检查项 | 结果 |
| --- | --- |
| 单元测试 | 55/55 |
| 仪器化测试 | Android 13 与 Android 15 各 47/47（新增 `AutoSyncTest` 7 例） |
| `AutoSyncTest` | 7/7：关开关不动、自动同步真的把中心笔记拉到本机并写「上次自动同步」、本机改动推上中心、地址 / 密钥缺失返回 success、中心未开机返回 retry、401 原因透出、周期任务真的排上 / 撤掉 |
| 中心测试（PC） | 50/50：协议层（401 / 404 / 429 节流 / 坏 JSON / 协议版本）、引擎层（增量、冲突收敛、墓碑、图片只传一次、多轮）、调度层（双向、幂等、失败原因、水位线取 min）、库存差分、设备状态落库、局域网限制 |
| 中心测试（真 Termux，Android 11 / Python 3.14.6 / arm64） | 50/50，`pkg install python` 后直接跑，无 root、无第三方包 |
| 跨实现：中心拉 App | 中心 `--once` 拉回 App 8 条、推给 App 2 条；App 库 8 → 10 条，分组与标签按名字对齐 |
| 跨实现：App 同步中心 | 中心新建笔记后 App 拉到 11 条；App 推 10 条时中心落库 0 条（幂等） |
| 跨实现：Termux 中心 ⇄ PC 中心 | 一轮拉 12 条，第二轮 `拉 0 推 0`；`--status` 跨进程可见 |
| 跨实现：Termux 中心 ⇄ 真 App | 一轮从 App 拉 10 条，中心新建一条后推给 App（12 → 13 条） |

### v1.10.1（只在局域网里同步）

| 检查项 | 结果 |
| --- | --- |
| 单元测试 | 58/58（新增 `LanAddressTest` 3 例） |
| 仪器化测试 | Android 13 与 Android 15 各 50/50（新增 3 例：被拦时不发请求、跳过不是重试、约束为不计费网络） |
| 中心测试（PC / 真 Termux） | 41/41（新增 12 例局域网限制） |
| 「一个请求都不发」 | 仪器化用例断言主机侧收到的请求数 == 0，不只看返回值 |
| 其余服务端断言 | 403 前后笔记条数与内容哈希一致；直连公网 403、`CF-Connecting-IP` 为公网 403、多跳 `X-Forwarded-For` 只认第一跳、私有来源放行、`lan_only:false` 放行、公网 `bind` 使 `main()` 返回 2 |
| 真隧道对照 | `lan_only:true` 时经隧道访问 `/ping` 返回 403（日志记录代理头里的真实来源），改 `false` 后同一地址返回 200 |
| 覆盖安装（真机平板 / Android 16） | 1.10.0 → 1.10.1 原地升级：各项计数逐项相同、`notes` 内容哈希不变、偏好与图片字节一致、`firstInstallTime` 未变 |

### v1.10.2（同步 = 双向库存比对）

| 检查项 | 结果 |
| --- | --- |
| 故障定性 | 老设备水位线已被上一个对端推到当前 → 连新对端时算出的增量为空 → 新对端只拿到零星几条 |
| 单元测试 | 62/62（新增 `SyncDiffTest` 4 例：对方没有 / 我更新 / 打平但指纹不同 / 墓碑，含两侧哈希固定值） |
| 仪器化测试 | Android 13 与 Android 15 各 57/57（新增 5 例：水位线已推进时全新主机仍拿到全部 5 条、无待推增量时删除照样传过去、线结构体往返、黄金 JSON 双侧断言） |
| 中心测试（PC / 真 Termux） | 50/50（新增 8 例） |
| 跨实现指纹一致性 | `fnv1a32` 在 Kotlin 与 Python 各有一条同输入同期望值的断言（`551d9a74` / `04a770bf`） |
| 跨实现键名一致性 | 同一串黄金 JSON 两侧各钉一半：Python 断言生成结果、Kotlin 断言可解析出 inventory / want_guids / 正文 |
| 真机跨实现：Termux 中心 ⇄ PC 中心 | 空库第一轮拉 3 条 → `拉 0 推 0`（1 轮）→ 中心新建一条后推 1 条（2 轮） |
| 真机跨实现：PC 中心 ⇄ 真 App | App 点名要 2 条并落库（读 App 库核实：`user_version` 3、两条俱在、`sync_log` 记 `host · 推 2`），稳态 `拉 0 推 0`、1 轮 |
| 局域网闸未被放松 | 模拟器仅移动数据时 App 从机记 `Sync blocked: the device is not on a local network (mobile data?)`，未发出请求 |

### v1.10.3（修「自动同步从不触发」）

| 检查项 | 结果 |
| --- | --- |
| 定位证据 | 设备侧 WorkSpec `period_count = 0`、JobScheduler `Unsatisfied constraints: CONNECTIVITY`、当前网络 `Metered hint: true`；中心 520/520 次轮询超时且 `last_ok_at = 0` |
| 三个根因 | ① 设备侧周期任务要求 `UNMETERED`，手机热点 / 计费 Wi-Fi 永不满足 → 一次都不执行；② 失败返回 `retry()` 被指数退避推到几小时后；③ 中心配置里是样例地址 `192.168.1.7`，不在本机网段 |
| 单元测试 | 65/65（新增 `LanSubnetTest` 3 例：按真实前缀长度比网段、判不了时不误报、说明里带上两侧网段） |
| 仪器化测试 | Android 13 59/59（新增 4 例：无网络类型约束、任务状态可查、跨网段按 `skipped:` 记并写明原因、失败不再要求退避） |
| 中心测试（PC） | 60/60（新增 10 例：网段判定、本机网段解析、候选按最近接入排序、上限 3 个、不抢别的设备占用的地址、逐个往下试到连上、全失败时列出试过的每个地址、无历史时不猜、自称名对齐、失败日志节流） |
| 中心测试（真 Termux，Android 11 / Python 3.14.6 / arm64） | 60/60（22.7 秒），`pkg install python` 后直接跑 |
| 地址自学习（真实数据） | 取真中心库的副本 + 保持样例地址 `192.168.1.7` 跑 `--once`：跳过不在本机网段的配置地址，按最近接入顺序依次试三台曾接入过的设备（当时都没开主机模式，故均未连上） |
| 真机实测（计费热点） | 两台测试机当时都是 `Metered hint: true`（原缺陷的触发条件）：装上 1.10.3 并启动一次后，系统的任务约束只剩 `TIMING_DELAY`（原为 `TIMING_DELAY CONNECTIVITY` 且要求 `NOT_METERED`）；触发后两台都 `sync_auto_last_ok = true`（一台推 36 条、一台拉 1 条） |
| 地址自学习（真 center 上） | 中心升到 1.10.3 后配置仍留着样例地址 `192.168.1.7`：它跳过该地址，按最近接入顺序依次试三台曾接入的设备，日志逐条写明试了谁、什么结果 |


## 版本记录

| 版本 | 说明 |
| --- | --- |
| v1.0.0 | 首个可用版本：笔记 / 标签 / 分组 + 全文检索 + 导出 |
| v1.0.1 | 修 FTS 引擎误判导致检索降级为 LIKE；「更多」页增诊断信息；重复探测幂等 + 回归测试 |
| v1.1.0 | Markdown 渲染、编辑 / 预览切换、搜索历史、筛选记忆、回收站；数据库 1→2 真实迁移 + 迁移测试 |
| v1.1.1 | 修「记一条」中标签输完直接保存丢标签；点击 = 查看 / 长按 = 编辑；列表 ↔ 瀑布流切换 |
| v1.2.0 | 分组 / 标签页可查看组内与带该标签的笔记；卡片组件抽为共享 `NoteCards.kt`（该版为原地展开，v1.2.1 改为二级页面） |
| v1.2.1 | 改为独立二级页面（`group/{id}`、`tag/{id}` 路由 + `NoteCollectionScreen`）；修「点开看一眼就返回会被无条件刷新 `updated_at`」 |
| v1.3.0 | 局域网同步：一主多从、主机内嵌 HTTP 服务（手写 ServerSocket）、双接口、双向增量、时间戳冲突裁决、共享密钥；数据库 2→3（`guid` 唯一索引 + 墓碑 + `sync_log`）；彻底删除改墓碑 |
| v1.4.0 | 修「更多」页概览不刷新（改 Room Flow）；新增设置页（主题模式 / 动态取色 / 回收站定时清理） |
| v1.5.0 | 多语言 + 品牌化：四语言界面（254 条文案抽成资源，API 33+ 用 per-app language）；改名 KnowNote / 碎片笔记 / 碎片筆記；图标补单色层；诊断信息统一英文；修译文里的 Kotlin 模板插值被原样写进资源 |
| v1.6.0 | 可调阅读显示（字号五档、文字颜色七种）与检索范围勾选（标题 / 正文 / 标签）；检索与搜索历史忽略大小写 |
| v1.7.0 | 阅读页单独调字号（倍率制）+ MD / 文本模式；编辑器选中文字可加粗体 / 斜体 / 字号 / 六色；修渲染器不递归解析行内内容 |
| v1.8.0 | 正文插入图片：相册选择 → 私有目录缩放重编码 → 正文存 `img:` 引用；图片独占整行；图片随同步传输（同一张只传一次） |
| v1.9.0 | 图片参与局域网同步：协议升到 2（base64 承载，单批最多 6 张 / 4MB，多轮传完）；同步日志带图片收发张数 |
| v1.10.0 | 定时自动同步（设备侧 WorkManager）+ Termux 同步中心（纯标准库，可主动轮询各设备）；同步会话加串行闸；设备状态落库 |
| v1.10.1 | 只在局域网里同步（从机 / 主机 / 中心三处拦截），定时任务约束收紧到 `UNMETERED` 并改用 `UPDATE` 覆盖 |
| v1.10.2 | 同步改为双向库存比对（协议 3）：请求与响应带全量库存，两端同一纯函数算差集，正文按 `want_guids` 点名传输，水位线降级为快路径 |
| v1.10.3 | 修「自动同步从不触发」：设备侧去掉 `UNMETERED` 约束（热点 / 计费 Wi-Fi 上任务永不执行）、失败不再退避、卡片显示任务状态与超期提示；跨网段失败改说人话；中心加地址自学习、网段校验与失败日志节流 |

## 下一步

1. **同步加固**：主机改用前台 Service（常驻通知）以长期待命；上 TLS 或预共享密钥加密解决局域网窃听
2. **设备发现**：接入 NSD / mDNS 自动发现主机，免去手填 IP
3. **冲突体验**：在同步日志中列出被裁决的条目，并提供「查看对端版本」入口
4. **检索**：数据量上升后评估内置 SQLite（`requery/sqlite-android`）以启用 FTS5 与自定义分词器
5. **导出**：导出范围选择（分组 / 标签 / 时间）与导入
6. **墓碑增长**：`is_purged=1` 的行长期保留（删除可同步的前提），需要「墓碑保留期」—— 确认所有从机水位线越过后再真删

## 许可

[MIT](LICENSE)。
