#!/usr/bin/env bash
# e2e-emergency.sh — 空地一体化应急指挥端到端演示
#
# 流程：布控球自动发现 → 报警事件触发 → 联动规则匹配 → 无人机侦察任务启动 → 视频回传 → 应急指挥工作流
#
# 前置：cloud-backend(8080) 已运行（surveillance / alarm / mission 模块已启用）。
#       OnvifClient 为模拟实现，rapid-deploy 会返回 3 个预设布控球（海康/大华/宇视）。
#
# 便携性：JSON 格式化优先 jq，回退 python3 -m json.tool；JSON 字段提取用 python3。
#
# 用法：
#   ./scripts/e2e-emergency.sh                 # 默认 http://localhost:8080
#   BASE_URL=http://1.2.3.4:8080 ./scripts/e2e-emergency.sh
#
# 退出码：0 全流程通过；1 任一步失败。
set -uo pipefail
BASE_URL="${BASE_URL:-http://localhost:8080}"
FAIL=0

# 演示场景常量
SUBNET='192.168.1.0/24'
ONVIF_USER='admin'
ONVIF_PASS='admin123'
# 报警事件位置（北京天安门附近，演示用）
EVENT_LAT=39.9
EVENT_LON=116.4

# =====================================================================
# 工具函数
# =====================================================================

step() {  # step <标题>
  echo
  echo "================================================================"
  echo "  $1"
  echo "================================================================"
}

check() {  # check <描述> <条件表达式>
  if eval "$2"; then echo "   ✅ $1"; else echo "   ❌ $1"; FAIL=1; fi
}

# JSON 格式化输出（优先 jq，回退 python3 -m json.tool）
jsonfmt() {
  if command -v jq >/dev/null 2>&1; then
    jq . 2>/dev/null || cat
  else
    python3 -m json.tool 2>/dev/null || cat
  fi
}

# JSON 取值（便携，不依赖 jq）：jget <json字符串> <python表达式，变量为d>
jget() {
  python3 -c "import sys,json; d=json.loads(sys.argv[1]); print($2)" "$1" 2>/dev/null || echo ''
}

# 发起 HTTP 请求。用法: api_call <METHOD> <path> [body]
# 副作用: RESP=响应体, HTTP_CODE=状态码
api_call() {
  local method="$1" path="$2" body="${3:-}"
  local url="$BASE_URL$path"
  local tmp
  tmp=$(mktemp 2>/dev/null || echo "/tmp/e2e-emergency-$$.tmp")
  if [ -n "$body" ]; then
    HTTP_CODE=$(curl -s -o "$tmp" -w '%{http_code}' \
      -X "$method" -H 'Content-Type: application/json' -d "$body" "$url" 2>/dev/null)
  else
    HTTP_CODE=$(curl -s -o "$tmp" -w '%{http_code}' \
      -X "$method" "$url" 2>/dev/null)
  fi
  RESP=$(cat "$tmp" 2>/dev/null)
  rm -f "$tmp"
}

# 检查 HTTP 状态码为 2xx。用法: check_http <步骤描述>
check_http() {
  if [ "${HTTP_CODE:-0}" -ge 200 ] 2>/dev/null && [ "${HTTP_CODE:-0}" -lt 300 ] 2>/dev/null; then
    echo "   HTTP $HTTP_CODE ✅"
    return 0
  else
    echo "   HTTP ${HTTP_CODE:-N/A} ❌ — $1 失败"
    echo "   响应: $RESP"
    FAIL=1
    return 1
  fi
}

# 打印并格式化 RESP
print_resp() {
  echo "$RESP" | jsonfmt | sed 's/^/   /'
}

# =====================================================================
# 0. 前置：后端可达
# =====================================================================
step "前置：后端可达 $BASE_URL"
if curl -s --max-time 5 "$BASE_URL/api/surveillance/devices" >/dev/null 2>&1; then
  echo "   ✅ 后端可达"
else
  echo "   ❌ 后端 $BASE_URL 未运行，中止演示"
  exit 1
fi

