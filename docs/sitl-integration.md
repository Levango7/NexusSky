# NexusSky SITL 集成与 MAVLink 协议兼容验证

> **SITL（Software In The Loop）**：把飞控**真实固件**编译成 PC 程序运行，
> 传感器数据由仿真提供。它说的 MAVLink、跑的任务状态机与真机完全一致。
> 后端/地面站零改动——这正是"替换数据源，不替换架构"的验证时刻。
>
> 本文档覆盖 **PX4 SITL** 和 **ArduPilot SITL** 两种仿真后端，
> 以及 NexusSky cloud-backend 的对接配置与兼容性验证流程。

---

## 目录

1. [架构概览](#1-架构概览)
2. [PX4 SITL 环境搭建](#2-px4-sitl-环境搭建)
3. [ArduPilot SITL 环境搭建](#3-ardupilot-sitl-环境搭建)
4. [NexusSky 与 SITL 对接配置](#4-nexussky-与-sitl-对接配置)
5. [兼容性验证清单](#5-兼容性验证清单)
6. [验证脚本使用指南](#6-验证脚本使用指南)
7. [NexusSky 扩展消息（420-476）](#7-nexussky-扩展消息420-476)
8. [常见问题和解决方案](#8-常见问题和解决方案)
9. [已知边界与限制](#9-已知边界与限制)

---

## 1. 架构概览

```
┌─────────────────────────────────────────────────────────────┐
│                     开发机（Windows / WSL2）                 │
│                                                             │
│  ┌──────────────┐    MAVLink/UDP     ┌──────────────────┐   │
│  │  PX4 SITL    │─── 14556 ────────▶ │  cloud-backend   │   │
│  │  (jmavsim)   │─── 14540 ────────▶ │  (UDP 14550)     │   │
│  │  sysid=1     │                     │                  │   │
│  └──────────────┘                     │  REST :8080      │   │
│                                       │  WS   :8080      │   │
│  ┌──────────────┐                     │       │          │   │
│  │ ArduPilot    │─── 14550 ────────▶ │       ▼          │   │
│  │  SITL        │                     │  gcs-web         │   │
│  │  sysid=1     │                     │  (浏览器)         │   │
│  └──────────────┘                     └──────────────────┘   │
│                                                             │
│  ┌──────────────────────────────────────────────────────┐   │
│  │  mavlink-compatibility-check.py                       │   │
│  │  （纯 socket MAVLink 验证，不依赖 pymavlink）         │   │
│  └──────────────────────────────────────────────────────┘   │
└─────────────────────────────────────────────────────────────┘
```

**关键端口**：

| 端口  | 方向                  | 说明                                    |
|-------|----------------------|-----------------------------------------|
| 14540 | SITL → 外部           | PX4 默认 TELEM1，广播心跳               |
| 14550 | SITL → cloud-backend  | 标准 GCS 端口（cloud-backend 监听此处）  |
| 14556 | SITL → 外部           | PX4 "支持命令的 remote GCS" 端口         |
| 8080  | cloud-backend REST    | HTTP API + WebSocket                    |

---

## 2. PX4 SITL 环境搭建

### 2.1 前置依赖

```bash
# WSL2 Ubuntu-24.04 内执行
sudo apt update && sudo apt install -y \
  git build-essential cmake ninja-build python3-pip \
  genromfs kconfig libncurses5-dev libncursesw5-dev \
  libusb-dev libudev-dev libjsoncpp-dev libopencv-dev
```

### 2.2 克隆与编译

```bash
cd ~
git clone --depth 1 --branch v1.17.0 https://github.com/PX4/PX4-Autopilot.git
cd PX4-Autopilot

# 安装构建依赖（约 10 分钟）
bash Tools/setup/ubuntu.sh

# 编译 SITL（首次约 20-40 分钟）
make px4_sitl jmavsim          # 带 jMAVSim 可视化
# 或无头模式：
make px4_sitl_default           # 仅编译，不启动仿真器
```

### 2.3 启动 SITL（无头模式）

```bash
cd ~/PX4-Autopilot

# 无头启动，对外监听 UDP，sysid=1
PX4_SIM_HOST=127.0.0.1 PX4_SYSID=1 \
  ./build/px4_sitl_default/bin/px4 \
  -d /dev/null \
  -s etc/init/posix-airframes/rcS_posix \
  2>&1 | tee sitl.log

# SITL 起来后自动向 127.0.0.1:14540..14556 发 MAVLink
```

### 2.4 验证 SITL 已启动

```bash
# 检查日志中是否出现就绪标志
grep -m1 "Ready for takeoff\|Starting commander" sitl.log

# 检查 UDP 端口是否在发包
ss -lunp | grep 14540
```

### 2.5 多机 SITL（可选）

```bash
# 无人机 1：sysid=1，端口偏移 0
PX4_SYSID=1 ./build/px4_sitl_default/bin/px4 -d /dev/null \
  -s etc/init/posix-airframes/rcS_posix -t 1 &

# 无人机 2：sysid=2，端口偏移 10（14540+10=14550 需避让）
PX4_SYSID=2 ./build/px4_sitl_default/bin/px4 -d /dev/null \
  -s etc/init/posix-airframes/rcS_posix -t 2 &
```

> **注意**：多机 SITL 的端口分配由 `-t`（instance）参数控制，
> instance N 使用 14540+10×N 作为 TELEM1 端口。需在 cloud-backend
> 的 `aerofleet.drone-extra-ports` 中配置对应端口。

---

## 3. ArduPilot SITL 环境搭建

### 3.1 克隆与编译

```bash
cd ~
git clone --depth 1 https://github.com/ArduPilot/ardupilot.git
cd ardupilot
git submodule update --init --recursive

# 安装依赖
Tools/environment_install/install-prereqs-ubuntu.sh -y

# 编译 Copter SITL
./waf configure --board sitl
./waf copter
```

### 3.2 启动 ArduPilot SITL

```bash
# 启动 Copter 仿真，MAVLink 输出到 14550
./Tools/autotest/sim_vehicle.py -v ArduCopter \
  --map --console \
  --add-param-file=Tools/autotest/default_params/copter.parm \
  --instance=0

# 或无头模式：
sim_vehicle.py -v ArduCopter --no-mavproxy \
  --out=udp:127.0.0.1:14550
```

### 3.3 ArduPilot 与 PX4 的 MAVLink 差异

| 差异点              | PX4                          | ArduPilot                     |
|---------------------|------------------------------|-------------------------------|
| 心跳 custom_mode    | PX4 nav_state 枚举            | ArduPilot flight mode 枚举     |
| MISSION_REQUEST     | 回 msgId=43（legacy）         | 回 msgId=51（MISSION_REQUEST_INT）|
| MISSION_START(300)  | 部分版本 UNSUPPORTED          | 支持                          |
| 参数协议            | PARAM_SET/PARAM_VALUE         | 同                            |
| 命令确认             | COMMAND_ACK                   | 同                            |

NexusSky cloud-backend 的兼容层已处理上述差异（见 [§4.3](#43-兼容层)）。

---

## 4. NexusSky 与 SITL 对接配置

### 4.1 cloud-backend 配置

`cloud-backend/src/main/resources/application.yml`（或环境变量）：

```yaml
aerofleet:
  udp-port: 14550                    # MAVLink UDP 监听端口
  drone-host: 127.0.0.1              # 无人机/SITL 地址
  drone-port: 14540                  # 主动发现端口（发 GCS 心跳到此）
  drone-extra-ports: 14556           # 额外发现端口（PX4 remote GCS 端口）
```

等价环境变量：
```bash
export AEROFLEET_UDP_PORT=14550
export AEROFLEET_DRONE_HOST=127.0.0.1
export AEROFLEET_DRONE_PORT=14540
export AEROFLEET_DRONE_EXTRA_PORTS=14556
```

### 4.2 WSL2 网络配置

WSL2 的 localhost 与 Windows 双向互通（默认 `networkingMode=NAT`），
SITL 在 WSL2 内发包到 `127.0.0.1:14550` 会到达 Windows 上的 cloud-backend。

如遇不通：
```bash
# 检查 WSL2 IP
wsl hostname -I

# 或显式指定 Windows IP
export AEROFLEET_DRONE_HOST=$(cat /etc/resolv.conf | grep nameserver | awk '{print $2}')
```

### 4.3 兼容层

cloud-backend `UdpGateway` + `TelemetryIngestService` 已实现以下兼容处理：

| 差异点              | PX4 / ArduPilot 行为           | NexusSky 兼容层                   |
|---------------------|--------------------------------|-----------------------------------|
| 任务拉取请求        | PX4 回 `MISSION_REQUEST`(43)   | PendingAcks 双消息 ID 登记 waiter  |
| MISSION_COUNT 目标  | 拒绝 broadcast(0)              | `targetSystem()` 从注册表取真实 sysid |
| MISSION_START(300)  | 部分版本 UNSUPPORTED           | 回退 `DO_SET_MODE`(176) auto 模式  |
| 心跳 custom_mode    | PX4 nav_state / AP flight mode | `px4NavStateLabel()` 已映射        |
| GCS 心跳             | 需先收到才回包                  | 1Hz 周期 GCS 心跳（QGC 风格）       |
| 路由老化             | 多机替换场景                    | 5min 静默路由自动剔除（D3）         |

---

## 5. 兼容性验证清单

### 5.1 SITL 联调 PASS 标准

- [ ] **设备上线**：PX4/AP 心跳被识别为 Drone-1，mode=MANUAL → Standby
- [ ] **任务上传**：PX4 用 MISSION_REQUEST 拉取，双 waiter 生效
- [ ] **ARM** → PX4 状态机进 ARMED（遥测 armed=true）
- [ ] **开始任务** → PX4 进 MISSION 模式（custom_mode=3），飞机沿航点飞
- [ ] **RTL** → PX4 返航降落
- [ ] **轨迹** 200 点留存

### 5.2 MAVLink 协议兼容验证

- [ ] **标准消息解码**：HEARTBEAT / SYS_STATUS / ATTITUDE / GLOBAL_POSITION_INT / GPS_RAW_INT / VFR_HUD
- [ ] **任务协议**：MISSION_COUNT / MISSION_ITEM_INT / MISSION_REQUEST / MISSION_ACK 往返
- [ ] **命令协议**：COMMAND_LONG / COMMAND_ACK
- [ ] **v1/v2 兼容**：v1 帧（STX=0xFE）与 v2 帧（STX=0xFD）混合流自动识别
- [ ] **CRC 校验**：所有帧 CRC-16/X.25 校验通过
- [ ] **扩展消息**：420-476 全部 44 条编解码一致（含可变长度消息）

### 5.3 NexusSky 扩展消息验证

- [ ] **M2 喷洒物流**（423-426）：SPRAY_STATUS / SPRAY_COMMAND / GRIPPER_COMMAND / PAYLOAD_STATUS
- [ ] **M0b 环境气象**（421-422）：ENVIRONMENT_ALERT / ENVIRONMENT_STATUS
- [ ] **M3 感知成像**（430-434）：OBSTACLE_REPORT / MULTISPECTRAL_DATA / THERMAL_DATA / DEPTH_DATA / VISION_DETECTION
- [ ] **M4 硬件抽象**（437-441）：RADAR_SCAN / RADAR_TARGET / ROTOR_TELEMETRY / LIDAR_DATA / IMU_DATA
- [ ] **M5 Mesh 自愈**（450-454）：含可变长度 MESH_NEIGHBOR_TABLE
- [ ] **M6 移动基站**（455-458）
- [ ] **M7 星-空-地中继**（459-461）
- [ ] **M8 地形适配**（462-464）：含 3 条可变长度消息
- [ ] **M9-M13**（465-476）：应急编排 / 多机协同 / 自主决策 / 边缘融合 / 数字孪生

---

## 6. 验证脚本使用指南

### 6.1 mavlink-compatibility-check.py（Python，纯 socket）

**无需安装 pymavlink**，仅用 Python 标准库 `socket` + `struct`。

```bash
# 离线自检（无需后端，验证编解码一致性）
python3 scripts/mavlink-compatibility-check.py --self-test

# 端到端往返（需 cloud-backend 运行）
python3 scripts/mavlink-compatibility-check.py --roundtrip

# 全部
python3 scripts/mavlink-compatibility-check.py --all

# 列出所有已知消息
python3 scripts/mavlink-compatibility-check.py --list-messages
```

**验证内容**：
1. CRC-16/X.25 标准测试向量
2. 标准 MAVLink 消息（HEARTBEAT / ATTITUDE / GLOBAL_POSITION_INT / MISSION_ITEM_INT）编解码往返
3. NexusSky 扩展消息（420-476）帧层透传往返（全部 44 条）
4. MAVLink v1 vs v2 帧兼容性（混合流自动识别、扩展消息 v1 拒绝）
5. 消息 ID 无冲突检查
6. 端到端：发送帧到 cloud-backend UDP 14550，接收 GCS 心跳响应

### 6.2 sitl-compatibility-test.sh（Bash，全流程）

```bash
# 全流程（含 SITL 启动 + cloud-backend + 验证）
./scripts/sitl-compatibility-test.sh

# 仅离线自检（无 SITL、无后端）
./scripts/sitl-compatibility-test.sh --self-test-only

# 跳过 SITL 启动（SITL 已运行）
./scripts/sitl-compatibility-test.sh --skip-sitl

# 指定 PX4 目录
./scripts/sitl-compatibility-test.sh --px4-dir ~/PX4-Autopilot
```

**验证链**：
1. MAVLink 编解码离线自检
2. cloud-backend 前置检查（自动启动或检测已运行）
3. PX4 SITL 启动（无头模式）并等待就绪
4. MAVLink 端到端往返（Python → cloud-backend）
5. HEARTBEAT 上线验证（REST API `/api/v1/drones`）
6. GLOBAL_POSITION_INT 遥测路由验证（`/api/v1/drones/{id}/track`）
7. MISSION_ITEM_INT 任务管理验证（`/api/v1/missions`）
8. NexusSky 扩展消息编解码验证
9. SITL 联调验证（日志事件检查）

---

## 7. NexusSky 扩展消息（420-476）

NexusSky 在 MAVLink 标准消息之外定义了 44 条自定义扩展消息，
msgId 区间 **420-476**，与 MAVLink 官方消息无冲突。

### 7.1 消息总览

| 模块 | msgId 范围 | 消息数 | 说明 |
|------|-----------|--------|------|
| LED 控制 | 420 | 1 | LED_CONTROL |
| M0b 环境气象 | 421-422 | 2 | ENVIRONMENT_ALERT / STATUS |
| M2 喷洒物流 | 423-426 | 4 | SPRAY_STATUS / COMMAND / GRIPPER / PAYLOAD |
| M3 感知成像 | 430-434 | 5 | OBSTACLE / MULTISPECTRAL / THERMAL / DEPTH / VISION |
| M4 硬件抽象 | 437-441 | 5 | RADAR_SCAN / TARGET / ROTOR / LIDAR / IMU |
| M5 Mesh 自愈 | 450-454 | 5 | 含可变长度 MESH_NEIGHBOR_TABLE |
| M6 移动基站 | 455-458 | 4 | CELL_TOWER / HANDOVER / GROUND_TERMINAL |
| M7 多层中继 | 459-461 | 3 | SAT_LINK / PASS_SCHEDULE / HIERARCHICAL_ROUTE |
| M8 地形适配 | 462-464 | 3 | 全部可变长度 |
| M9 应急编排 | 465-467 | 3 | EMERGENCY_MISSION / COVERAGE / PRIORITY |
| M10 多机协同 | 468-470 | 3 | TASK_ASSIGNMENT / CONFLICT_ALERT / TASK_STATUS |
| M11 自主决策 | 471-472 | 2 | DECISION_EVENT / ADAPTIVE_PATH |
| M12 边缘融合 | 473-474 | 2 | EDGE_TASK_STATUS / SENSOR_FUSION_DATA |
| M13 数字孪生 | 475-476 | 2 | TWIN_STATE_SYNC / PREDICTION_RESULT |

### 7.2 CRC_EXTRA 计算

每条扩展消息的 CRC_EXTRA 是固定值，提取自 `mavlink-core/MavlinkMessageInfo.java`。
计算方法：对消息名 + 字段名 + 字段类型拼接字符串，用 CRC-16/X.25 计算。

> **来源**：经验 `2026-09-16-mavlink-crc-extra-calculation-from-message-field-types`

### 7.3 可变长度消息

4 条消息使用可变长度（`LEN=-1`）：
- `MESH_NEIGHBOR_TABLE` (454)
- `TERRAIN_TYPE_MAP` (462)
- `TERRAIN_UPDATE` (463)
- `FLIGHT_RESTRICTION` (464)

验证脚本对可变长度消息使用 8 字节示例 payload 进行帧层往返测试。

### 7.4 SITL 兼容性说明

PX4 / ArduPilot SITL **不发送** NexusSky 扩展消息（它们只发标准 MAVLink）。
扩展消息的验证通过以下方式完成：
1. **离线自检**：`mavlink-compatibility-check.py --self-test` 验证编解码一致性
2. **drone-sim 注入**：NexusSky 的 `drone-sim` 模块可模拟发送扩展消息
3. **端到端往返**：Python 脚本直接向 cloud-backend 发送扩展消息帧

---

## 8. 常见问题和解决方案

### Q1: SITL 启动后 cloud-backend 收不到心跳

**原因**：PX4 需要先收到 GCS 心跳才向 14556 端口回包。

**解决**：cloud-backend 的 `UdpGateway` 已实现 QGC 风格的 1Hz 主动 GCS 心跳
（`heartbeatLoop()`），启动后端即可。如仍不通，检查：
```bash
# 确认 cloud-backend 在监听 14550
ss -lunp | grep 14550

# 确认 SITL 在发包
tcpdump -i lo udp port 14540 -c 5
```

### Q2: WSL2 网络不通

**原因**：WSL2 NAT 模式下 localhost 互通，但 mirrored 模式或自定义网络可能不同。

**解决**：
```bash
# 检查 .wslconfig
cat /mnt/c/Users/$USER/.wslconfig

# 显式使用 Windows IP
WIN_IP=$(cat /etc/resolv.conf | grep nameserver | awk '{print $2}')
export AEROFLEET_DRONE_HOST=$WIN_IP
```

### Q3: MISSION_REQUEST 拉取失败

**原因**：PX4 用 `MISSION_REQUEST`(43) 而非 `MISSION_REQUEST_INT`(51) 拉取任务。

**解决**：cloud-backend `PendingAcks` 已实现双消息 ID 登记 waiter，同时处理 43 和 51。
如仍失败，检查 sysid 是否匹配（PX4 拒绝 broadcast target=0）。

### Q4: ARM 后立即 RTL

**原因**：PX4 datalink failsafe（`NAV_DLLC_ACT`）在链路静默 ~15s 后触发 RTL。

**解决**：cloud-backend 的 1Hz GCS 心跳会维持链路。如仍触发，
调大 PX4 参数：`param set NAV_DLLC_ACT 0`（禁用）或增大超时。

### Q5: GPS 未解锁导致 ARM 失败

**原因**：SITL 传感器仿真里 GPS 信号依赖 EKF，解锁前可能需要虚拟 GPS 健康。

**解决**：
```bash
# PX4 参数：允许无 GPS 解锁
param set COM_ARM_WO_GPS 1

# 或等待 GPS 3D fix（查看 STATUSTEXT 提示）
```

### Q6: Python 脚本报 "python3: command not found"

**解决**：
```bash
# 指定 Python 路径
PYTHON=/usr/bin/python3 ./scripts/sitl-compatibility-test.sh

# 或 Windows
PYTHON=python ./scripts/sitl-compatibility-test.sh
```

### Q7: 扩展消息被 SITL 拒绝

**原因**：PX4 / ArduPilot 不识别 msgId > 256 的自定义消息，会忽略或报错。

**解决**：这是预期行为。扩展消息由 NexusSky drone-sim 发送，不经过 PX4/AP。
验证扩展消息兼容性使用 `mavlink-compatibility-check.py --self-test`（离线编解码验证）
或 drone-sim → cloud-backend 端到端路径。

### Q8: v1 帧与 v2 帧混合流解析错误

**原因**：解包器需根据 STX 字节（0xFE=v1, 0xFD=v2）自动识别帧版本。

**解决**：`mavlink-compatibility-check.py` 的 `unpack()` 函数已实现自动识别。
如 cloud-backend 收到混合流异常，检查 `MavlinkFrame.Parser` 是否支持 v1 STX。

---

## 9. 已知边界与限制

### 9.1 SITL 限制

- **传感器仿真**：GPS 信号依赖 `SYS_FORCE_VTYP` / EKF，首次联调可能需调参
- **多机 SITL**：不同 SYSID/端口由 `PX4_SIM_HOST` / `-t` 参数控制，骨架阶段单机验证
- **无硬件接口**：SITL 无法验证串口 / CAN / I2C 等硬件链路
- **实时性**：SITL 非硬实时，高负载时仿真步长可能抖动

### 9.2 协议兼容边界

- **扩展消息**：PX4 / ArduPilot SITL 不发送 NexusSky 扩展消息（420-476），
  扩展消息兼容性通过离线自检和 drone-sim 验证
- **MAVLink v1**：扩展消息（msgId > 255）无法用 v1 帧发送，必须用 v2
- **可变长度消息**：4 条可变长度消息的帧层验证使用固定示例 payload，
  实际业务场景需 drone-sim 生成真实数据

### 9.3 验证覆盖范围

| 验证项                  | SITL 验证 | 离线自检 | drone-sim |
|-------------------------|:---------:|:--------:|:---------:|
| 标准 MAVLink 消息解码    | ✅        | ✅       | ✅        |
| 任务协议往返             | ✅        | ✅       | ✅        |
| 命令协议往返             | ✅        | —        | ✅        |
| v1/v2 兼容性             | ✅        | ✅       | —        |
| 扩展消息编解码           | —         | ✅       | ✅        |
| 扩展消息端到端           | —         | ✅       | ✅        |
| 硬件链路                 | —         | —        | —        |

---

## 附录：快速启动命令

```bash
# 1. 离线自检（最快，无需任何外部依赖）
python3 scripts/mavlink-compatibility-check.py --self-test

# 2. 全流程验证（需 PX4 SITL + cloud-backend）
#    WSL2 内启动 PX4 SITL
cd ~/PX4-Autopilot
PX4_SIM_HOST=127.0.0.1 PX4_SYSID=1 \
  ./build/px4_sitl_default/bin/px4 -d /dev/null \
  -s etc/init/posix-airframes/rcS_posix &

#    Windows 内启动 cloud-backend
java -jar cloud-backend/target/aerofleet-cloud-backend-0.1.0-SNAPSHOT.jar &

#    运行验证脚本
./scripts/sitl-compatibility-test.sh --skip-sitl

# 3. 仅 Python 端到端往返
python3 scripts/mavlink-compatibility-check.py --roundtrip
```
