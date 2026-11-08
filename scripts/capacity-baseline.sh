#!/usr/bin/env bash
#
# 容量基线：起真后端 + N 架虚拟无人机 + K 条 WS 连接，测端到端时延与扇出丢弃。
#
# 为什么要有这个脚本：docs/customer-onboarding-guide.md 的部署选型表写着
# 「Docker Compose 单机 ≤ 20 架 / K8s ≤ 50 架 / 多节点 ≤ 100 架」，但全仓没有任何
# 一条实测支撑它。本脚本把那几个数字变成可复现的测量——要么为它们找到依据，
# 要么证明它们不成立。
#
# 三个进程分开跑，避免互相污染：
#   1. 后端（java -jar，dev profile，H2 内存库）
#   2. 注入器（fleetload inject，只发 MAVLink 帧）
#   3. 探针（fleetload probe，只收 WS 并计时）
#
# 用法：
#   ./scripts/capacity-baseline.sh --drones 20 --hz 10 --clients 10 --duration 60
#
# 参数：
#   --drones N       虚拟无人机数量（sysid 1..N）
#   --hz H           每架每秒业务帧数（上限 100，受后端 max-frame-rate-per-sysid 约束）
#   --clients K      WS 并发连接数（超过 --max-per-ip 时多余连接会被拒，探针会记进 connectFailures）
#   --duration S     测量时长（秒）
#   --max-per-ip M   覆盖 aerofleet.ws.max-connections-per-ip（默认沿用产品值 10，
#                    以便测出客户真实会遇到的连接上限；测平台容量上限时才调大）
#   --inject-procs P 注入进程数（默认 1）。单线程在 254 架时只能到约 10 Hz/架，
#                    要压更高帧率必须多进程各管一段 sysid，否则测的是注入器上限
#   --port P         HTTP/WS 端口（默认 18190）
#   --udp-port U     MAVLink UDP 端口（默认 14560）
#   --out DIR        报告目录（默认 docs/capacity）
#
# 退出码：0 成功；非 0 表示链路未跑通（不是「性能不达标」——性能结论在报告里）。

set -uo pipefail

DRONES=20
HZ=10
CLIENTS=10
DURATION=60
MAX_PER_IP=10
INJECT_PROCS=1
PORT=18190
UDP_PORT=14560
OUT_DIR=docs/capacity

while [[ $# -gt 0 ]]; do
    case "$1" in
        --drones)     DRONES="$2"; shift 2 ;;
        --hz)         HZ="$2"; shift 2 ;;
        --clients)    CLIENTS="$2"; shift 2 ;;
        --duration)   DURATION="$2"; shift 2 ;;
        --max-per-ip) MAX_PER_IP="$2"; shift 2 ;;
        --inject-procs) INJECT_PROCS="$2"; shift 2 ;;
        --port)       PORT="$2"; shift 2 ;;
        --udp-port)   UDP_PORT="$2"; shift 2 ;;
        --out)        OUT_DIR="$2"; shift 2 ;;
        -h|--help)    sed -n '2,30p' "$0" | sed 's/^# \{0,1\}//'; exit 0 ;;
        *) echo "未知参数: $1" >&2; exit 2 ;;
    esac
done

REPO="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$REPO"

STAMP="$(date +%Y%m%d-%H%M%S)"
LABEL="d${DRONES}-hz${HZ}-c${CLIENTS}-ip${MAX_PER_IP}"
WORK="/f/tmp/capacity"
mkdir -p "$OUT_DIR" "$WORK"

info() { echo "[capacity] $*"; }
fail() { echo "[capacity] ERROR: $*" >&2; }

# PATH 里的 java 可能是 1.8（本机实测如此），必须显式用 JAVA_HOME 的 17+，
# 否则 java -jar 会在类版本上直接炸，且报错与容量无关，容易误判成被测系统的问题。
JAVA="java"
if [[ -n "${AF_JAVA:-}" ]]; then
    JAVA="$AF_JAVA"
