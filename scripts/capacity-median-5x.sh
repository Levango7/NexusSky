#!/usr/bin/env bash
# 5 轮中位数测量驱动：三档 × 5 次，按轮交错（H,C,X,H,C,X,...）以均摊机器状态漂移。
# 用法：bash scripts/capacity-median-5x.sh
set -u

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
OUT="$ROOT/docs/capacity"
LOG="/f/tmp/cap5"
mkdir -p "$LOG"

# 三档参数（与 2.9/2.11 历史档位一致）
declare -A HZ=( [H]=10 [C]=20 [X]=25 )
declare -A PROCS=( [H]=1 [C]=2 [X]=4 )

for rep in 1 2 3 4 5; do
    for tier in H C X; do
        stamp="$(date +%H%M%S)"
        echo "===== rep=$rep tier=$tier hz=${HZ[$tier]} procs=${PROCS[$tier]} start=$(date +%H:%M:%S) ====="
        bash "$ROOT/scripts/capacity-baseline.sh" \
            --drones 254 --hz "${HZ[$tier]}" --clients 10 \
            --inject-procs "${PROCS[$tier]}" --duration 60 \
            --out "$OUT" > "$LOG/rep${rep}-${tier}-${stamp}.log" 2>&1
        rc=$?
        echo "     rc=$rc  done=$(date +%H:%M:%S)"
        if [ $rc -ne 0 ]; then
            echo "     !! 非零退出，日志：$LOG/rep${rep}-${tier}-${stamp}.log"
        fi
        # 档间留 10s 让端口/进程彻底释放
        sleep 10
    done
done

echo "===== ALL DONE $(date +%H:%M:%S) ====="
