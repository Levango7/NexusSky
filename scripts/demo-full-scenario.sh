#!/usr/bin/env bash
# NexusSky 综合演示场景脚本（Linux/bash 版本）
#
# 讲述一个完整的无人机协同作业故事：
#   场景1：单机侦察 — 一架无人机起飞执行侦察任务并返航
#   场景2：多机协同 — 检测到异常，增派两架无人机协同作业
#   场景3：编队包围 — 三机组成编队，队形变换，灯光同步
#   场景4：应急响应 — 一架无人机失联，自动返航，剩余机继续任务
#   场景5：追踪恢复 — 定位失联无人机，引导搜索
#
# 用法：
#   bash scripts/demo-full-scenario.sh
#   bash scripts/demo-full-scenario.sh --skip-build
#
# 前置：JDK 17 + Maven + Python3 + curl

set -u
SKIP_BUILD=0
[[ "${1:-}" == "--skip-build" ]] && SKIP_BUILD=1

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
FAIL=0
BASE="http://localhost:8080/api/v1"
BACKEND_PID=""
SIM_PIDS=""

# ---- 端口配置 ----
BACKEND_REST_PORT=8080
DRONE_SIM_PORTS=(14551 14552 14553)
DRONE_SYSIDS=(1 2 3)

# ---- 演示参数 ----
REF_LAT=22.5431
REF_LON=113.9578
TAKEOFF_ALT=30

# ---- 输出辅助 ----
banner() { echo ""; echo "================================================"; echo "  $1"; echo "================================================"; }
step() { echo ""; echo "-- $1"; }
check() { if eval "$2"; then echo "   [PASS] $1"; else echo "   [FAIL] $1"; FAIL=1; fi; }
info() { echo "   $1"; }
story() { echo ""; echo "   >>> $1"; }

# ---- JSON 解析 ----
jsonget() { python3 -c "import sys,json;d=json.load(sys.stdin);print(d.get('$1'))" 2>/dev/null; }
jsonlenG() { python3 -c "import sys,json;print(len(json.load(sys.stdin)))" 2>/dev/null; }

# ---- HTTP 请求 ----
apiget() { curl -s --max-time 10 "$BASE$1" 2>/dev/null; }
apipost() { curl -s --max-time 10 -X POST -H 'Content-Type: application/json' -d "$2" "$BASE$1" 2>/dev/null; }

# ---- 清理 ----
cleanup() {
    banner "系统清理"
    for pid in $SIM_PIDS; do
        info "停止 drone-sim PID=$pid"
        kill "$pid" 2>/dev/null || true
    done
    if [ -n "$BACKEND_PID" ]; then
        info "停止 cloud-backend PID=$BACKEND_PID"
        kill "$BACKEND_PID" 2>/dev/null || true
    fi
    info "清理完成"
}
trap cleanup EXIT

# ---- 主流程 ----
banner "NexusSky 综合演示场景"
echo "讲述一个完整的无人机协同作业故事"
echo ""
echo "场景1：单机侦察 — 一架无人机起飞执行侦察任务并返航"
echo "场景2：多机协同 — 检测到异常，增派两架无人机协同作业"
echo "场景3：编队包围 — 三机组成编队，队形变换，灯光同步"
echo "场景4：应急响应 — 一架无人机失联，自动返航"
echo "场景5：追踪恢复 — 定位失联无人机，引导搜索"

# ============================================================
# 0. 构建与启动
# ============================================================
if [ "$SKIP_BUILD" -eq 0 ]; then
    banner "步骤0：构建系统"
    step "Maven 构建（跳过测试）"
    (cd "$ROOT" && mvn -q -DskipTests package) || { echo "[ERROR] Maven 构建失败"; exit 1; }
    check "Maven 构建成功" "[ -f '$ROOT/drone-sim/target/aerofleet-drone-sim-0.1.0-SNAPSHOT.jar' ]"
fi

