#!/usr/bin/env bash
# 守卫：maven 本地仓库的缓存键必须在所有 workflow 里同源。
#
# 为什么需要它：缓存键出现在 ci.yml 的 9 个 job 里（maven-warm / java / gcs-e2e / e2e-smoke /
# e2e-capability / integration / sdk-integration / security-scan / codeql）。actions/cache 的语义
# 是「同一 key 首个保存者胜出，之后只读不覆盖」，所以只要抬代次时漏了某几处，warm job 写进去
# 的那一代就永远没人读得到（或反过来：各 job 各自冷启动去公网重下，共享 runner IP 上直接撞
# Maven Central 429）。2026-10-07 整仓被一个过期代次冻红之后，"抬键尾 v 号"变成例行动作，
# 这个 9 处漂移面就必须自己变红，而不是靠人记住。
#
# 用法：bash scripts/ci-maven-cache-key.sh [workflows 目录]   （默认 .github/workflows）
set -u

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
REPO_ROOT="$(dirname "$SCRIPT_DIR")"
TARGET_DIR="${1:-$REPO_ROOT/.github/workflows}"

CANON='mvn-${{ runner.os }}-${{ hashFiles('"'"'**/pom.xml'"'"') }}-v2'

if command -v mvn >/dev/null 2>&1; then
  TOOLCHAIN="$(mvn -version 2>/dev/null | head -1)"
else
  TOOLCHAIN="(本环境无 mvn，跳过工具链留痕)"
fi

total=0
mismatch=0
while IFS= read -r raw; do
  [ -z "$raw" ] && continue
  total=$((total + 1))
  # grep -rn 输出形如 path:lineno:<内容>；按前两个冒号切出真正的行
  site="$(printf '%s' "$raw" | cut -d: -f1)"
  lineno="$(printf '%s' "$raw" | cut -d: -f2)"
  body="$(printf '%s' "$raw" | cut -d: -f3- | sed -e 's/^[[:space:]]*//' -e 's/[[:space:]]*$//')"
  value="${body#key: }"
  if [ "$value" != "$CANON" ]; then
    mismatch=$((mismatch + 1))
    printf '  ✗ %s:%s\n      实得: %s\n      规范: %s\n' "$site" "$lineno" "$value" "$CANON"
  fi
done < <(grep -rn --include='*.yml' -E '^[[:space:]]*key:[[:space:]]+mvn-' "$TARGET_DIR" 2>/dev/null || true)

echo "maven cache key dir=$TARGET_DIR sites=$total mismatch=$mismatch toolchain=$TOOLCHAIN"

if [ "$total" -eq 0 ]; then
  echo "::error::没找到任何 maven 缓存键（门禁自己失明 = 假绿）：要么 workflow 被改坏，要么本脚本的匹配式过期"
  exit 1
fi
if [ "$mismatch" -ne 0 ]; then
  echo "::error::maven 缓存键不同源（$mismatch/$total 处偏离规范值）⇒ 抬代次必须所有站点一起抬"
  exit 1
fi
echo "OK: maven 缓存键 $total 处同源"
