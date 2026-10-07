#!/usr/bin/env bash
# M4 硬件抽象端到端测试（Linux/CI 版，bash）
# 前置：cloud-backend(8080) 已运行，且已有一架在线无人机（sysid=1）。
# 断言链：雷达配置 -> 雷达状态 -> 雷达目标 -> 旋翼遥测 -> LiDAR -> IMU
#
# 端点口径（对齐 io.aerofleet.cloud.api.controller.HardwareDataController）：
#   POST /api/v1/radar/config          body{sysid,mode,azimCenter,azimWidth,elevCenter,beamWidth,range,scanPeriodMs,enabled}
#                                     —— 字段名是 azimCenter/azimWidth（不带 Deg 后缀），且 6 个数值字段全部必填
#   GET  /api/v1/radar/config/{sysid}  查询配置（未配置 → 404）
#   GET  /api/v1/radar/status/{sysid}  查询扫描状态（未配置也返回 200 + 默认值）
#   GET  /api/v1/radar/targets/{sysid} 目标列表，响应 targets 为数组，可能为空
#   POST /api/v1/rotor/config          body{sysid,rotorCount,diameter,pitch,maxRpm,airDensity}
#   GET  /api/v1/rotor/telemetry/{sysid} 气动遥测（未配置也返回 200 + 默认值）
#   GET  /api/v1/lidar/data/{sysid}    LiDAR 数据（无数据 → 404 no LiDAR data）
#   GET  /api/v1/imu/data/{sysid}      IMU 数据（无数据 → 404 no IMU data）
#
# 关于物理模型切换：仓内没有 POST /hardware/physics-model 端点（已全量检索确认），
#   原脚本的 aero/kinematics 断言指向不存在的端点，已移除。动力侧改用真实存在的
#   /rotor/config + /rotor/telemetry 覆盖。
#
# 关于 LiDAR/IMU：drone-sim 默认不注入 LiDARSource/ImuSource（VirtualDrone 字段初值为 null，
#   DroneSimMain 未装配），因此这两个端点在 CI 默认返回 404。此时只断言"端点契约正确"，
#   不把 404 判为失败——真实载荷数据需硬件或显式装配 Source 后才可断言。
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
skip()  { echo "   ⚠️  $1（按契约允许，不判失败）"; }
# 便携 JSON 取值（不依赖 jq）：jqget <json> <python-expr on d>
jqget() { python3 -c "import sys,json; d=json.loads(sys.argv[1]); print($2)" "$1" 2>/dev/null || echo ''; }
# 取 HTTP 状态码：httpcode <url>
httpcode() { curl -s -o /dev/null -w '%{http_code}' --max-time 10 "$1" 2>/dev/null || echo '000'; }

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
# 字段名对齐 HardwareDataController.configureRadar：azimCenter/azimWidth/elevCenter/
# beamWidth/range/scanPeriodMs 全部由 num() 读取，缺一即 400 "missing numeric field"
step '配置雷达扫描 (SECTOR_SCAN, 方位 0°±60°, 俯仰 -10°±20°, 量程 500m, 周期 2000ms)'
CFG='{"sysid":1,"mode":"SECTOR_SCAN","azimCenter":0,"azimWidth":120,"elevCenter":-10,"beamWidth":20,"range":500,"scanPeriodMs":2000,"enabled":true}'
R=$(curl -s --max-time 10 -X POST -H 'Content-Type: application/json' -d "$CFG" "$BASE/radar/config" 2>/dev/null || echo '{}')
echo "   响应: $R"
check "雷达配置返回 200 且无 error 字段" "echo '$R' | grep -q 'azimCenter' && ! echo '$R' | grep -q '\"error\"'"
MODE=$(jqget "$R" 'd.get("mode")')
check "回显 mode=SECTOR_SCAN" "[ '$MODE' = 'SECTOR_SCAN' ]"
RNG=$(jqget "$R" 'd.get("range")')
# 响应里是 JSON double（500.0），按数值比较而非字符串
check "回显 range=500" "python3 -c \"import sys; sys.exit(0 if abs(float('$RNG')-500.0)<1e-6 else 1)\" 2>/dev/null"

# ---- 2. 校验配置可回读 ----
step '回读雷达配置 GET /radar/config/{sysid}'
RC=$(curl -s --max-time 10 "$BASE/radar/config/$SYSID" 2>/dev/null || echo '{}')
echo "   响应: $RC"
check "配置可回读且 scanPeriodMs=2000" "echo '$RC' | grep -q 'scanPeriodMs' && echo '$RC' | grep -q '2000'"

# ---- 3. 查询雷达状态（未收到扫描帧时返回默认值，字段仍应齐备）----
step '查询雷达状态'
ST=$(curl -s --max-time 10 "$BASE/radar/status/$SYSID" 2>/dev/null || echo '{}')
echo "   响应: $ST"
BEAM=$(jqget "$ST" 'd.get("beamAzim")')
check "状态包含 beamAzim 字段" "[ -n '$BEAM' ] && [ '$BEAM' != 'None' ]"
TC=$(jqget "$ST" 'd.get("targetCount")')
check "状态包含 targetCount 字段" "[ -n '$TC' ] && [ '$TC' != 'None' ]"