elif [[ -n "${JAVA_HOME:-}" && -x "$JAVA_HOME/bin/java.exe" ]]; then
    JAVA="$JAVA_HOME/bin/java.exe"
elif [[ -n "${JAVA_HOME:-}" && -x "$JAVA_HOME/bin/java" ]]; then
    JAVA="$JAVA_HOME/bin/java"
fi

BACKEND_JAR="$(ls -1 cloud-backend/target/*.jar 2>/dev/null | grep -vE 'sources|javadoc|original' | head -1)"
SIM_JAR="$(ls -1 drone-sim/target/*-shaded.jar 2>/dev/null | head -1)"
[[ -n "$BACKEND_JAR" ]] || { fail "后端 jar 不存在，先跑: mvn -o -q -pl cloud-backend -am -DskipTests package"; exit 1; }
[[ -n "$SIM_JAR" ]] || { fail "drone-sim shaded jar 不存在，先跑: mvn -o -q -pl drone-sim -am -DskipTests package"; exit 1; }

BACKEND_PID=""
INJECT_PIDS=()
PEAK_PID=""

cleanup() {
    for pid in "$PEAK_PID" "${INJECT_PIDS[@]}" "$BACKEND_PID"; do
        if [[ -n "$pid" ]] && kill -0 "$pid" 2>/dev/null; then
            kill -9 "$pid" 2>/dev/null || true
        fi
    done
    wait 2>/dev/null || true
}
trap cleanup EXIT INT TERM

info "后端 jar: $(basename "$BACKEND_JAR")"
info "模拟器 jar: $(basename "$SIM_JAR")"
info "JAVA: $JAVA"

# ---- 1. 起后端 ----
info "启动后端（dev profile, port=$PORT, udp=$UDP_PORT, max-connections-per-ip=$MAX_PER_IP）"
"$JAVA" -Xmx1g -jar "$BACKEND_JAR" \
    --spring.profiles.active=dev \
    --server.port="$PORT" \
    --aerofleet.udp-port="$UDP_PORT" \
    --aerofleet.ws.max-connections-per-ip="$MAX_PER_IP" \
    --aerofleet.ws.max-connections-per-tenant=200 \
    --spring.datasource.url="jdbc:h2:mem:capacity;DB_CLOSE_DELAY=-1" \
    --logging.level.io.aerofleet=WARN \
    --logging.level.org.springframework.web=WARN \
    --logging.level.org.hibernate.SQL=WARN \
    --spring.jpa.show-sql=false \
    > "$WORK/backend-$STAMP.log" 2>&1 &
BACKEND_PID=$!

READY=0
for t in $(seq 1 120); do
    if ! kill -0 "$BACKEND_PID" 2>/dev/null; then
        fail "后端进程已退出，日志尾部："
        tail -20 "$WORK/backend-$STAMP.log" >&2
        exit 1
    fi
    if curl -sf -m 2 "http://localhost:$PORT/actuator/health" 2>/dev/null | grep -q '"status":"UP"'; then
        READY=$t
        break
    fi
    sleep 1
done
[[ "$READY" -gt 0 ]] || { fail "后端 120s 内未就绪，见 $WORK/backend-$STAMP.log"; exit 1; }
info "后端就绪（${READY}s）"

# ---- 2. 起注入器 ----
# 时长比测量多 30s：探针要跑满 duration，注入器必须一直有帧
#
# 为什么可能要多进程：单线程注入每帧要 encode(CRC)+UDP send，实测约 0.35ms/帧。
# 254 架跑一轮要 90ms+，因此单进程在 254 架时只能到约 10 Hz/架——
# 设定 50Hz 也发不出去。此时探针测到的低速率是**注入器上限**，
# 若不加区分就会被误读成服务端容量上限。用 --inject-procs 各管一段 sysid 分摊。
info "注入负载：$DRONES 架 × $HZ Hz → 127.0.0.1:$UDP_PORT（$INJECT_PROCS 个进程）"
BASE=$((DRONES / INJECT_PROCS))
REM=$((DRONES % INJECT_PROCS))
SYSID=1
for ((p = 0; p < INJECT_PROCS; p++)); do
    N=$BASE
    if ((p < REM)); then N=$((BASE + 1)); fi
    "$JAVA" -jar "$SIM_JAR" fleetload inject \
        --drones "$N" --hz "$HZ" --sysid-start "$SYSID" \
        --target "127.0.0.1:$UDP_PORT" --duration "$((DURATION + 30))" \
        > "$WORK/inject-$STAMP-p$p.log" 2>&1 &
    INJECT_PIDS+=($!)
    info "  进程 $p: sysid $SYSID..$((SYSID + N - 1))（$N 架）"
    SYSID=$((SYSID + N))
