#!/usr/bin/env bash
# 公开仓库前的历史脱敏。
#
# 背景（两处都真实踩过）：
#   1. 真机截图（lg-shots/、demo-shots/）里有同步共享密钥、设备标识（android-xxxx）、
#      局域网地址，还有本机笔记内容；
#   2. lg-backup/ 下的 knownotes.db 是**真机数据库备份**，含笔记正文，从 v1.1.0 起误入库。
# 只删工作区没用 —— git 历史里照样拿得到，所以这里重写历史。
#
# 注意：本脚本**不写入任何真实密钥/设备标识**，验证只用通用模式匹配。
#
# 用法：bash tools/sanitize-history.sh   （跑完看输出，确认无误再 push --force）
set -euo pipefail
cd "$(dirname "$0")/.."

echo "== 0. 本地备份分支（要回滚就 git reset --hard backup/pre-sanitize）=="
git rev-parse --verify backup/pre-sanitize >/dev/null 2>&1 \
  && echo "   备份分支已存在" \
  || { git branch backup/pre-sanitize; echo "   已建 backup/pre-sanitize"; }

echo "== 1. 重写历史：删除截图/数据库备份 + README 型号泛化 =="
FILTER_BRANCH_SQUELCH_WARNING=1 git filter-branch -f --tree-filter '
  rm -rf lg-shots demo-shots lg-backup seed
  find . -maxdepth 3 \( -name "*.db" -o -name "*.db-wal" -o -name "*.db-shm" \) -print0 2>/dev/null | xargs -0 -r rm -f
  if [ -f README.md ]; then
    sed -i "s/LGE LM-G820/Android 13 真机/g; s/LM-G820/Android 13 真机/g; s/LGE /LG /g" README.md
  fi
  true
' --tag-name-filter cat -- --all

echo "== 2. 丢弃旧引用与对象 =="
git for-each-ref --format='%(refname)' refs/original/ | xargs -r -n1 git update-ref -d
git reflog expire --expire=now --all
git gc --prune=now >/dev/null

echo "== 3. 验证 =="
left_shots=$(git log --all --name-only --pretty=format: | grep -cE "^(lg-shots|demo-shots)/" || true)
echo "   历史中截图文件引用数：$left_shots  （应为 0）"

left_dbs=$(git log --all --name-only --pretty=format: | grep -cE '\.db(-wal|-shm)?$|^lg-backup/|^seed/' || true)
echo "   历史中数据库文件引用数：$left_dbs  （应为 0）"

PATTERN='android-[0-9a-f]{8,}|10\.[0-9]{1,3}\.[0-9]{1,3}\.[0-9]{1,3}|192\.168\.[0-9]{1,3}\.[0-9]{1,3}'
bad=0
while read -r c; do
  hits=$(git grep -I -l -E "$PATTERN" "$c" -- 2>/dev/null | grep -v "192.168.1.20" || true)
  if [ -n "$hits" ]; then
    echo "   !! $c 仍含敏感模式：$hits"
    bad=$((bad + 1))
  fi
done < <(git rev-list --all)
echo "   含敏感模式的提交数：$bad  （应为 0）"

echo "== 完成。检查无误后再（**不要用 --all**，那会连带推送备份分支，里面有带密钥的旧文件）："
echo "     git push --force origin main && git push --force --tags"
