# attempt6b：capacity-loadaxis.sh 的 R3 续跑（Nex）。
# 背景：attempt6 wrapper 在 R2p4 DONE 后于 i=3 端口检查处 ABORT（exit 5，
# "ports busy n=3 after 60s"——R2p4 后端 JVM 关停滞后瞬态占 18190/14560，
# 5 分钟后已自净；当时无我方残留进程）。已有效 4 轮（R1p2/R1p4/R2p2/R2p4），
# 预登记设计＝3 轮 × 2 档＝6 轮齐才下结论（wrapper 自身 rounds!=6 FATAL）。
# 本脚本不重跑 R1/R2，只按原纪律补 R3（p2+p4）：
#   门控（连续 3 采样 <=25% 且 pitest 不在场，fail-closed）/ 端口检查 / 硬对账
#   逐字同 capacity-loadaxis.sh；trace 命名 cold3-*；日志追加同一 loadaxis-run.log。
# 唯一变量仍是负载轴；代码固定 31afbc10；harness 同仓内 capacity-baseline.sh。
set -uo pipefail
REPO="/f/Nexus/nexussky-pre283"
export NEX_REPO="$REPO"
HARNESS="$REPO/scripts/capacity-baseline.sh"
OUT=docs/capacity
TRACE_DIR="$OUT/traces-loadaxis"
LOG="$OUT/loadaxis-run.log"
DRONES=200 HZ=30 CLIENTS=20 MAX_PER_IP=20 DURATION=60 GATE=25 GATE_MAX=7200
ROUNDS_DONE=0
LOCKD="$REPO/.capacity-loadaxis.lock"
mkdir -p "$TRACE_DIR"
if ! mkdir "$LOCKD" 2>/dev/null; then
    echo "$(date -u +%H:%M:%S)Z ABORT lock held by another run" >> "$LOG"; exit 6
fi
trap 'rmdir "$LOCKD" 2>/dev/null' EXIT

cd "$REPO" || { echo "$(date -u +%H:%M:%S)Z ABORT repo missing $REPO" >> "$LOG"; exit 8; }
echo "==== LOADAXIS CONTINUE attempt6b R3 START $(date -u +%Y-%m-%dT%H:%M:%SZ) ====" >> "$LOG"

