#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
KnowNote 局域网同步中心（LAN Sync Hub）
=====================================

一个可以**长期跑在 Termux** 上的一主多从同步中心。它同时扮演两个角色：

1. **主机**：说出与 App 完全相同的有线协议（`GET /ping` + `POST /sync`，协议版本 2），
   所以 App 里「填地址 + 共享密钥」就能把中心当主机用 —— 不需要改 App 的任何代码。
2. **定时轮询者（本文件的核心）**：中心按固定间隔主动去连每一台**开着主机模式**的设备，
   把「设备的新变更」拉回来、把「别的设备的新变更」推过去。
   定时是从**服务器侧**发起的，手机不需要在后台跑任何定时任务、也不需要醒着。

拓扑（中心 = 常驻服务器，手机 = 随时上下线）：

    ┌────────────┐   POST /sync（手机主动）  ┌──────────────────────┐
    │  手机 A     │ ───────────────────────▶ │  KnowNote Hub        │
    │ （从机/主机）│ ◀─────────────────────── │  Termux 上常驻        │
    └────────────┘   定时轮询（中心主动）      │  SQLite + images/    │
    ┌────────────┐   POST /sync（手机主动）   │  规范数据 + 变更日志   │
    │  手机 B     │ ◀──────────────────────▶ │                      │
    └────────────┘                           └──────────────────────┘

协议、增量（change_log + 水位线）、冲突裁决（时间戳优先，打平按规范串收敛）、
图片（base64、单批 6 张 / 4MB、多轮传完）全部与 App 的 `data/sync/` 逐条对齐 ——
两边各说各的实现，最后以「真机上双向同步成功」为准（见 README 的验证记录）。

只用 Python 3 标准库：Termux 里 `pkg install python` 就够，不需要 pip、不需要编译。

常用命令：

    python knownote_hub.py --init-config hub.conf.json   # 生成配置（含随机密钥）
    python knownote_hub.py --config hub.conf.json        # 常驻：主机 + 定时轮询
    python knownote_hub.py --config hub.conf.json --once  # 只轮询一轮就退出（cron / termux-job-scheduler）
    python knownote_hub.py --config hub.conf.json --no-poll   # 只当主机，不主动连设备
    python knownote_hub.py --config hub.conf.json --check     # 只测每台设备通不通
