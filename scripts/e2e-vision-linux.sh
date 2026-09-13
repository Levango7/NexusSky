#!/usr/bin/env bash
# AeroFleet 视觉链路回归（Linux/CI 版，Batch A5）
# 前置：cloud-backend(8080) 已运行；PATH 上的 java >= 17（CI 的 temurin 17 满足）。
# 断言链：相机会话(518/520/521) -> 单拍定位(误差<2m) -> 环绕4站(站站检出,误差<3m)
#        -> 航迹(>=1 条 ACTIVE, >=4 hits)
set -u
BASE='http://localhost:8080/api/v1'
FAIL=0
JAR="$(dirname "$0")/../drone-sim/target/aerofleet-drone-sim-0.1.0-SNAPSHOT.jar"

step()  { echo "== $1"; }
check() {
  if [ "$2" = "0" ]; then echo "   PASS: $1"
  else echo "   FAIL: $1"; FAIL=1; fi
}

jqget() { # jqget <json> <python-expr on d>
  python3 -c "import sys,json; d=json.loads(sys.argv[1]); print($2)" "$1"
}

# ---- 0. 前置 ----
curl -s --max-time 5 "$BASE/drones" >/dev/null || { echo '后端未运行，中止'; exit 1; }

# ---- 1. 视觉模拟器（目标 A=环绕中心, B=中心东 30m；环半径 25m@60m 全程入画）----
step '启动视觉模拟器 (sysid=9, mavlink=14542, truth=18080)'
java -jar "$JAR" --port 14542 --sysid 9 --name AF-VISION-01 --http-port 18080 \
  --targets 'static:22.5916,113.9345;static:22.5916,113.93479' &
SIM_PID=$!
sleep 3

registered=1
for i in $(seq 1 15); do
  sleep 1
  # 必须在线：offline 残影不算注册成功
  if curl -s "$BASE/drones" | python3 -c "import sys,json; sys.exit(0 if any(d['sysid']==9 and d['online'] for d in json.load(sys.stdin)) else 1)" 2>/dev/null; then registered=0; break; fi
done
check '视觉模拟器上线 (sysid=9)' "$registered"
[ "$registered" = "1" ] && { kill $SIM_PID 2>/dev/null; echo 'VISION E2E FAILED'; exit 1; }

cleanup() { kill $SIM_PID 2>/dev/null; }
trap cleanup EXIT

# ---- 2. 相机会话 ----
step '相机会话 (CAMERA_INFORMATION / SETTINGS / CAPTURE_STATUS)'
# 等 2s：让后端的 GCS HEARTBEAT 探测（1s 周期）把地址教给 sim（lastPeer 学习竞态）
sleep 2
for cmd in 518 520 521; do
  ack=$(curl -s --max-time 10 -X POST -H 'Content-Type: application/json' \
    -d "{\"type\":\"raw\",\"cmd\":$cmd}" "$BASE/drones/9/commands")
  res=$(jqget "$ack" 'd["result"]')
  [ "$res" = "MAV_RESULT_0" ]; check "MAV_CMD $cmd ACK accepted ($res)" "$?"
done

# ---- 3. 单拍定位 ----
step '起飞 -> 飞临目标上空 -> 单拍 -> truth score'
curl -s -X POST -H 'Content-Type: application/json' -d '{"type":"arm"}' "$BASE/drones/9/commands" >/dev/null
curl -s -X POST -H 'Content-Type: application/json' -d '{"type":"takeoff","alt":60}' "$BASE/drones/9/commands" >/dev/null
sleep 8
curl -s -X POST -H 'Content-Type: application/json' \
  -d '{"items":[{"cmd":"waypoint","lat":22.5916,"lon":113.9345,"alt":60,"holdTime":2}]}' \
  "$BASE/drones/9/mission" >/dev/null
curl -s -X POST -H 'Content-Type: application/json' -d '{"type":"start_mission"}' "$BASE/drones/9/commands" >/dev/null
sleep 18
cap=$(curl -s --max-time 30 -X POST "$BASE/vision/drones/9/capture")
n=$(jqget "$cap" 'len(d["detections"])')
[ "$n" -ge 1 ]; check "拍照完成且检出目标 ($n 个)" "$?"
maxerr=$(jqget "$cap" 'max([x["truthErrorM"] for x in d["detections"]] or [99])')
awk -v e="$maxerr" 'BEGIN{exit !(e<2)}'; check "定位误差 < 2m (max=$maxerr m)" "$?"

# ---- 4. 环绕闭环 ----
step '环绕 orbit -> 逐站拍照 -> 航迹'
orb=$(curl -s --max-time 300 -X POST -H 'Content-Type: application/json' \
  -d '{"lat":22.5916,"lon":113.9345,"radiusM":25,"altM":60,"photos":4}' \
  "$BASE/vision/drones/9/orbit")
taken=$(jqget "$orb" 'd["photosTaken"]')
[ "$taken" = "4" ]; check "环绕完成 (photosTaken=$taken/4)" "$?"
stations=$(jqget "$orb" 'sum(1 for s in d["shots"] if len(s["detections"])>=1)')
[ "$stations" = "4" ]; check "每站都有检出 (>=1 的站数=$stations)" "$?"
omax=$(jqget "$orb" 'max([x["truthErrorM"] for s in d["shots"] for x in s["detections"]] or [99])')
awk -v e="$omax" 'BEGIN{exit !(e<3)}'; check "全环定位误差 < 3m (max=$omax m)" "$?"
for s in $(seq 0 3); do
  echo "   station[$s] dets=$(jqget "$orb" "len(d['shots'][$s]['detections'])") r/p/y=$(jqget "$orb" "d['shots'][$s].get('droneRollDeg'),d['shots'][$s].get('dronePitchDeg'),d['shots'][$s].get('droneYawDeg')")"
done

# ---- 5. 航迹 ----
step '航迹查询 tracks'
tr=$(curl -s "$BASE/vision/drones/9/tracks")
active=$(jqget "$tr" 'sum(1 for t in d["tracks"] if t["state"]=="ACTIVE")')
[ "$active" -ge 1 ]; check "存在 ACTIVE 航迹 ($active 条)" "$?"
hits4=$(jqget "$tr" 'sum(1 for t in d["tracks"] if t["hits"]>=4)')
[ "$hits4" -ge 1 ]; check "至少一条航迹 >= 4 hits（环绕 4 站连续命中）" "$?"
for t in $(jqget "$tr" "range(len(d['tracks']))"); do
  echo "   track id=$(jqget "$tr" "d['tracks'][$t]['trackId']") state=$(jqget "$tr" "d['tracks'][$t]['state']") hits=$(jqget "$tr" "d['tracks'][$t]['hits']")"
done

if [ "$FAIL" = "0" ]; then echo 'VISION E2E PASSED'; exit 0
else echo 'VISION E2E FAILED'; exit 1; fi
