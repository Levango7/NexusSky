#!/usr/bin/env bash
# AeroFleet Failsafe regression (Linux/CI): link-loss mid-mission must trigger
# the vehicle-side failsafe RTL (PX4 NAV_DLLC_ACT behavior), the cloud must
# flag offline during the blackout, and the drone must land by itself.
# Preconditions: cloud-backend (8080/14550) already running.
set -u
Base="http://localhost:8080/api/v1"
Fail=0

step()  { echo "== $1"; }
check() {
    if [ "$2" = "true" ]; then echo "   PASS: $1"; else echo "   FAIL: $1"; Fail=1; fi
}

# 0. backend online
curl -sf "$Base/drones" >/dev/null || { echo "backend not running, abort"; exit 1; }

# 1. fault drone on 14541 (backend default discovery includes it)
step "start fault drone (sysid=9, link-loss@60s:45s)"
java -jar drone-sim/target/aerofleet-drone-sim-0.1.0-SNAPSHOT.jar \
    --port 14541 --sysid 9 --name AF-FAILSAFE-01 --scenario link-loss:60:45 &
SIM_PID=$!

registered=false
for i in $(seq 1 20); do
    sleep 1
    if curl -sf "$Base/drones" | grep -q '"sysid":9'; then registered=true; break; fi
done
check "fault drone online (sysid=9)" "$registered"
if [ "$registered" != "true" ]; then kill $SIM_PID 2>/dev/null; exit 1; fi

# 2. long mission, blackout lands mid-flight
step "upload long mission and launch"
up=$(curl -sf -X POST "$Base/drones/9/mission" -H 'Content-Type: application/json' -d '{
  "items":[
    {"cmd":"takeoff","lat":22.5907,"lon":113.9345,"alt":30,"holdTime":0},
    {"cmd":"waypoint","lat":22.5924,"lon":113.9345,"alt":50,"holdTime":1},
    {"cmd":"waypoint","lat":22.5924,"lon":113.9370,"alt":50,"holdTime":1},
    {"cmd":"waypoint","lat":22.5907,"lon":113.9370,"alt":50,"holdTime":1},
    {"cmd":"rtl","lat":22.5907,"lon":113.9345,"alt":0,"holdTime":0}
  ]}')
echo "$up" | grep -q '"status":"ok"' && check "mission upload" true || check "mission upload" false

curl -sf -X POST "$Base/drones/9/commands" -H 'Content-Type: application/json' -d '{"type":"arm"}' >/dev/null && check "arm" true || check "arm" false
curl -sf -X POST "$Base/drones/9/commands" -H 'Content-Type: application/json' -d '{"type":"start_mission"}' >/dev/null && check "start mission" true || check "start mission" false

# 3. silent observation: offline during blackout, RTL after recovery, landed
step "silent observation of the failsafe chain (130s)"
sawOffline=false; sawRtl=false; sawLanded=false
for i in $(seq 1 26); do
    sleep 5
    row=$(curl -sf "$Base/drones" 2>/dev/null || true)
    [ -z "$row" ] && continue
    d9=$(echo "$row" | python3 -c '
import sys, json
for d in json.load(sys.stdin):
    if d["sysid"] == 9:
        print(f"{d[\"online\"]} {d[\"mode\"]} {d.get(\"armed\", False)}")
        break
' 2>/dev/null || true)
    [ -z "$d9" ] && continue
    online=$(echo "$d9" | cut -d" " -f1)
    mode=$(echo "$d9" | cut -d" " -f2)
    armed=$(echo "$d9" | cut -d" " -f3)
    echo "   [$((i*5))s] online=$online mode=$mode"
    [ "$online" = "False" ] && sawOffline=true
    [ "$online" = "True" ] && [ "$mode" = "RTL" ] && sawRtl=true
    [ "$online" = "True" ] && [ "$mode" = "STANDBY" ] && [ "$armed" = "False" ] && [ $i -gt 14 ] && sawLanded=true
done

check "cloud flagged offline during blackout" "$sawOffline"
check "vehicle self-RTL after failsafe (seen online as RTL)" "$sawRtl"
check "vehicle landed by itself (STANDBY, disarmed)" "$sawLanded"

kill $SIM_PID 2>/dev/null
if [ $Fail -eq 0 ]; then echo "ALL FAILSAFE TESTS PASSED"; exit 0
else echo "FAILSAFE TESTS FAILED"; exit 1; fi