step "启动 cloud-backend"
BACKEND_JAR="$ROOT/cloud-backend/target/aerofleet-cloud-backend-0.1.0-SNAPSHOT.jar"
[ -f "$BACKEND_JAR" ] || { echo "[ERROR] cloud-backend jar 不存在"; exit 1; }
java -jar "$BACKEND_JAR" &
BACKEND_PID=$!
info "cloud-backend PID=$BACKEND_PID"

# 等待后端启动
for i in $(seq 1 45); do
    if curl -s --max-time 2 "$BASE/drones" >/dev/null 2>&1; then break; fi
    sleep 2
done
check "cloud-backend 启动" "curl -s --max-time 2 '$BASE/drones' >/dev/null 2>&1"

step "启动 3 台 drone-sim"
DRONE_SIM_JAR="$ROOT/drone-sim/target/aerofleet-drone-sim-0.1.0-SNAPSHOT.jar"
[ -f "$DRONE_SIM_JAR" ] || { echo "[ERROR] drone-sim jar 不存在"; exit 1; }
for i in 0 1 2; do
    PORT=${DRONE_SIM_PORTS[$i]}
    SYSID=${DRONE_SYSIDS[$i]}
    java -jar "$DRONE_SIM_JAR" --port "$PORT" --sysid "$SYSID" &
    SIM_PIDS="$SIM_PIDS $!"
    info "drone-sim sysid=$SYSID port=$PORT PID=$!"
done

step "等待 3 台无人机注册"
REGISTERED=0
for i in $(seq 1 20); do
    COUNT=$(curl -s "$BASE/drones" 2>/dev/null | python3 -c "import sys,json;print(len(json.load(sys.stdin)))" 2>/dev/null || echo 0)
    if [ "$COUNT" -ge 3 ]; then REGISTERED=1; break; fi
    sleep 2
done
check "3 台无人机注册" "[ '$REGISTERED' = '1' ]"

# ============================================================
# 场景1：单机侦察
# ============================================================
banner "场景1：单机侦察"
story "一架无人机起飞，沿预定航线执行侦察任务，完成后返航。"

SYSID1=1
step "上传侦察任务（方形航线）"
MISSION='{"items":[{"cmd":"takeoff","lat":'"$REF_LAT"',"lon":'"$REF_LON"',"alt":'"$TAKEOFF_ALT"',"holdTime":0},{"cmd":"waypoint","lat":'"$(python3 -c "print($REF_LAT+0.001)")"',"lon":'"$REF_LON"',"alt":'"$TAKEOFF_ALT"',"holdTime":2},{"cmd":"waypoint","lat":'"$(python3 -c "print($REF_LAT+0.001)")"',"lon":'"$(python3 -c "print($REF_LON+0.001)")"',"alt":'"$TAKEOFF_ALT"',"holdTime":2},{"cmd":"waypoint","lat":'"$REF_LAT"',"lon":'"$(python3 -c "print($REF_LON+0.001)")"',"alt":'"$TAKEOFF_ALT"',"holdTime":2},{"cmd":"rtl","lat":'"$REF_LAT"',"lon":'"$REF_LON"',"alt":0,"holdTime":0}]}'
UP=$(apipost "/drones/$SYSID1/mission" "$MISSION")
check "任务上传" "echo '$UP' | grep -q ok"

step "ARM 解锁"
R=$(apipost "/drones/$SYSID1/commands" '{"type":"arm"}')
check "ARM" "echo '$R' | grep -q ok"

step "开始任务"
R=$(apipost "/drones/$SYSID1/commands" '{"type":"start_mission"}')
check "start_mission" "echo '$R' | grep -q ok"

step "等待任务推进"
sleep 8
TEL=$(apiget "/drones/$SYSID1/telemetry")
SEQ=$(echo "$TEL" | jsonget missionSeq)
info "missionSeq=$SEQ"
check "遥测数据有效" "[ -n '$SEQ' ] && [ '$SEQ' != 'None' ]"
check "任务序列号推进" "[ -n '$SEQ' ] && [ '$SEQ' != '0' ] && [ '$SEQ' != 'None' ]"

