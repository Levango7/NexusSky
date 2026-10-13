#!/usr/bin/env bash
# 给 CI 的 mvn 调用包一层「取件类失败」的重试与取证。
#
# 为什么必须有它（2026-10-13 本机取证）：master 的 push run `38283464705` 与 PR #61 的
# run `38288389281`（attempt 1）各红一条 job，日志只有
#   [ERROR] Non-resolvable import POM: … org.junit:junit-bom:pom:5.11.4 (absent):
#           Could not find artifact … in central (https://repo.maven.apache.org/maven2)
#   [ERROR] … org.apache.maven.surefire:surefire-junit-platform:jar:3.5.2 (absent) …
# 而**同一时刻同一台开发机 curl 这两个 URL 都是 HTTP 200**（`junit-bom:5.11.4` 根本不可能
# "不存在"）。两条红的请求间隔只有 ~15ms，也不像真走到中央仓再取回 404 的样子。
# 问题是：**光看日志无法区分"版本真的没有"和"这个 runner 到中央仓的通路坏了"**，
# 于是只能靠重跑碰运气——而红已经记在 PR 头上了。
#
# 现在：命中取件类错误 ⇒ 先由 runner 自己按坐标回查中央仓并把**真实 HTTP 状态码**打进日志，
# 再重试一次；重试仍红就把取证原样留在末尾、按原退码退出。这样下次同类红自带判决：
# probe 200 而构建红 ⇒ 通路/缓存问题（可重跑）；probe 也 404 ⇒ 坐标本身错了（必须改码）。
#
# 已知边界：只认 "could not be resolved / Could not find artifact / Non-resolvable import POM"
# 这三类文本，测试失败、编译错误一律**不重试**（那是要人看的红，不是抖动）。
set -uo pipefail

LOG=$(mktemp)
trap 'rm -f "$LOG"' EXIT

CENTRAL="https://repo.maven.apache.org/maven2"

probe_one() {
  # 输入形如 org.junit:junit-bom:pom:5.11.4，输出该坐标在中央仓的实际状态码
  local coord="$1" g a t v path code
  IFS=':' read -r g a t v <<<"$coord"
  if [ -z "${g:-}" ] || [ -z "${a:-}" ] || [ -z "${t:-}" ] || [ -z "${v:-}" ]; then
    return 0
  fi
  path="${g//./\/}/${a}/${v}/${a}-${v}.${t}"
  code=$(curl -s -o /dev/null -w '%{http_code}' --max-time 25 "${CENTRAL}/${path}" 2>/dev/null || echo "curl-err")
  echo "    probe HTTP ${code}  ${CENTRAL}/${path}"
}

diagnose() {
  local coords c
  coords=$(grep -oE '[A-Za-z0-9_.-]+:[A-Za-z0-9_.-]+:(pom|jar):[0-9][A-Za-z0-9_.-]*' "$1" \
    | sort -u | head -20)
  echo "::group::取件失败取证（runner 侧直查中央仓）"
  if [ -z "$coords" ]; then
    echo "    没能从 mvn 输出里提取到坐标——请把上面的 [ERROR] 行原样贴出来，不要靠重跑猜测"
  else
    for c in $coords; do probe_one "$c"; done
  fi
  echo "::endgroup::"
}

is_resolution_failure() {
  grep -qE 'could not be resolved|Could not find artifact|Non-resolvable import POM' "$1"
}

run_mvn() {
  mvn "$@" >"$LOG" 2>&1
  local rc=$?
  cat "$LOG"
  return $rc
}

rc=0
run_mvn "$@" || rc=$?

if [ "$rc" -ne 0 ] && is_resolution_failure "$LOG"; then
  echo "⚠️ 依赖解析失败（rc=${rc}），判定为取件类而非代码类 ⇒ 取证后重试一次"
  diagnose "$LOG"
  sleep 5
  rc=0
  run_mvn "$@" || rc=$?
  if [ "$rc" -ne 0 ]; then
    echo "❌ 重试仍失败（rc=${rc}）。再看一遍上面的 probe 状态码："
    echo "   · probe 200 ⇒ 中央仓有这个件，坏的是这个 runner 的通路或恢复下来的 ~/.m2 ⇒ 重跑或抬缓存键尾 v 号"
    echo "   · probe 也 404 ⇒ 坐标/版本本身不存在 ⇒ 这是要改代码的红，不要重跑掩盖"
    diagnose "$LOG"
  fi
fi

exit "$rc"
