# KnowNote 同步中心（LAN Sync Hub）

跑在常开设备（旧手机 / 树莓派 / PC）上的局域网同步中心：汇总各设备的笔记，并由服务端按固定间隔主动同步 ——
设备无需常驻后台，也不受 Android 省电策略影响。

- 纯 Python 3 标准库，**零依赖、零编译**（Termux 里 `pkg install python` 即可，无需 pip）
- 与 App 同一套线协议（`GET /ping` + `POST /sync`，协议版本 3）：App 填「地址 + 共享密钥」即可把中心当主机，无需改动 App
- 两个角色：**主机**（接受设备连接）与**轮询者**（每 `interval_seconds` 秒主动连接各设备）
- SQLite 存笔记（含墓碑），`images/` 存图片；冲突裁决与 App 同规则（时间戳优先，打平按规范串收敛）
- 共享密钥认证（常量时间比较）+ 同 IP 连续猜错限流；**未设密钥拒绝启动**
- **仅在局域网内服务**（`lan_only`，默认开）：非局域网来源一律 403（含 `/ping`），隧道 / 反代按 `CF-Connecting-IP` / `X-Forwarded-For` 的真实来源判断；`bind` 为公网地址或本机无局域网地址时拒绝启动
- 浏览器可直接查看状态：`http://<中心地址>:8765/status`

## 一、拓扑

```
   ┌────────────┐   ① 设备 → 中心（POST /sync，手动或定时）      ┌───────────────────────┐
   │  设备 A     │ ─────────────────────────────────────────▶ │  KnowNote 同步中心      │
   │（KnowNote） │ ◀───────────────────────────────────────── │  Termux（常开设备）     │
   └────────────┘   ② 中心 → 设备（每 N 秒轮询，需设备开主机模式） └───────────────────────┘
   ┌────────────┐                                             │  SQLite + images/      │
   │  设备 B     │ ◀────────────────────────────────────────▶  │  规范数据 + 变更日志     │
   └────────────┘                                             └───────────────────────┘
```

两条路走通任意一条即可完成汇总：A 的改动经中心落到 B，反之亦然。

## 二、Termux 部署

```bash
# 1) 装 Python
pkg install -y python

# 2) 把 server/ 传进设备
#    adb push <仓库>/server /sdcard/Download/knownote-hub
mkdir -p ~/knownote-hub && cd ~/knownote-hub
cp -r /sdcard/Download/knownote-hub/* .        # 或 unzip / syncthing / U 盘

# 3) 生成配置（打印随机共享密钥）
python knownote_hub.py --init-config hub.conf.json

# 4) 填 devices 里的设备地址（App「更多 → 局域网同步 → 本机地址」可查到）
nano hub.conf.json

# 5) 运行
bash start-hub.sh           # 前台（Ctrl+C 停止）
bash start-hub.sh bg        # 后台（日志写 hub.log）
bash start-hub.sh check     # 只探测各设备连通性
bash start-hub.sh status    # 本地条数与最近同步记录
bash logs.sh                # 跟踪日志
bash stop-hub.sh            # 停止后台进程
```

改轮询间隔：`bash start-hub.sh bg 120`（每 2 分钟），或修改配置里的 `interval_seconds`。

### 设备侧设置（二选一或都开）

| 方式 | 设备侧操作 | 同步时机 | 可靠性 |
| --- | --- | --- | --- |
| **中心轮询**（推荐） | 打开「局域网同步 → 主机模式」（建议同时开自动恢复） | 每 `interval_seconds` 秒 | App 进程被系统回收时中心连不上，日志记 `unreachable` |
| **设备侧定时** | 打开「定时自动同步」，填中心地址与密钥 | Android 最短 15 分钟一次，系统可能推迟 | 不要求 App 在前台，由 WorkManager 拉起 |

两者同时开启最稳：设备主动推、中心主动拉，任一方向先到即可。

### 开机自启（可选）

```bash
mkdir -p ~/.termux/boot
cat > ~/.termux/boot/knownote-hub.sh <<'EOF'
#!/data/data/com.termux/files/usr/bin/sh
termux-wake-lock
cd ~/knownote-hub && bash start-hub.sh bg
EOF
chmod +x ~/.termux/boot/knownote-hub.sh
```

需已安装 Termux:Boot 并至少启动过一次。也可用 `termux-services`（`sv`），本项目不强依赖。

### 单轮模式