done

# ---- 3. 等设备在线 ----
# TelemetryPusher 只推「在线」设备，而 online 由 HEARTBEAT 驱动（1Hz）。
# 必须等设备真注册进来再测，否则前几秒测的是空机队。
info "等待 $DRONES 架设备上线..."
ONLINE=0
WAITED=0
for t in $(seq 1 60); do
    ONLINE=$(curl -s -m 3 "http://localhost:$PORT/api/v1/drones" 2>/dev/null \
        | grep -o '"online":true' | wc -l | tr -d ' ')
    WAITED=$t
    [[ "$ONLINE" -ge "$DRONES" ]] && break
    sleep 1
done
info "在线设备：$ONLINE / $DRONES（等待 ${WAITED}s）"

# ---- 4. 跑探针 ----
info "测量 ${DURATION}s，$CLIENTS 条 WS 连接..."
PROBE_RAW="$WORK/probe-$STAMP.json"

# Gauge 类指标（ws_sessions / queue_depth）是瞬时量：探针结束后再采，
# 客户端已断开、队列已排空，采到的必然是 0。必须在测量期间轮询取峰值，
# 否则这两个指标永远无参考价值（早期版本即踩此坑）。
PEAK_LOG="$WORK/peak-$STAMP.txt"
: > "$PEAK_LOG"
(
    while true; do
        P="$(curl -s -m 2 "http://localhost:$PORT/actuator/prometheus" 2>/dev/null)"
        D="$(echo "$P" | grep -E '^aerofleet_ws_fanout_queue_depth' | head -1 | awk '{print $NF}')"
        S="$(echo "$P" | grep -E '^aerofleet_ws_sessions' | head -1 | awk '{print $NF}')"
        echo "${D:-0} ${S:-0}"
        sleep 2
    done
) > "$PEAK_LOG" 2>/dev/null &
PEAK_PID=$!

"$JAVA" -jar "$SIM_JAR" fleetload probe \
    --clients "$CLIENTS" --url "ws://127.0.0.1:$PORT/ws/telemetry" \
    --duration "$DURATION" --out "$PROBE_RAW" \
    > "$WORK/probe-$STAMP.log" 2>&1
PROBE_RC=$?

kill -9 "$PEAK_PID" 2>/dev/null || true
PEAK_PID=""

# 峰值取整段采样的最大值；无样本时记 0
PEAKS="$(awk '{if($1+0>d)d=$1+0; if($2+0>s)s=$2+0} END{printf "%d %d", d+0, s+0}' "$PEAK_LOG" 2>/dev/null)"
M_DEPTH_PEAK="${PEAKS%% *}"
M_SESS_PEAK="${PEAKS##* }"
info "测量期峰值：queueDepth=${M_DEPTH_PEAK:-0} wsSessions=${M_SESS_PEAK:-0}（采样 $(wc -l < "$PEAK_LOG" 2>/dev/null | tr -d ' ') 次）"

# ---- 5. 采集后端指标 ----
PROM="$(curl -s -m 20 "http://localhost:$PORT/actuator/prometheus" 2>/dev/null)"
echo "$PROM" > "$WORK/prom-$STAMP.txt"
[[ -n "$PROM" ]] || fail "prometheus 采集为空（见 $WORK/backend-$STAMP.log），指标将记 null"
metric() {
    echo "$PROM" | grep -E "^$1(\{| )" | head -1 | awk '{print $NF}'
}
M_SESSIONS="$(metric aerofleet_ws_sessions)"
M_DEPTH="$(metric aerofleet_ws_fanout_queue_depth)"
M_DROPPED="$(metric aerofleet_ws_fanout_dropped_total)"
M_ONLINE="$(metric aerofleet_drones_online)"
M_DECODED="$(metric aerofleet_messages_decoded_total)"