"""

from __future__ import annotations

import argparse
import base64
import hmac
import ipaddress
import json
import os
import random
import re
import socket
import sqlite3
import sys
import threading
import time
import urllib.error
import urllib.request
from dataclasses import dataclass, field
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from typing import Dict, List, Optional, Sequence, Set, Tuple

# ---------------------------------------------------------------------------
# 协议常量（必须与 app/src/main/java/.../data/sync/SyncModels.kt 一字不差）
# ---------------------------------------------------------------------------

SYNC_PROTOCOL = 2
MAX_SYNC_IMAGES = 6
MAX_SYNC_IMAGE_BYTES = 4 * 1024 * 1024
MAX_IMAGE_ROUNDS = 4
SYNC_DEFAULT_PORT = 8765

# 服务端自己的上限：一次 HTTP 请求体最多多少字节（协议上限约 4MB 图片 + 笔记 JSON）
MAX_BODY_BYTES = 32 * 1024 * 1024

CONNECT_TIMEOUT = 4.0
READ_TIMEOUT = 60.0  # 中心可能一次收/发 6 张图，读超时给宽一点

# 密钥是 32 个易读字符里随机取 8 位（与 App 生成规则一致），暴力猜的空间约 1.1e12；
# 再叠一层「同一 IP 连续失败就暂时拒绝」，把在线爆破也挡掉。
KEY_ALPHABET = "abcdefghjkmnpqrstuvwxyz23456789"
MAX_AUTH_FAILURES = 8
AUTH_FAILURE_WINDOW = 600.0  # 秒

# 图片文件名白名单：App 生成的是 img_xxxxxxxxxxxx.jpg。
# 只接受字母数字与 . _ - —— 对端发来 "../x" 这种名字直接跳过，绝不落到目录之外。
IMAGE_NAME_RE = re.compile(r"^[A-Za-z0-9][A-Za-z0-9._\-]{0,119}$")


def now_ms() -> int:
    return int(time.time() * 1000)


def log(message: str) -> None:
    stamp = time.strftime("%Y-%m-%d %H:%M:%S")
    sys.stdout.write("[%s] %s\n" % (stamp, message))
    sys.stdout.flush()


def generate_key() -> str:
    return "".join(random.choice(KEY_ALPHABET) for _ in range(8))


def _int_or(value, default: int) -> int:
    """只有「没填」才用默认值 —— 显式的 0 是合法输入（端口 0 = 让系统分配）。"""
    if value is None or value == "":
        return int(default)
    try:
        return int(value)
    except (TypeError, ValueError):
        return int(default)


# ---------------------------------------------------------------------------
# 线格式
# ---------------------------------------------------------------------------


@dataclass(frozen=True)
class SyncNote:
    """一条笔记在网线上的形态（线格式里**不带任何本地 id**）。"""

    guid: str
    title: str = ""
    content: str = ""
    group: Optional[str] = None
    tags: Tuple[str, ...] = ()
    created_at: int = 0
    updated_at: int = 0
    is_deleted: bool = False
    is_purged: bool = False

    def canonical(self) -> str:
        """
        冲突裁决用的规范串：时间戳打平时比较它，两端取「较大」的那版。

        顺序与 App 的 SyncNote.canonical() 必须完全一致，否则两台设备会各留各的、永不收敛。
        """
        return "\x00".join(
            [
                self.title,
                self.content,
                self.group or "",
                ",".join(sorted(self.tags)),
                "1" if self.is_deleted else "0",
                "1" if self.is_purged else "0",
            ]
        )

    def to_json(self) -> dict:
        return {
            "guid": self.guid,
            "title": self.title,
            "content": self.content,
            "group": self.group,
            "tags": list(self.tags),
            "created_at": self.created_at,
            "updated_at": self.updated_at,
            "is_deleted": 1 if self.is_deleted else 0,
            "is_purged": 1 if self.is_purged else 0,
        }

    @staticmethod
    def from_json(obj: dict) -> "SyncNote":
        guid = obj.get("guid")
        if not isinstance(guid, str) or not guid:
            raise ValueError("note without guid")
        group = obj.get("group")
        if group is not None and not isinstance(group, str):
            group = None
        if group is not None and group.strip() == "":
            group = None
        raw_tags = obj.get("tags") or []
        tags = tuple(str(t) for t in raw_tags if isinstance(t, str) and t.strip())
        return SyncNote(
            guid=guid,
            title=str(obj.get("title") or ""),
            content=str(obj.get("content") or ""),
            group=group,
            tags=tags,
            created_at=int(obj.get("created_at") or 0),
            updated_at=int(obj.get("updated_at") or 0),
            is_deleted=int(obj.get("is_deleted") or 0) == 1,
            is_purged=int(obj.get("is_purged") or 0) == 1,
        )


@dataclass(frozen=True)
class SyncImage:
    name: str
    data: bytes

    def to_json(self) -> dict:
        return {"name": self.name, "data": base64.b64encode(self.data).decode("ascii")}

    @staticmethod
    def from_json(obj: dict) -> Optional["SyncImage"]:
        name = obj.get("name")
        payload = obj.get("data")
        if not isinstance(name, str) or not isinstance(payload, str):
            return None
        if not IMAGE_NAME_RE.match(name):
            return None
        try:
            return SyncImage(name=name, data=base64.b64decode(payload, validate=False))
        except Exception:
            return None


def parse_images(raw) -> List[SyncImage]:
    """坏数据直接跳过 —— 不让一张坏图打断整次同步。"""
    if not isinstance(raw, list):
        return []
    out = []
    for item in raw:
        if isinstance(item, dict):
            image = SyncImage.from_json(item)
            if image is not None:
                out.append(image)
    return out


@dataclass
class SyncRequest:
    device_id: str
    device_name: str
    last_sync_at: int
    notes: List[SyncNote] = field(default_factory=list)
    images_i_have: List[str] = field(default_factory=list)
    images: List[SyncImage] = field(default_factory=list)

    def to_json(self) -> dict:
        return {
            "protocol": SYNC_PROTOCOL,
            "device_id": self.device_id,
            "device_name": self.device_name,
            "last_sync_at": self.last_sync_at,
            "notes": [n.to_json() for n in self.notes],
            "images_i_have": list(self.images_i_have),
            "images": [i.to_json() for i in self.images],
        }

    @staticmethod
    def from_json(obj: dict) -> "SyncRequest":
        notes = []
        for item in obj.get("notes") or []:
            if isinstance(item, dict):
                try:
                    notes.append(SyncNote.from_json(item))
                except Exception:
                    continue
        have = [str(x) for x in (obj.get("images_i_have") or []) if isinstance(x, str)]
        return SyncRequest(
            device_id=str(obj.get("device_id") or "unknown-device"),
            device_name=str(obj.get("device_name") or "Unnamed device"),
            last_sync_at=int(obj.get("last_sync_at") or 0),
            notes=notes,
            images_i_have=have,
            images=parse_images(obj.get("images")),
        )


@dataclass
class SyncResponse:
    device_id: str
    device_name: str
    server_time: int
    notes: List[SyncNote] = field(default_factory=list)
    applied_notes: int = 0
    conflicts: int = 0
    images_i_have: List[str] = field(default_factory=list)
    images: List[SyncImage] = field(default_factory=list)
    images_received: int = 0

    def to_json(self) -> dict:
        return {
            "protocol": SYNC_PROTOCOL,
            "device_id": self.device_id,
            "device_name": self.device_name,
            "server_time": self.server_time,
            "applied_notes": self.applied_notes,
            "conflicts": self.conflicts,
            "notes": [n.to_json() for n in self.notes],
            "images_i_have": list(self.images_i_have),
            "images": [i.to_json() for i in self.images],
            "images_received": self.images_received,
        }

    @staticmethod
    def from_json(obj: dict) -> "SyncResponse":
        notes = []
        for item in obj.get("notes") or []:
            if isinstance(item, dict):
                try:
                    notes.append(SyncNote.from_json(item))
                except Exception:
                    continue
        have = [str(x) for x in (obj.get("images_i_have") or []) if isinstance(x, str)]
        return SyncResponse(
            device_id=str(obj.get("device_id") or ""),
            device_name=str(obj.get("device_name") or "Unnamed device"),
            server_time=int(obj.get("server_time") or 0),
            notes=notes,
            applied_notes=int(obj.get("applied_notes") or 0),
            conflicts=int(obj.get("conflicts") or 0),
            images_i_have=have,
            images=parse_images(obj.get("images")),
            images_received=int(obj.get("images_received") or 0),
        )


# ---------------------------------------------------------------------------
# 图片仓库（同名不覆盖、先写 .part 再 rename）
# ---------------------------------------------------------------------------


class ImageDir:
    """中心本地的图片目录。names/read/write 三个口子，与 App 的 ImageStore 对齐。"""

    def __init__(self, path: str) -> None:
        self.path = os.path.abspath(os.path.expanduser(path))
        os.makedirs(self.path, exist_ok=True)

    def names(self) -> Set[str]:
        try:
            return {
                n
                for n in os.listdir(self.path)
                if not n.endswith(".part") and os.path.isfile(os.path.join(self.path, n))
            }
        except OSError:
            return set()

    def read(self, name: str) -> Optional[bytes]:
        if not IMAGE_NAME_RE.match(name or ""):
            return None
        target = os.path.join(self.path, name)
        try:
            with open(target, "rb") as fh:
                return fh.read()
        except OSError:
            return None

    def write(self, name: str, data: bytes) -> bool:
        """返回 True 表示确实新增了一张（已存在则 False，不覆盖对端与本地的旧版本）。"""
        if not IMAGE_NAME_RE.match(name or ""):
            return False
        target = os.path.join(self.path, name)
        if os.path.exists(target):
            return False
        tmp = target + ".part"
        try:
            with open(tmp, "wb") as fh:
                fh.write(data)
            os.replace(tmp, target)
            return True
        except OSError:
            try:
                os.remove(tmp)
            except OSError:
                pass
            return False


# ---------------------------------------------------------------------------
# 中心的数据层（SQLite）
# ---------------------------------------------------------------------------


class HubStore:
    """
    中心的规范数据。

    表结构与 App 的 Room 库**不要求相同** —— 中心不需要标签字典表和分组排序，
    只需要能原样吐回线格式需要的东西（分组用名字、标签用名字）。
    """

    def __init__(self, db_path: str) -> None:
        self.db_path = os.path.abspath(os.path.expanduser(db_path))
        parent = os.path.dirname(self.db_path)
        if parent:
            os.makedirs(parent, exist_ok=True)
        self._lock = threading.RLock()
        self._conn = sqlite3.connect(self.db_path, check_same_thread=False)
        self._conn.row_factory = sqlite3.Row
        with self._lock:
            self._conn.execute("PRAGMA journal_mode=WAL")
            self._conn.execute("PRAGMA synchronous=NORMAL")
            self._create_schema()

    def close(self) -> None:
        with self._lock:
            self._conn.close()

    def _create_schema(self) -> None:
        self._conn.executescript(
            """
            CREATE TABLE IF NOT EXISTS notes (
                guid       TEXT PRIMARY KEY,
                title      TEXT NOT NULL DEFAULT '',
                content    TEXT NOT NULL DEFAULT '',
                group_name TEXT,
                created_at INTEGER NOT NULL DEFAULT 0,
                updated_at INTEGER NOT NULL DEFAULT 0,
                is_deleted INTEGER NOT NULL DEFAULT 0,
                is_purged  INTEGER NOT NULL DEFAULT 0
            );
            CREATE TABLE IF NOT EXISTS note_tags (
                guid TEXT NOT NULL,
                name TEXT NOT NULL,
                PRIMARY KEY (guid, name)
            );
            CREATE TABLE IF NOT EXISTS change_log (
                id     INTEGER PRIMARY KEY AUTOINCREMENT,
                guid   TEXT NOT NULL,
                at     INTEGER NOT NULL,
                source TEXT
            );
            CREATE INDEX IF NOT EXISTS idx_change_log_at ON change_log (at);
            CREATE TABLE IF NOT EXISTS kv (
                k TEXT PRIMARY KEY,
                v TEXT NOT NULL
            );
            CREATE TABLE IF NOT EXISTS peer_images (
                peer TEXT NOT NULL,
                name TEXT NOT NULL,
                PRIMARY KEY (peer, name)
            );
            CREATE TABLE IF NOT EXISTS sync_log (
                id        INTEGER PRIMARY KEY AUTOINCREMENT,
                at        INTEGER NOT NULL,
                role      TEXT NOT NULL,
                peer      TEXT NOT NULL,
                pulled    INTEGER NOT NULL DEFAULT 0,
                pushed    INTEGER NOT NULL DEFAULT 0,
                conflicts INTEGER NOT NULL DEFAULT 0,
                ok        INTEGER NOT NULL DEFAULT 1,
                message   TEXT NOT NULL DEFAULT ''
            );
            CREATE TABLE IF NOT EXISTS device_state (
                address    TEXT PRIMARY KEY,
                name       TEXT NOT NULL DEFAULT '',
                enabled    INTEGER NOT NULL DEFAULT 1,
                polls      INTEGER NOT NULL DEFAULT 0,
                failures   INTEGER NOT NULL DEFAULT 0,
                last_ok_at INTEGER NOT NULL DEFAULT 0,
                last_error TEXT NOT NULL DEFAULT '',
                pulled     INTEGER NOT NULL DEFAULT 0,
                pushed     INTEGER NOT NULL DEFAULT 0,
                conflicts  INTEGER NOT NULL DEFAULT 0
            );
            """
        )
        self._conn.commit()

    # ---------- 键值（设备标识、每台设备的水位线） ----------

    def kv_get(self, key: str, default: str = "") -> str:
        with self._lock:
            row = self._conn.execute("SELECT v FROM kv WHERE k = ?", (key,)).fetchone()
        return row["v"] if row else default

    def kv_set(self, key: str, value: str) -> None:
        with self._lock:
            self._conn.execute(
                "INSERT INTO kv (k, v) VALUES (?, ?) ON CONFLICT(k) DO UPDATE SET v = excluded.v",
                (key, str(value)),
            )
            self._conn.commit()

    # ---------- 笔记 ----------

    def _tags_of(self, guid: str) -> Tuple[str, ...]:
        rows = self._conn.execute(
            "SELECT name FROM note_tags WHERE guid = ? ORDER BY name", (guid,)
        ).fetchall()
        return tuple(r["name"] for r in rows)

    def _row_to_note(self, row: sqlite3.Row) -> SyncNote:
        return SyncNote(
            guid=row["guid"],
            title=row["title"],
            content=row["content"],
            group=row["group_name"],
            tags=self._tags_of(row["guid"]),
            created_at=row["created_at"],
            updated_at=row["updated_at"],
            is_deleted=bool(row["is_deleted"]),
            is_purged=bool(row["is_purged"]),
        )

    def note_by_guid(self, guid: str) -> Optional[SyncNote]:
        with self._lock:
            row = self._conn.execute("SELECT * FROM notes WHERE guid = ?", (guid,)).fetchone()
            return self._row_to_note(row) if row else None

    def write_note(self, note: SyncNote, origin_device: Optional[str] = None) -> str:
        """
        落库一条笔记，返回 'inserted' / 'updated'。

        origin_device 非空 = 这条是**中转**进来的（中心要把这次变更记进 change_log，
        否则第三台设备永远拉不到它）。这与 App 主机侧 writeFromRemote 的语义一致。
        """
        with self._lock:
            existing = self._conn.execute(
                "SELECT guid FROM notes WHERE guid = ?", (note.guid,)
            ).fetchone()
            if existing is None:
                self._conn.execute(
                    "INSERT INTO notes (guid, title, content, group_name, created_at, updated_at,"
                    " is_deleted, is_purged) VALUES (?, ?, ?, ?, ?, ?, ?, ?)",
                    (
                        note.guid,
                        note.title,
                        note.content,
                        note.group,
                        note.created_at,
                        note.updated_at,
                        1 if note.is_deleted else 0,
                        1 if note.is_purged else 0,
                    ),
                )
                op = "inserted"
            else:
                self._conn.execute(
                    "UPDATE notes SET title = ?, content = ?, group_name = ?, updated_at = ?,"
                    " is_deleted = ?, is_purged = ? WHERE guid = ?",
                    (
                        note.title,
                        note.content,
                        note.group,
                        note.updated_at,
                        1 if note.is_deleted else 0,
                        1 if note.is_purged else 0,
                        note.guid,
                    ),
                )
                op = "updated"

            self._conn.execute("DELETE FROM note_tags WHERE guid = ?", (note.guid,))
            for tag in sorted(set(note.tags)):
                if tag.strip():
                    self._conn.execute(
                        "INSERT OR IGNORE INTO note_tags (guid, name) VALUES (?, ?)",
                        (note.guid, tag),
                    )

            if origin_device is not None:
                # at 用「中心收到的时间」而不是对方的 updated_at：
                # 否则水位线已经越过对方时间戳的设备永远拉不到这条中转变更。
                self._conn.execute(
                    "INSERT INTO change_log (guid, at, source) VALUES (?, ?, ?)",
                    (note.guid, now_ms(), origin_device),
                )
            self._conn.commit()
            return op

    def collect_changes(self, since: int) -> List[SyncNote]:
        """自水位线之后改动过的笔记（含软删除与墓碑），走 change_log 而不是全表扫。"""
        with self._lock:
            rows = self._conn.execute(
                "SELECT n.* FROM notes n JOIN ("
                "  SELECT DISTINCT guid FROM change_log WHERE at > ?"
                ") c ON c.guid = n.guid",
                (since,),
            ).fetchall()
            return [self._row_to_note(r) for r in rows]

    def counts(self) -> Dict[str, int]:
        with self._lock:
            notes = self._conn.execute(
                "SELECT COUNT(*) c FROM notes WHERE is_purged = 0 AND is_deleted = 0"
            ).fetchone()["c"]
            trashed = self._conn.execute(
                "SELECT COUNT(*) c FROM notes WHERE is_purged = 0 AND is_deleted = 1"
            ).fetchone()["c"]
            tombstones = self._conn.execute(
                "SELECT COUNT(*) c FROM notes WHERE is_purged = 1"
            ).fetchone()["c"]
            changes = self._conn.execute("SELECT COUNT(*) c FROM change_log").fetchone()["c"]
            total = self._conn.execute("SELECT COUNT(*) c FROM notes").fetchone()["c"]
        return {
            "notes": notes,
            "trashed": trashed,
            "tombstones": tombstones,
            "change_log": changes,
            "total_rows": total,
        }

    # ---------- 对端「已有哪些图」缓存 ----------

    def peer_images(self, peer: str) -> Optional[Set[str]]:
        with self._lock:
            rows = self._conn.execute(
                "SELECT name FROM peer_images WHERE peer = ?", (peer,)
            ).fetchall()
        if not rows:
            return None  # 从未记录过 = 当成「它什么都没有」，最坏是多传几轮
        return {r["name"] for r in rows}

    def save_peer_images(self, peer: str, names: Sequence[str]) -> None:
        with self._lock:
            self._conn.execute("DELETE FROM peer_images WHERE peer = ?", (peer,))
            self._conn.executemany(
                "INSERT OR IGNORE INTO peer_images (peer, name) VALUES (?, ?)",
                [(peer, n) for n in sorted(set(names))],
            )
            self._conn.commit()

    # ---------- 同步日志 ----------

    def log_sync(
        self,
        role: str,
        peer: str,
        pulled: int = 0,
        pushed: int = 0,
        conflicts: int = 0,
        ok: bool = True,
        message: str = "",
    ) -> None:
        with self._lock:
            self._conn.execute(
                "INSERT INTO sync_log (at, role, peer, pulled, pushed, conflicts, ok, message)"
                " VALUES (?, ?, ?, ?, ?, ?, ?, ?)",
                (now_ms(), role, peer, pulled, pushed, conflicts, 1 if ok else 0, message),
            )
            self._conn.commit()

    def recent_log(self, limit: int = 15) -> List[dict]:
        with self._lock:
            rows = self._conn.execute(
                "SELECT * FROM sync_log ORDER BY at DESC LIMIT ?", (limit,)
            ).fetchall()
        return [dict(r) for r in rows]

    # ---------- 每台设备的同步状态（落库，--status 是另一个进程也要看得见） ----------

    def device_states(self) -> Dict[str, dict]:
        with self._lock:
            rows = self._conn.execute("SELECT * FROM device_state").fetchall()
        return {r["address"]: dict(r) for r in rows}

    def save_device_state(self, stats: dict) -> None:
        with self._lock:
            self._conn.execute(
                """
                INSERT INTO device_state
                    (address, name, enabled, polls, failures, last_ok_at, last_error,
                     pulled, pushed, conflicts)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                ON CONFLICT(address) DO UPDATE SET
                    name=excluded.name,
                    enabled=excluded.enabled,
                    polls=excluded.polls,
                    failures=excluded.failures,
                    last_ok_at=excluded.last_ok_at,
                    last_error=excluded.last_error,
                    pulled=excluded.pulled,
                    pushed=excluded.pushed,
                    conflicts=excluded.conflicts
                """,
                (
                    stats.get("address", ""),
                    stats.get("name", ""),
                    1 if stats.get("enabled", True) else 0,
                    int(stats.get("polls", 0)),
                    int(stats.get("failures", 0)),
                    int(stats.get("last_ok_at", 0)),
                    stats.get("last_error", "") or "",
                    int(stats.get("pulled", 0)),
                    int(stats.get("pushed", 0)),
                    int(stats.get("conflicts", 0)),
                ),
            )
            self._conn.commit()


# ---------------------------------------------------------------------------
# 同步引擎（与 App 的 SyncEngine 同规则）
# ---------------------------------------------------------------------------


class SyncEngine:
    def __init__(self, store: HubStore, images: ImageDir) -> None:
        self.store = store
        self.images = images

    # ---------- 笔记 ----------

    def collect_changes(self, since: int) -> List[SyncNote]:
        return self.store.collect_changes(since)

    def apply_changes(self, remote: Sequence[SyncNote], origin_device: Optional[str] = None) -> dict:
        """
        应用远端变更，返回 {inserted, updated, conflicts, skipped}。

        规则（README 6.4，与 App 一字不差）：
        1. updated_at 大的那版整体覆盖；
        2. 本机更新就不动 —— 对端下次同步拿到本机这版，会按同样规则认输；
        3. 时间戳**打平但内容不同**：两端都取「规范串较大」的那版，保证收敛到同一份。
        """
        inserted = updated = conflicts = skipped = 0
        for item in remote:
            if not item.guid:
                skipped += 1
                continue
            local = self.store.note_by_guid(item.guid)
            if local is None:
                if item.is_purged:
                    skipped += 1  # 本机本来就没有这条，墓碑无事可做
                else:
                    self.store.write_note(item, origin_device)
                    inserted += 1
                continue

            if item.updated_at > local.updated_at:
                self.store.write_note(item, origin_device)
                updated += 1
            elif item.updated_at < local.updated_at:
                skipped += 1
            else:
                if local.canonical() == item.canonical():
                    skipped += 1
                else:
                    conflicts += 1
                    if item.canonical() > local.canonical():
                        self.store.write_note(item, origin_device)
                        # 注：App 在「打平改判」这一支里只计冲突、不计落库条数，
                        # 于是它的日志会说「推 0 条」而库里其实被改写了。中心这里两样都计 ——
                        # 裁决规则（谁赢）与 App 完全一致，只是统计口径更诚实一点。
                        updated += 1
        return {
            "inserted": inserted,
            "updated": updated,
            "conflicts": conflicts,
            "skipped": skipped,
            "changed": inserted + updated,
        }

    # ---------- 图片 ----------

    def outgoing_images(self, peer_has: Set[str]) -> List[SyncImage]:
        """本机有、对端没有的图（按文件名排序 + 张数/字节两道闸，超量交给多轮）。"""
        candidates = sorted(self.images.names() - set(peer_has))
        out: List[SyncImage] = []
        total = 0
        for name in candidates:
            if len(out) >= MAX_SYNC_IMAGES:
                break
            data = self.images.read(name)
            if data is None:
                continue
            if total + len(data) > MAX_SYNC_IMAGE_BYTES and out:
                break
            total += len(data)
            out.append(SyncImage(name=name, data=data))
        return out

    def apply_images(self, images: Sequence[SyncImage]) -> int:
        added = 0
        for image in images:
            if self.images.write(image.name, image.data):
                added += 1
        return added


# ---------------------------------------------------------------------------
# HTTP 客户端（中心 → 设备）
# ---------------------------------------------------------------------------


class HubClient:
    """连设备用的 HTTP 客户端。

    刻意用 `ProxyHandler({})` 建 opener：在 PC 上调试时系统里可能开着 Clash 之类代理，
    代理会把发往 192.168.x.x 的请求吃掉，看起来像「设备连不上」。
    """

    def __init__(self) -> None:
        self._opener = urllib.request.build_opener(urllib.request.ProxyHandler({}))

    def request(
        self,
        host: str,
        port: int,
        path: str,
        method: str = "GET",
        payload: Optional[dict] = None,
        key: Optional[str] = None,
        timeout: float = READ_TIMEOUT,
    ) -> Tuple[Optional[int], str]:
        """返回 (状态码, 响应文本)；连接层失败时状态码为 None。"""
        url = "http://%s:%d%s" % (host, int(port), path)
        data = None
        if payload is not None:
            data = json.dumps(payload, ensure_ascii=False).encode("utf-8")
        request = urllib.request.Request(url, data=data, method=method)
        if data is not None:
            request.add_header("Content-Type", "application/json; charset=utf-8")
        if key:
            request.add_header("X-KnowNote-Key", key)
        try:
            with self._opener.open(request, timeout=timeout) as response:
                return response.status, response.read().decode("utf-8", "replace")
        except urllib.error.HTTPError as error:
            try:
                return error.code, error.read().decode("utf-8", "replace")
            except Exception:
                return error.code, ""
        except Exception as error:  # 连不上 / 超时 / DNS
            return None, "%s: %s" % (type(error).__name__, error)


def parse_address(text: str, default_port: int = SYNC_DEFAULT_PORT) -> Optional[Tuple[str, int]]:
    """与 App 的 parseSyncAddress 同样宽容：允许 1.2.3.4 / 1.2.3.4:8765 / http://1.2.3.4:8765。"""
    value = (text or "").strip()
    for prefix in ("http://", "https://"):
        if value.startswith(prefix):
            value = value[len(prefix):]
    value = value.rstrip("/")
    if not value:
        return None
    if value.startswith("[") and "]" in value:  # [::1]:8765
        host, _, tail = value.partition("]")
        host = host[1:]
        port = default_port
        if tail.startswith(":") and tail[1:].isdigit():
            port = int(tail[1:])
        return (host, port) if 1 <= port <= 65535 else None
    if ":" in value:
        host, _, port_text = value.rpartition(":")
        if port_text.isdigit():
            port = int(port_text)
            if not host or not (1 <= port <= 65535):
                return None
            return (host, port)
        return None
    return (value, default_port)


