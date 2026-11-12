# 负载轴提载实验编排（Nex）：#283^（31afbc10）在 d400/hz30/c20 档复测 pre283 弱信号。
# 背景：bistable-pre283-vs-post283-20261112.md 中 pre283 R2p4 出现「队列近满(4075/4096)＋扇出 drop 1668」
# 弱 A 型签名（非风暴形态）。本实验提负载轴看它是否放大成历史 A 型饱和。
# 唯一变量仍是负载轴；代码固定 31afbc10；harness 用仓内 scripts/capacity-baseline.sh（正统）。
# 门控同前：开火前连续 3 采样 <=25% 且 pitest 不在场（fail-closed）；端口 18190/14560 被占即中止。
# 每轮：CPU 轨迹落 docs/capacity/traces-loadaxis/（进仓，/f/tmp 已被共享清理过一次不再信任）；
# 硬对账 sentFrames == decoded + kernelDropEstimate，不闭合记 FAIL 并 abort。
# 产物：JSON 落 docs/capacity/，运行日志落 docs/capacity/loadaxis-run.log。
set -uo pipefail
REPO="/f/Nexus/nexussky-pre283"
export NEX_REPO="$REPO"
HARNESS="$REPO/scripts/capacity-baseline.sh"
OUT=docs/capacity
TRACE_DIR="$OUT/traces-loadaxis"
LOG="$OUT/loadaxis-run.log"
DRONES=400 HZ=30 CLIENTS=20 MAX_PER_IP=20 DURATION=60 GATE=25 GATE_MAX=1800
ROUNDS_DONE=0
LOCKD="$REPO/.capacity-loadaxis.lock"
mkdir -p "$TRACE_DIR"
if ! mkdir "$LOCKD" 2>/dev/null; then
    echo "$(date -u +%H:%M:%S)Z ABORT lock held by another run" >> "$LOG"; exit 6
fi
trap 'rmdir "$LOCKD" 2>/dev/null' EXIT

cd "$REPO" || { echo "$(date -u +%H:%M:%S)Z ABORT repo missing $REPO" >> "$LOG"; exit 8; }
echo "==== LOADAXIS START $(date -u +%Y-%m-%dT%H:%M:%SZ) ====" >> "$LOG"

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

for i in 1 2 3; do
    port_ok=0
    for _t in 1 2 3 4 5 6; do
        n="$(port_busy)"
        [[ "${n:-0}" == "0" ]] && { port_ok=1; break; }
        sleep 10
    done
    [[ "$port_ok" == 1 ]] || { echo "$(date -u +%H:%M:%S)Z ABORT ports busy n=${n:-?} after 60s" >> "$LOG"; exit 5; }
    wait_idle || { echo "$(date -u +%H:%M:%S)Z ABORT cpu gate not satisfied (p2 $i)" >> "$LOG"; exit 3; }
    run_round "$i" 2 || exit 4
    wait_idle || { echo "$(date -u +%H:%M:%S)Z ABORT cpu gate not satisfied (p4 $i)" >> "$LOG"; exit 3; }
    run_round "$i" 4 || exit 4
done

echo "==== LOADAXIS COMPLETE rounds=$ROUNDS_DONE $(date -u +%Y-%m-%dT%H:%M:%SZ) ====" >> "$LOG"
if [[ "$ROUNDS_DONE" -ne 6 ]]; then
    echo "$(date -u +%H:%M:%S)Z FATAL rounds=$ROUNDS_DONE != 6 — NOT success" >> "$LOG"
    exit 7
fi
echo "ALL_ROUNDS_DONE"
tail -5 "$LOG"