适合 cron / `termux-job-scheduler`：跑一轮即退出，退出码 0 = 全部设备成功，1 = 有设备失败。

```bash
python knownote_hub.py --config hub.conf.json --once
```

## 三、配置项

| 键 | 说明 |
| --- | --- |
| `device_name` | 中心在设备列表 / 同步日志中的名字 |
| `device_id` | 设备标识（出现在对端日志里，勿与设备重名） |
| `bind` / `port` | 监听地址与端口，默认 `0.0.0.0:8765`（与 App 默认端口一致） |
| `key` | **必填**。设备连接中心所用的共享密钥；留空拒绝启动 |
| `lan_only` | 默认 `true`。非局域网来源一律 403（含 `/ping`）；带代理头时按 `CF-Connecting-IP` / `X-Real-IP` / `X-Forwarded-For` 的真实来源判断；`bind` 为公网地址或本机无局域网地址时拒绝启动。确需跨网段才改 `false` |
| `db` / `images_dir` | 数据库与图片目录（相对路径按配置文件所在目录计算） |
| `log_file` | 日志文件；不填只输出到屏幕 |
| `interval_seconds` | 轮询间隔，最小 30 |
| `poll_on_start` | 启动后先跑一轮再进入定时循环 |
| `devices[].name` | 设备名（显示用） |
| `devices[].host` / `port` / `address` | 设备地址，三种写法均可：`192.168.1.7`、`192.168.1.7:8765`、`http://192.168.1.7:8765` |
| `devices[].key` | 该设备的共享密钥；留空表示与顶层 `key` 相同 |
| `devices[].enabled` | 设为 false 则跳过该设备 |

命令行可临时覆盖：`--port`、`--interval`、`--no-poll`（只当主机）、`--no-serve`（只轮询）、`--print-key`、`--set-key`。

## 四、安全边界

- **认证**：`X-KnowNote-Key` 共享密钥，`hmac.compare_digest` 常量时间比较。同一 IP 10 分钟内错 8 次临时返回 429（`Retry-After` 600 秒）。
- **保密**：**无**。局域网内明文 HTTP，同网段抓包可见笔记内容；保密需 TLS 或预共享密钥加密。
- **暴露面**：默认绑定 `0.0.0.0`（局域网可连）。**未设密钥拒绝启动**。不要把端口映射到公网；更严的做法是把 `bind` 改为 `127.0.0.1`，配合 SSH 隧道 / Tailscale 访问。
- **仅局域网服务**（`lan_only`，默认开）：非局域网来源直接 403，`/ping` 也不答，且**不计入**错密钥节流。两条同时满足才放行：

  1. **直连地址**必须在局域网网段（10/8、172.16/12、192.168/16、127/8、169.254/16，IPv6 的 `::1` / `fc00::/7` / `fe80::/10`）。此条**先判**，因此伪造代理头无效。
  2. 请求带代理头（`CF-Connecting-IP` / `X-Real-IP` / `X-Forwarded-For`）时，头中的**真实来源**也必须在局域网网段。隧道会把连接说成来自 `127.0.0.1`，真身在头里。

  运营商大内网 `100.64.0.0/10` **刻意视为局域网外**（手机在移动数据上即该网段）。启动守卫：`bind` 解析结果全为公网地址、或本机无局域网地址 → 打印原因并以退出码 2 结束；判断不出时只警告。

- **图片文件名**：仅接受以 `[A-Za-z0-9]` 开头、由字母数字与 `. _ -` 组成且不超过 120 字符的名字，其余跳过（`../x.jpg` 之类无法落到目录之外）。
- **请求体上限**：32MB（协议单批上限为 6 张图 / 4MB）。

## 五、与 App 协议的对应关系