# ---------------------------------------------------------------------------
# 中心的配置
# ---------------------------------------------------------------------------


# ---------------------------------------------------------------------------
# 局域网限制（v1.10.1）
#
# 「只在局域网里同步」要拦两种东西：
#   ① 来源在局域网外的请求（公网直连、路由器端口映射）；
#   ② 经隧道 / 反向代理进来的请求 —— 这时直连地址常是 127.0.0.1，
#      真身在 CF-Connecting-IP / X-Real-IP / X-Forwarded-For 里，所以要看头。
# 两条都满足才放行，缺一不可。
# ---------------------------------------------------------------------------

# 什么算「局域网」：RFC1918 + 环回 + 链路本地，以及 IPv6 的 ::1 / ULA / 链路本地。
# 刻意**不**收 100.64.0.0/10（运营商大内网）：手机在移动数据上就是那个网段，
# 那已经是「局域网外」—— 收进来这条规矩就形同虚设了。
_LAN_NETWORKS = tuple(
    ipaddress.ip_network(text)
    for text in (
        "10.0.0.0/8",
        "172.16.0.0/12",
        "192.168.0.0/16",
        "127.0.0.0/8",
        "169.254.0.0/16",
        "::1/128",
        "fc00::/7",
        "fe80::/10",
    )
)

