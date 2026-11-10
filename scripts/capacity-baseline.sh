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
#   --udp-rcvbuf B   后端 UDP 接收缓冲区字节数（默认 0 = 沿用 OS 默认）。
#                    摄入侧是单线程串行收包，突发下内核缓冲溢出会静默丢包；
#                    加大缓冲是不改线程模型的最低成本缓解手段
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
UDP_RCVBUF=0
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
        --udp-rcvbuf) UDP_RCVBUF="$2"; shift 2 ;;
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
# 只有显式调过缓冲区才进文件名，避免改变既有档位的命名
if [[ "$UDP_RCVBUF" -gt 0 ]]; then LABEL="${LABEL}-rb${UDP_RCVBUF}"; fi
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
info "启动后端（dev profile, port=$PORT, udp=$UDP_PORT, max-connections-per-ip=$MAX_PER_IP, udp-rcvbuf=$UDP_RCVBUF）"
"$JAVA" -Xmx1g -jar "$BACKEND_JAR" \
    --spring.profiles.active=dev \
    --server.port="$PORT" \
    --aerofleet.udp-port="$UDP_PORT" \
    --aerofleet.udp.receive-buffer-bytes="$UDP_RCVBUF" \
    --aerofleet.ws.max-connections-per-ip="$MAX_PER_IP" \
    --aerofleet.ws.max-connections-per-tenant=200 \
    --spring.datasource.url="jdbc:h2:mem:capacity;DB_CLOSE_DELAY=-1" \
    --logging.level.io.aerofleet=WARN \
    --logging.level.io.aerofleet.mavlink.transport=INFO \
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
# 时长比测量多 10s：探针比注入器晚启动约 2s（等后端就绪 + 等设备上线），多 10s 足够。
# 刻意不取 30s：注入器跑得越久，datagrams 的统计窗口就越长于注入日志末行，
# 「注入量 − 收到量」算出的内核丢包失真越大（实测 30s 富余会算出负数，见第 5 节）。
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
        --target "127.0.0.1:$UDP_PORT" --duration "$((DURATION + 10))" \
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
# 先等注入器自然结束，再采 PROM：注入器的「总帧」覆盖全程，若此时 datagrams 只
# 覆盖到探针结束时刻，两者相减会把「还没发出的帧」误算成内核丢包
# （实测三档都差约 5%，与窗口误差同量级，无法区分）。等它结束后两个口径才同窗。
for pid in "${INJECT_PIDS[@]}"; do
    for _ in $(seq 1 20); do
        kill -0 "$pid" 2>/dev/null || break
        sleep 1
    done
done

PROM="$(curl -s -m 20 "http://localhost:$PORT/actuator/prometheus" 2>/dev/null)"
echo "$PROM" > "$WORK/prom-$STAMP.txt"
[[ -n "$PROM" ]] || fail "prometheus 采集为空（见 $WORK/backend-$STAMP.log），指标将记 null"
metric() {
    echo "$PROM" | grep -E "^$1(\{| )" | head -1 | awk '{print $NF}'
}
# 带标签的 Counter 会展开成多条序列（messages.received 按 msgId、ingest.dropped 按 reason）。
# 上面 metric() 只取 head -1，对这些指标会漏掉其余序列，必须按需求和或按标签取值。
metric_sum() {
    echo "$PROM" | grep -E "^$1(\{| )" | awk '{s+=$NF} END{printf "%d", s+0}'
}
metric_reason() {
    echo "$PROM" | grep -E "^$1\{" | grep -F "reason=\"$2\"" | head -1 | awk '{print $NF}'
}
M_SESSIONS="$(metric aerofleet_ws_sessions)"
M_DEPTH="$(metric aerofleet_ws_fanout_queue_depth)"
M_DROPPED="$(metric aerofleet_ws_fanout_dropped_total)"
M_ONLINE="$(metric aerofleet_drones_online)"
M_DECODED="$(metric aerofleet_messages_decoded_total)"