| 位置 | App（Kotlin） | 中心（Python） |
| --- | --- | --- |
| 线格式 | `data/sync/SyncModels.kt` | `SyncNote` / `SyncImage` / `SyncRequest` / `SyncResponse` / `NoteEntry` |
| 差集（协议 3 起） | 双方各报**全量库存**（`guid` + `updated_at` + 墓碑位 + 指纹 `g/u/p/h`），用同一纯函数 `SyncDiff` 算「我发什么 / 我要什么」；正文由对方 `want_guids` 点名后按 guid 传 | 同（`SyncDiff` 逐位一致，`fnv1a32` 两侧同输入同期望值有断言） |
| 增量（快路径） | `change_log` + `last_sync_at` → `at > since` | 同（正确性不依赖它，仅省一轮往返） |
| 冲突 | `updated_at` 优先；打平取规范串较大者 | 同（`canonical()` 字段顺序一致） |
| 主机顺序 | **先取要发的增量，再应用对端推来的** | 同（顺序反了会把对方刚推的变更回声回去） |
| 中转 | 主机收到从机变更时记一条 `change_log` | 同（否则第二台设备拉不到） |
| 水位线 | `min(对端 server_time, 本机时间)` | 同 |
| 图片 | base64、单批 6 张 / 4MB、同名不覆盖、`.part` + rename | 同 |

两处**刻意**不一致（只影响计数与效率，不影响最终数据）：

1. 打平改判时 App 只计「冲突」不计「落库条数」，中心两者都计 —— 否则日志会写「推 0 条」而库中内容已被改写。
2. App 客户端一轮最多从主机拉 6 张图（其余需再点一次同步）；中心轮询会自动续轮，一次补齐所缺图片（上限 5 轮）。

## 六、排错

先跑 `bash start-hub.sh check`（只测连通性，不动数据）。

| 现象 | 原因 / 处理 |
| --- | --- |
| `unreachable: ... 拒绝连接` | 设备不在同一 Wi-Fi、IP 变了、App 未开主机模式，或 App 进程被系统回收 |
| `401 Shared key mismatch` | 中心的 `devices[].key` 与设备上的密钥不一致（可在设备上重新生成后抄进配置） |
| `426 Protocol version mismatch` | 两端版本不一致（协议 3 = App v1.10.2 及以上）。协议 3 为断点：v1.10.1 及更早的 App 与协议 3 的中心会互相回 426 |
| `429 Too many failed attempts` | 密钥连错多次，等待 10 分钟或重启中心 |
| 笔记已同步但图片未传 | 单批上限 6 张 / 4MB，等下一轮；中心侧一次最多 5 轮 |
| 中心侧笔记数一直为 0 | 设备从未连上中心：要么在设备上手动同步一次，要么让中心 `check` 通过后轮询自动拉取 |

日志中常看的三行：

```
[host] 手机A@192.168.1.7 接入：给它 3 条 / 它推来 1 条落库（冲突 0）、图片 +0/-0
[poll] 平板A (192.168.1.7:8765) 拉 2 条 / 推 1 条落库（冲突 0）、图片 +1/-0、1 轮
[poll] 平板A (192.168.1.7:8765) 同步失败：unreachable: ... 拒绝连接
```

## 七、测试

```bash
# 仓库根目录
python -m unittest discover -s server/tests -t . -v     # 50 个用例
python server/tests/test_hub.py
# Termux 内（验证零依赖）
cd server && python3 -m unittest discover -s tests -t .
```

覆盖：协议（`/ping`、错密钥 401、协议 426、坏 JSON 400、404、限流 429、无密钥拒绝启动）、
引擎（增量、时间戳优先、打平收敛、墓碑不复活、未知 guid 的墓碑忽略）、
库存差分（新对端拿全库、水位线推进后仍全推、轮数与幂等、只传真差集、纯逻辑四分支、墓碑不主动请求、线结构体往返、与 App 同串黄金 JSON）、
图片（双向传输、只传一次、单批张数与字节上限、路径穿越被拒）、
调度（一轮双向、两台设备经中心中转、幂等、图片多轮补齐、连不上与密钥错的日志、水位线、定时线程按节拍执行）、
设备状态落库、局域网限制（公网来源与代理头中的公网真身一律 403 且不改动数据、私有来源放行、关掉开关放行、地址分类含运营商大内网与 IPv6、`bind` 为公网地址时拒绝启动）。

**真机验证**：Android 11 设备（Termux 自带 Python 3.14.6 / arm64）上 **50/50 通过**（19.3 秒）；
两条跨实现链路：Termux 中心 ⇄ PC 中心（一轮拉 12 条，第二轮 0/0 幂等）、Termux 中心 ⇄ 真机 App（先拉 10 条，再推送自身新建的一条）。

**局域网限制的真隧道对照**：把 Cloudflare 隧道临时指向中心，从公网访问 `https://example.com/ping` 得到 **403**
（日志记录代理头中的真实来源不在局域网网段）；`lan_only` 改回 `false` 后同一地址返回 **200**。
