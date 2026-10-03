#!/usr/bin/env bash
# CI 与本地共用的 E2E 入口：起后端+模拟器 → 跑 Playwright → 清理。
#
# 为什么不把这段直接写进 ci.yml：GitHub Actions 的 run 块是 YAML 里的多行字符串，
# 出错时行号对不上源文件，排查成本高；而且本地要复现 CI 的完整链路时，
# 复制粘贴那段 YAML 很脆。抽成脚本后 `bash e2e/ci-run.sh` 与 CI 跑的是同一份代码。
set -uo pipefail

BACKEND_URL="${AF_BACKEND_ORIGIN:-http://127.0.0.1:18099}"
WAIT_SECONDS="${AF_READY_TIMEOUT:-90}"

node e2e/start-backend.mjs &
BACKEND_PID=$!
# EXIT trap 覆盖三种退出：测试失败、被 cancel、以及正常结束——
# 后端与模拟器是本脚本的子进程，不 trap 就会在 runner 上留孤儿进程占端口。
trap 'kill "$BACKEND_PID" 2>/dev/null || true' EXIT

ready=0
for _ in $(seq 1 "$WAIT_SECONDS"); do
  if curl -sf "$BACKEND_URL/api/v1/drones" > /dev/null; then
    ready=1
    break
  fi
  # 编排进程先死 = 后端没起来，再等也是白等，直接把日志尾部交出去
  if ! kill -0 "$BACKEND_PID" 2>/dev/null; then
    echo "::error::编排进程提前退出（后端可能未启动），日志尾部："
    tail -40 .e2e-logs/backend.log 2>/dev/null || true
    exit 1
  fi
  sleep 1
done

if [ "$ready" != "1" ]; then
  echo "::error::后端 ${WAIT_SECONDS}s 未就绪，日志尾部："
  tail -40 .e2e-logs/backend.log 2>/dev/null || true
  exit 1
fi

AF_BACKEND_ORIGIN="$BACKEND_URL" npx playwright test