step "返航 RTL"
R=$(apipost "/drones/$SYSID1/commands" '{"type":"rtl"}')
check "RTL" "echo '$R' | grep -q ok"

step "验证轨迹记录"
sleep 3
TRK=$(apiget "/drones/$SYSID1/track")
TRKN=$(echo "$TRK" | jsonlenG 2>/dev/null || echo 0)
info "轨迹点数：$TRKN"
check "轨迹点数 > 1" "[ '$TRKN' -gt 1 ] 2>/dev/null"

# ============================================================
# 场景2：多机协同部署
# ============================================================
banner "场景2：多机协同部署"
story "侦察发现异常情况，指挥中心增派两架无人机前往协同作业。"

SYSID2=2
SYSID3=3

step "2号机起飞执行巡逻任务"
MISSION2='{"items":[{"cmd":"takeoff","lat":'"$(python3 -c "print($REF_LAT-0.001)")"',"lon":'"$REF_LON"',"alt":'"$TAKEOFF_ALT"',"holdTime":0},{"cmd":"waypoint","lat":'"$(python3 -c "print($REF_LAT-0.002)")"',"lon":'"$(python3 -c "print($REF_LON+0.002)")"',"alt":'"$TAKEOFF_ALT"',"holdTime":2},{"cmd":"waypoint","lat":'"$(python3 -c "print($REF_LAT-0.001)")"',"lon":'"$(python3 -c "print($REF_LON+0.003)")"',"alt":'"$TAKEOFF_ALT"',"holdTime":2},{"cmd":"rtl","lat":'"$(python3 -c "print($REF_LAT-0.001)")"',"lon":'"$REF_LON"',"alt":0,"holdTime":0}]}'
R=$(apipost "/drones/$SYSID2/mission" "$MISSION2")
check "2号机任务上传" "echo '$R' | grep -q ok"
R=$(apipost "/drones/$SYSID2/commands" '{"type":"arm"}')
check "2号机 ARM" "echo '$R' | grep -q ok"
R=$(apipost "/drones/$SYSID2/commands" '{"type":"start_mission"}')
check "2号机开始任务" "echo '$R' | grep -q ok"

step "3号机起飞执行监控任务"
MISSION3='{"items":[{"cmd":"takeoff","lat":'"$REF_LAT"',"lon":'"$(python3 -c "print($REF_LON-0.001)")"',"alt":'"$TAKEOFF_ALT"',"holdTime":0},{"cmd":"waypoint","lat":'"$(python3 -c "print($REF_LAT+0.001)")"',"lon":'"$(python3 -c "print($REF_LON-0.002)")"',"alt":'"$TAKEOFF_ALT"',"holdTime":2},{"cmd":"waypoint","lat":'"$(python3 -c "print($REF_LAT+0.002)")"',"lon":'"$(python3 -c "print($REF_LON-0.001)")"',"alt":'"$TAKEOFF_ALT"',"holdTime":2},{"cmd":"rtl","lat":'"$REF_LAT"',"lon":'"$(python3 -c "print($REF_LON-0.001)")"',"alt":0,"holdTime":0}]}'
R=$(apipost "/drones/$SYSID3/mission" "$MISSION3")
check "3号机任务上传" "echo '$R' | grep -q ok"
R=$(apipost "/drones/$SYSID3/commands" '{"type":"arm"}')
check "3号机 ARM" "echo '$R' | grep -q ok"
R=$(apipost "/drones/$SYSID3/commands" '{"type":"start_mission"}')
check "3号机开始任务" "echo '$R' | grep -q ok"

step "验证多机同时在线"
sleep 5
DRONES=$(apiget '/drones')
ONLINE_COUNT=$(echo "$DRONES" | python3 -c "import sys,json;d=json.load(sys.stdin);print(sum(1 for x in d if x.get('online')))" 2>/dev/null || echo 0)
info "在线数量：$ONLINE_COUNT"
check "至少 2 机在线" "[ '$ONLINE_COUNT' -ge 2 ] 2>/dev/null"