# ---- 6. 汇总 ----
REPORT="$OUT_DIR/$STAMP-$LABEL.json"
export LABEL DRONES HZ CLIENTS DURATION MAX_PER_IP ONLINE READY
export M_SESSIONS M_DEPTH M_DROPPED M_ONLINE M_DECODED PROBE_RAW
export M_DEPTH_PEAK M_SESS_PEAK INJECT_PROCS
python - "$REPORT" <<PYEOF
import json, sys, os
report_path = sys.argv[1]
probe_raw = os.environ.get("PROBE_RAW", "")
probe = None
if probe_raw and os.path.exists(probe_raw):
    with open(probe_raw, encoding="utf-8") as f:
        probe = json.load(f)

def num(v):
    if v is None or v == "":
        return None
    try:
        return float(v)
    except ValueError:
        return None

out = {
    "label": os.environ["LABEL"],
    "drones": int(os.environ["DRONES"]),
    "hz": int(os.environ["HZ"]),
    "clients": int(os.environ["CLIENTS"]),
    "durationSec": int(os.environ["DURATION"]),
    "maxConnPerIp": int(os.environ["MAX_PER_IP"]),
    "injectProcs": int(os.environ["INJECT_PROCS"]),
    "onlineDrones": int(os.environ["ONLINE"]),
    "backendStartSec": int(os.environ["READY"]),
    "probe": probe,
    "metrics": {
        "wsSessions": num(os.environ.get("M_SESSIONS")),
        "queueDepth": num(os.environ.get("M_DEPTH")),
        "droppedTotal": num(os.environ.get("M_DROPPED")),
        "wsSessionsPeak": num(os.environ.get("M_SESS_PEAK")),
        "queueDepthPeak": num(os.environ.get("M_DEPTH_PEAK")),
        "dronesOnline": num(os.environ.get("M_ONLINE")),
        "messagesDecoded": num(os.environ.get("M_DECODED")),
    },
}
with open(report_path, "w", encoding="utf-8", newline="\n") as f:
    json.dump(out, f, ensure_ascii=False, indent=2)
print(report_path)
PYEOF

info "报告：$REPORT"

# ---- 7. 控制台摘要 ----
if [[ -s "$PROBE_RAW" ]]; then
    python - "$PROBE_RAW" "$ONLINE" "$DRONES" "$CLIENTS" "$M_DROPPED" "$M_DEPTH" "$M_DEPTH_PEAK" <<'PYEOF'
import json, sys
probe_path, online, drones, clients, dropped, depth, depth_peak = sys.argv[1:8]
with open(probe_path, encoding="utf-8") as f:
    p = json.load(f)
print()
print(f"  在线 {online}/{drones} 架 | 活跃连接 {p.get('activeClients')}/{clients} "
      f"| 握手成功 {p.get('handshakeOk')} | 服务端拒绝 {p.get('rejectedByServer')} "
      f"| 消息 {p['totalMessages']}")
lat = p.get("latencyMs")
if lat:
    print(f"  时延 p50={lat['p50']}ms p95={lat['p95']}ms p99={lat['p99']}ms max={lat['max']}ms")
else:
    print("  时延：无样本（逐帧推送未到达）")
print(f"  丢弃={dropped or 'n/a'} 队列深度峰值={depth_peak or 'n/a'}（末值 {depth or 'n/a'}）")
if p.get("connectFailures"):
    print(f"  ⚠ 连接失败 {p['connectFailures']} 条")
    for c in p.get("perClient", []):
        if c.get("error"):
            print(f"     #{c['index']}: {c['error']}")
            break
print()
PYEOF
else
    fail "探针未产出报告，见 $WORK/probe-$STAMP.log"
fi

exit "$PROBE_RC"
