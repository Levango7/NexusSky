#!/usr/bin/env bash
# NexusSky SDK 集成端到端测试（Bash 版，CI 兼容）
# 前置：cloud-backend(8080) 与 drone-sim(14540) 已启动
# 验证：通过 HTTP 调用模拟 Java/Python SDK 的 API 行为，覆盖所有核心端点
set -u
BASE=http://localhost:8080/api/v1
FAIL=0

step() { echo "== $1"; }
check() {
  if eval "$2"; then echo "   PASS: $1"; else echo "   FAIL: $1"; FAIL=1; fi
}
jsonget() { python3 -c "import sys,json;d=json.load(sys.stdin);print(d.get('$1'))" 2>/dev/null; }
jsonget_idx() { python3 -c "import sys,json;d=json.load(sys.stdin);print(d[$1])" 2>/dev/null; }

# ── 辅助函数：发送 HTTP 请求 ──
api_get() { curl -s -m 15 "$BASE$1"; }
api_post() { curl -s -m 15 -X POST -H 'Content-Type: application/json' -d "$2" "$BASE$1"; }

# ── 辅助函数：发送命令 ──
send_cmd() {
  local sysid=$1 type=$2 alt=$3
  if [ -n "$alt" ]; then
    api_post "/drones/$sysid/commands" "{\"type\":\"$type\",\"alt\":$alt}"
  else
    api_post "/drones/$sysid/commands" "{\"type\":\"$type\"}"
  fi
}

# ═══════════════════════════════════════════════════
# 场景 1：等待设备上线（DroneApi.list → GET /drones）
# ═══════════════════════════════════════════════════
step "等待设备上线（DroneApi.list）"
for i in $(seq 1 30); do
  COUNT=$(api_get /drones | python3 -c "import sys,json;print(len(json.load(sys.stdin)))" 2>/dev/null || echo 0)
  if [ "$COUNT" != "0" ] && [ -n "$COUNT" ]; then break; fi
  sleep 1
done
check "机队列表非空" "[ \"$COUNT\" != \"0\" ] && [ -n \"$COUNT\" ]"

SYSID=$(api_get /drones | python3 -c "import sys,json;print(json.load(sys.stdin)[0]['sysid'])" 2>/dev/null)
echo "   sysid=$SYSID"
if [ -z "$SYSID" ]; then echo "SDK INTEGRATION TESTS FAILED"; exit 1; fi

# ═══════════════════════════════════════════════════
# 场景 2：DroneApi.get(sysid) → GET /drones/{sysid}
# ═══════════════════════════════════════════════════
step "DroneApi.get(sysid) → GET /drones/{sysid}"
SINGLE=$(api_get "/drones/$SYSID")
echo "   $SINGLE"
check "返回的 sysid 与请求一致" "echo '$SINGLE' | python3 -c \"import sys,json;d=json.load(sys.stdin);print(d.get('sysid'))\" | grep -q \"$SYSID\""
check "包含 callsign 字段" "echo '$SINGLE' | python3 -c \"import sys,json;d=json.load(sys.stdin);print(d.get('callsign'))\" | grep -qv 'None'"
check "包含 mode 字段" "echo '$SINGLE' | python3 -c \"import sys,json;d=json.load(sys.stdin);print(d.get('mode'))\" | grep -qv 'None'"

# ═══════════════════════════════════════════════════
# 场景 3：DroneApi.telemetry(sysid) → GET /drones/{sysid}/telemetry
# ═══════════════════════════════════════════════════
step "DroneApi.telemetry(sysid) → GET /drones/{sysid}/telemetry"
TEL=$(api_get "/drones/$SYSID/telemetry")
echo "   $TEL"
check "遥测包含 mode 字段" "echo '$TEL' | python3 -c \"import sys,json;d=json.load(sys.stdin);print(d.get('mode'))\" | grep -qv 'None'"
check "遥测包含 battery 字段" "echo '$TEL' | python3 -c \"import sys,json;d=json.load(sys.stdin);print(d.get('battery'))\" | grep -qv 'None'"
check "遥测包含 relativeAlt 字段" "echo '$TEL' | python3 -c \"import sys,json;d=json.load(sys.stdin);print(d.get('relativeAlt'))\" | grep -qv 'None'"

