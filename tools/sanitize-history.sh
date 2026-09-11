#!/usr/bin/env bash
# 公开仓库前的历史脱敏。
#
# 背景：开发过程的真机截图（lg-shots/、demo-shots/）里有同步共享密钥、设备标识（android-xxxx）、
# 局域网地址，还有本机笔记内容 —— 只删当前工作区没用，git 历史里仍然拿得到。
# 所以这里重写历史：把两个截图目录从所有提交里摘掉，并把 README 里的真机型号泛化。
#
# 注意：本脚本**不写入任何真实密钥/设备标识**，验证只用通用模式匹配。
#
# 用法：bash tools/sanitize-history.sh   （跑完自己看输出，确认无误再 push --force）
set -euo pipefail
cd "$(dirname "$0")/.."

echo "== 0. 本地备份分支（要回滚就 git reset --hard backup/pre-sanitize）=="
git rev-parse --verify backup/pre-sanitize >/dev/null 2>&1 \
  && echo "   备份分支已存在" \
  || { git branch backup/pre-sanitize; echo "   已建 backup/pre-sanitize"; }

echo "== 1. 重写历史：删除截图目录 + README 型号泛化 =="
FILTER_BRANCH_SQUELCH_WARNING=1 git filter-branch -f --tree-filter '
  rm -rf lg-shots demo-shots
  if [ -f README.md ]; then
    sed -i "s/LGE LM-G820/Android 13 真机/g; s/LM-G820/Android 13 真机/g; s/LGE /LG /g" README.md
  fi
  true
' --tag-name-filter cat -- --all

echo "== 2. 丢弃旧引用与对象 =="
git for-each-ref --format='%(refname)' refs/original/ | xargs -r -n1 git update-ref -d
git reflog expire --expire=now --all
git gc --prune=now >/dev/null

echo "== 3. 验证：历史里是否还有截图 / 通用敏感模式 =="
left_shots=$(git log --all --name-only --pretty=format: | grep -cE "^(lg-shots|demo-shots)/" || true)
echo "   历史中截图文件引用数：$left_shots  （应为 0）"

# 通用模式：设备标识 android-<hex>、内网 IPv4（不含示例用的 192.168.1.20）
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

echo "== 完成。检查无误后再： git push --force --all && git push --force --tags =="
