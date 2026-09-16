#!/usr/bin/env bash
# M4 硬件抽象端到端测试（Linux/CI 版，bash）
# 前置：cloud-backend(8080) 已运行，且已有一架在线无人机（sysid=1）。
# 断言链：雷达配置 -> 雷达状态 -> 雷达目标 -> 旋翼遥测 -> LiDAR -> IMU -> 物理模型切换(aero/kinematics)
# 便携性：JSON 解析用 python3（CI 自带），不依赖 jq；字符串校验用 grep。
set -euo pipefail
BASE='http://localhost:8080/api/v1'
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
if [ "$online" = '1' ]; then echo '❌ M4 硬件抽象 e2e 失败：无人机未上线'; exit 1; fi

# ---- 1. 配置雷达扫描 ----
step '配置雷达扫描 (SECTOR_SCAN, 方位 0°, 宽度 120°, 周期 2000ms)'
CFG='{"sysid":1,"mode":"SECTOR_SCAN","azimCenterDeg":0,"azimWidthDeg":120,"scanPeriodMs":2000}'
R=$(curl -s --max-time 10 -X POST -H 'Content-Type: application/json' -d "$CFG" "$BASE/radar/config" 2>/dev/null || echo '{}')
echo "   响应: $R"
check "雷达配置返回成功" "echo '$R' | grep -qiE 'ok|success|true'"

# ---- 2. 查询雷达状态 ----
step '查询雷达状态'
sleep 1
ST=$(curl -s --max-time 10 "$BASE/radar/status/$SYSID" 2>/dev/null || echo '{}')
echo "   响应: $ST"
BEAM=$(jqget "$ST" "d.get('beamAzim')")
check "状态包含 beamAzim 字段" "[ -n '$BEAM' ] && [ '$BEAM' != 'None' ]"

# ---- 3. 查询雷达目标 ----
step '查询雷达目标（列表可能为空，但结构应正确）'
TG=$(curl -s --max-time 10 "$BASE/radar/targets/$SYSID" 2>/dev/null || echo '{}')
echo "   响应: $TG"
TGN=$(jqget "$TG" "len(d.get('targets', d.get('list', []))))")
check "目标列表结构正确" "[ -n '$TGN' ] && [ '$TGN' != 'None' ]"

# ---- 4. 查询旋翼遥测 ----
step '查询旋翼遥测'
RT=$(curl -s --max-time 10 "$BASE/rotor/telemetry/$SYSID" 2>/dev/null || echo '{}')
echo "   响应: $RT"
RPM=$(jqget "$RT" "d.get('rpm')")
check "遥测包含 rpm 字段" "[ -n '$RPM' ] && [ '$RPM' != 'None' ]"

# ---- 5. 查询 LiDAR 数据 ----
step '查询 LiDAR 数据'
LD=$(curl -s --max-time 10 "$BASE/lidar/data/$SYSID" 2>/dev/null || echo '{}')
echo "   响应: $LD"
ND=$(jqget "$LD" "d.get('nearestDistance')")
check "LiDAR 包含 nearestDistance 字段" "[ -n '$ND' ] && [ '$ND' != 'None' ]"

# ---- 6. 查询 IMU 数据 ----
step '查询 IMU 数据'
IM=$(curl -s --max-time 10 "$BASE/imu/data/$SYSID" 2>/dev/null || echo '{}')
echo "   响应: $IM"
ACCEL=$(jqget "$IM" "d.get('accel')")
GYRO=$(jqget "$IM" "d.get('gyro')")
check "IMU 包含 accel 字段" "[ -n '$ACCEL' ] && [ '$ACCEL' != 'None' ]"
check "IMU 包含 gyro 字段" "[ -n '$GYRO' ] && [ '$GYRO' != 'None' ]"

# ---- 7. 切换物理模型 (aero) ----
step '切换物理模型 (aero)'
PM=$(curl -s --max-time 10 -X POST -H 'Content-Type: application/json' -d '{"sysid":1,"model":"aero"}' "$BASE/hardware/physics-model" 2>/dev/null || echo '{}')
echo "   响应: $PM"
check "切换 aero 模型返回成功" "echo '$PM' | grep -qiE 'ok|success|true|aero'"

# ---- 8. 切换回运动学模型 (kinematics) ----
step '切换回运动学模型 (kinematics)'
PM2=$(curl -s --max-time 10 -X POST -H 'Content-Type: application/json' -d '{"sysid":1,"model":"kinematics"}' "$BASE/hardware/physics-model" 2>/dev/null || echo '{}')
echo "   响应: $PM2"
check "切换 kinematics 模型返回成功" "echo '$PM2' | grep -qiE 'ok|success|true|kinematics'"

# ---- 收尾 ----
if [ "$FAIL" = '0' ]; then
  echo '✅ M4 硬件抽象 e2e 全部通过'
  exit 0
else
  echo '❌ M4 硬件抽象 e2e 失败'
  exit 1
fi