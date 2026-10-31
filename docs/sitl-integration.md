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
7. [NexusSky 扩展消息（30000-30063）](#7-nexussky-扩展消息420-30063)
8. [常见问题和解决方案](#8-常见问题和解决方案)
9. [已知边界与限制](#9-已知边界与限制)
10. [PX4 SITL 协议兼容性验证](#10-px4-sitl-协议兼容性验证)
11. [ArduPilot SITL 首验实录（2026-10-28）](#11-ardupilot-sitl-首验实录2026-10-28)

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

| 差异点              | PX4 / drone-sim              | ArduPilot                     |
|---------------------|------------------------------|-------------------------------|
| 心跳 custom_mode    | PX4 nav_state 枚举            | ArduCopter 模式枚举（编号与 PX4 完全不同；`modeLabel()` 按 `HEARTBEAT.autopilot` 分派，见 §11.2-③）|
| 任务项拉取请求       | MISSION_REQUEST_INT(51)（drone-sim 实测）| legacy MISSION_REQUEST(**40**)（2026-10-28 AP SITL 实测；双 waiter 兼容层吸收。原表写「PX4 回 43 / AP 回 51」与实测相反，已按证据改正）|
| 任务槽 0            | 普通任务条目                  | **保留给 HOME**：写入索引 0 被固件忽略，真实命令从索引 1 起（`withArduPilotHomeSlot` 前置占位，见 §11.2-②）|
| MISSION_START(300)  | 部分版本 UNSUPPORTED          | 支持（实测接受；任务不满足 AUTO 地面启动契约时按 `MAV_RESULT=4` 拒绝，见 §11.2-⑥）|
| 参数协议            | PARAM_SET/PARAM_VALUE         | 同                            |
| 命令确认             | COMMAND_ACK                   | 同                            |

NexusSky cloud-backend 的兼容层已处理上述差异（见 [§4.3](#43-兼容层)）；逐条现场验证见 [§11](#11-ardupilot-sitl-首验实录2026-10-28)。

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
| 任务拉取请求        | drone-sim 回 `MISSION_REQUEST_INT`(51)；AP 实测回 `MISSION_REQUEST`(40) | PendingAcks 双消息 ID（40+51）登记 waiter |
| MISSION_COUNT 目标  | 拒绝 broadcast(0)              | `targetSystem()` 从注册表取真实 sysid |
| MISSION_START(300)  | 部分版本 UNSUPPORTED           | 回退 `DO_SET_MODE`(176) auto 模式  |
| 任务槽 0（AP）      | AP 忽略写入索引 0 的条目（保留给 HOME）| `withArduPilotHomeSlot` 前置 home 占位、用户条目后移（按 `HEARTBEAT.autopilot` 分派）|
| 带位置命令报文格式   | drone-sim 收 LONG/INT 双口径 | AP **强制 COMMAND_INT**（LONG→`MAV_RESULT=8`）| `command()` 按 `COMMAND_INT_REQUIRED` 集合自动路由（P2-F2）|
| 尾零裁剪（v2）      | AP 的 C 库裁掉 payload 尾部全零   | 解码入口按消息定义长度零填充（wire 长度语义不变）|
| 心跳 custom_mode    | PX4 nav_state / ArduCopter 两套枚举 | `modeLabel()` 按 `HEARTBEAT.autopilot` 分派两种表 |
| GCS 心跳             | 需先收到才回包                  | 1Hz 周期 GCS 心跳（QGC 风格）       |
| 路由老化             | 多机替换场景                    | 5min 静默路由自动剔除（D3）         |

---

## 5. 兼容性验证清单

> **图例**：`[AP-实测]` = 2026-10-28 ArduPilot Copter SITL 真机首验（见 [§11](#11-ardupilot-sitl-首验实录2026-10-28)）；
> `[仿真]` = drone-sim e2e 回归；`[离线]` = `mavlink-compatibility-check.py` 自检。

### 5.1 SITL 联调 PASS 标准

- [x] **设备上线**：心跳被识别为 Drone-1，遥测/模式标签正常 `[AP-实测]`（AP 启动态显示 STABILIZE；`mode=MANUAL → Standby` 为 PX4 口径措辞）
- [x] **任务上传**：拉取请求双 waiter 生效 `[AP-实测]`（AP 实际回 legacy `MISSION_REQUEST(40)`，见 §11.2-④）
- [x] **ARM** → 状态机进 ARMED（遥测 armed=true）`[AP-实测]`（须先切可 arm 模式，见 §11.2-⑥）
- [x] **开始任务** → 沿航点飞 `[AP-实测]`（AP 进 AUTO，custom_mode=3；任务须 TAKEOFF 开头，见 §11.2-⑥）
- [x] **RTL** → 返航降落、自动 disarm `[AP-实测]`
- [x] **轨迹** 200 点留存 `[AP-实测]`

### 5.2 MAVLink 协议兼容验证

- [x] **标准消息解码**：HEARTBEAT / SYS_STATUS / ATTITUDE / GLOBAL_POSITION_INT / GPS_RAW_INT / VFR_HUD `[AP-实测]`
- [x] **任务协议**：MISSION_COUNT / MISSION_ITEM_INT / MISSION_REQUEST / MISSION_ACK 往返 `[AP-实测]`
- [x] **命令协议**：COMMAND_LONG / COMMAND_ACK `[AP-实测]`（arm / start / set_mode 全走 ACK 往返）
- [x] **v1/v2 兼容**：v1 帧（STX=0xFE）与 v2 帧（STX=0xFD）混合流自动识别 `[离线]`（本次 AP 流为纯 v2）
- [x] **CRC 校验**：所有帧 CRC-16/X.25 校验通过 `[AP-实测]`（含尾零裁剪帧解码回归，见 §11.2-①）
- [x] **扩展消息**：30000-30063 全部 51 条编解码一致（含可变长度消息）`[离线]`+`[仿真]`

### 5.3 NexusSky 扩展消息验证

> SITL 固件不会发出私有扩展消息（30000-30063），该组由 drone-sim e2e 与离线自检覆盖——`[仿真]`/`[离线]` 口径同 §5.2 图例。

- [x] **M2 喷洒物流**（30003-30006）：SPRAY_STATUS / SPRAY_COMMAND / GRIPPER_COMMAND / PAYLOAD_STATUS `[仿真]`
- [x] **M0b 环境气象**（30001-30002）：ENVIRONMENT_ALERT / ENVIRONMENT_STATUS `[仿真]`
- [x] **M3 感知成像**（30010-30014）：OBSTACLE_REPORT / MULTISPECTRAL_DATA / THERMAL_DATA / DEPTH_DATA / VISION_DETECTION `[仿真]`
- [x] **M4 硬件抽象**（30017-30021）：RADAR_SCAN / RADAR_TARGET / ROTOR_TELEMETRY / LIDAR_DATA / IMU_DATA `[仿真]`
- [x] **M5 Mesh 自愈**（30030-30034）：含可变长度 MESH_NEIGHBOR_TABLE `[仿真]`
- [x] **M6 移动基站**（30035-30038）`[仿真]`
- [x] **M7 星-空-地中继**（30039-30041）`[仿真]`
- [x] **M8 地形适配**（30042-30044）：含 3 条可变长度消息 `[仿真]`
- [x] **M9-M13**（30045-30056）：应急编排 / 多机协同 / 自主决策 / 边缘融合 / 数字孪生 `[仿真]`

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
3. NexusSky 扩展消息（30000-30063）帧层透传往返（全部 51 条）
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
7. MISSION_ITEM_INT 任务管理验证（`/api/v1/drones/{sysid}/mission`）
8. NexusSky 扩展消息编解码验证
9. SITL 联调验证（日志事件检查）

---

## 7. NexusSky 扩展消息（30000-30063）

NexusSky 在 MAVLink 标准消息之外定义了 51 条自定义扩展消息，
msgId 区间 **30000-30063**（私有方言段 30000-30099），与 MAVLink 官方消息无冲突。

> **治理搬迁说明（2026-10）**：扩展消息原先使用 msgId 420-483，该段位于 common.xml
> 官方分配带 300-10000 内，其中 420/437/440 已与官方消息实锤冲突（420=RADIO_RC_CHANNELS、
> 437=AVAILABLE_MODES_MONITOR、440=ILLUMINATOR_STATUS）。全部 51 条消息已等差平移
> +29580 至私有方言段（MAVLink 治理规则：私有方言可使用 300-10000 之外的任意区间）。
> CRC_EXTRA 与 payload 布局不变（CRC_EXTRA 由字段签名决定，与 msgId 无关）；飞行日志
> 存领域 JSON、不含原始 msgId，历史数据与回放不受影响。
> `scripts/mavlink-compatibility-check.py --self-test` 内嵌 392 个官方已分配 msgId
> 快照，逐条核对私有段无冲突。

> **命令 ID 治理搬迁说明（2026-10）**：与消息同源的另一处官方带占用——8 条自定义
> MAV_CMD（环境配置 310-312、喷洒/抛投/载荷 320-322、雷达/旋翼配置 420/421）原先
> 直接使用 MAVLink 官方/方言命令分配带的数值，其中 420/421 与 ArduPilot 方言实锤
> 冲突（420=NAV_GUIDED_ENABLE、421=NAV_CONTINUE_AND_CHANGE_ALT）。全部 8 条已搬入
> 私有区**命令子段 30080-30099**（消息占 30000-30063；命令与消息分属不同命名空间，
> 数值本可重叠，分段纯为日志可读性）：310→30080 / 311→30081 / 312→30082 /
> 320→30083 / 321→30084 / 322→30085 / 420→30086 / 421→30087。常量统一收口在
> `MavEnums.MAV_CMD_NEXUS_*`（云端下发与机载 switch 派发共用一份），并由双测试
> 钉死：mavlink-core 私有区守卫（常量出段即红）+ drone-sim 真实 UDP 派发往返
> （机载 case 标签没跟上搬迁即红）。其中环境配置（30080-30082）与载荷查询
> （30085）为机载本地控制面 / 按需冗余触发，仓库内无云端生产者属设计边界
> （详见 README「已知边界」），协议面保留给 SITL 与外部工具。

### 7.1 消息总览

| 模块 | msgId 范围 | 消息数 | 说明 |
|------|-----------|--------|------|
| LED 控制 | 30000 | 1 | LED_CONTROL |
| M0b 环境气象 | 30001-30002 | 2 | ENVIRONMENT_ALERT / STATUS |
| M2 喷洒物流 | 30003-30006 | 4 | SPRAY_STATUS / COMMAND / GRIPPER / PAYLOAD |
| M3 感知成像 | 30010-30014 | 5 | OBSTACLE / MULTISPECTRAL / THERMAL / DEPTH / VISION |
| M4 硬件抽象 | 30017-30021 | 5 | RADAR_SCAN / TARGET / ROTOR / LIDAR / IMU |
| M5 Mesh 自愈 | 30030-30034 | 5 | 含可变长度 MESH_NEIGHBOR_TABLE |
| M6 移动基站 | 30035-30038 | 4 | CELL_TOWER / HANDOVER / GROUND_TERMINAL |
| M7 多层中继 | 30039-30041 | 3 | SAT_LINK / PASS_SCHEDULE / HIERARCHICAL_ROUTE |
| M8 地形适配 | 30042-30044 | 3 | 全部可变长度 |
| M9 应急编排 | 30045-30047 | 3 | EMERGENCY_MISSION / COVERAGE / PRIORITY |
| M10 多机协同 | 30048-30050 | 3 | TASK_ASSIGNMENT / CONFLICT_ALERT / TASK_STATUS |
| M11 自主决策 | 30051-30052 | 2 | DECISION_EVENT / ADAPTIVE_PATH |
| M12 边缘融合 | 30053-30054 | 2 | EDGE_TASK_STATUS / SENSOR_FUSION_DATA |
| M13 数字孪生 | 30055-30056 | 2 | TWIN_STATE_SYNC / PREDICTION_RESULT |
| 后续扩展（安防/通信/集群/灾害） | 30057-30063 | 7 | ALARM_TRIGGER / ALARM_ACK / SURVEILLANCE_STATUS / QOS_ROUTE_DECISION / CLUSTER_FORMATION / DISASTER_MODE_STATUS / BUZZER_CONTROL |

### 7.2 CRC_EXTRA 计算

每条扩展消息的 CRC_EXTRA 是固定值，提取自 `mavlink-core/MavlinkMessageInfo.java`。
计算方法：对消息名 + 字段名 + 字段类型拼接字符串，用 CRC-16/X.25 计算。

> **来源**：经验 `2026-09-16-mavlink-crc-extra-calculation-from-message-field-types`

### 7.3 可变长度消息

4 条消息使用可变长度（`LEN=-1`）：
- `MESH_NEIGHBOR_TABLE` (30034)
- `TERRAIN_TYPE_MAP` (30042)
- `TERRAIN_UPDATE` (30043)
- `FLIGHT_RESTRICTION` (30044)

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

- **扩展消息**：PX4 / ArduPilot SITL 不发送 NexusSky 扩展消息（30000-30063），
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
---

## 10. PX4 SITL 协议兼容性验证

> **本章覆盖**：使用 `scripts/e2e-px4-sitl.ps1` 在 Windows PowerShell 下对 PX4 SITL
> 进行全链路协议验证，证明 NexusSky 的 MAVLink 协议栈与真实 PX4 固件完全兼容。

### 10.1 验证脚本

**脚本路径**：`scripts/e2e-px4-sitl.ps1`

**运行方式**：

```powershell
# 全流程验证（自动检测 PX4 SITL 并启动）
.\scripts\e2e-px4-sitl.ps1

# 如 PX4 SITL 已在 WSL2 中运行，脚本会自动检测并连接
```

**脚本特点**：

- 自动检测 PX4 SITL 安装（Windows PATH / WSL2 / 常见目录）
- 未安装时打印详细安装指引，不自动安装
- 支持 WSL2 模式启动 PX4 SITL（无头模式）
- 自动检测并启动 cloud-backend（如未运行）
- 每步输出 PASS/FAIL，最终汇总结果
- 异常时自动清理 SITL 和 cloud-backend 进程

### 10.2 验证步骤清单

| 步骤 | 验证项 | 验证方法 | 预期结果 |
|------|--------|---------|----------|
| 0 | 前置检查 | 检测 `px4` 命令 / WSL2 PX4 目录 / Windows 安装路径 | 检测到安装或打印安装指引 |
| 1 | PX4 SITL 启动 | WSL2 无头模式启动，等待日志出现 "Starting commander" | SITL 进程就绪，60s 内完成 |
| 2 | cloud-backend 启动 | 检测 health endpoint 或自动启动 JAR | health UP，30s 内完成 |
| 3a | 心跳交换 | 查询 `/api/v1/drones` 等待无人机上线 | 收到 PX4 SITL 的 HEARTBEAT |
| 3b | 设备注册 | 查询 `/api/v1/drones/{sysid}` | PX4 SITL 出现在设备列表，含 sysid/mode/armed |
| 3c | 任务上传 | POST `/api/v1/drones/{sysid}/mission`（5 航点方形任务） | status=ok，PX4 通过 MISSION_REQUEST 拉取 |
| 3d | ARM 命令 | POST `/api/v1/drones/{sysid}/commands` type=arm | status=ok，PX4 进入 ARMED 状态 |
| 3e | 遥测接收 | GET `/api/v1/drones/{sysid}/telemetry` 和 `/track` | 收到 ATTITUDE、GLOBAL_POSITION_INT 等 |
| 3f | RTL 命令 | POST `/api/v1/drones/{sysid}/commands` type=rtl | status=ok，PX4 执行返航 |

### 10.3 预期结果

全流程通过时输出：

```
ALL PX4 SITL TESTS PASSED
```

验证结果汇总示例：

```
   PX4 SITL:     已启动
   cloud-backend: 已连接
   心跳交换:      PASS
   设备注册:      PASS
   任务上传:      PASS
   ARM 命令:      PASS
   遥测接收:      PASS
   RTL 命令:      PASS
```

### 10.4 已知限制

| 限制 | 说明 | 影响 |
|------|------|------|
| WSL2 依赖 | PX4 SITL 在 WSL2 内运行，需预先安装 Ubuntu-24.04 | 首次配置耗时约 30-60 分钟 |
| 编译时间 | PX4 SITL 首次编译约 20-40 分钟 | 仅首次需要 |
| GPS 解锁 | SITL 的 GPS 信号依赖 EKF，ARM 前可能需等待 GPS 3D fix | 可设 `COM_ARM_WO_GPS=1` 绕过 |
| Datalink Failsafe | PX4 在链路静默 ~15s 后触发 RTL | cloud-backend 的 1Hz GCS 心跳应维持链路 |
| 扩展消息 | PX4 SITL 不发送 NexusSky 扩展消息（30000-30063） | 扩展消息通过离线自检和 drone-sim 验证 |
| 无硬件链路 | SITL 无法验证串口 / CAN / I2C 等硬件接口 | 需真机测试覆盖 |
| 实时性 | SITL 非硬实时，高负载时仿真步长可能抖动 | 不影响协议验证，影响时序敏感场景 |

### 10.5 与 drone-sim 验证的区别

| 维度 | drone-sim 验证 | PX4 SITL 验证 |
|------|---------------|---------------|
| 飞控代码 | NexusSky 自研模拟逻辑 | PX4 真实固件代码 |
| MAVLink 实现 | drone-sim 内置 MAVLink 编码 | PX4 uORB → MAVLink 真实链路 |
| 任务状态机 | 简化模拟 | PX4 完整状态机（Preflight→Standby→Armed→Mission→RTL） |
| 协议边界 | 已知兼容（同源代码） | 真实兼容性验证（第三方实现） |
| 发现价值 | 功能逻辑验证 | 协议兼容性、边界条件、异常处理验证 |
| 适用场景 | 日常开发、CI/CD | 发布前兼容性确认、协议变更验证 |

### 10.6 故障排查

| 症状 | 可能原因 | 解决方案 |
|------|---------|---------|
| SITL 60s 内未就绪 | PX4 未编译或编译不完整 | `cd ~/PX4-Autopilot && make px4_sitl_default` |
| 心跳未收到 | WSL2 网络不通 / 端口未开放 | 检查 WSL2 NAT 模式，确认 localhost 互通 |
| ARM 失败 | GPS 未解锁 / Pre-arm 检查未通过 | 等待 GPS 3D fix 或设 `COM_ARM_WO_GPS=1` |
| 任务上传失败 | 固件拉取变体不一：drone-sim 用 MISSION_REQUEST_INT(51)，AP 实测用 legacy MISSION_REQUEST(40) | cloud-backend 已兼容双消息 ID（40+51）；AP 另注意任务槽 0=HOME（§11.2-②） |
| ARM 后立即 RTL | Datalink failsafe 触发 | cloud-backend GCS 心跳维持链路，或调大 `NAV_DLLC_ACT` |
| 遥测为空 | SITL 未起飞 / 位置数据未积累 | 先 ARM + start_mission，等待 5s 后查询 |

---

## 11. ArduPilot SITL 首验实录（2026-10-28）

**P2「SITL 首次真实验证」的验收记录**：首次以真实固件（ArduPilot Copter，非本仓模拟器）
对接 cloud-backend，走通"连接 → 任务上传 → 起飞 → 航点 → RTL → 降落"最小闭环，并逐项
产出真固件与 drone-sim 的行为差异。环境：WSL2 Ubuntu 内 `sim_vehicle.py -v ArduCopter
--no-rebuild --out=udp:127.0.0.1:14550`（sysid=1），cloud-backend dev profile 监听 14550、
飞行日志目录独立。复现要点：`POST /api/v1/drones/1/mission`（`{"items":[...]}`）→
`POST /api/v1/drones/1/commands`（`{"type":"arm"|"start_mission"|"rtl"|"raw", ...}`）→
`GET /api/v1/drones/1/mission`（读回）与 `GET /api/v1/drones/1/track`（轨迹）。

### 11.1 闭环结果与传统验证手段的覆盖对照

| 步骤 | 结果 | 证据 |
|------|------|------|
| 设备上线 | ✅ 心跳注册为 Drone-1，`autopilot=3` 存档，模式按 ArduCopter 表显示 | `/api/v1/drones/1` 遥测全字段（attitude/GPS/VFR_HUD 等）非空 |
| 任务上传（4 条 / 2 条 / 1 条） | ✅ 三种形状全部 `{"status":"ok"}` | 2 条用户条目 → 读回 3 条（含 home 槽）；1 条单点 → 读回 2 条（修复②前单点任务等于没传） |
| ARM | ✅（可 arm 模式下）armed=true | STATUSTEXT "Arming motors" |
| 开始任务 | ✅ 进 AUTO，爬升到 20.0m，按序执行 | STATUSTEXT "Mission: 1 Takeoff"、"Mission: 2 RTL" |
| RTL / 降落 | ✅ 返航→降高 20→0→触地→自动 disarm | "SIM Hit ground"、"Disarming motors"，armed=false |
| 轨迹 | ✅ 200 点留存 | `/api/v1/drones/1/track` |
| 模式标签 | ✅ STABILIZE / AUTO / GUIDED 全部解对 | DO_SET_MODE GUIDED 后显示 "GUIDED"（修复③前显示 "RTL"——危险错位） |
| 尾零裁剪回归 | ✅ 被裁的 ACCEPTED 帧（2 字节 payload）正常解码 | 上传不再 2.5s 后被 ERROR 误判为拒绝（修复①前每次上传必"失败"） |

> 复现脚本为一次性联调脚本（未入仓）。命令即 §11 开头的三条 curl；判定全绿的标准
> 同 §5.1（armed→relAlt→降落→track）。

### 11.2 真实固件 vs drone-sim 行为差异记录

逐条对照 drone-sim（PX4 口径）与 ArduPilot Copter 真机实测。①-③ 为本轮修复项（已带回归
测试落地）；④-⑦ 为固件契约差异，⑤-⑦ 属工作流适配、已立项（见 §11.3）。

| # | 差异点 | drone-sim | ArduPilot 实测 | 处置 |
|---|--------|-----------|----------------|------|
| ① | MAVLink v2 尾零裁剪 | 本仓编码全长发送，不裁 | C 库裁掉 payload 尾部全零字节：ACCEPTED 的 MISSION_ACK 被裁到 2 字节 | **已修**：解码入口按消息定义长度零填充（wire 长度语义不变）；`MissionTrailingZeroPayloadTest` 用抓包原文回归 |
| ② | 任务槽 0 | 普通条目，从 seq 0 执行 | **保留给 HOME**，写入被忽略（"must be home"），命令从 seq 1 起（`AP_MISSION_FIRST_REAL_COMMAND=1`） | **已修**：`withArduPilotHomeSlot` 前置 home 占位；实测上传 [takeoff,rtl] 读回 [home,takeoff,rtl] 并完整执行 |
| ③ | custom_mode 枚举 | PX4 nav_state（3=MISSION，4=RTL） | ArduCopter 枚举（3=AUTO，4=GUIDED，6=RTL…） | **已修**：`modeLabel()` 按 `HEARTBEAT.autopilot` 分派两套表；修复前 GUIDED 误显示 RTL |
| ④ | 任务项拉取变体 | 发 MISSION_REQUEST_INT(51) | 发 legacy MISSION_REQUEST(**40**) | 兼容层（双 waiter）本就吸收，无改动；本文档原文写反，已按实测改正 |
| ⑤ | MISSION_CURRENT 计数口径 | `total` = 任务条目数 | 读回 3 条（含 home）/ 遥测 `missionTotal=2`（不含 home） | 仅口径差异，记录不处理（前端进度条按遥测字段走） |
| ⑥ | arm 的模式约束 | 任意模式可 arm | **AUTO 下 arm 被拒**：STATUSTEXT "Arm: Auto mode not armable"（任务非 TAKEOFF 开头时）；切 GUIDED 后 arm 成功 | **已修**（P2-F1 方案 A）：`DroneCommandService.arm` 对 AP+AUTO 先 `DO_SET_MODE(custom=4)` 再 arm；`ArduPilotGroundArmTest` 5 例钉住命令序列 |
| ⑦ | AUTO 地面启动约束 | 无（sim 直接起飞/执行） | **要求任务首条命令 = TAKEOFF**：否则 MISSION_START(300) 回 `MAV_RESULT=4`，回退 DO_SET_MODE(176) 回 `MAV_RESULT=2`，STATUSTEXT "Mode change to Auto failed: init failed"；单点"去某点"任务从地面起不来。**同族补充（2026-10-29 实测）**：**armed 且在地面**时 `DO_SET_MODE(AUTO)` 本身也被拒（同属 init failed） | **已修**（P2-F1 方案 A）：**地面**（`landedState`=ON_GROUND；**armed ≠ 空中**——dispatch 先 arm 再上传，见 §11.3）AP 任务在 home 槽后自动前置 `NAV_TAKEOFF`（alt=首条用户项，≤0 兜底 10m）；`ArduPilotMissionUploadTest` +7 例（含全握手线上形状与"arm 后仍在地面"回归） |
| ⑧ | COMMAND_LONG TAKEOFF | **只查 armed**（任意模式都接受） | **2026-10-29 实测矩阵**：地面+AUTO+未 arm → 拒 `MAV_RESULT=4`；arm（自动切 GUIDED）后 → **接受并爬升**；空中+AUTO → 拒（非 GUIDED）；空中+GUIDED → 仍拒（**已起飞状态**不接受） | **已闭环**（P2-F1）：产品流"arm（自动进 GUIDED）→ takeoff"实测可用；**takeoff 刻意不做模式适配**——空中切 GUIDED 会静默打断任务、未 arm 不该由 takeoff 代做（arm 走独立入口 + 拦截链）。该决策由 `ArduPilotTakeoffModeTest` 4 例钉住；惯用法 B（GUIDED+DO_REPOSITION，不传任务）仍立项待触发 |

| ⑨ | 带位置命令的报文格式（DO_REPOSITION） | LONG 可（sim 双口径都收） | **强制 COMMAND_INT**：LONG → 拒 `MAV_RESULT=8`（COMMAND_INT_ONLY，与模式无关）；INT+非 GUIDED → `2`（DENIED，模式约束）；INT+GUIDED → ACCEPTED 并飞到位（**1e-7 度级精度**） | **已闭环**（P2-F2，2026-10-30）：`command()` 对 `COMMAND_INT_REQUIRED` 集合自动路由；此前编队修正/队形机动在 AP 上 100% 静默失效。回归：`CommandIntTest`（pymavlink 官方帧逐字节）/ `CommandIntRoutingTest` / sim INT 入站测试 |

**无差异项**（同样记录在案）：任务上传的 2.5s 丢包重发恢复在 AP 上工作正常；COMMAND_ACK
接受/拒绝语义一致；遥测解码字段（位置/姿态/电量/GPS）逐项对上；GCS 1Hz 心跳维持链路未触发
任何 failsafe。

### 11.3 遗留项

**P2-F1 · ArduPilot 地面派飞序列适配**（影响 `IncidentDispatchService.dispatch` 等
"arm → 单点任务 → start" 流程在 AP 上的可用性——两条硬契约叠加：⑥ AUTO 不可 arm、
⑦ AUTO 地面启动须 takeoff 开头）。**方案 A 已落地并 SITL 验收（2026-10-29）；方案 B 立项待触发。**

- **已落地（方案 A）**：`DroneCommandService` 两处线上适配——
  ① `arm`：对 AP 且当前 AUTO 的目标先 `DO_SET_MODE(custom=4 GUIDED)` 再 arm
  （非 AP/非 AUTO 目标一字节不变；切换未获确认不吞错，仍发 arm 由固件如实拒绝）；
  ② `withArduPilotGroundTakeoff`：对**尚在地面**的 AP 目标在 home 槽后前置
  `NAV_TAKEOFF`（高度取首条航点 alt，≤0 兜底 10m），保持现有任务模型、用户条目原样后移。
  地面判据 = `landedState`（1=ON_GROUND；2..4=空中）优先、`relativeAlt>1m` 兜底——
  **armed ≠ 空中**：dispatch 是"先 arm 再上传"，上传时刻已 armed 而机体在地面；
  首版用 `armed` 判空中被本轮 SITL 验收抓回重做（回归钉子
  `ArduPilotMissionUploadTest.groundTakeoff_insertedWhenArmedButStillOnGround`）。
- **验收（同一 dispatch 场景从地面全自动完成）`[AP-实测]` 2026-10-29**，SITL `-I1` +
  独立后端实例（未扰 ZCode 会话）：置 AUTO → arm（"从 AUTO 切 GUIDED 后再 arm"，
  COMMAND_ACK(400) ACCEPTED）→ 上传单点任务（"地面任务前置 TAKEOFF"，读回
  `[home, takeoff(50m), wp(50m,hold30)]`）→ `MISSION_START(300)` ACCEPTED →
  爬升 7.2→20.3→33.4→47.2→50.0m → 到点驻留 30s → `rtl` 返航降落（全部 COMMAND_ACK
  attempt 1 接受）。
- **独立复验（ZCode 实例，master `d2522e1c`，2026-10-29 晚）`[AP-实测]`**：同场景在另一套
  SITL 实例（`-I0`/14550 + 后端 18099，与上条验收用的 `-I1` 实例互不相干）从零重放，全部通过——
  预置"地面 + AUTO + 未 arm"起手，上传单点任务读回 `[home, takeoff(30m), wp(30m)]`；
  arm（自动切 GUIDED）ACCEPTED；`start_mission` 进 AUTO；t+18s 爬升至 30.0m、北飞 ~56m 到点
  驻留；手发 `rtl` 返航降落自动 disarm。固件侧实录 `Mission: 1 Takeoff` / `Mission: 2 WP`。
- **修复前对照（红）**：主树旧代码同一场景实测——AUTO 下 arm 被拒 `MAV_RESULT=4`；
  手工切 GUIDED 绕过 arm 后读回只有 `[home, wp]`（无 TAKEOFF），`start` 回
  `MAV_RESULT=4 → 回退 2`，固件侧实录 "Mode change to Auto failed: init failed"。
- **同族收口（⑧ `COMMAND_LONG TAKEOFF`）`[AP-实测]` 2026-10-29**：矩阵见 §11.2-⑧——
  产品流"arm（自动进 GUIDED）→ takeoff"实测**接受并爬升**；**`takeoff` 刻意不做模式适配**
  （空中切 GUIDED 会静默打断任务；未 arm 应由 `arm` 显式完成、走围栏拦截链），
  该决策由 `ArduPilotTakeoffModeTest`（4 例）钉住，变异 A/B 已验证（加模式切换 ⇒ 红）。
- **P2-F2 · 带位置命令 COMMAND_INT 契约（已落地，2026-10-30）`[AP-实测]`**：
  `FormationKeeper` 偏差修正与 `FormationService` 队形机动的 `DO_REPOSITION` 在 AP 真机上
  **完全不工作**——AP 对携带经纬度的命令**强制 INT 报文**，LONG 一律回 `MAV_RESULT=8`
  （COMMAND_INT_ONLY；与模式无关——非 GUIDED 与 GUIDED 悬停中结果相同）。而全栈缺这条消息
  → 又一例"sim 测不出"（drone-sim 两种口径都收）。修法：新增
  `CommandInt(75, LEN=35, CRC=158)`（编码与 pymavlink 官方打包帧**逐字节一致**）+
  `command()` 对 `COMMAND_INT_REQUIRED` 集合自动路由（p5/p6→x/y×1e7、
  frame=GLOBAL_RELATIVE_ALT_INT）+ sim 入站 INT 支持——**单一收口点**，REST raw 与编队的
  所有调用方同时受益。**SITL 复验（同一实例同一脚本，修复前后对照）**：非 GUIDED 态
  `8`→`2`（"格式不认"变"模式不对"，两层约束分离清晰）；GUIDED 悬停态 `8`→**ACCEPTED +
  爬 25m / 北飞 ~50m / 精确悬停**（落点 -35.362812 与**线上 int32 目标逐位一致**——
  1e-7 度刻度内的到位，float 口径下做不到的精度）。
  此落地同时把**方案 B 的地基铺平**（"去某点"惯用法所需的 DO_REPOSITION 真机可用）。
- **方案 B（目标形态，立项待触发）**：惯用法侧适配——AP 生态的"去某点"用
  `GUIDED + TAKEOFF + DO_REPOSITION(192)` 表达（Mission Planner "Fly To" 同款），
  不传任务；需要新命令语义、驻留/返航行为定义与 e2e 覆盖。与 A 并存、互不冲突，
  待真实需求触发时立项；**其命令地基（DO_REPOSITION 真机可用）已由 P2-F2 落地**。

---

## 12. PX4 SITL 首验实录（2026-10-30）

**P2「SITL 首次真实验证」的第二固件**：首次以 PX4（v1.13 源码构建，SIH 软件在环无头）
对接 cloud-backend，走通"连接 → 任务上传 → 起飞 → 航点 → RTL → 降落"最小闭环，并逐项产出
PX4 与 drone-sim / ArduPilot 的行为差异。环境：WSL2 Ubuntu 内
`PX4_SIM_MODEL=sihsim_quadx ./bin/px4 -d -s etc/init.d-posix/rcS`（sysid=1），
cloud-backend dev profile 监听 14550、`--aerofleet.drone-port=18570`（PX4 Normal 实例）、
`--aerofleet.udp.max-frame-rate-per-sysid=20000`（见 §12.2-⑤）。复现要点与 AP 同：
`POST /api/v1/drones/1/mission` → `POST /api/v1/drones/1/commands`（arm/start_mission/rtl）→
`GET /api/v1/drones/1`（遥测/告警）与线级抓包（`tcpdump -i lo udp port 14550 or 18570`）。

### 12.1 闭环结果

| 步骤 | 结果 | 证据 |
|------|------|------|
| 设备上线 | ✅ 心跳注册为 Drone-1，`autopilot=12`（PX4） | 遥测全字段非空（Montreal 家坐标 45.467116,-73.757837） |
| 模式标签 | ✅ `mode=HOLD`、`customMode=50593792`（0x03040000） | 打包字解码（§12.2-①）：地面待命 = AUTO/LOITER（QGC 里显示 "Hold"） |
| 任务上传 | ✅ `{"uploaded":3}`；线级时序 `MISSION_COUNT(3)` → `REQUEST seq0` → `ITEM(22,f6)` → `REQUEST_INT 1` → `ITEM(16,f6)` → `REQUEST_INT 2` → `ITEM(20,**f2**)` → **`MISSION_ACK result=0`** | tcpdump 时序（`px4-mission-timeline.py` 逐帧解码）；上传前基线见 §12.2-② |
| ARM | ✅ `COMMAND_ACK(400) result=0`，心跳 `armed=true base_mode=0x9D` | 地面+AUTO_LOITER 下 PX4 直接接受 arm（无 AP 那种 "Auto mode not armable" 约束） |
| 开始任务 | ✅ `MISSION_START(300)` 原生 ACCEPTED（未触发 DO_SET_MODE 回退） | STATUSTEXT "Armed by external command"→"Takeoff to 20.0 meters above home" |
| 飞行 | ✅ mode=`MISSION`（0x04040000）→ 爬升至 20.0m → 北飞 55m 航点 → 任务尾 RTL 返航降落自动 disarm | 遥测 relAlt 轨迹 + STATUSTEXT "Takeoff detected" / RTL / "Disarmed by landing" |
| 修复前对照 | ❌→✅ 三层红：任务整单被拒（`MAV_MISSION_RESULT=3`，见 §12.2-②）→ ACK 被限速淹（`mission upload timed out`，340k 条丢帧，见 §12.2-⑤）→ 到点即停（autocontinue=0，见 §12.2-③） | 同一脚本 `px4-before.sh` / `px4-after.sh` 前后对照 |

> 复现脚本为一轮联调脚本（未入仓）：先验三件套 + PX4 侧补丁（§12.2-⑥）在 `docs` 本段记全。

### 12.2 真实固件 vs drone-sim 行为差异记录（PX4 侧）

①-③ 为本轮修复项（已带回归测试落地）；④-⑦ 为固件/环境契约，处置见「处置」列。

| # | 差异点 | drone-sim | PX4 v1.13 实测 | 处置 |
|---|--------|-----------|----------------|------|
| ① | custom_mode 编码 | 裸 nav_state 值 0..22（历史口径） | **打包字** `(main_mode<<16)\|(sub_mode<<24)`（px4_custom_mode.h 联合体）：地面待命 `0x03040000`=AUTO/LOITER、任务中 `0x04040000`=AUTO/MISSION、返航 `0x05040000`；v1.13 对 AUTO_READY 等 nav_state 打包为 **0**（编码缺口，不猜模式） | **已修**（P2-F3）：`modeLabel` 按打包字解码；**sim 同批对齐发打包字**（单一口径，删历史裸值表）；`FlightModeLabelTest` 8 例 + sim `FlightStateModeWordTest` 3 例钉住数值 |
| ② | 任务项帧硬校验 | 帧随便填 | `parse_mavlink_mission_item`：带坐标帧（0/3/5/6）只收「位置命令」，**RTL(20) 用 frame=3 → 整单 `MAV_MISSION_UNSUPPORTED=3`**（且 PX4 随即中止传输，全任务作废）；无坐标命令必须 `MAV_FRAME_MISSION(2)` | **已修**（P2-F4）：`normalizeMissionItems` 单一收口点——无坐标命令→2、相对高 3→6（INT 规范编码）；覆盖 REST 任务/事件派飞/出警派飞全路径 |
| ③ | `autocontinue` 位 | **不消费**（整列照飞） | **真正消费**（mission.cpp「switch to next waypoint if autocontinue flag set」）：置 0 时到点即停——SITL 实测起飞到 20m 后悬停 150s 不奔航点 | **已修**（P2-F4 同收口点）：所有任务项 `autocontinue=1`；停驻语义走 `param1`（NAV_WAYPOINT time_inside）。**又一条"sim 测不出"** |
| ④ | mavlink 实例拓扑 | 单实例单端口 | PX4 SITL 一次起 4 个实例（Normal 18570→14550、Onboard 14580→14540、14280、Gimbal）；**任何实例被 GCS 主动握手后都会向该 GCS 回话**——后端 `drone-extra-ports=14580` 一配，两个同 sysid 实例同时喂路由表，**last-seen 路由在 18570/14580 间游走，任务传输跨实例即 `IGN MISSION_ITEM: No transfer`**（首轮失败实录） | **配置口径**：PX4 只配 Normal 实例端口（`--aerofleet.drone-port=18570`，**不要**加 14580）；产品侧「多链路路由钉定」立候选（§12.3） |
| ⑤ | 链路帧率 | 数十 fps | SITL Normal 实例 4MB/s（≈8.6k fps 实测）；后端默认限速 100/s/机 → **ACCEPTED 的 MISSION_ACK 与心跳成批被丢**（单轮 340k+ 条 `Rate limit exceeded` 日志；上传必现 `timed out`，心跳停更致 armed 显示滞后） | **运行口径**：SITL 联调调高 `--aerofleet.udp.max-frame-rate-per-sysid`；产品侧「限速饥饿」立候选（§12.3） |
| ⑥ | 环境构建口径 | — | SIH 必须 **`sih start` + `sensors start` 都起**（EKF2 需 sensors hub 汇聚 vehicle_imu，缺则 `ekf2 start failed(-1)`、arm 被拒 "angular velocity invalid"）；SITL 默认 lockstep 只由 `simulator` 模块驱动，SIH 场景须 `ENABLE_LOCKSTEP_SCHEDULER=no` 否则 hrt 时钟冻结（所有周期任务停摆） | **环境脚本口径**（本文档记录即闭环；PX4 侧补丁不入仓） |
| ⑦ | MISSION_CURRENT 扩展字段 | `total`=任务条目数 | **`total` 恒 0**（v1.13 不用该扩展字段）；`seq` 有效 | 口径差异，记录不处理（前端进度条按 total=0 时退化为只显示 seq，另行评估） |

### 12.3 遗留项

- **P2-F5 · 接收限速对低频关键帧的饥饿（~~候选~~ → **2026-10-31 已落地**）**：`UdpGateway`
  的单机滑动窗口限速（默认 100 fps）按时间到即丢，无消息类优先级——高码率流（SITL 4MB/s；
  真机遥测突发/日志下载同理）会在每个窗口开头吃满桶，**低频关键帧（MISSION_ACK/COMMAND_ACK/
  心跳）被成批丢弃**，表现为"命令超时"而非"链路过载"。**落地**：限速器拆双预算——关键帧
  （ACK/心跳/任务协议回执/STATUSTEXT）走独立小预算
  `aerofleet.udp.max-critical-frame-rate-per-sysid`（默认 50/s，不占主窗口），非关键帧主窗口
  语义不变；关键预算耗尽仍会丢（防滥用语义保留），配置 <=0 可回退旧行为。
  回归：`UdpGatewayCriticalFrameRateTest` 4 例。证据：§12.2-⑤。
- **P2-F6 · 多链路路由钉定（候选）**：`RouteTable` 按 sysid 记 last-seen 地址；
  多链路（PX4 双实例、真机数传+机载双链路）下状态性交换（任务传输）可能跨链路，
  在另一实例上表现为 "No transfer" 整单失败。修复方向 = 状态性交换绑定发起方地址
  （mission 处理器记住请求来源，items 回发该地址），而非全局路由。证据：§12.2-④。