# 代理 / 隧道会加的头，按可信度排。直连过来的对端这些头是空的。
_ORIGIN_HEADERS = ("cf-connecting-ip", "x-real-ip", "x-forwarded-for")


def parse_ip(value) -> Optional[object]:
    """把 IP 字面量 / `IP:端口` / `[IPv6]:端口` / v4-mapped 解析成地址对象；解析不了返回 None。"""
    if isinstance(value, (ipaddress.IPv4Address, ipaddress.IPv6Address)):
        return value
    text = str(value or "").strip()
    if not text:
        return None
    if text.startswith("["):                        # [::1]:8765
        text = text[1:].split("]", 1)[0]
    elif text.count(":") == 1 and "." in text:      # 192.168.1.5:8765
        text = text.split(":", 1)[0]
    text = text.split("/", 1)[0]                    # 带掩码的写法
    if text.lower().startswith("::ffff:"):          # v4-mapped
        text = text[7:]
    try:
        return ipaddress.ip_address(text)
    except ValueError:
        return None


def is_lan_address(value) -> bool:
    """是不是局域网地址。非 IP（域名、空值、乱码）一律当「不是」—— 保守的方向才是安全的方向。"""
    address = parse_ip(value)
    if address is None:
        return False
    return any(
        address.version == network.version and address in network
        for network in _LAN_NETWORKS
    )


def claimed_origin(headers) -> str:
    """代理头里那个「被代理的真实来源」；没有就返回空串。多跳只取第一个（最靠近客户端的那跳）。"""
    if not headers:
        return ""
    # HTTP 头在协议上不分大小写：email.message.Message 自带大小写无关，普通 dict 没有，
    # 所以统一摊成「小写键」的字典再看（头就那么几个，代价可以忽略）。
    try:
        items = dict(headers).items()
    except Exception:
        items = []
    lower = {str(key).lower(): value for key, value in items}
    for name in _ORIGIN_HEADERS:
        raw = lower.get(name)
        if not raw:
            continue
        return str(raw).split(",")[0].strip()
    return ""


def resolve_ip_addresses(host: str) -> List[str]:
    """把一个 bind 值（IP 字面量或主机名）解析成 IP 列表；解析不了返回空列表。"""
    text = str(host or "").strip()
    if not text or text in ("0.0.0.0", "::", "*"):
        return []
    try:
        infos = socket.getaddrinfo(text, None)
    except Exception:
        return []
    addresses: List[str] = []
    for info in infos:
        address = info[4][0]
        if address not in addresses:
            addresses.append(address)
    return addresses


@dataclass
class DeviceConfig:
    name: str
    host: str
    port: int
    key: str
    enabled: bool = True

    @property
    def address(self) -> str:
        return "%s:%d" % (self.host, self.port)

    @property
    def label(self) -> str:
        return "%s (%s)" % (self.name, self.address)