# 接入侧与摄入侧（2026-11-10 跨模块桥接后新增）：
# 这批指标把「摄入丢包」从「注入量 − 探针量」反推，变成后端自己可归因的计数。
M_RECEIVED="$(metric_sum aerofleet_messages_received_total)"
M_ERRORS="$(metric aerofleet_messages_errors_total)"
M_DATAGRAMS="$(metric aerofleet_udp_ingest_datagrams_total)"
M_NOISE_BYTES="$(metric aerofleet_udp_ingest_noise_bytes_total)"
M_INGEST_DROP_TOTAL="$(metric_sum aerofleet_udp_ingest_dropped_total)"
M_DROP_CRC="$(metric_reason aerofleet_udp_ingest_dropped_total frame-crc-error)"
M_DROP_SIG="$(metric_reason aerofleet_udp_ingest_dropped_total frame-signature-rejected)"
M_DROP_WL="$(metric_reason aerofleet_udp_ingest_dropped_total frame-whitelist-rejected)"
M_DROP_RL="$(metric_reason aerofleet_udp_ingest_dropped_total frame-rate-limited)"
M_DROP_TRUNC="$(metric_reason aerofleet_udp_ingest_dropped_total datagram-truncated)"
M_DROP_RXERR="$(metric_reason aerofleet_udp_ingest_dropped_total datagram-receive-error)"
M_DROP_LISTENER="$(metric_reason aerofleet_udp_ingest_dropped_total frame-listener-error)"

# 注入端累计送出包数，用于和 udp.ingest.datagrams 对账。
# 注入器每 10s 打印「[inject] t=<秒>s 高频帧=<累计> … 心跳=<累计>」，
# send() 每帧一个 DatagramPacket，故 1 帧 = 1 个数据报。
# 只取 t <= DURATION 的最后一行：注入器比测量多跑 30s，末条日志会晚于
# Prometheus 采集时刻，直接用会把「多跑的那段」算进送出量，凭空造出内核丢包。
INJECT_SENT=0
INJECT_LOG_T=0
for f in "$WORK"/inject-"$STAMP"-p*.log; do
    [[ -f "$f" ]] || continue
    # 优先用收尾行「结束：…总帧 N」：它覆盖注入器全程，与「等注入器结束后采集的
    # datagrams」同一窗口，是内核丢包对账唯一可靠的分子。进度行是 10s 粒度，
    # 只作退路（例如注入器被 kill、没来得及打印收尾行）。
    fin="$(sed -n 's/.*总帧 \([0-9]*\).*/\1/p' "$f" 2>/dev/null | tail -1)"
    if [[ -n "$fin" ]]; then
        INJECT_SENT=$((INJECT_SENT + fin))
        INJECT_LOG_T=$((DURATION + 10))
        continue
    fi
    line="$(awk -v d="$((DURATION + 10))" '
        match($0, /t=[0-9]+s/) {
            t = substr($0, RSTART + 2, RLENGTH - 3) + 0
            if (t <= d) { last = $0; lastT = t }
        }
        END { if (last != "") printf "%s\n%d\n", last, lastT }
    ' "$f" 2>/dev/null)"
    [[ -n "$line" ]] || continue
    row="$(echo "$line" | head -1)"
    tval="$(echo "$line" | tail -1)"
    task="$(echo "$row" | sed -n 's/.*高频帧=\([0-9]*\).*/\1/p')"
    hb="$(echo "$row" | sed -n 's/.*心跳=\([0-9]*\).*/\1/p')"
    INJECT_SENT=$((INJECT_SENT + ${task:-0} + ${hb:-0}))
    [[ "${tval:-0}" -gt "$INJECT_LOG_T" ]] && INJECT_LOG_T="${tval:-0}"
done
info "摄入侧：数据报=${M_DATAGRAMS:-n/a} 解析错误=${M_ERRORS:-n/a} 噪声字节=${M_NOISE_BYTES:-n/a} 丢弃=${M_INGEST_DROP_TOTAL:-n/a}"
# Prometheus 的值是浮点（7188.0），$(( )) 只吃整数，必须先截掉小数部分。
DG_INT="${M_DATAGRAMS%%.*}"
DG_INT="${DG_INT:-0}"
if [[ "$INJECT_SENT" -gt 0 && "$DG_INT" -gt 0 ]]; then
    if [[ "$INJECT_SENT" -ge "$DG_INT" ]]; then
        info "注入端送出=${INJECT_SENT} 帧（截至 t=${INJECT_LOG_T}s）→ 内核层丢弃 ≈ $(( INJECT_SENT - DG_INT )) 包"
    else
        # 收到多于送出是物理上不可能的，只能说明两个窗口没对齐：
        # PROM 在探针结束后才采（curl 还要几秒），而注入器日志只到 t=DURATION。
        info "注入端送出=${INJECT_SENT} 帧 < 后端收到=${DG_INT} 包：采样窗口未对齐，本次不做内核丢包对账"
    fi
fi

