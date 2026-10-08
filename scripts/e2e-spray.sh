#!/usr/bin/env bash
# M2 喷洒物流端到端测试（Linux/CI 版，bash）
# 前置：cloud-backend(8080) 已运行，且已有一架在线无人机（sysid=1）。
# 断言链：创建喷洒任务 -> 查询喷洒任务 -> 控制(START/STOP) -> 创建配送任务 -> 查询站点 -> 负载清单 -> 按需负载上报
#
# 端点口径（对齐 io.aerofleet.cloud.mission.spray.SprayController / mission.delivery.DeliveryController）：
#   POST   /api/v1/spray                  创建喷洒任务，body{targetSysid,waypoints:[[lat,lon],..],targetRate,capacityMl,sprayWidth}
#   GET    /api/v1/spray/{id}             查询任务，响应含 taskId/state/coverageRate/remainingChemical/currentSegment/segmentCount
#   POST   /api/v1/spray/{id}/control     控制任务，body{action: START|PAUSE|STOP|EMERGENCY_STOP}
#   POST   /api/v1/delivery               创建配送任务，body{targetSysid,sites:[{lat,lon,alt,payloadId,payloadWeightKg,payloadVolumeL,dropAccuracyM}]}
#   GET    /api/v1/delivery/{id}          查询序列，响应 sites 为对象数组（不是数量）
#   GET    /api/v1/delivery/{id}/payload  当前负载清单
#   POST   /api/v1/delivery/{id}/payload/query  按需触发 MAV_CMD 30085 负载上报
#
# 注意：夹爪（gripper）在本仓没有 REST 端点，PayloadPlugin 是 SPI 内部接口，
#   故原脚本的 /spray/gripper 断言已移除；夹爪的**机载**能力由本脚本的
#   配送 START（MAV_CMD 30084 GRIPPER_CONTROL）覆盖。
#
# ⚠️ drone-sim 必须带 --actuators：SprayPump / Gripper 默认不注入
#   （VirtualDrone 构造器 actuatorsEnabled=false ⇒ 两者为 null），
#   于是 30083/30084/30085 的处理器一律返回 MAV_RESULT_UNSUPPORTED、回执 result=-1。
#   本脚本**要求**拿到肯定回执，把"漏了 --actuators"变成响亮的失败而不是一条告警 ——
#   那正是演示时"命令被拒"却无人察觉的由来。
#
# 便携性：JSON 解析用 python3（CI 自带），不依赖 jq。
set -euo pipefail
BASE="${AF_BACKEND_BASE:-http://localhost:8080}/api/v1"
FAIL=0
SYSID=1

step()  { echo "== $1"; }
check() { # check <desc> <expr>
  if eval "$2"; then echo "   ✅ $1"; else echo "   ❌ $1"; FAIL=1; fi
}
# 便携 JSON 取值（不依赖 jq）：jqget <json> <python-expr on d>
jqget() { python3 -c "import sys,json; d=json.loads(sys.argv[1]); print($2)" "$1" 2>/dev/null || echo ''; }

# ---- 0. 前置：后端可达 + sysid=1 在线 ----
step '前置：后端可达'
if curl -s --max-time 5 "$BASE/drones" >/dev/null 2>&1; then
  echo '   ✅ 后端可达'
else
  echo '   ❌ 后端未运行，中止'; exit 1
fi

step "等待无人机 sysid=$SYSID 上线"
online=1
for _ in $(seq 1 20); do
  if curl -s "$BASE/drones" 2>/dev/null | python3 -c "import sys,json; sys.exit(0 if any(d['sysid']==$SYSID and d.get('online') for d in json.load(sys.stdin)) else 1)" 2>/dev/null; then
    online=0; break
  fi
  sleep 1
done
check "无人机 sysid=$SYSID 已上线" "[ '$online' = '0' ]"
if [ "$online" = '1' ]; then echo '❌ M2 喷洒物流 e2e 失败：无人机未上线'; exit 1; fi