cpu_now() {
    powershell -NoProfile -Command '(Get-Counter "\Processor(_Total)\% Processor Time").CounterSamples | ForEach-Object { [math]::Round($_.CookedValue,1) }' 2>/dev/null | tail -1
}
port_busy() {
    powershell -NoProfile -Command "(Get-NetTCPConnection -LocalPort 18190,14560 -State Listen -ErrorAction SilentlyContinue | Measure-Object).Count" 2>/dev/null | tail -1
}
storm_check() {
    local n
    n="$(powershell -NoProfile -Command '(Get-CimInstance Win32_Process -Filter "Name=''java.exe''" | Where-Object { $_.CommandLine -match "mutationCoverage|pitest" } | Measure-Object).Count' 2>/dev/null | tail -1 | tr -d ' \r')"
    [[ "${n:-1}" == "0" ]]
}
wait_idle() {
    local waited=0 c quiet=0
    while (( waited < GATE_MAX )); do
        [[ -e "$REPO/.git" ]] || { echo "$(date -u +%H:%M:%S)Z ABORT repo vanished mid-wait" >> "$LOG"; exit 9; }
        c="$(cpu_now)"
        echo "$(date -u +%H:%M:%S)Z gate cpu=${c}%" >> "$LOG"
        if [[ -n "$c" ]] && awk -v c="$c" -v t="$GATE" 'BEGIN{exit !(c<=t)}'; then
            if storm_check; then
                quiet=$((quiet+1))
                (( quiet >= 3 )) && return 0
            else
                echo "$(date -u +%H:%M:%S)Z gate storm=ON holding" >> "$LOG"
                quiet=0
            fi
        else
            quiet=0
        fi
        sleep 20; waited=$((waited+20))
    done
    return 1
}
run_round() {  # $1=idx $2=injectProcs
    local idx="$1" procs="$2" rc json recon
    local before after
    before="$(ls "$OUT"/*.json 2>/dev/null | sort)"
    echo "$(date -u +%H:%M:%S)Z ROUND $idx p${procs} START" >> "$LOG"
    CPU_TRACE="$TRACE_DIR/loadaxis-cold${idx}-p${procs}-$(date +%s).log"
    ( while true; do echo "$(date -u +%H:%M:%S) $(cpu_now)" >> "$CPU_TRACE"; sleep 5; done ) &
    TRACER=$!
    bash "$HARNESS" --drones "$DRONES" --hz "$HZ" --clients "$CLIENTS" --duration "$DURATION" \
        --max-per-ip "$MAX_PER_IP" --inject-procs "$procs" --out "$OUT" >> "$LOG" 2>&1
    rc=$?
    kill "$TRACER" 2>/dev/null; wait "$TRACER" 2>/dev/null
    after="$(ls "$OUT"/*.json 2>/dev/null | sort)"
    json="$(comm -13 <(printf '%s\n' "$before") <(printf '%s\n' "$after") | head -1)"
    if [[ $rc -ne 0 || -z "$json" ]]; then
        echo "$(date -u +%H:%M:%S)Z ROUND $idx p${procs} FAIL rc=$rc json=[${json:-none}]" >> "$LOG"
        return 1
    fi
    recon="$(python - "$json" <<'PY'
import json,sys
d=json.load(open(sys.argv[1],encoding="utf-8"))
sent=d["injector"]["sentFrames"]; dec=d["metrics"]["messagesDecoded"]
kd=d["injector"]["kernelDropEstimate"]
print("OK" if sent==dec+(kd or 0) else f"BAD sent={sent} dec={dec} kd={kd}")
PY
)"
    echo "$(date -u +%H:%M:%S)Z ROUND $idx p${procs} DONE rc=$rc recon=$recon json=$(basename "$json")" >> "$LOG"
    [[ "$recon" == OK ]] || return 1
    ROUNDS_DONE=$((ROUNDS_DONE+1))
}

# R3 前端口检查（同 wrapper i 循环顶：6×10s，占即 abort）
port_ok=0
for _t in 1 2 3 4 5 6; do
    n="$(port_busy)"
    [[ "${n:-0}" == "0" ]] && { port_ok=1; break; }
    sleep 10
done
[[ "$port_ok" == 1 ]] || { echo "$(date -u +%H:%M:%S)Z ABORT ports busy n=${n:-?} after 60s" >> "$LOG"; exit 5; }

wait_idle || { echo "$(date -u +%H:%M:%S)Z ABORT cpu gate not satisfied (p2 3)" >> "$LOG"; exit 3; }
run_round 3 2 || exit 4
wait_idle || { echo "$(date -u +%H:%M:%S)Z ABORT cpu gate not satisfied (p4 3)" >> "$LOG"; exit 3; }
run_round 3 4 || exit 4

echo "==== LOADAXIS CONTINUE attempt6b R3 COMPLETE rounds=$ROUNDS_DONE $(date -u +%Y-%m-%dT%H:%M:%SZ) ====" >> "$LOG"
# 全 attempt6（R1/R2 四轮 + 本轮两轮）闭合核对：自 attempt6 START 标记起 ROUND DONE 必须 = 6
total="$(awk '/LOADAXIS START 2026-11-13T04:50/{f=1} f&&/ROUND [0-9] p[0-9] DONE/{n++} END{print n+0}' "$LOG")"
echo "$(date -u +%H:%M:%S)Z attempt6 total ROUND DONE since 04:50 START = $total" >> "$LOG"
if [[ "$total" != "6" ]]; then
    echo "$(date -u +%H:%M:%S)Z FATAL attempt6 total=$total != 6 — NOT success" >> "$LOG"
    exit 7
fi
echo "ALL_ROUNDS_DONE"
