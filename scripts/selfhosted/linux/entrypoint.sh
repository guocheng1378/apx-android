#!/bin/bash
# GitHub Actions Self-Hosted Runner 启动脚本（Linux）
#
# 环境变量（必填）：
#   REPO_URL      https://github.com/<owner>/<repo>
#   RUNNER_TOKEN  从 GitHub repo → Settings → Actions → Runners → New self-hosted runner
#                 页面生成的 token（1 小时有效，detached 模式一次性）
# 环境变量（可选）：
#   RUNNER_NAME   runner 显示名（默认 apxpc-linux-<hostname>）
#   RUNNER_LABELS runner labels，逗号分隔（默认 apxpc-linux,x64,linux）
#   RUNNER_WORK   work 目录（默认 /home/runner/work，可挂 volume 持久化）
#
# 容器生命周期：每次 docker run 都要新 token；如果容器重启频繁，
# 用 GitHub PAT + REST API /repos/{owner}/{repo}/actions/runners/registration-token
# 可以程序化获取，见 scripts/selfhosted/linux/refresh_token.py。

set -euo pipefail

ACTION_RUNNER_DIR="/opt/actions-runner"
cd "$ACTION_RUNNER_DIR"

if [[ -z "${REPO_URL:-}" || -z "${RUNNER_TOKEN:-}" ]]; then
    echo "❌ 缺少 REPO_URL 或 RUNNER_TOKEN 环境变量"
    echo "   正确用法：docker run -e REPO_URL=... -e RUNNER_TOKEN=... apxpc-linux-runner"
    exit 1
fi

RUNNER_NAME="${RUNNER_NAME:-apxpc-linux-$(hostname)}"
RUNNER_LABELS="${RUNNER_LABELS:-apxpc-linux,x64,linux}"
RUNNER_WORK="${RUNNER_WORK:-/home/runner/work}"

echo "============================================"
echo " AllPeriph Linux Self-Hosted Runner"
echo " Repo:   $REPO_URL"
echo " Name:   $RUNNER_NAME"
echo " Labels: $RUNNER_LABELS"
echo " Work:   $RUNNER_WORK"
echo "============================================"

# 如果已经有 .runner（容器用了持久化 work volume，且之前注册过），先 unconfig
if [[ -f ".runner" ]]; then
    echo "检测到已存在的 .runner 文件，先注销..."
    ./config.sh remove --token "$RUNNER_TOKEN" 2>/dev/null || true
fi

echo "注册 runner..."
# --unattached：detached 模式，token 用完就失效；--runasservice 在容器里不适用
./config.sh \
    --url "$REPO_URL" \
    --token "$RUNNER_TOKEN" \
    --name "$RUNNER_NAME" \
    --labels "$RUNNER_LABELS" \
    --work "$RUNNER_WORK" \
    --ephemeral \
    --replace

if [[ $? -ne 0 ]]; then
    echo "❌ Runner 注册失败（token 可能过期；回 https://github.com/.../settings/actions/runners/new 再拿一个）"
    exit 1
fi

echo "✅ 注册成功，开始 run..."
# run.sh 前台阻塞；容器 restart=always 时 runner 崩了会自动重启
exec ./run.sh