class HubConfig:
    def __init__(self, raw: dict, path: str) -> None:
        self.path = path
        # 相对路径按「配置文件所在目录」算：这样从哪个目录启动中心都读的是同一份数据
        self.base_dir = os.path.dirname(os.path.abspath(path)) or "."
        self.raw = raw
        self.device_name = str(raw.get("device_name") or "KnowNote Hub (Termux)")
        self.device_id = str(raw.get("device_id") or "").strip() or ("hub-" + generate_key())
        self.bind = str(raw.get("bind") or "0.0.0.0")
        # 只在局域网里服务（默认开）。关掉它意味着公网也能连 —— 那时只剩共享密钥把关。
        self.lan_only = bool(raw.get("lan_only", True))
        # 端口显式写 0 时不能当成「没填」—— 0 是合法写法（让系统分配一个空闲端口，测试就用它）
        self.port = _int_or(raw.get("port"), SYNC_DEFAULT_PORT)
        self.key = str(raw.get("key") or "").strip()
        self.db = str(raw.get("db") or "knownote-hub.db")
        self.images_dir = str(raw.get("images_dir") or "images")
        self.log_file = raw.get("log_file")
        self.interval_seconds = max(30, int(raw.get("interval_seconds") or 300))
        self.poll_on_start = bool(raw.get("poll_on_start", True))
        self.devices: List[DeviceConfig] = []
        for item in raw.get("devices") or []:
            if not isinstance(item, dict):
                continue
            address = str(item.get("address") or item.get("host") or "").strip()
            parsed = parse_address(address, SYNC_DEFAULT_PORT) if address else None
            if parsed is None:
                log("[warn] 配置里的设备地址无法解析，已跳过: %r" % (address,))
                continue
            host, port = parsed
            self.devices.append(
                DeviceConfig(
                    name=str(item.get("name") or host),
                    host=str(item.get("host") or host),
                    port=_int_or(item.get("port"), port),
                    key=str(item.get("key") or self.key),
                    enabled=bool(item.get("enabled", True)),
                )
            )

    @staticmethod
    def load(path: str) -> "HubConfig":
        with open(path, "r", encoding="utf-8") as fh:
            raw = json.load(fh)
        if not isinstance(raw, dict):
            raise ValueError("配置文件必须是一个 JSON 对象")
        return HubConfig(raw, path)

    def resolve(self, path: str) -> str:
        """把配置里的相对路径解析成「相对配置文件所在目录」的绝对路径。"""
        expanded = os.path.expanduser(str(path))
        if os.path.isabs(expanded):
            return expanded
        return os.path.join(self.base_dir, expanded)

    def lan_bind_problem(self) -> Optional[str]:
        """lan_only 打开时，检查「当前要监听的地方到底在不在局域网里」。

        返回问题描述；没问题返回 None。**只在确定不在局域网时才拦**：
        判断不出来（解析不了主机名、枚举不到本机地址）一律放行，只记一条警告 ——
        启动守卫的假警报比漏洞更烦人，真正的把关在每次请求上（`Hub.lan_check`）。
        """
        if not self.lan_only:
            return None
        text = str(self.bind or "").strip().strip("[]")
        if text in ("", "0.0.0.0", "::", "*"):
            # 通配地址：这时服务会挂在本机所有网卡上，按「本机有没有局域网地址」判断
            addresses = [a for a in local_ipv4_addresses() if a]
            if not addresses:
                log("[warn] 枚举不到本机 IPv4 地址，跳过「有没有连局域网」这项启动检查（bind=%s）" % self.bind)
                return None
            if any(is_lan_address(a) for a in addresses):
                return None
            return ("本机地址 %s 都不在局域网网段（10/8、172.16/12、192.168/16…），看起来没连着局域网"
                    % "、".join(addresses))
        addresses = resolve_ip_addresses(text)
        if not addresses:
            log("[warn] 解析不了 bind=%r，跳过这项启动检查" % self.bind)
            return None
        if any(is_lan_address(a) for a in addresses):
            if not all(is_lan_address(a) for a in addresses):
                log("[warn] bind=%s 同时解析到局域网和公网地址（%s），只用了局域网那一部分"
                    % (self.bind, "、".join(addresses)))
            return None
        return "bind 指向的不是局域网地址：%s（解析为 %s）" % (self.bind, "、".join(addresses))


def example_config(port: int = SYNC_DEFAULT_PORT) -> dict:
    return {
        "_说明": "KnowNote 同步中心配置。改完直接重启中心即可生效。",
        "device_name": "KnowNote Hub (Termux)",
        "device_id": "hub-" + generate_key(),
        "bind": "0.0.0.0",
        "port": port,
        "key": generate_key(),
        "lan_only": True,
        "_lan_only_说明": ("只在局域网里服务（默认开）：来源网段不对的请求一律 403，"
                           "隧道/反代进来的按 CF-Connecting-IP / X-Forwarded-For 里的真实来源判断；"
                           "bind 指向公网地址或本机没连局域网时直接拒绝启动。"
                           "确实要跨网段用才改成 false。"),
        "_key_说明": "设备要连本中心时用的共享密钥。建议与手机上的密钥一致。留空会拒绝启动。",
        "db": "knownote-hub.db",
        "images_dir": "images",
        "log_file": "hub.log",
        "interval_seconds": 300,
        "_interval_说明": "定时轮询间隔（秒）。中心每这么久主动连一次下面列出的设备。最小 30。",
        "poll_on_start": True,
        "devices": [
            {
                "name": "我的平板",
                "host": "192.168.1.7",
                "port": SYNC_DEFAULT_PORT,
                "_host_说明": "在手机的『局域网同步』页可以看到本机地址；设备需开着主机模式。",
                "key": "",
                "_key_说明": "留空表示与顶层 key 相同。",
                "enabled": True,
            }
        ],
    }


# ---------------------------------------------------------------------------
# 中心本体：主机接口 + 定时轮询 + 状态
# ---------------------------------------------------------------------------