# ---- 6. 汇总 ----
REPORT="$OUT_DIR/$STAMP-$LABEL.json"
export LABEL DRONES HZ CLIENTS DURATION MAX_PER_IP ONLINE READY
export M_SESSIONS M_DEPTH M_DROPPED M_ONLINE M_DECODED PROBE_RAW
export M_DEPTH_PEAK M_SESS_PEAK INJECT_PROCS UDP_RCVBUF
export M_RECEIVED M_ERRORS M_DATAGRAMS M_NOISE_BYTES M_INGEST_DROP_TOTAL
export M_DROP_CRC M_DROP_SIG M_DROP_WL M_DROP_RL M_DROP_TRUNC M_DROP_RXERR M_DROP_LISTENER
export INJECT_SENT INJECT_LOG_T
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
    "udpReceiveBufferBytes": int(os.environ.get("UDP_RCVBUF") or 0),
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
        "messagesReceived": num(os.environ.get("M_RECEIVED")),
        "messagesErrors": num(os.environ.get("M_ERRORS")),
        "udpDatagrams": num(os.environ.get("M_DATAGRAMS")),
        "udpNoiseBytes": num(os.environ.get("M_NOISE_BYTES")),
        "ingestDroppedTotal": num(os.environ.get("M_INGEST_DROP_TOTAL")),
        "ingestDroppedByReason": {
            "frame-crc-error": num(os.environ.get("M_DROP_CRC")),
            "frame-signature-rejected": num(os.environ.get("M_DROP_SIG")),
            "frame-whitelist-rejected": num(os.environ.get("M_DROP_WL")),
            "frame-rate-limited": num(os.environ.get("M_DROP_RL")),
            "datagram-truncated": num(os.environ.get("M_DROP_TRUNC")),
            "datagram-receive-error": num(os.environ.get("M_DROP_RXERR")),
            "frame-listener-error": num(os.environ.get("M_DROP_LISTENER")),
        },
    },
    "injector": {
        "sentFrames": int(os.environ.get("INJECT_SENT") or 0),
        "sentFramesAtSec": int(os.environ.get("INJECT_LOG_T") or 0),
        "kernelDropEstimate": None,
        "kernelDropWindowAligned": None,
    },
}
# 内核层丢弃只能间接对账：Java DatagramSocket 无跨平台溢出计数。
# 误差来源是注入日志的 10s 打印粒度（sentFrames 截至 t 秒，指标截至采集时刻），
# 量级 = 10s × 注入速率，只能当量级参考，不能当精确值。
#
# 差值为负意味着「收到的比送出的还多」，物理上不可能——只说明 PROM 采集时刻
# 晚于注入器日志末行（探针结束后 curl 还要几秒）。此时必须记 null 并标记未对齐，
# 否则报告里会出现负数丢包这种自相矛盾的结论。
if out["injector"]["sentFrames"] > 0 and out["metrics"]["udpDatagrams"] is not None:
    delta = out["injector"]["sentFrames"] - out["metrics"]["udpDatagrams"]
    aligned = delta >= 0
    out["injector"]["kernelDropWindowAligned"] = aligned
    out["injector"]["kernelDropEstimate"] = delta if aligned else None
with open(report_path, "w", encoding="utf-8", newline="\n") as f:
    json.dump(out, f, ensure_ascii=False, indent=2)
print(report_path)
PYEOF

info "报告：$REPORT"

# ---- 7. 控制台摘要 ----
if [[ -s "$PROBE_RAW" ]]; then
    python - "$PROBE_RAW" "$ONLINE" "$DRONES" "$CLIENTS" "$M_DROPPED" "$M_DEPTH" "$M_DEPTH_PEAK" \
        "$M_DATAGRAMS" "$M_ERRORS" "$M_INGEST_DROP_TOTAL" "$INJECT_SENT" <<'PYEOF'
import json, sys
(probe_path, online, drones, clients, dropped, depth, depth_peak,
 datagrams, errors, ingest_drop, sent) = sys.argv[1:12]
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
print(f"  摄入：数据报={datagrams or 'n/a'} 解析错误={errors or 'n/a'} 帧 摄入丢弃={ingest_drop or 'n/a'} 帧")
try:
    sent_n = int(sent or 0)
    dg_n = int(float(datagrams)) if datagrams else 0
    if sent_n > 0 and dg_n > 0:
        if sent_n >= dg_n:
            print(f"  注入端送出={sent_n} 帧 → 内核层丢弃≈{sent_n - dg_n}"
                  "（日志 10s 粒度，仅量级参考）")
        else:
            # 收到多于送出不可能，只说明 PROM 采集晚于注入器日志末行
            print(f"  注入端送出={sent_n} 帧 < 后端收到={dg_n} 包：采样窗口未对齐，不做对账")
except (TypeError, ValueError):
    pass
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