step "验证多机遥测"
TEL2=$(apiget "/drones/$SYSID2/telemetry")
TEL3=$(apiget "/drones/$SYSID3/telemetry")
LAT2=$(echo "$TEL2" | jsonget lat)
LAT3=$(echo "$TEL3" | jsonget lat)
info "2号机 lat=$LAT2"
info "3号机 lat=$LAT3"
check "2号机遥测有效" "[ -n '$LAT2' ] && [ '$LAT2' != 'None' ]"
check "3号机遥测有效" "[ -n '$LAT3' ] && [ '$LAT3' != 'None' ]"

# ============================================================
# 场景3：编队包围
# ============================================================
banner "场景3：编队包围"
story "三架无人机组队形成编队，执行队形变换和灯光同步演示。"

step "创建三机编队（LINE 队形）"
FORMATION_PAYLOAD='{"members":[1,2,3],"shape":"LINE","spacingM":5,"headingDeg":0,"refLat":'"$REF_LAT"',"refLon":'"$REF_LON"',"refAlt":0}'
FORMATION=$(apipost '/formation/create' "$FORMATION_PAYLOAD")
FORMATION_ID=$(echo "$FORMATION" | jsonget formationId)
info "编队ID：$FORMATION_ID"
check "编队创建" "[ -n '$FORMATION_ID' ] && [ '$FORMATION_ID' != 'None' ]"

if [ -n "$FORMATION_ID" ] && [ "$FORMATION_ID" != "None" ]; then
    step "编队起飞"
    R=$(apipost "/formation/$FORMATION_ID/command" "{\"type\":\"TAKEOFF\",\"alt\":$TAKEOFF_ALT}")
    check "编队起飞命令" "echo '$R' | grep -q ok"

    step "等待编队状态 STABLE"
    STABLE=0
    for i in $(seq 1 30); do
        F=$(apiget "/formation/$FORMATION_ID")
        STATE=$(echo "$F" | jsonget state)
        if [ "$STATE" = "STABLE" ]; then STABLE=1; break; fi
        sleep 2
    done
    check "编队 STABLE" "[ '$STABLE' = '1' ]"

    if [ "$STABLE" = "1" ]; then
        step "队形变换 LINE → CIRCLE"
        R=$(apipost "/formation/$FORMATION_ID/transition" '{"newShape":"CIRCLE","steps":4}')
        check "队形变换命令" "echo '$R' | grep -q ok"

        sleep 5
        F=$(apiget "/formation/$FORMATION_ID")
        F_STATE=$(echo "$F" | jsonget state)
        F_SHAPE=$(echo "$F" | jsonget shape)
        info "编队状态：$F_STATE 队形：$F_SHAPE"

        step "灯光同步演示（RAINBOW 模式）"
        R=$(apipost "/formation/$FORMATION_ID/lights" '{"pattern":"RAINBOW"}')
        check "灯光同步命令" "echo '$R' | grep -q ok"

        sleep 3
        LIGHTS=$(apiget "/formation/$FORMATION_ID/lights")
        LIGHT_PATTERN=$(echo "$LIGHTS" | jsonget pattern)
        info "灯光状态：$LIGHT_PATTERN"

        step "解散编队"
        R=$(apipost "/formation/$FORMATION_ID/command" '{"type":"DISSOLVE"}')
        check "编队解散" "echo '$R' | grep -q ok"
    fi
fi

# ============================================================
# 场景4：应急响应
# ============================================================
banner "场景4：应急响应"
story "一架无人机突然失联，系统检测到链路中断，触发自动返航保护。"

step "1号机执行 RTL（模拟应急返航）"
R=$(apipost "/drones/$SYSID1/commands" '{"type":"rtl"}')
check "1号机 RTL" "echo '$R' | grep -q ok"