# ---- 4. 查询雷达目标（数组可为空，但结构须正确）----
step '查询雷达目标'
TG=$(curl -s --max-time 10 "$BASE/radar/targets/$SYSID" 2>/dev/null || echo '{}')
echo "   响应: $TG"
TGN=$(jqget "$TG" "len(d.get('targets',[]))")
check "targets 为数组（长度>=0）" "[ -n '$TGN' ] && [ '$TGN' != 'None' ]"
check "响应含 count 字段" "echo '$TG' | grep -q 'count'"

# ---- 5. 配置动力并查询遥测（替代原 physics-model 断言）----
step '配置动力参数 (4 桨, 0.229m, 12°桨距, 8000rpm)'
ROTOR='{"sysid":1,"rotorCount":4,"diameter":0.229,"pitch":12,"maxRpm":8000,"airDensity":1.225}'
RR=$(curl -s --max-time 10 -X POST -H 'Content-Type: application/json' -d "$ROTOR" "$BASE/rotor/config" 2>/dev/null || echo '{}')
echo "   响应: $RR"
check "旋翼配置返回 200 且无 error" "echo '$RR' | grep -q 'rotorCount' && ! echo '$RR' | grep -q '\"error\"'"

step '查询旋翼遥测'
RT=$(curl -s --max-time 10 "$BASE/rotor/telemetry/$SYSID" 2>/dev/null || echo '{}')
echo "   响应: $RT"
RPM=$(jqget "$RT" 'd.get("rpm")')
check "遥测包含 rpm 字段" "[ -n '$RPM' ] && [ '$RPM' != 'None' ]"
TT=$(jqget "$RT" 'd.get("thrust")')
check "遥测包含 thrust 字段" "[ -n '$TT' ] && [ '$TT' != 'None' ]"

# ---- 6. LiDAR 数据 ----
# drone-sim 默认不注入 LiDARSource → 404。按契约区分：有数据则校验字段，无数据则记 skip。
step '查询 LiDAR 数据'
LD_CODE=$(httpcode "$BASE/lidar/data/$SYSID")
LD=$(curl -s --max-time 10 "$BASE/lidar/data/$SYSID" 2>/dev/null || echo '{}')
echo "   HTTP $LD_CODE 响应: $LD"
if [ "$LD_CODE" = '200' ]; then
  ND=$(jqget "$LD" 'd.get("nearestDistance")')
  check "LiDAR 包含 nearestDistance 字段" "[ -n '$ND' ] && [ '$ND' != 'None' ]"
  PC=$(jqget "$LD" 'd.get("pointCount")')
  check "LiDAR 包含 pointCount 字段" "[ -n '$PC' ] && [ '$PC' != 'None' ]"
elif [ "$LD_CODE" = '404' ]; then
  check "无数据时返回 404" "echo '$LD' | grep -qi 'no LiDAR data'"
  skip "LiDAR 无载荷数据（drone-sim 未注入 LiDARSource），字段断言跳过"
else
  check "LiDAR 端点可达（期望 200 或 404，实得 $LD_CODE）" "false"
fi

# ---- 7. IMU 数据 ----
# 字段是 accelX/accelY/accelZ/gyroX/... 的扁平结构（原脚本误读了嵌套 accel/gyro 对象）
step '查询 IMU 数据'
IM_CODE=$(httpcode "$BASE/imu/data/$SYSID")
IM=$(curl -s --max-time 10 "$BASE/imu/data/$SYSID" 2>/dev/null || echo '{}')
echo "   HTTP $IM_CODE 响应: $IM"
if [ "$IM_CODE" = '200' ]; then
  AX=$(jqget "$IM" 'd.get("accelX")')
  check "IMU 包含 accelX 字段" "[ -n '$AX' ] && [ '$AX' != 'None' ]"
  GX=$(jqget "$IM" 'd.get("gyroX")')
  check "IMU 包含 gyroX 字段" "[ -n '$GX' ] && [ '$GX' != 'None' ]"
  TC2=$(jqget "$IM" 'd.get("tempC")')
  check "IMU 包含 tempC 字段" "[ -n '$TC2' ] && [ '$TC2' != 'None' ]"
elif [ "$IM_CODE" = '404' ]; then
  check "无数据时返回 404" "echo '$IM' | grep -qi 'no IMU data'"
  skip "IMU 无载荷数据（drone-sim 未注入 ImuSource），字段断言跳过"
else
  check "IMU 端点可达（期望 200 或 404，实得 $IM_CODE）" "false"
fi

# ---- 收尾 ----
if [ "$FAIL" = '0' ]; then
  echo '✅ M4 硬件抽象 e2e 全部通过'
  exit 0
else
  echo '❌ M4 硬件抽象 e2e 失败'
  exit 1
fi