class Hub:
    def __init__(self, config: HubConfig) -> None:
        self.config = config
        self.started_at = now_ms()
        self.store = HubStore(config.resolve(config.db))
        self.images = ImageDir(config.resolve(config.images_dir))
        self.engine = SyncEngine(self.store, self.images)
        self.client = HubClient()
        self.device_stats: Dict[str, dict] = {}
        self._auth_failures: Dict[str, List[float]] = {}
        self._auth_lock = threading.Lock()
        log_path = config.resolve(config.log_file) if config.log_file else None
        self._log_file = open(log_path, "a", encoding="utf-8") if log_path else None
        self._log_lock = threading.Lock()
        self.last_poll_started_at = 0
        self.last_poll_finished_at = 0
        self.polls = 0

    # ---------- 日志 ----------

    def note(self, message: str) -> None:
        log(message)
        if self._log_file is not None:
            with self._log_lock:
                self._log_file.write(
                    "%s %s\n" % (time.strftime("%Y-%m-%d %H:%M:%S"), message)
                )
                self._log_file.flush()

    # ---------- 认证节流 ----------

    def lan_check(self, peer_ip: str, headers) -> str:
        """只在局域网里同步（v1.10.1）。返回拒绝理由；放行返回空串。

        规则（两条都得满足）：
          ① 直连的对端地址必须在局域网网段（公网直连、路由器端口映射在这里被拦下）；
          ② 请求若带了代理头（CF-Connecting-IP / X-Real-IP / X-Forwarded-For），
             头里那个「真实来源」也必须在局域网网段 —— 隧道会把连接说成来自 127.0.0.1，
             真身在头里，只有这一条能挡住隧道。

        注意顺序：**先看直连地址**。公网直连的请求即便伪造代理头也过不去；
        只有直连地址本身是私有的（隧道 / 本机反代）才轮到第二代代理头来看。
        """
        if not self.config.lan_only:
            return ""
        if not is_lan_address(peer_ip):
            return "直连地址 %s 不在局域网网段" % peer_ip
        origin = claimed_origin(headers)
        if origin and not is_lan_address(origin):
            return "代理头里的真实来源 %s 不在局域网网段" % origin
        return ""

    def auth_blocked(self, remote_ip: str) -> bool:
        with self._auth_lock:
            stamps = [t for t in self._auth_failures.get(remote_ip, []) if time.time() - t < AUTH_FAILURE_WINDOW]
            self._auth_failures[remote_ip] = stamps
            return len(stamps) >= MAX_AUTH_FAILURES

    def note_auth_failure(self, remote_ip: str) -> int:
        with self._auth_lock:
            stamps = [t for t in self._auth_failures.get(remote_ip, []) if time.time() - t < AUTH_FAILURE_WINDOW]
            stamps.append(time.time())
            self._auth_failures[remote_ip] = stamps
            return len(stamps)

    # ---------- 主机接口（App 作为从机连过来时走这里） ----------

    def handle_ping(self) -> dict:
        return {"protocol": SYNC_PROTOCOL, "device_name": self.config.device_name}

    def handle_sync(self, body: SyncRequest, remote_ip: str) -> dict:
        """
        顺序与 App 的 SyncServer 完全一致：

        1. 先收图片、算出要回给对方的图片；
        2. **先取本次请求之前中心的变更**（顺序不能反，反了会把对方这次刚推上来的变更回声给它自己）；
        3. 再应用对方推来的变更（中心是中转者 → 记变更日志）。
        """
        images_received = self.engine.apply_images(body.images)
        outbound_images = self.engine.outgoing_images(set(body.images_i_have))
        outbound = self.engine.collect_changes(body.last_sync_at)
        applied = self.engine.apply_changes(body.notes, origin_device=body.device_id)

        self.store.log_sync(
            role="host",
            peer="%s (%s)" % (body.device_name, remote_ip),
            pulled=len(outbound),
            pushed=applied["changed"],
            conflicts=applied["conflicts"],
            ok=True,
            message="device %s connected, images +%d/-%d"
            % (body.device_id[:8], images_received, len(outbound_images)),
        )
        self.store.save_device_state({
            "address": "client:" + remote_ip,
            "name": body.device_name or body.device_id[:8],
            "enabled": True,
            "polls": 0,
            "failures": 0,
            "last_ok_at": now_ms(),
            "last_error": "",
            "pulled": len(outbound),
            "pushed": applied["changed"],
            "conflicts": applied["conflicts"],
        })
        self.note(
            "[host] %s@%s 接入：给它 %d 条 / 它推来 %d 条落库（冲突 %d）、图片 +%d/-%d"
            % (body.device_name, remote_ip, len(outbound), applied["changed"], applied["conflicts"],
               images_received, len(outbound_images))
        )

        return SyncResponse(
            device_id=self.config.device_id,
            device_name=self.config.device_name,
            server_time=now_ms(),
            notes=outbound,
            applied_notes=applied["changed"],
            conflicts=applied["conflicts"],
            images_i_have=sorted(self.images.names()),
            images=outbound_images,
            images_received=images_received,
        ).to_json()

    # ---------- 轮询设备（中心作为请求方，设备开着 App 内嵌主机） ----------

    def poll_device(self, device: DeviceConfig) -> dict:
        """与 App 的 SyncCoordinator 同一条流程：取增量 → 发 → 落库 → 水位线 → 日志。"""
        peer_key = device.address
        stats = self.device_stats.setdefault(
            peer_key,
            {
                "name": device.name,
                "address": peer_key,
                "enabled": device.enabled,
                "polls": 0,
                "failures": 0,
                "last_ok_at": 0,
                "last_error": "",
                "watermark": 0,
                "pulled": 0,
                "pushed": 0,
                "conflicts": 0,
                "images_pushed": 0,
                "images_pulled": 0,
            },
        )
        stats["polls"] += 1

        watermark = int(self.store.kv_get("wm:" + peer_key, "0") or 0)
        pending = self.engine.collect_changes(watermark)
        notes_to_push = len(pending)

        rounds = 0
        images_pushed = images_pulled = pulled = peer_applied = conflicts = 0
        peer_name = device.name

        while True:
            rounds += 1
            peer_has = self.store.peer_images(peer_key) or set()
            images = self.engine.outgoing_images(peer_has)
            request = SyncRequest(
                device_id=self.config.device_id,
                device_name=self.config.device_name,
                last_sync_at=watermark,
                notes=pending,
                images_i_have=sorted(self.images.names()),
                images=images,
            )
            code, text = self.client.request(
                device.host, device.port, "/sync", "POST", request.to_json(), device.key
            )
            if code is None:
                return self._fail(stats, peer_key, device, rounds, "unreachable: %s" % text)
            if code != 200:
                reason = text
                try:
                    reason = json.loads(text).get("error") or text
                except Exception:
                    pass
                return self._fail(
                    stats, peer_key, device, rounds, "HTTP %s: %s" % (code, reason.strip()[:200])
                )

            try:
                payload = json.loads(text)
                response = SyncResponse.from_json(payload)
            except Exception as error:
                return self._fail(stats, peer_key, device, rounds, "bad JSON: %s" % error)
            if int(payload.get("protocol") or 0) != SYNC_PROTOCOL:
                return self._fail(
                    stats, peer_key, device, rounds,
                    "protocol mismatch (hub %d, device %s)" % (SYNC_PROTOCOL, payload.get("protocol")),
                )

            peer_name = response.device_name or device.name
            pulled += len(response.notes)
            peer_applied += response.applied_notes
            conflicts += response.conflicts

            # 中心是中转者：收到的变更要记变更日志，否则别的设备永远拉不到
            applied = self.engine.apply_changes(response.notes, origin_device=self.config.device_id)
            conflicts += applied["conflicts"]

            images_pulled += self.engine.apply_images(response.images)
            images_pushed += response.images_received
            self.store.save_peer_images(peer_key, response.images_i_have)

            # 水位线 = min(设备时间, 中心时间)：宁可下次多要一点（重复应用是幂等的），
            # 也不因为两台机器时钟有偏差而漏掉变更。设备时间用在设备自己的时钟域里，是对的。
            watermark = min(response.server_time, now_ms())
            self.store.kv_set("wm:" + peer_key, str(watermark))

            pending = []
            # 还有图没传完就再来一轮：单次请求有张数/字节两道闸。
            # App 的客户端只按「对端缺我的图」续轮，所以 App↔App 拉一堆图要再点一次同步；
            # 中心是常驻服务器，顺手把「我还缺对端的图」也续上 —— 一次会话把图片补齐。
            peer_missing_mine = bool(self.images.names() - set(response.images_i_have))
            mine_missing_from_peer = bool(set(response.images_i_have) - self.images.names())
            if rounds >= MAX_IMAGE_ROUNDS:
                break
            if not peer_missing_mine and not mine_missing_from_peer:
                break

        stats.update(
            {
                "failures": 0,
                "last_error": "",
                "last_ok_at": now_ms(),
                "watermark": watermark,
                "pulled": stats["pulled"] + pulled,
                "pushed": stats["pushed"] + peer_applied,
                "conflicts": stats["conflicts"] + conflicts,
                "images_pushed": stats["images_pushed"] + images_pushed,
                "images_pulled": stats["images_pulled"] + images_pulled,
            }
        )
        self.store.save_device_state(stats)
        self.store.log_sync(
            role="poll",
            peer="%s (%s)" % (peer_name, peer_key),
            pulled=pulled,
            pushed=peer_applied,
            conflicts=conflicts,
            ok=True,
            message="images +%d/-%d in %d round(s)" % (images_pulled, images_pushed, rounds),
        )
        self.note(
            "[poll] %s 拉 %d 条 / 推 %d 条落库（冲突 %d）、图片 +%d/-%d、%d 轮"
            % (device.label, pulled, peer_applied, conflicts, images_pulled, images_pushed, rounds)
        )
        return {"ok": True, "device": device.label, "pulled": pulled, "pushed": peer_applied,
                "rounds": rounds, "images_pulled": images_pulled, "images_pushed": images_pushed}

    def _fail(self, stats: dict, peer_key: str, device: DeviceConfig, rounds: int, reason: str) -> dict:
        stats["failures"] = stats.get("failures", 0) + 1
        stats["last_error"] = reason
        self.store.save_device_state(stats)
        self.store.log_sync(
            role="poll", peer="%s (%s)" % (device.name, peer_key), ok=False,
            message="%s (round %d)" % (reason, rounds),
        )
        self.note("[poll] %s 同步失败：%s" % (device.label, reason))
        return {"ok": False, "device": device.label, "error": reason}

    def poll_all(self) -> List[dict]:
        self.last_poll_started_at = now_ms()
        results = []
        for device in self.config.devices:
            if not device.enabled:
                continue
            try:
                results.append(self.poll_device(device))
            except Exception as error:  # 单台设备出问题不能拖垮整轮
                results.append({"ok": False, "device": device.label, "error": repr(error)})
                self.note("[poll] %s 异常：%r" % (device.label, error))
        self.last_poll_finished_at = now_ms()
        self.polls += 1
        return results

    def check_devices(self) -> None:
        """--check：只测连通性，不改任何数据（顺带看密钥对不对）。"""
        if not self.config.devices:
            self.note("配置里没有任何设备（devices 为空）")
            return
        for device in self.config.devices:
            code, text = self.client.request(device.host, device.port, "/ping", "GET")
            if code is None:
                self.note("[check] %s ✗ 连不上（%s）—— 设备是否开机、同一 Wi-Fi、App 里开着主机模式？"
                          % (device.label, text))
                continue
            if code != 200:
                self.note("[check] %s ✗ HTTP %s %s" % (device.label, code, text.strip()[:120]))
                continue
            try:
                payload = json.loads(text)
            except Exception:
                payload = {}
            protocol = payload.get("protocol")
            if protocol != SYNC_PROTOCOL:
                self.note("[check] %s ✓ 通，但协议版本是 %s（中心要求 %d）—— 两边 App 版本不一致"
                          % (device.label, protocol, SYNC_PROTOCOL))
                continue
            self.note("[check] %s ✓ 通（对端自称 %s，协议 %d）"
                      % (device.label, payload.get("device_name"), protocol))

    # ---------- 状态 ----------

    def status(self) -> dict:
        counts = self.store.counts()
        persisted = self.store.device_states()
        devices = []
        for device in self.config.devices:
            merged = dict(persisted.get(device.address, {}))
            merged.update(self.device_stats.get(device.address, {}))
            merged.setdefault("address", device.address)
            merged.setdefault("name", device.name)
            devices.append(merged)
        return {
            "protocol": SYNC_PROTOCOL,
            "device_id": self.config.device_id,
            "device_name": self.config.device_name,
            "lan_only": self.config.lan_only,
            "db": self.store.db_path,
            "images_dir": self.images.path,
            "started_at": self.started_at,
            "uptime_seconds": int((now_ms() - self.started_at) / 1000),
            "interval_seconds": self.config.interval_seconds,
            "polls": self.polls,
            "last_poll_started_at": self.last_poll_started_at,
            "last_poll_finished_at": self.last_poll_finished_at,
            "images": len(self.images.names()),
            "counts": counts,
            "devices": devices,
            "sync_log": self.store.recent_log(10),
        }


