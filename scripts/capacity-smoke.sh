#!/usr/bin/env bash
# capacity-smoke.sh — 容量基线脚本的冒烟检查（不是容量测量）。
# 目的：验证 capacity-baseline.sh 能跑通完整链路（后端 + 注入器 + 探针 + Prometheus 采集 + JSON 产出），
# 而不产生任何可外推的容量结论；生产容量结论只能来自完整档位（见 docs/capacity/README.md）。
#
# 用法：./scripts/capacity-smoke.sh
# 退出码：0 = 冒烟链路跑通（产物在打印出来的 out 目录）；非 0 = 链路失败（原因见 stderr）。

set -uo pipefail

REPO="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
mkdir -p "${CAPACITY_WORK:-/tmp/capacity-smoke}"

DRONES="${SMOKE_DRONES:-3}"
HZ="${SMOKE_HZ:-10}"
CLIENTS="${SMOKE_CLIENTS:-2}"
DURATION="${SMOKE_DURATION:-15}"
OUT="${CAPACITY_SMOKE_OUT:-$(mktemp -d /tmp/capacity-smoke-out.XXXXXX)}"

echo "[smoke] drones=$DRONES hz=$HZ clients=$CLIENTS duration=${DURATION}s out=$OUT"

# 出口验收：后端 jar / drone-sim shaded jar 必须已构建（ci 流程：先 package）
[[ -n "$(ls -1 "$REPO"/cloud-backend/target/*.jar 2>/dev/null | grep -vE 'sources|javadoc|original' | head -1)" ]] \
    || { echo "[smoke] FAIL: cloud-backend jar 未构建（mvn -B -q -pl cloud-backend -am package -DskipTests）" >&2; exit 1; }
[[ -n "$(ls -1 "$REPO"/drone-sim/target/*-shaded.jar 2>/dev/null | head -1)" ]] \
    || { echo "[smoke] FAIL: drone-sim shaded jar 未构建（mvn -B -q -pl drone-sim -am package -DskipTests）" >&2; exit 1; }

export CAPACITY_WORK="${CAPACITY_SMOKE_WORK:-/tmp/capacity-smoke}"
bash "$REPO/scripts/capacity-baseline.sh" \
    --drones "$DRONES" --hz "$HZ" --clients "$CLIENTS" --duration "$DURATION" \
    --out "$OUT" > "$OUT/smoke.log" 2>&1
RC=$?
echo "[smoke] capacity-baseline.sh rc=$RC log=$OUT/smoke.log"
compgen -G "$OUT/*.json" > /dev/null && echo "[smoke] JSON report found" || { echo "[smoke] FAIL: no JSON report found in $OUT" >&2; ls -la "$OUT" >&2; exit 3; }
echo "[smoke] out=$OUT"
exit $RC