# ---- 1. 创建喷洒任务 ----
# waypoints 是 List<double[]>，JSON 形态为 [[lat,lon], ...]；targetRate/capacityMl/sprayWidth 必须 > 0
step '创建喷洒任务 (targetRate=2500mL/s, capacityMl=50000, sprayWidth=4m, 3 航点)'
TASK='{"targetSysid":1,"waypoints":[[22.5907,113.9345],[22.5917,113.9345],[22.5917,113.9355]],"targetRate":2500,"capacityMl":50000,"sprayWidth":4}'
RESP=$(curl -s --max-time 10 -X POST -H 'Content-Type: application/json' -d "$TASK" "$BASE/spray" 2>/dev/null || echo '{}')
echo "   响应: $RESP"
SPRAY_ID=$(jqget "$RESP" 'd.get("taskId")')
check "创建返回 taskId" "[ -n '$SPRAY_ID' ] && [ '$SPRAY_ID' != 'None' ]"

SEG=$(jqget "$RESP" 'd.get("segments")')
check "分段数=航点数-1(应为2)" "[ '$SEG' = '2' ]"

STATE=$(jqget "$RESP" 'd.get("state")')
# SprayTask.State = PENDING / RUNNING / PAUSED / COMPLETED / FAILED，无 CREATED
check "初始状态为 PENDING" "[ '$STATE' = 'PENDING' ]"

AREA=$(jqget "$RESP" 'd.get("totalArea")')
check "totalArea 为正数" "python3 -c \"import sys; sys.exit(0 if float('$AREA')>0 else 1)\" 2>/dev/null"

# ---- 2. 查询喷洒任务 ----
step "查询喷洒任务 GET /spray/$SPRAY_ID"
ST=$(curl -s --max-time 10 "$BASE/spray/$SPRAY_ID" 2>/dev/null || echo '{}')
echo "   响应: $ST"
QSTATE=$(jqget "$ST" 'd.get("state")')
check "查询返回 state" "[ -n '$QSTATE' ] && [ '$QSTATE' != 'None' ]"
REM=$(jqget "$ST" 'd.get("remainingChemical")')
check "remainingChemical 为正数（初始=药箱容量）" "python3 -c \"import sys; sys.exit(0 if float('$REM')>0 else 1)\" 2>/dev/null"

# ---- 3. 控制喷洒任务 ----
# DroneOfflineException → 409；响应体含 taskId 与 results
step '控制喷洒任务 START'
START_R=$(curl -s --max-time 10 -X POST -H 'Content-Type: application/json' -d '{"action":"START"}' "$BASE/spray/$SPRAY_ID/control" 2>/dev/null || echo '{}')
echo "   响应: $START_R"
check "START 返回 results 结构" "echo '$START_R' | grep -q 'results'"
# 机载回执必须为肯定。drone-sim 带 --actuators 时 SprayPump 已注入，
# handleSprayControl 才返回 MAV_RESULT_ACCEPTED；result=-1 说明执行机构没装配
# （CI 与本地脚本均已确保 --actuators），这是必须暴露的配置问题而非可容忍的噪声。
check "START 拿到机载肯定回执（需 drone-sim 带 --actuators）" "echo '$START_R' | grep -vq '\"result\":-1'"

step '控制喷洒任务 STOP'
STOP_R=$(curl -s --max-time 10 -X POST -H 'Content-Type: application/json' -d '{"action":"STOP"}' "$BASE/spray/$SPRAY_ID/control" 2>/dev/null || echo '{}')
echo "   响应: $STOP_R"
check "STOP 返回 results 结构" "echo '$STOP_R' | grep -q 'results'"
check "STOP 拿到机载肯定回执（需 drone-sim 带 --actuators）" "echo '$STOP_R' | grep -vq '\"result\":-1'"

# ---- 4. 创建配送任务 ----
step '创建配送任务 (2 站点，各带 1 件负载)'
DEL='{"targetSysid":1,"sites":[{"lat":22.5907,"lon":113.9345,"alt":80,"payloadId":1,"payloadWeightKg":2.5,"payloadVolumeL":8,"dropAccuracyM":1.5},{"lat":22.5917,"lon":113.9355,"alt":80,"payloadId":2,"payloadWeightKg":1.5,"payloadVolumeL":5,"dropAccuracyM":2.0}]}'
DRESP=$(curl -s --max-time 10 -X POST -H 'Content-Type: application/json' -d "$DEL" "$BASE/delivery" 2>/dev/null || echo '{}')
echo "   响应: $DRESP"
DEL_ID=$(jqget "$DRESP" 'd.get("deliveryId")')
check "创建返回 deliveryId" "[ -n '$DEL_ID' ] && [ '$DEL_ID' != 'None' ]"