# ---------------------------------------------------------------------------
# HTTP 主机（App 从这里进来）
# ---------------------------------------------------------------------------


class HubRequestHandler(BaseHTTPRequestHandler):
    protocol_version = "HTTP/1.1"
    server_version = "KnowNoteHub/1.0"

    hub: Hub  # 由 make_server 注入

    def log_message(self, fmt, *args):  # 访问日志由 Hub 自己记（带中文摘要）
        return

    # ---- 工具 ----

    def _send_json(self, code: int, payload: dict, extra_headers: Optional[dict] = None) -> None:
        body = json.dumps(payload, ensure_ascii=False).encode("utf-8")
        self.send_response(code)
        self.send_header("Content-Type", "application/json; charset=utf-8")
        self.send_header("Content-Length", str(len(body)))
        for key, value in (extra_headers or {}).items():
            self.send_header(key, value)
        self.send_header("Connection", "close")
        self.end_headers()
        self.close_connection = True  # 一次请求一条连接：客户端（App）与手写实现都最好懂
        try:
            self.wfile.write(body)
        except Exception:
            pass

    def _error(self, code: int, message: str, extra_headers: Optional[dict] = None) -> None:
        self._send_json(code, {"error": message}, extra_headers)

    def _read_body(self) -> Tuple[Optional[str], Optional[int]]:
        """返回 (正文, 错误码)。**无论走哪条分支都要把请求体读完** ——
        拒绝（401/429/404）时若不读，客户端还在写正文而我们已经关连接，
        对端看到的会是「连接被中止」而不是那句清楚的原因。"""
        try:
            length = int(self.headers.get("Content-Length") or 0)
        except ValueError:
            return None, 400
        if length < 0:
            return None, 400
        if length > MAX_BODY_BYTES:
            return None, 413
        if length == 0:
            return "", None
        data = b""
        while len(data) < length:  # 按 Content-Length 精确读满，不假设一次读完
            chunk = self.rfile.read(length - len(data))
            if not chunk:
                break
            data += chunk
        try:
            return data.decode("utf-8"), None
        except UnicodeDecodeError:
            return None, 400

    def _presented_key(self) -> str:
        return self.headers.get("X-KnowNote-Key") or ""

    def _auth_ok(self) -> bool:
        key = self.hub.config.key
        if not key:
            return False
        return hmac.compare_digest(self._presented_key(), key)

    # ---- 路由 ----

    def do_GET(self) -> None:
        self._route("GET")

    def do_POST(self) -> None:
        self._route("POST")

    def _route(self, method: str) -> None:
        path = self.path.split("?", 1)[0].rstrip("/") or "/"

        # 先把请求体读完（含被拒绝的情况，理由见 _read_body 的注释）
        raw, body_error = self._read_body()
        if body_error is not None:
            self._error(body_error, "Request body too large or malformed")
            return

        # 局域网限制（v1.10.1）：不在局域网的来源一律挡在门外，/ping 也不例外 ——
        # 连「这台服务在不在」都不告诉外网。放在最前面，也就不占用错密钥的节流计数。
        remote_ip = self.client_address[0] if self.client_address else "?"
        blocked = self.hub.lan_check(remote_ip, self.headers)
        if blocked:
            self.hub.note("[warn] 拒绝局域网外的请求：%s（%s %s）" % (blocked, method, path))
            self._error(403, "Refusing requests from outside the local network: %s" % blocked)
            return

        if method == "GET" and path == "/ping":
            # 不需要密钥：用来把「地址填错 / 主机没开」和「密钥不对」区分开
            self._send_json(200, self.hub.handle_ping())
            return

        if self.hub.auth_blocked(remote_ip):
            self._error(429, "Too many failed attempts; try again later",
                        {"Retry-After": str(int(AUTH_FAILURE_WINDOW))})
            return

        if not self._auth_ok():
            failures = self.hub.note_auth_failure(remote_ip)
            self.hub.note("[warn] %s 出示的共享密钥不匹配（第 %d 次），已拒绝" % (remote_ip, failures))
            self._error(401, "Shared key mismatch")
            return

        if method == "GET" and path == "/status":
            self._send_json(200, self.hub.status())
            return

        if method == "POST" and path == "/sync":
            if not raw:
                self._error(400, "Request body is not valid JSON")
                return
            try:
                payload = json.loads(raw)
            except Exception:
                self._error(400, "Request body is not valid JSON")
                return
            if not isinstance(payload, dict):
                self._error(400, "Request body is not valid JSON")
                return
            if int(payload.get("protocol") or 0) != SYNC_PROTOCOL:
                self._error(426, "Protocol version mismatch; update both devices to the same version")
                return
            self._send_json(200, self.hub.handle_sync(SyncRequest.from_json(payload), remote_ip))
            return

        self._error(404, "No such endpoint")


class HubHttpServer(ThreadingHTTPServer):
    daemon_threads = True
    allow_reuse_address = True


def make_server(hub: Hub) -> HubHttpServer:
    handler = type("BoundHubHandler", (HubRequestHandler,), {"hub": hub})
    return HubHttpServer((hub.config.bind, hub.config.port), handler)


# ---------------------------------------------------------------------------
# 定时轮询（服务器侧的「定时自动同步」）
# ---------------------------------------------------------------------------


class PollScheduler(threading.Thread):
    def __init__(self, hub: Hub, stop_event: threading.Event) -> None:
        super().__init__(name="knownote-poller", daemon=True)
        self.hub = hub
        self.stop_event = stop_event

    def run(self) -> None:
        interval = self.hub.config.interval_seconds
        if self.hub.config.poll_on_start:
            if self.hub.config.devices:
                self.hub.note("[poll] 启动后先跑一轮（间隔 %d 秒）" % interval)
                self.hub.poll_all()
            else:
                self.hub.note("[poll] 未配置设备：只当主机用（App 里填本机地址 + 密钥即可同步）")
        while not self.stop_event.wait(interval):
            try:
                self.hub.poll_all()
            except Exception as error:
                self.hub.note("[poll] 轮询异常：%r" % error)


# ---------------------------------------------------------------------------
# 命令行
# ---------------------------------------------------------------------------


def build_parser() -> argparse.ArgumentParser:
    parser = argparse.ArgumentParser(
        description="KnowNote 局域网同步中心（可跑在 Termux）：既当主机，也按固定间隔主动去同步各台设备。",
        formatter_class=argparse.RawDescriptionHelpFormatter,
        epilog="示例：\n"
               "  python knownote_hub.py --init-config hub.conf.json\n"
               "  python knownote_hub.py --config hub.conf.json\n"
               "  python knownote_hub.py --config hub.conf.json --check\n"
               "  python knownote_hub.py --config hub.conf.json --once\n",
    )
    parser.add_argument("--config", "-c", default="hub.conf.json", help="配置文件（默认 hub.conf.json）")
    parser.add_argument("--init-config", metavar="PATH", help="生成一份带随机密钥的配置样例后退出")
    parser.add_argument("--print-key", action="store_true", help="打印配置里的共享密钥后退出")
    parser.add_argument("--set-key", metavar="KEY", help="改共享密钥（写回配置文件）后退出")
    parser.add_argument("--once", action="store_true", help="只轮询一轮就退出（配合 cron / termux-job-scheduler）")
    parser.add_argument("--no-poll", action="store_true", help="只当主机，不主动连设备")
    parser.add_argument("--no-serve", action="store_true", help="只轮询，不开主机接口")
    parser.add_argument("--check", action="store_true", help="测每台设备是否可达后退出")
    parser.add_argument("--port", type=int, help="覆盖配置里的监听端口")
    parser.add_argument("--interval", type=int, help="覆盖配置里的轮询间隔（秒，最小 30）")
    parser.add_argument("--status", action="store_true", help="打印一份状态摘要后退出")
    return parser