# =====================================================================
# 1. 布控球自动发现 + 一键注册（rapid-deploy）
# =====================================================================
step "1/9 布控球自动发现 + 一键注册（POST /api/surveillance/rapid-deploy）"
echo "   子网: $SUBNET  用户: $ONVIF_USER"
DEPLOY_BODY="{\"subnet\":\"$SUBNET\",\"username\":\"$ONVIF_USER\",\"password\":\"$ONVIF_PASS\"}"
api_call POST /api/surveillance/rapid-deploy "$DEPLOY_BODY"
check_http "布控球快速部署" || { echo "   跳过后续步骤"; exit 1; }
echo "   响应:"
print_resp
DEPLOY_COUNT=$(jget "$RESP" 'd.get("count",0)')
check "发现并注册设备数 >= 1" "[ '${DEPLOY_COUNT:-0}' -ge 1 ] 2>/dev/null"

# =====================================================================
# 2. 查看已注册设备
# =====================================================================
step "2/9 查看已注册设备（GET /api/surveillance/devices）"
api_call GET /api/surveillance/devices
check_http "查询设备列表" || exit 1
echo "   响应:"
print_resp
DEV_COUNT=$(jget "$RESP" 'd.get("count",0)')
# 取第一个设备 ID，用于后续报警事件来源（演示更真实）
FIRST_DEVICE_ID=$(jget "$RESP" "d.get('devices',[{}])[0].get('id','')" 2>/dev/null)
if [ -z "$FIRST_DEVICE_ID" ] || [ "$FIRST_DEVICE_ID" = "None" ]; then
  FIRST_DEVICE_ID="cam-1-xxx"
fi
check "已注册设备数 >= 1" "[ '${DEV_COUNT:-0}' -ge 1 ] 2>/dev/null"
echo "   首个设备 ID: $FIRST_DEVICE_ID"

# =====================================================================
# 3. 创建报警联动规则
# =====================================================================
# 注意：后端 AlarmController.parseRule 期望字段为 matchEventType / matchSeverity
#       （而非 eventType / minSeverity），此处与后端实现对齐。
step "3/9 创建报警联动规则（POST /api/alarms/rules）"
echo "   规则: 火灾自动侦察 — FIRE+CRITICAL → DEPLOY_DRONE(2架, 半径100m, 高度60m)"
RULE_BODY='{"name":"火灾自动侦察","matchEventType":"FIRE","matchSeverity":"CRITICAL","actionType":"DEPLOY_DRONE","droneCount":2,"targetRadiusM":100,"altitudeM":60}'
api_call POST /api/alarms/rules "$RULE_BODY"
check_http "创建联动规则" || exit 1
echo "   响应:"
print_resp
RULE_ID=$(jget "$RESP" 'd.get("ruleId","")')
check "返回 ruleId 非空" "[ -n '$RULE_ID' ] && [ '$RULE_ID' != 'None' ]"
echo "   规则 ID: $RULE_ID"

# =====================================================================
# 4. 模拟报警事件（触发联动）
# =====================================================================
step "4/9 模拟报警事件（POST /api/alarms/events）— 触发联动规则"
echo "   来源设备: $FIRST_DEVICE_ID  位置: ($EVENT_LAT, $EVENT_LON)"
EVENT_BODY="{\"sourceDeviceId\":\"$FIRST_DEVICE_ID\",\"eventType\":\"FIRE\",\"severity\":\"CRITICAL\",\"lat\":$EVENT_LAT,\"lon\":$EVENT_LON,\"description\":\"布控球检测到火情\"}"
api_call POST /api/alarms/events "$EVENT_BODY"
check_http "接收报警事件" || exit 1
echo "   响应（含联动触发结果）:"
print_resp
EVENT_ID=$(jget "$RESP" 'd.get("eventId","")')
MATCHED_COUNT=$(jget "$RESP" 'd.get("matchedCount",0)')
check "返回 eventId 非空" "[ -n '$EVENT_ID' ] && [ '$EVENT_ID' != 'None' ]"
check "联动规则匹配数 >= 1" "[ '${MATCHED_COUNT:-0}' -ge 1 ] 2>/dev/null"
echo "   事件 ID: $EVENT_ID  匹配规则数: $MATCHED_COUNT"

# =====================================================================
# 5. 查看报警事件列表
# =====================================================================
step "5/9 查看报警事件列表（GET /api/alarms/events）"
api_call GET /api/alarms/events
check_http "查询报警事件列表" || exit 1
echo "   响应:"
print_resp
EVENT_TOTAL=$(jget "$RESP" 'd.get("total",0)')
check "事件列表总数 >= 1" "[ '${EVENT_TOTAL:-0}' -ge 1 ] 2>/dev/null"