SITECNT=$(jqget "$DRESP" 'd.get("sites")')
check "创建响应 sites=站点数2" "[ '$SITECNT' = '2' ]"

# ---- 5. 查询配送序列（sites 是对象数组）----
step "查询配送序列 GET /delivery/$DEL_ID"
SEQ=$(curl -s --max-time 10 "$BASE/delivery/$DEL_ID" 2>/dev/null || echo '{}')
echo "   响应: $SEQ"
SITES_LEN=$(jqget "$SEQ" "len(d.get('sites',[]))")
check "配送序列包含 2 个站点" "[ '$SITES_LEN' = '2' ]"
S0=$(jqget "$SEQ" "d['sites'][0]['payloadId'] if d.get('sites') else ''")
check "首站 payloadId=1" "[ '$S0' = '1' ]"
PROG=$(jqget "$SEQ" 'd.get("progress")')
check "progress 字段存在" "[ -n '$PROG' ] && [ '$PROG' != 'None' ]"

# ---- 6. 查询负载清单 ----
step '查询负载清单 GET /delivery/{id}/payload'
PAY=$(curl -s --max-time 10 "$BASE/delivery/$DEL_ID/payload" 2>/dev/null || echo '{}')
echo "   响应: $PAY"
check "负载清单含 payloads 数组" "echo '$PAY' | grep -q 'payloads'"
TW=$(jqget "$PAY" 'd.get("totalWeight")')
check "totalWeight 为数值" "[ -n '$TW' ] && [ '$TW' != 'None' ]"

# ---- 7. 按需触发机载负载上报（MAV_CMD 30085）----
step '按需触发负载上报 POST /delivery/{id}/payload/query'
PQ=$(curl -s --max-time 10 -X POST -H 'Content-Type: application/json' -d '{}' "$BASE/delivery/$DEL_ID/payload/query" 2>/dev/null || echo '{}')
echo "   响应: $PQ"
PQ_SYSID=$(jqget "$PQ" 'd.get("sysid")')
check "负载上报目标 sysid=1" "[ '$PQ_SYSID' = '1' ]"
PQ_ST=$(jqget "$PQ" 'd.get("status")')
# 同上：payload query 的处理器 handlePayloadQuery 也要求 gripper 已注入，
# 否则恒回 error。status=ok 才算机载真的回了 PAYLOAD_STATUS(30006)。
check "机载接受负载上报并回执（需 drone-sim 带 --actuators）" "[ '$PQ_ST' = 'ok' ]"

# ---- 8. 控制配送任务 ----
step '夹爪复位到 IDLE（GRIPPER_RESET 30084/subcmd=2）'
# 抓取的前置条件是 state==IDLE；上一轮跑完本脚本后夹爪停在 HOLDING，
# 直接 START 会被机载正确拒绝（MAV_RESULT_DENIED）。先复位让断言可重复执行，
# 顺带覆盖 RESET 子命令。
GRESET=$(curl -s --max-time 10 -X POST -H 'Content-Type: application/json' -d '{"action":"RESET"}' "$BASE/delivery/$DEL_ID/control" 2>/dev/null || echo '{}')
echo "   响应: $GRESET"
check "夹爪复位返回 results 结构" "echo '$GRESET' | grep -q 'results'"
check "夹爪复位拿到机载肯定回执（需 drone-sim 带 --actuators）" "echo '$GRESET' | grep -vq '\"result\":-1'"

step '控制配送任务 START'
DSTART=$(curl -s --max-time 10 -X POST -H 'Content-Type: application/json' -d '{"action":"START"}' "$BASE/delivery/$DEL_ID/control" 2>/dev/null || echo '{}')
echo "   响应: $DSTART"
check "配送 START 返回 results 结构" "echo '$DSTART' | grep -q 'results'"
check "配送 START 拿到机载肯定回执（需 drone-sim 带 --actuators）" "echo '$DSTART' | grep -vq '\"result\":-1'"

# ---- 收尾 ----
if [ "$FAIL" = '0' ]; then
  echo '✅ M2 喷洒物流 e2e 全部通过'
  exit 0
else
  echo '❌ M2 喷洒物流 e2e 失败'
  exit 1
fi
