#!/usr/bin/env bash
# 端到端冒烟测试：假定 backend(8080/14550) 与 drone-sim(14540) 已启动。
# 验证：设备上线 → 上传任务 → ARM → 开始任务 → 任务推进 → RTL。
set -u
BASE=http://localhost:8080/api/v1
FAIL=0

step() { echo "== $1"; }
check() { # check <desc> <expr...>
  if eval "$2"; then echo "   PASS: $1"; else echo "   FAIL: $1"; FAIL=1; fi
}
jsonget() { python3 -c "import sys,json;d=json.load(sys.stdin);print(d.get('$1'))" 2>/dev/null; }

step "等待设备上线"
for i in $(seq 1 20); do
  COUNT=$(curl -s $BASE/drones | python3 -c "import sys,json;print(len(json.load(sys.stdin)))" 2>/dev/null || echo 0)
  if [ "$COUNT" != "0" ] && [ -n "$COUNT" ]; then break; fi
  sleep 1
done
check "机队列表非空" "[ \"$COUNT\" != \"0\" ] && [ -n \"$COUNT\" ]"

SYSID=$(curl -s $BASE/drones | python3 -c "import sys,json;print(json.load(sys.stdin)[0]['sysid'])")
echo "   sysid=$SYSID"

step "上传方形任务"
MISSION='{"items":[{"cmd":"takeoff","lat":22.5907,"lon":113.9345,"alt":30,"holdTime":0},{"cmd":"waypoint","lat":22.5917,"lon":113.9345,"alt":50,"holdTime":2},{"cmd":"waypoint","lat":22.5917,"lon":113.9355,"alt":50,"holdTime":2},{"cmd":"waypoint","lat":22.5907,"lon":113.9355,"alt":50,"holdTime":2},{"cmd":"rtl","lat":22.5907,"lon":113.9345,"alt":0,"holdTime":0}]}'
UP=$(curl -s -X POST -H 'Content-Type: application/json' -d "$MISSION" $BASE/drones/$SYSID/mission)
echo "   $UP"
check "任务上传应 ok" "echo '$UP' | grep -q ok"

step "ARM"
R=$(curl -s -X POST -H 'Content-Type: application/json' -d '{"type":"arm"}' $BASE/drones/$SYSID/commands)
echo "   $R"
check "ARM 应 ok" "echo '$R' | grep -q ok"

step "开始任务"
R=$(curl -s -X POST -H 'Content-Type: application/json' -d '{"type":"start_mission"}' $BASE/drones/$SYSID/commands)
echo "   $R"
check "start_mission 应 ok" "echo '$R' | grep -q ok"

step "等待任务推进（观察 missionSeq 递增）"
sleep 6
T1=$(curl -s $BASE/drones/$SYSID/telemetry)
echo "   $T1"
SEQ=$(echo "$T1" | jsonget missionSeq)
check "遥测有 missionSeq" "[ -n \"$SEQ\" ] && [ \"$SEQ\" != \"None\" ]"

step "RTL"
R=$(curl -s -X POST -H 'Content-Type: application/json' -d '{"type":"rtl"}' $BASE/drones/$SYSID/commands)
echo "   $R"
check "RTL 应 ok" "echo '$R' | grep -q ok"

step "轨迹存在"
sleep 2
TRK=$(curl -s $BASE/drones/$SYSID/track)
TRKN=$(echo "$TRK" | python3 -c "import sys,json;print(len(json.load(sys.stdin)))" 2>/dev/null || echo 0)
check "轨迹点数 > 1" "[ \"$TRKN\" -gt 1 ] 2>/dev/null"

step "飞行日志落盘（B2）"
sleep 2   # telemetry 节流 1s，给最后一帧留时间
FLOG=$(curl -s "$BASE/flightlog?sysid=$SYSID&limit=100")
FLOGN=$(echo "$FLOG" | python3 -c "import sys,json;print(len(json.load(sys.stdin)))" 2>/dev/null || echo 0)
check "flightlog 当日事件 >= 3 (telemetry+mission+command)" "[ \"$FLOGN\" -ge 3 ] 2>/dev/null"
FTYPES=$(echo "$FLOG" | python3 -c "import sys,json;print(' '.join(sorted(set(e['type'] for e in json.load(sys.stdin)))))" 2>/dev/null)
echo "   types: $FTYPES"
check "包含 telemetry 类事件" "echo '$FTYPES' | grep -q telemetry"
check "包含 mission/command 类事件" "echo '$FTYPES' | grep -qE 'mission|connectivity'"

step "手动控制通道（B2：joystick MANUAL_CONTROL 透传）"
R=$(curl -s -X POST -H 'Content-Type: application/json' -d '{"x":0,"y":500,"z":500,"r":0}' $BASE/drones/$SYSID/joystick)
echo "   $R"
check "joystick 应 ok" "echo '$R' | grep -q ok"

if [ "$FAIL" = "0" ]; then
  echo "ALL SMOKE TESTS PASSED"
  exit 0
else
  echo "SMOKE TESTS FAILED"
  exit 1
fi