def cmd_init_config(path: str) -> int:
    if os.path.exists(path):
        log("配置文件已存在，未覆盖：%s" % path)
        return 1
    with open(path, "w", encoding="utf-8") as fh:
        json.dump(example_config(), fh, ensure_ascii=False, indent=2)
        fh.write("\n")
    log("已生成配置：%s" % path)
    log("共享密钥：%s   ← 手机『局域网同步』页填同一个（也可以直接在那边生成后抄过来）" % json.load(open(path, encoding="utf-8"))["key"])
    log("接下来：把 devices 里那台手机的地址改成真实地址，然后 python knownote_hub.py --config %s" % path)
    return 0


def cmd_status(hub: Hub) -> int:
    counts = hub.store.counts()
    log("设备名：%s   标识：%s" % (hub.config.device_name, hub.config.device_id))
    log("访问限制：%s" % ("只服务局域网来源（lan_only=true）" if hub.config.lan_only
                       else "未限制来源网段（lan_only=false）"))
    log(
        "笔记 %d 条 / 回收站 %d 条 / 墓碑 %d 条 / 变更日志 %d 条；图片 %d 张"
        % (counts["notes"], counts["trashed"], counts["tombstones"], counts["change_log"],
           len(hub.images.names()))
    )
    persisted = hub.store.device_states()
    configured = set()
    for device in hub.config.devices:
        configured.add(device.address)
        log("设备 %s：%s" % (device.label, describe_device(persisted.get(device.address, {}))))
    for address, stats in sorted(persisted.items()):
        # 主机角色接待过的客户端（对方主动连过来，不在 devices 配置里）
        if address in configured or not address.startswith("client:"):
            continue
        log("曾接入 %s（%s）：%s" % (stats.get("name") or "?", address.split(":", 1)[1],
                                     describe_device(stats)))
    for row in hub.store.recent_log(5):
        log("日志 %s %s 拉%s 推%s 冲突%s %s" % (
            time.strftime("%Y-%m-%d %H:%M:%S", time.localtime(row["at"] / 1000.0)),
            row["role"], row["pulled"], row["pushed"], row["conflicts"],
            row["message"] or ("ok" if row["ok"] else "failed"),
        ))
    return 0


def describe_device(stats: dict) -> str:
    """一行说清某台设备的同步状况（--status 用）。"""
    if not stats:
        return "还没成功同步过"
    last_ok = int(stats.get("last_ok_at") or 0)
    if not last_ok:
        text = "还没成功同步过"
    else:
        text = "已同步过（%s，拉 %d / 推 %d）" % (
            time.strftime("%Y-%m-%d %H:%M", time.localtime(last_ok / 1000.0)),
            int(stats.get("pulled") or 0), int(stats.get("pushed") or 0),
        )
    if stats.get("last_error"):
        text += "；上次错误：%s" % stats["last_error"]
    return text


def main(argv: Optional[Sequence[str]] = None) -> int:
    args = build_parser().parse_args(argv)

    if args.init_config:
        return cmd_init_config(args.init_config)

    config_path = args.config
    if not os.path.exists(config_path):
        log("找不到配置文件 %s；先跑 python knownote_hub.py --init-config %s" % (config_path, config_path))
        return 2

    try:
        config = HubConfig.load(config_path)
    except Exception as error:
        log("配置读取失败：%r" % error)
        return 2

    if args.port:
        config.port = args.port
    if args.interval:
        config.interval_seconds = max(30, args.interval)

    if args.print_key:
        print(config.key)
        return 0
    if args.set_key:
        raw = json.load(open(config_path, encoding="utf-8"))
        raw["key"] = args.set_key
        with open(config_path, "w", encoding="utf-8") as fh:
            json.dump(raw, fh, ensure_ascii=False, indent=2)
            fh.write("\n")
        log("已更新 %s 里的共享密钥（记得手机上也要改成同一个）" % config_path)
        return 0

    if not config.key:
        log("拒绝启动：没有设置共享密钥。")
        log("一个谁都能连上、把全部笔记读走/改掉的服务，比『忘记开同步』危险得多 —— 与 App 主机模式同一条规矩。")
        log("跑 python knownote_hub.py --init-config %s 生成一个，或用 --set-key 设置。" % config_path)
        return 2

    problem = config.lan_bind_problem()
    if problem:
        log("拒绝启动：%s" % problem)
        log("中心默认只在局域网里服务（配置项 lan_only，默认 true）：来源网段不对的请求一律 403，"
            "经隧道/反代进来的还会按 CF-Connecting-IP / X-Forwarded-For 里的真实来源判断。")
        log("先确认这台机器连的是家里/公司的局域网；确实要跨网段用（比如走隧道），"
            "就在配置里写 \"lan_only\": false —— 但那时只剩共享密钥把关了。")
        return 2

    hub = Hub(config)

    if args.check:
        hub.check_devices()
        return 0
    if args.status:
        return cmd_status(hub)
    if args.once:
        hub.note("[poll] 单轮模式：先跑一轮再退出")
        results = hub.poll_all()
        failed = [r for r in results if not r.get("ok")]
        return 1 if failed else 0

    httpd = None
    if not args.no_serve:
        try:
            httpd = make_server(hub)
        except OSError as error:
            log("监听 %s:%d 失败：%s" % (config.bind, config.port, error))
            return 2

    stop_event = threading.Event()
    if not args.no_poll:
        PollScheduler(hub, stop_event).start()

    hub.note("=" * 62)
    hub.note("KnowNote 同步中心已启动")
    hub.note("  设备名 / 标识：%s / %s" % (config.device_name, config.device_id))
    hub.note("  共享密钥：%s" % config.key)
    hub.note("  访问限制：%s" % ("只服务局域网来源（lan_only=true）" if config.lan_only
                              else "未限制来源网段（lan_only=false）"))
    if httpd is not None:
        for address in local_ipv4_addresses():
            hub.note("  主机地址（手机里填这个）：http://%s:%d" % (address, config.port))
    else:
        hub.note("  主机接口：未开启（--no-serve）")
    hub.note("  数据库：%s   图片：%s" % (hub.store.db_path, hub.images.path))
    hub.note("  数据：笔记 %d 条、墓碑 %d 条、变更 %d 条"
             % (hub.store.counts()["notes"], hub.store.counts()["tombstones"], hub.store.counts()["change_log"]))
    if args.no_poll:
        hub.note("  定时轮询：未开启（--no-poll）")
    else:
        hub.note("  定时轮询：每 %d 秒一次，共 %d 台设备%s"
                 % (config.interval_seconds, len(config.devices),
                    "" if config.devices else "（配置里没有设备）"))
    hub.note("=" * 62)

    try:
        if httpd is not None:
            httpd.serve_forever()
        else:  # --no-serve：只轮询，主线程等 Ctrl+C
            while True:
                time.sleep(1)
    except KeyboardInterrupt:
        hub.note("收到 Ctrl+C，正在退出…")
    finally:
        stop_event.set()
        if httpd is not None:
            httpd.shutdown()
            httpd.server_close()
        hub.store.close()
    return 0


def local_ipv4_addresses() -> List[str]:
    """本机在局域网里的 IPv4 地址（给用户填到手机里）。不联网也能算出来。"""
    import socket

    addresses: List[str] = []
    try:
        hostname = socket.gethostname()
        for info in socket.getaddrinfo(hostname, None, socket.AF_INET):
            address = info[4][0]
            if address not in addresses:
                addresses.append(address)
    except Exception:
        pass
    try:  # 兜底：连一下外网地址（不会真的发包）拿本机出口 IP
        probe = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
        probe.connect(("8.8.8.8", 80))
        address = probe.getsockname()[0]
        probe.close()
        if address not in addresses:
            addresses.append(address)
    except Exception:
        pass
    return [a for a in addresses if not a.startswith("127.")]


if __name__ == "__main__":
    sys.exit(main())