# =====================================================================
# 6. 确认报警
# =====================================================================
step "6/9 确认报警（POST /api/alarms/events/{eventId}/ack）"
echo "   事件 ID: $EVENT_ID"
api_call POST "/api/alarms/events/$EVENT_ID/ack" '{}'
check_http "确认报警" || exit 1
echo "   响应:"
print_resp
ACKED=$(jget "$RESP" 'd.get("acknowledged",False)')
check "acknowledged == true" "[ '$ACKED' = 'True' ]"

# =====================================================================
# 7. 创建应急指挥命令（接报）
# =====================================================================
# 注意：后端路径为 /api/emergency-command（非 /api/v1/emergency/commands），
#       字段为 incidentType / severity（非 scenarioType / radius），此处与后端对齐。
step "7/9 创建应急指挥命令（POST /api/emergency-command）— 接报"
echo "   事件类型: 火灾  严重程度: CRITICAL  位置: ($EVENT_LAT, $EVENT_LON)"
CMD_BODY="{\"incidentType\":\"火灾\",\"severity\":\"CRITICAL\",\"lat\":$EVENT_LAT,\"lon\":$EVENT_LON,\"description\":\"布控球火情侦察\",\"reporterName\":\"e2e-demo\",\"reporterContact\":\"110\"}"
api_call POST /api/emergency-command "$CMD_BODY"
check_http "创建应急指挥命令" || exit 1
echo "   响应:"
print_resp
COMMAND_ID=$(jget "$RESP" 'd.get("id","")')
CMD_PHASE=$(jget "$RESP" 'd.get("currentPhase","")')
check "返回 commandId 非空" "[ -n '$COMMAND_ID' ] && [ '$COMMAND_ID' != 'None' ]"
echo "   命令 ID: $COMMAND_ID  当前阶段: $CMD_PHASE"

# =====================================================================
# 8. 一键应急响应（自动走完 接报→研判→部署→执行→评估→总结）
# =====================================================================
step "8/9 一键应急响应（POST /api/emergency-command/{id}/one-click）"
echo "   命令 ID: $COMMAND_ID"
echo "   预期阶段流转: 接报 → 研判 → 部署 → 执行 → 评估 → 总结"
api_call POST "/api/emergency-command/$COMMAND_ID/one-click" '{}'
check_http "一键应急响应" || exit 1
echo "   响应:"
print_resp
FINAL_PHASE=$(jget "$RESP" 'd.get("currentPhase","")')
PHASE_HIST_SIZE=$(jget "$RESP" 'd.get("phaseHistorySize",0)')
check "最终阶段为 CLOSED/SUMMARY" "echo '$FINAL_PHASE' | grep -qiE 'CLOSED|SUMMARY|总结'"
check "阶段转移历史 >= 4 步" "[ '${PHASE_HIST_SIZE:-0}' -ge 4 ] 2>/dev/null"
echo "   最终阶段: $FINAL_PHASE  转移步数: $PHASE_HIST_SIZE"

# =====================================================================
# 9. 查看应急指挥历史
# =====================================================================
step "9/9 查看应急指挥历史（GET /api/emergency-command）"
api_call GET /api/emergency-command
check_http "查询应急指挥历史" || exit 1
echo "   响应:"
print_resp
CMD_TOTAL=$(jget "$RESP" 'd.get("total",0)')
check "历史命令数 >= 1" "[ '${CMD_TOTAL:-0}' -ge 1 ] 2>/dev/null"

# =====================================================================
# 演示总结
# =====================================================================
step "演示总结"
echo "   后端地址:        $BASE_URL"
echo "   布控球子网:      $SUBNET"
echo "   注册设备数:      ${DEPLOY_COUNT:-0}"
echo "   联动规则 ID:     ${RULE_ID:-N/A}"
echo "   报警事件 ID:     ${EVENT_ID:-N/A}  (匹配规则 ${MATCHED_COUNT:-0} 条)"
echo "   应急命令 ID:     ${COMMAND_ID:-N/A}"
echo "   命令最终阶段:    ${FINAL_PHASE:-N/A}  (转移 ${PHASE_HIST_SIZE:-0} 步)"
echo "   历史命令数:      ${CMD_TOTAL:-0}"
echo
if [ "$FAIL" = '0' ]; then
  echo "✅ 空地一体化应急指挥端到端演示全部通过"
  echo "   完整流程: 布控球自动发现 → 报警事件触发 → 联动规则匹配 → 应急指挥工作流(一键响应)"
  exit 0
else
  echo "❌ 空地一体化应急指挥端到端演示存在失败步骤（见上方 ❌ 标记）"
  exit 1
fi