step "验证告警生成"
sleep 3
FLOG=$(apiget "/flightlog?sysid=$SYSID1&limit=50")
FLOGN=$(echo "$FLOG" | jsonlenG 2>/dev/null || echo 0)
info "飞行日志条数：$FLOGN"
check "飞行日志记录" "[ '$FLOGN' -gt 0 ] 2>/dev/null"

step "2号机和3号机继续执行任务"
TEL2=$(apiget "/drones/$SYSID2/telemetry")
TEL3=$(apiget "/drones/$SYSID3/telemetry")
LAT2=$(echo "$TEL2" | jsonget lat)
LAT3=$(echo "$TEL3" | jsonget lat)
check "2号机仍在线" "[ -n '$LAT2' ] && [ '$LAT2' != 'None' ]"
check "3号机仍在线" "[ -n '$LAT3' ] && [ '$LAT3' != 'None' ]"

step "2号机执行 RTL"
R=$(apipost "/drones/$SYSID2/commands" '{"type":"rtl"}')
check "2号机 RTL" "echo '$R' | grep -q ok"

step "3号机执行 RTL"
R=$(apipost "/drones/$SYSID3/commands" '{"type":"rtl"}')
check "3号机 RTL" "echo '$R' | grep -q ok"

# ============================================================
# 场景5：追踪恢复
# ============================================================
banner "场景5：追踪恢复"
story "系统查询失联无人机的历史轨迹，为搜索提供引导数据。"

step "查询1号机历史轨迹"
TRK1=$(apiget "/drones/$SYSID1/track")
TRK1N=$(echo "$TRK1" | jsonlenG 2>/dev/null || echo 0)
info "1号机轨迹点数：$TRK1N"
check "1号机轨迹存在" "[ '$TRK1N' -gt 1 ] 2>/dev/null"

step "查询2号机历史轨迹"
TRK2=$(apiget "/drones/$SYSID2/track")
TRK2N=$(echo "$TRK2" | jsonlenG 2>/dev/null || echo 0)
info "2号机轨迹点数：$TRK2N"
check "2号机轨迹存在" "[ '$TRK2N' -gt 1 ] 2>/dev/null"

step "查询3号机历史轨迹"
TRK3=$(apiget "/drones/$SYSID3/track")
TRK3N=$(echo "$TRK3" | jsonlenG 2>/dev/null || echo 0)
info "3号机轨迹点数：$TRK3N"
check "3号机轨迹存在" "[ '$TRK3N' -gt 1 ] 2>/dev/null"

step "查询飞行日志（全机队）"
FLOG=$(apiget "/flightlog?limit=100")
FLOGN=$(echo "$FLOG" | jsonlenG 2>/dev/null || echo 0)
info "飞行日志总条数：$FLOGN"
check "飞行日志记录丰富" "[ '$FLOGN' -ge 3 ] 2>/dev/null"

# ============================================================
# 总结
# ============================================================
banner "演示总结"
if [ "$FAIL" = "0" ]; then
    echo "  所有场景验证通过！"
    echo "  NexusSky 系统完整功能链路演示成功。"
else
    echo "  部分场景验证失败，请检查日志。"
fi
echo ""
echo "  演示覆盖："
echo "    - 单机侦察飞行（任务上传→ARM→航线飞行→RTL→轨迹记录）"
echo "    - 多机协同部署（3机同时在线→各自独立任务→遥测并行）"
echo "    - 编队包围（创建→起飞→队形变换→灯光同步→解散）"
echo "    - 应急响应（RTL保护→飞行日志→剩余机继续任务）"
echo "    - 追踪恢复（历史轨迹查询→飞行日志全量审计）"

if [ "$FAIL" = "0" ]; then
    echo ""
    echo "[SUCCESS] NexusSky 综合演示全部通过"
    exit 0
else
    echo ""
    echo "[FAILURE] NexusSky 综合演示存在失败项"
    exit 1
fi