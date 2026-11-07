#!/usr/bin/env bash
# 把迁移前快照 a769548（PR #64 的 parent，Views 时代）的 TV 源码物化到 legacy-snapshot/。
# CI 里用 actions/checkout path=legacy-snapshot ref=a769548 等价替代本脚本；本地构建跑它一次即可。
# 快照与当前 master 的关系：TV UI 主屏（MainActivity/tvhome）用旧版，:shared 用主仓当前版（协议一致）。
set -euo pipefail
cd "$(dirname "$0")"
rm -rf legacy-snapshot
mkdir -p legacy-snapshot
git -C .. archive a769548437a1794bf5b3329904faddd50a654f20 android/tv | tar -x -C legacy-snapshot
echo "OK: $(find legacy-snapshot -type f | wc -l) files materialized"
