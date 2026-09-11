#!/usr/bin/env python3
"""给 KnowNote 灌一批演示数据（直接改 SQLite 库文件）。

用途：快速把应用填满内容，方便看界面 / 验证检索 / 演示导出。

流程：
  1. 先安装并启动一次 App（让它建好库与表）
  2. adb shell am force-stop <pkg>
  3. adb exec-out run-as <pkg> cat databases/knownotes.db > seed.db
  4. python tools/seed-demo-db.py seed.db
  5. adb push seed.db /data/local/tmp/knownote-seed.db
     adb shell run-as <pkg> rm -f databases/knownotes.db-wal databases/knownotes.db-shm
     adb shell run-as <pkg> cp /data/local/tmp/knownote-seed.db databases/knownotes.db
  6. 重启 App

刻意不写 notes_fts：App 启动时发现索引条数与在线笔记数不一致会自动重建，
顺带验证 FtsStore.repair() 的自愈逻辑。
"""

import os
import sqlite3
import sys
import time

GROUPS = ["Java 后端", "Android", "数据库"]

# (标题, 正文, 分组名或 None, [标签])
NOTES = [
    (
        "HashMap 扩容为什么是 2 的幂",
        "容量保持 2 的幂，可以让 (n-1) & hash 等价于取模，把取模换成位运算。\n"
        "扩容时会重新分配桶；JDK8 之后用高低位链表拆分，避免重算 hash。\n"
        "面试追问点：为什么负载因子默认 0.75 —— 空间与冲突概率的折中。",
        "Java 后端",
        ["面试常考", "集合"],
    ),
    (
        "synchronized 锁升级过程",
        "无锁 → 偏向锁 → 轻量级锁（自旋）→ 重量级锁。\n"
        "偏向锁在 JDK15 之后默认关闭，因为撤销成本高、现代应用竞争激烈。\n"
        "锁的是对象头 Mark Word，不是代码块本身。",
        "Java 后端",
        ["并发", "面试常考"],
    ),
    (
        "SQLite 全文检索：FTS5 与 FTS4 的区别",
        "FTS5 支持 bm25() 相关度排序、更好的前缀与短语查询语法。\n"
        "但 Android 系统自带的 SQLite 并不保证编译 FTS5：实测 Android 15（SQLite 3.44.3）\n"
        "只有 FTS4，报错 no such module: fts5。\n"
        "另一个坑：FTS4 的标准查询语法不支持显式 AND，多词要用空格隐式 AND。",
        "数据库",
        ["索引", "待验证", "SQLite"],
    ),
    (
        "中文分词：为什么 unicode61 不适合",
        "SQLite 的 unicode61 把连续汉字当成一个 token，所以「检索」搜不到「全文检索」。\n"
        "解决思路：写入索引前把 CJK 逐字拆开，查询时用 phrase（\"检 索\"）保证位置相邻，\n"
        "就能得到真正的子串命中，且不需要集成 jieba 这类外部分词器。",
        "数据库",
        ["索引", "中文分词"],
    ),
    (
        "Compose 的 remember 与 rememberSaveable",
        "remember：重组之间保留，进程被杀就丢。\n"
        "rememberSaveable：走 Bundle 保存，能扛住配置变更与进程重建，但要求值可序列化。\n"
        "容易踩的坑：把大对象塞进 rememberSaveable 会触发 TransactionTooLargeException。",
        "Android",
        ["Compose", "Android"],
    ),
    (
        "Room 的 @Relation 与 Junction",
        "多对多需要用 Junction 指定中间表，并且中间表要有联合主键 + 两个外键。\n"
        "查询必须加 @Transaction，否则关系查询会走两次独立查询，可能读到不一致的快照。",
        "Android",
        ["Room", "Android"],
    ),
    (
        "增量同步的冲突裁决",
        "时间戳优先：以 updated_at 较新者为准，简单可靠但会丢字段级修改。\n"
        "字段级合并更准，代价是需要保存每次变更的字段集合。\n"
        "初期建议先上时间戳优先，把冲突标记出来让人工兜底。",
        "Java 后端",
        ["待验证", "同步"],
    ),
    (
        "gradle wrapper 在国内的坑",
        "wrapper 会去 services.gradle.org 校验发行包，国内直连常常超时导致 BUILD FAILED。\n"
        "解决办法：生成时指定国内镜像\n"
        "gradle wrapper --gradle-distribution-url https://mirrors.huaweicloud.com/gradle/gradle-8.13-bin.zip",
        "Android",
        ["构建"],
    ),
]


def main(path: str) -> None:
    db = sqlite3.connect(path)
    cur = db.cursor()
    now = int(time.time() * 1000)

    def upsert_group(name: str) -> int:
        cur.execute("SELECT id FROM groups WHERE name = ?", (name,))
        row = cur.fetchone()
        if row:
            return row[0]
        cur.execute(
            "INSERT INTO groups(name, sort_order) VALUES(?, ?)",
            (name, GROUPS.index(name) + 1),
        )
        return cur.lastrowid

    def upsert_tag(name: str) -> int:
        cur.execute("SELECT id FROM tags WHERE name = ?", (name,))
        row = cur.fetchone()
        if row:
            return row[0]
        cur.execute("INSERT INTO tags(name) VALUES(?)", (name,))
        return cur.lastrowid

    group_ids = {name: upsert_group(name) for name in GROUPS}

    for index, (title, content, group_name, tags) in enumerate(NOTES):
        created = now - (len(NOTES) - index) * 3_600_000
        # guid 是 v3 起的跨设备身份，带唯一索引 —— 手写数据必须自己生成，
        # 否则多条空 guid 会被唯一索引直接拒绝（scripts 第一次跑就踩到）
        guid = os.urandom(16).hex()
        cur.execute(
            "INSERT INTO notes(title, content, group_id, created_at, updated_at, is_deleted, guid, is_purged) "
            "VALUES(?, ?, ?, ?, ?, 0, ?, 0)",
            (
                title,
                content,
                group_ids[group_name] if group_name else None,
                created,
                created,
                guid,
            ),
        )
        note_id = cur.lastrowid
        for tag in tags:
            cur.execute(
                "INSERT OR IGNORE INTO note_tags(note_id, tag_id) VALUES(?, ?)",
                (note_id, upsert_tag(tag)),
            )

    db.commit()

    def count_of(table: str) -> str:
        # FTS 虚拟表可能尚未建立（例如设备不支持任何全文引擎），不作为错误
        try:
            return str(cur.execute(f"SELECT count(*) FROM {table}").fetchone()[0])
        except sqlite3.OperationalError:
            return "表不存在"

    counts = {
        "notes": count_of("notes"),
        "tags": count_of("tags"),
        "groups": count_of("groups"),
        "note_tags": count_of("note_tags"),
        "notes_fts(故意留空)": count_of("notes_fts"),
    }
    db.close()
    print("灌数据完成：", counts)


if __name__ == "__main__":
    if len(sys.argv) != 2:
        print(__doc__)
        raise SystemExit(2)
    main(sys.argv[1])
