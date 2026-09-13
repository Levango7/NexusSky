#!/usr/bin/env bash
# AeroFleet 故障注入回归（Linux/CI 版）：与 e2e-fault.ps1 同逻辑。
# 前置：cloud-backend(8080/14550) 已运行；故障模拟器由本脚本启动 (sysid=7, port=14541)。
set -u
BASE=http://localhost:8080/api/v1
JAR=drone-sim/target/aerofleet-drone-sim-0.1.0-SNAPSHOT.jar
FAIL=0

step() { echo "== $1"; }
check() { if [ "$2" = "1" ]; then echo "   PASS: $1"; else echo "   FAIL: $1"; FAIL=1; fi; }
jget() { python3 -c "import sys,json;d=json.load(sys.stdin);print(d.get('$1'))" 2>/dev/null; }

step "启动故障模拟器 (sysid=7, port=14541)"
java -jar $JAR --port 14541 --sysid 7 --name AF-FAULT-01 \
  --scenario gps-loss:8:10,battery-fault:40,link-loss:60:20 &
SIM_PID=$!
trap 'kill $SIM_PID 2>/dev/null' EXIT
sleep 4

registered=0
for i in $(seq 1 15); do
  if curl -s $BASE/drones | python3 -c "import sys,json;sys.exit(0 if any(d['sysid']==7 for d in json.load(sys.stdin)) else 1)" 2>/dev/null; then
    registered=1; break
  fi
  sleep 1
done
check "故障模拟器上线 (sysid=7)" "$registered"

step "等待 GPS 丢失窗口 (t=8..18s)"
gps_degraded=0; gps_restored=0
for i in $(seq 1 22); do
  sleep 1
  T=$(curl -s $BASE/drones/7/telemetry)
  H=$(echo "$T" | jget gpsHealthy)
  if [ "$gps_degraded" = "0" ] && [ "$H" = "False" ]; then
    gps_degraded=1
    echo "   gpsHealthy=false fixType=$(echo "$T" | jget fixType) sats=$(echo "$T" | jget satellites) (t~${i}s)"
  fi
  if [ "$gps_degraded" = "1" ] && [ "$H" = "True" ]; then gps_restored=1; break; fi
done
check "GPS 丢失被检测 (gpsHealthy=false)" "$gps_degraded"
check "GPS 恢复被检测 (gpsHealthy=true)" "$gps_restored"

step "等待电池故障 (t=40s)"
battery_fired=0
for i in $(seq 1 25); do
  sleep 1
  B=$(curl -s $BASE/drones/7/telemetry | jget battery)
  if [ -n "$B" ] && [ "$B" != "None" ] && [ "$B" -le 15 ] 2>/dev/null && [ "$B" -ge 5 ] 2>/dev/null; then
    battery_fired=1
    echo "   battery=${B}% (t~$((40+i))s)"
    break
  fi
done
check "电池故障被检测 (battery 跳变)" "$battery_fired"

step "等待链路中断 (t=60..80s)"
link_lost=0
for i in $(seq 1 35); do
  sleep 1
  ON=$(curl -s $BASE/drones | python3 -c "import sys,json;d=[x for x in json.load(sys.stdin) if x['sysid']==7];print(d[0]['online'] if d else 'missing')" 2>/dev/null)
  if [ "$ON" = "False" ]; then
    link_lost=1
    echo "   online=false (心跳超时触发, t~$((60+i))s)"
    break
  fi
done
check "链路中断被检测 (online=false)" "$link_lost"

if [ "$FAIL" = "0" ]; then echo "ALL FAULT-SCENARIO TESTS PASSED"; exit 0
else echo "FAULT-SCENARIO TESTS FAILED"; exit 1; fi