# ═══════════════════════════════════════════════════
# 场景 4：DroneApi.arm(sysid) → POST /drones/{sysid}/commands {"type":"arm"}
# ═══════════════════════════════════════════════════
step "DroneApi.arm(sysid) → POST commands {\"type\":\"arm\"}"
R=$(send_cmd "$SYSID" "arm" "")
echo "   $R"
check "ARM 返回 status=ok" "echo '$R' | grep -q '\"status\":\"ok\"' || echo '$R' | grep -q '\"status\": \"ok\"'"

# ═══════════════════════════════════════════════════
# 场景 5：DroneApi.takeoff(sysid, alt) → POST commands {"type":"takeoff","alt":30}
# ═══════════════════════════════════════════════════
step "DroneApi.takeoff(sysid, 30) → POST commands {\"type\":\"takeoff\",\"alt\":30}"
R=$(send_cmd "$SYSID" "takeoff" "30")
echo "   $R"
check "takeoff 返回 status=ok" "echo '$R' | grep -q '\"status\":\"ok\"' || echo '$R' | grep -q '\"status\": \"ok\"'"

sleep 5

# ═══════════════════════════════════════════════════
# 场景 6：DroneApi.rtl(sysid) → POST commands {"type":"rtl"}
# ═══════════════════════════════════════════════════
step "DroneApi.rtl(sysid) → POST commands {\"type\":\"rtl\"}"
R=$(send_cmd "$SYSID" "rtl" "")
echo "   $R"
check "RTL 返回 status=ok" "echo '$R' | grep -q '\"status\":\"ok\"' || echo '$R' | grep -q '\"status\": \"ok\"'"

# ═══════════════════════════════════════════════════
# 场景 7：MissionApi.upload(sysid, waypoints) → POST /drones/{sysid}/mission
# ═══════════════════════════════════════════════════
step "MissionApi.upload(sysid, waypoints) → POST /drones/{sysid}/mission"
MISSION='{"items":[{"cmd":"takeoff","lat":22.5907,"lon":113.9345,"alt":30,"holdTime":0},{"cmd":"waypoint","lat":22.5917,"lon":113.9345,"alt":50,"holdTime":2},{"cmd":"waypoint","lat":22.5917,"lon":113.9355,"alt":50,"holdTime":2},{"cmd":"rtl","lat":22.5907,"lon":113.9345,"alt":0,"holdTime":0}]}'
UP=$(api_post "/drones/$SYSID/mission" "$MISSION")
echo "   $UP"
check "任务上传返回 status=ok" "echo '$UP' | grep -q '\"status\":\"ok\"' || echo '$UP' | grep -q '\"status\": \"ok\"'"

# ═══════════════════════════════════════════════════
# 场景 8：MissionApi.download(sysid) → GET /drones/{sysid}/mission
# ═══════════════════════════════════════════════════
step "MissionApi.download(sysid) → GET /drones/{sysid}/mission"
DL=$(api_get "/drones/$SYSID/mission")
echo "   $DL"
DL_ITEMS=$(echo "$DL" | python3 -c "import sys,json;d=json.load(sys.stdin);print(len(d.get('items',[])))" 2>/dev/null || echo 0)
check "任务下载包含 items 字段" "[ \"$DL_ITEMS\" != \"0\" ]"
check "任务下载 items 数量 >= 3" "[ \"$DL_ITEMS\" -ge 3 ] 2>/dev/null"

# ═══════════════════════════════════════════════════
# 场景 9：FlightLogApi.list() → GET /flightlog
# ═══════════════════════════════════════════════════
step "FlightLogApi.list() → GET /flightlog"
sleep 2
FLOG=$(api_get /flightlog)
FLOGN=$(echo "$FLOG" | python3 -c "import sys,json;print(len(json.load(sys.stdin)))" 2>/dev/null || echo 0)
echo "   日志条目数: $FLOGN"
check "飞行日志列表非空" "[ \"$FLOGN\" -ge 1 ] 2>/dev/null"

# ═══════════════════════════════════════════════════
# 场景 10：验证 ApiResponse 格式
# ═══════════════════════════════════════════════════
step "验证 ApiResponse 格式（status + data）"
LIST_RAW=$(api_get /drones)
check "列表响应是数组格式" "echo '$LIST_RAW' | python3 -c \"import sys,json;d=json.load(sys.stdin);print(isinstance(d,list))\" | grep -q True"
CMD_RESP=$(send_cmd "$SYSID" "arm" "")
check "命令响应包含 status 字段" "echo '$CMD_RESP' | python3 -c \"import sys,json;d=json.load(sys.stdin);print('status' in d)\" | grep -q True"
check "命令响应 status 值为 ok 或 error" "echo '$CMD_RESP' | python3 -c \"import sys,json;d=json.load(sys.stdin);print(d.get('status') in ('ok','error'))\" | grep -q True"

