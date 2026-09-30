#!/usr/bin/env bash
# =============================================================================
# 覆盖率门禁阈值的「实测计算 + 一致性核对」脚本
#
# 为什么需要它：CI 里 "Coverage gate" 的数字写在各模块 pom 的 jacoco check 规则里，
# 而 jacoco:check 在 target/jacoco.exec 缺失时会打一条 warn 然后**静默跳过**——
# 于是"加了规则"和"规则真的在拦"是两件事。本脚本把两件事都量化：
#   1. 从真实 jacoco.csv 汇总 BUNDLE 级 LINE 覆盖率（与 jacoco check 的口径一致）；
#   2. 按策略"实测覆盖率向下取整到 5%"算出应声明的阈值；
#   3. 与 pom 里已声明的 <minimum> 比对，判定门禁是真拦、空转、还是被人为调低过门。
#
# 口径：jacoco CSV 的 LINE_MISSED(第 8 列) / LINE_COVERED(第 9 列)，
#       覆盖率 = COVERED / (MISSED + COVERED)，与 element=BUNDLE + counter=LINE
#       + value=COVEREDRATIO 的判定完全一致。
#
# 用法：
#   bash scripts/ci-coverage-threshold.sh                       # 全部 6 个门禁模块
#   bash scripts/ci-coverage-threshold.sh mavlink-core link-sim  # 指定模块
#   bash scripts/ci-coverage-threshold.sh --strict               # 阈值低于策略值也算失败
#
# 退出码：0=门禁与实测自洽；1=有模块缺产物/缺规则/阈值高于实测（会挡住 CI）
#         （非 --strict 时"阈值低于策略值"只 WARN，因为覆盖率自然上涨不该让构建变红）。
# =============================================================================
set -uo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
MODULES_DEFAULT=(mavlink-core drone-sim link-sim cloud-backend sdk-java regulator-sim)
STRICT=0

if [ "${1:-}" = "--strict" ]; then
  STRICT=1
  shift
fi
MODULES=()
if [ "$#" -gt 0 ]; then
  MODULES=("$@")
else
  MODULES=("${MODULES_DEFAULT[@]}")
fi

FAIL=0
printf '%-14s %10s %10s %10s %10s  %s\n' \
  "MODULE" "MISSED" "COVERED" "MEASURED" "POLICY" "DECLARED(pom)"

for m in "${MODULES[@]}"; do
  CSV="$ROOT/$m/target/site/jacoco/jacoco.csv"
  POM="$ROOT/$m/pom.xml"

  if [ ! -f "$CSV" ]; then
    printf '%-14s %s\n' "$m" "❌ 缺 $m/target/site/jacoco/jacoco.csv —— 该模块的门禁本次无法自证（要么没配 jacoco，要么构建被 -DskipTests 跳过）"
    FAIL=1
    continue
  fi

  # 声明阈值 = pom 里第一个 jacoco check 的 LINE COVEREDRATIO minimum
  DECLARED=$(sed -n 's:.*<minimum>\([0-9.]*\)</minimum>.*:\1:p' "$POM" | head -1)
  if [ -z "$DECLARED" ]; then
    printf '%-14s %s\n' "$m" "❌ $m/pom.xml 里没有 jacoco check 的 <minimum> —— 无门禁"
    FAIL=1
    continue
  fi

  read -r MISSED COVERED MEASURED POLICY <<< "$(awk -F, '
    NR>1 { missed += $8; covered += $9 }
    END {
      total = missed + covered
      if (total == 0) { printf "%d %d nan nan\n", missed, covered; exit }
      ratio = covered / total
      # 向下取整到 5%：floor(ratio*100/5)*5/100
      policy = int(ratio * 20) / 20
      printf "%d %d %.4f %.2f\n", missed, covered, ratio, policy
    }' "$CSV")"

  if [ "$MEASURED" = "nan" ]; then
    printf '%-14s %s\n' "$m" "❌ jacoco.csv 里没有可统计的行（classes 目录为空？）"
    FAIL=1
    continue
  fi

  # 比较用百分比整数，避免浮点
  MEASURED_PCT=$(awk -v r="$MEASURED" 'BEGIN { printf "%d", r * 100 }')
  DECLARED_PCT=$(awk -v d="$DECLARED" 'BEGIN { printf "%d", d * 100 }')
  POLICY_PCT=$(awk -v p="$POLICY" 'BEGIN { printf "%d", p * 100 }')

  STATUS="✅ 自洽"
  if [ "$DECLARED_PCT" -gt "$MEASURED_PCT" ]; then
    STATUS="❌ 阈值 $DECLARED_PCT% 高于实测 $MEASURED_PCT% —— verify 会失败（真拦，但门槛定在了现状之上）"
    FAIL=1
  elif [ "$DECLARED_PCT" -lt "$POLICY_PCT" ]; then
    STATUS="⚠️ 阈值 $DECLARED_PCT% 低于策略值 $POLICY_PCT%（实测 $MEASURED_PCT% 向下取整到 5%）"
    if [ "$STRICT" = "1" ]; then
      STATUS="❌ --strict：$STATUS"
      FAIL=1
    fi
  fi
  printf '%-14s %10s %10s %9s%% %9s%%  %s -> %s\n' \
    "$m" "$MISSED" "$COVERED" "$MEASURED_PCT" "$POLICY_PCT" "$DECLARED_PCT%" "$STATUS"
done

echo ""
echo "说明：POLICY = 实测 LINE 覆盖率向下取整到 5%（本仓库给新模块定阈值的唯一口径）；"
echo "      DECLARED 取自各模块 pom.xml 的 jacoco check 规则，CI 真正执行的是 DECLARED。"
if [ "$FAIL" -ne 0 ]; then
  echo "RESULT: FAIL"
  exit 1
fi
echo "RESULT: OK"