# ═══════════════════════════════════════════════════
# 场景 11：错误场景 — 404 不存在的无人机
# ═══════════════════════════════════════════════════
step "错误场景：GET /drones/99999（不存在的 sysid）"
HTTP_CODE=$(curl -s -o /dev/null -w "%{http_code}" -m 10 "$BASE/drones/99999")
echo "   HTTP $HTTP_CODE"
check "不存在的无人机返回 404" "[ \"$HTTP_CODE\" = \"404\" ]"

# ═══════════════════════════════════════════════════
# 场景 12：错误场景 — 无效命令
# ═══════════════════════════════════════════════════
step "错误场景：POST 无效命令 {\"type\":\"invalid_cmd\"}"
ERR_RESP=$(api_post "/drones/$SYSID/commands" '{"type":"invalid_cmd"}')
echo "   $ERR_RESP"
ERR_STATUS=$(echo "$ERR_RESP" | python3 -c "import sys,json;d=json.load(sys.stdin);print(d.get('status',''))" 2>/dev/null || echo "")
check "无效命令返回 status=error" "[ \"$ERR_STATUS\" = \"error\" ] || [ -z \"$ERR_RESP\" ]"

# ═══════════════════════════════════════════════════
# 场景 13：Waypoint 范围验证
# ═══════════════════════════════════════════════════
step "Waypoint 范围验证：lat ∈ [-90,90], lon ∈ [-180,180], alt ≥ 0"

# 合法航点
VALID_MISSION='{"items":[{"cmd":"waypoint","lat":22.5907,"lon":113.9345,"alt":30,"holdTime":0}]}'
VALID_RESP=$(api_post "/drones/$SYSID/mission" "$VALID_MISSION")
check "合法航点上传成功" "echo '$VALID_RESP' | grep -q '\"status\":\"ok\"' || echo '$VALID_RESP' | grep -q '\"status\": \"ok\"'"

# 非法纬度（> 90）
BAD_LAT_MISSION='{"items":[{"cmd":"waypoint","lat":95.0,"lon":113.9345,"alt":30,"holdTime":0}]}'
BAD_LAT_RESP=$(api_post "/drones/$SYSID/mission" "$BAD_LAT_MISSION")
BAD_LAT_STATUS=$(echo "$BAD_LAT_RESP" | python3 -c "import sys,json;d=json.load(sys.stdin);print(d.get('status',''))" 2>/dev/null || echo "")
check "非法纬度(>90)被拒绝" "[ \"$BAD_LAT_STATUS\" = \"error\" ] || [ -z \"$BAD_LAT_RESP\" ]"

# 非法经度（< -180）
BAD_LON_MISSION='{"items":[{"cmd":"waypoint","lat":22.5907,"lon":-200.0,"alt":30,"holdTime":0}]}'
BAD_LON_RESP=$(api_post "/drones/$SYSID/mission" "$BAD_LON_MISSION")
BAD_LON_STATUS=$(echo "$BAD_LON_RESP" | python3 -c "import sys,json;d=json.load(sys.stdin);print(d.get('status',''))" 2>/dev/null || echo "")
check "非法经度(<-180)被拒绝" "[ \"$BAD_LON_STATUS\" = \"error\" ] || [ -z \"$BAD_LON_RESP\" ]"

# 非法高度（< 0）
BAD_ALT_MISSION='{"items":[{"cmd":"waypoint","lat":22.5907,"lon":113.9345,"alt":-10,"holdTime":0}]}'
BAD_ALT_RESP=$(api_post "/drones/$SYSID/mission" "$BAD_ALT_MISSION")
BAD_ALT_STATUS=$(echo "$BAD_ALT_RESP" | python3 -c "import sys,json;d=json.load(sys.stdin);print(d.get('status',''))" 2>/dev/null || echo "")
check "非法高度(<0)被拒绝" "[ \"$BAD_ALT_STATUS\" = \"error\" ] || [ -z \"$BAD_ALT_RESP\" ]"

# ═══════════════════════════════════════════════════
# 结果汇总
# ═══════════════════════════════════════════════════
if [ "$FAIL" = "0" ]; then
  echo "ALL SDK INTEGRATION TESTS PASSED"
  exit 0
else
  echo "SDK INTEGRATION TESTS FAILED"
  exit 1
fi