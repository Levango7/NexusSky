# NexusSky · 天枢 — 无人机智能操控系统

> **NexusSky（天枢）**：Nexus = 枢纽/连接点——这套系统的本质是**连接**：飞机 ↔ 云端 ↔
> 地面站，一条链路穿越 WiFi / LTE / 数传电台 / 卫星；Sky = 天空疆域。中文名"天枢"
> 取自北斗七星之首，主掌天机运转——无人机智能操控的中枢，亦暗合北斗导航的意象。

> **定位**：在"没有硬件、没有团队"的条件下，搭建一套**还能跑起来的**无人机智能控制系统骨架。
> 真飞机的位置被一台**软件模拟器**顶替，但所有协议、接口与真实硬件（PX4 飞控）完全一致——
> 未来买来 Pixhawk 硬件的那天，替换的是数据源，不是架构。

## 系统架构

```
┌──────────────┐   MAVLink/UDP    ┌──────────────────┐   REST/WS   ┌─────────────┐
│  drone-sim   │ ───────────────► │  cloud-backend   │ ──────────► │   gcs-web   │
│  虚拟无人机    │   14540 → 14550 │  Spring Boot 3    │  8080       │  React 地面站 │
│  (代替真飞控)  │ ◄─────────────── │  设备网关+API     │ ◄────────── │  MapLibre    │
└──────────────┘                  └──────────────────┘             └─────────────┘
                                        ▲
                    将来：Pixhawk/PX4 真机用 MAVLink/UDP 直连同一网关
```

| 模块 | 技术 | 职责 | 替换为真硬件时 |
|---|---|---|---|
| `mavlink-core` | 纯 Java 17 | MAVLink v1/v2 二进制协议栈（帧/CRC/消息编解码/UDP 传输，标准消息 + M0a–M9 扩展消息 420–479），**185 个单测，CRC 与官方逐字节一致** | 不需要换——PX4 原生说 MAVLink |
| `drone-sim` | 纯 Java 17 | 虚拟四轴：任务上传(Mission Protocol)、ARM/起飞/航点飞行/RTL 状态机、遥测 1-5Hz 广播 | 换成真飞控，UDP 端口不变 |
| `cloud-backend` | Spring Boot 3.5 | MAVLink 设备网关、机队注册表、任务上传客户端、REST API（155+ 端点）、WebSocket 推送、JWT 安全认证、多租户隔离、应急编排引擎 | 不需要换 |
| `gcs-web` | React 18 + MapLibre | Web 地面站：实时地图轨迹、飞行仪表 HUD、任务规划、命令下发、告警流、编队/喷洒/安防/应急等 39 个功能面板 | 不需要换 |

> **M0a–M4 能力扩展**：组网/环境/编队/喷洒/成像/硬件抽象均在上述四模块内叠加，
> 未新增顶层模块——详见下文 [能力扩展（M0a–M4）](#能力扩展m0am4) 章节。

## 快速开始（Windows）

```cmd
:: 1. 一键构建并启动 模拟器+后端
scripts\start-all.cmd

:: 2. 启动地面站（新开窗口）
cd gcs-web
npm install
npm run dev
```

打开 http://localhost:5173 —— 约 2 秒内机队列表会出现第一台虚拟无人机（Drone-1）。

**典型操作流**：左侧「任务规划 → 测绘模板」→「上传任务到飞机」→ 右侧「解锁」→「开始任务」→
观察地图上飞机沿航线飞行、轨迹线延伸、姿态仪摆动 → 「返航 RTL」。

> 注意：**后端必须与模拟器同机启动**（骨架阶段 UDP 走 `127.0.0.1`，模拟器像 PX4 真机
> 一样等待 GCS 先发心跳才开始通信，后端已内置 QGroundControl 式主动握手）。

## docker-compose 边界（Linux，如实声明）

`docker-compose.yml` 三个服务**全员 host 网络**（MAVLink UDP 的发现/路由依赖同网段语义，
bridge 网络下 backend 发往 `127.0.0.1:14540` 的探测包出不了容器——这是审查发现的实际缺陷，
已修正）。host 模式下 `ports:` 无效，已删除。**本仓沙箱环境无法运行 Docker daemon
（named pipe 被策略拒绝），compose 仅经过配置审查，未做 `docker compose up` 实测**；
CI 也没有 docker runner。在有 Docker 的 Linux 机器上验证：

```bash
mvn -DskipTests package
docker compose up -d
bash scripts/e2e-smoke.sh   # 复用既有冒烟（需要宿主 python3/curl）
```

前置：宿主已装 JDK17/Maven 打包（jar 是挂载不是构建进镜像）；前端用 dev server
（生产化应改多阶段构建，见 compose 注释）。

## 冒烟测试

模拟器与后端启动后：

```powershell
powershell -ExecutionPolicy Bypass -File scripts\e2e-smoke.ps1
```

验证链路：设备发现 → 任务上传 → ARM → 任务推进 → RTL → 轨迹留存。（另有 bash 版
`scripts/e2e-smoke.sh` 供 Linux/CI 使用。）

## 网络仿真（link-sim：无网段条件下的多网络环境）

没有硬件、没有多网段，一样能测**真实网络环境下的系统行为**。`link-sim` 是一个
MAVLink/UDP 链路损伤代理，插在飞机与云端之间，把"网络"从固定参数变成可编程的实验变量：

```
[drone-sim --bind-ip 127.0.0.2]  <──受损链路──>  [link-sim :14600]  <──>  [cloud-backend :14550]
        每架飞机一个 127.x.y.z「网段身份」              7 种真实链路画像
```

**四种损伤引擎**（每个方向独立实例——真实链路上下行不对称）：

| 引擎 | 模型 | 为什么这样设计 |
|---|---|---|
| 延迟 | base + 均匀抖动 | 模拟 RTT 及其波动 |
| 丢包 | **Gilbert-Elliot 两状态马尔可夫** | 真实无线丢包是**突发+有记忆**的（坏区连坏几包）；独立随机丢包测不出重传韧性 |
| 带宽 | 令牌桶（桶满丢，不排无穷队） | 无线拥塞的真实行为是丢而非缓冲 |
| 分区 | up/down 周期黑洞 | Starlink 换星/遮挡/数传被干扰的周期性失联 |

**7 种链路画像**：`lan`（对照）、`wifi5`、`wifi24-far`、`lte`、`lte-edge`、`radio-sik`（数传电台 5.7KB/s）、`starlink`（25s 通 / 2.5s 黑洞）。

**实测结果**（e2e-network.ps1 自动回归）：

- LTE 链路（1.1% 突发丢包 + 62ms 延迟）：完整任务流 7/7 PASS——任务上传重发自愈生效
- Starlink 链路（**9.6% 丢包**，每 27.5s 一次 2.5s 完全失联）：设备持续在线 + 完整任务流 PASS
  ——10s 心跳超时对 2.5s 黑洞的容忍度设计正确

**为过损链路而做的协议加固**（这些就是"网络测试的价值"）：
- 后端命令按 sysid 路由（路由表从入帧源地址学习，多机/多代理不串线）
- MISSION_COUNT 每 2.5s 重发直到飞控响应；ITEM 同样重发自愈
- drone-sim 上传会话 3s 无进展重发 REQUEST
- 后端 `aerofleet.drone-port=14600` 指向链路代理时自动走损伤链路

**用法**：

```cmd
:: 后端 discovery 已指向 14600（损伤链路）
scripts\e2e-network.ps1                 :: 默认 LTE 画像全回归
powershell -File scripts\e2e-network.ps1 -Profile lte-edge   :: 换更恶劣的画像
```

手动实验：`link-sim --profile radio-sik --port 14600 --drone-ip 127.0.0.2` 然后起
`drone-sim --bind-ip 127.0.0.2`——GCS 上能直观看到窄带链路的遥测断续。

模拟器支持 `--scenario` 在时间轴上注入硬件级故障，验证云端/地面站对异常的**反应链**：

```cmd
:: GPS 在开机后 30s 丢失、持续 20s 后恢复
java -jar drone-sim\target\aerofleet-drone-sim-0.1.0-SNAPSHOT.jar --scenario gps-loss:30:20

:: 组合故障：全程 6m/s 侧风 + 45s 起链路黑洞 15s
java -jar drone-sim\target\...jar --scenario wind:0:9999:6,link-loss:45:15
```

| 场景 | 语法 | 飞机侧表现 | 系统侧应检测到的 |
|---|---|---|---|
| GPS 丢失 | `gps-loss:起:持续` | fixType=0、卫星 0、位置冻结 | 后端 gpsHealthy=false + CRITICAL 告警 + GCS 红色横幅 |
| 链路中断 | `link-loss:起:持续` | 遥测/心跳全部黑洞 | 心跳超时 10s → 设备标记 offline + GCS 黄色横幅 |
| 电池故障 | `battery-fault:起[:剩余%]` | 电压骤降、百分比跳变 | 电压/电量突变 + 低电告警 |
| 持续风扰 | `wind:起:持续:风速` | 航迹侧偏（25% 残差）、roll 偏置 | 轨迹与计划航线偏离可见 |
| GPS 漂移 | `gps-noise:起:持续:半径m` | 上报位置随机游走 | 轨迹毛刺可见 |

回归测试（后端运行中执行；自动起停故障模拟器）：

```powershell
powershell -ExecutionPolicy Bypass -File scripts\e2e-fault.ps1
```

三条检测链全部自动断言：GPS 丢失/恢复、电池跳变、链路超时。CI 里对应
`scripts/e2e-fault-linux.sh`。

## 机载 Failsafe（PX4 兼容的自主保护）

模拟器内置了与 PX4 缺省参数一致的自动驾驶保护层（`--failsafe off` 可关，
便于 A/B 对照）。这不是"云端看得见"的告警——是**飞机自己救自己**：

| 触发 | 阈值 | 动作 | PX4 参数对应 |
|---|---|---|---|
| 数据链路失联 | 心跳沉默 15s | 自动 RTL（返航降落，全程零人工） | `NAV_DLLC_ACT=RTL` |
| 电量临界 | ≤22% | 自动 RTL | `BAT_CRIT_THR` |
| GPS 丢失 | fixType<3 | HOLD 原地悬停，恢复后续飞原任务 | `COM_GPSLOSS_ACT=Hold` |

优先级：电量 > 链路 > GPS（与 PX4 一致）。`link-loss` 故障场景为**双向黑洞**
（真实电台断了就是双向断），入站命令同样丢弃——所以 failsafe 的失联计时器
看到的与真机完全一致。

**实测**（`scripts/e2e-failsafe.ps1` / `.sh` 自动断言）：任务中段注入 40s
链路黑洞——云端 10s 心跳超时标记 offline；飞机在失联第 15 秒自主进入 RTL、
爬升、返航、降落、disarm，全程无任何命令；链路恢复后云端重新上线看到一架
已自行安全落地的飞机。

两个配套修复（failsafe 暴露出的真实缺陷）：

1. **云端 1Hz GCS 心跳**（`UdpGateway.heartbeatLoop`）：QGroundControl 永远
   在"说话"；原骨架 discovery 握手完成后就静默，正常任务飞到 15 秒也会被
   飞机的失联保护打断返航——这正是有了 failsafe 才测得出的隐性缺陷。
2. **心跳携带真实 PX4 nav_state**：STANDBY=13 / POSITION=2 / MISSION=3 /
   RTL=4 / HOLD=15，GCS 模式显示不再恒为 MANUAL。

## 物理引擎 v2 与传感器故障（P1）

**运动学 v2**（`DronePhysics`）：水平加速度受限 3 m/s²、协调转弯（机头跟随
速度矢量、偏航率上限 1.8 rad/s、bank ≤30°）、目标前制动（不冲过验收半径）、
姿态由真实加速度矢量导出（多旋翼"往哪加速往哪倾"的物理事实）。轨迹从此有
真实的**转弯半径**与**加减速斜坡**，不再是瞬时转向的匀速直线。

**传感器故障场景**（`--scenario` 新增三种，只污染上报值、真值照飞——和真实
EKF 带偏置陀螺仪的行为一致）：

| 场景 | 语法 | 上报表现 |
|---|---|---|
| IMU 陀螺漂移 | `imu-bias:40:60:2` | roll/pitch/yaw 以 2°/s 持续偏离真值 |
| 气压计漂移 | `baro-drift:30:60:0.1` | 高度读数 0.1m/s 缓慢发散（垂直通道失效经典相） |
| 磁罗盘干扰 | `mag-interference:50:20` | yaw 在 ±15° 内随机游走（过高压线/钢结构） |

**地形与围栏**（PX4 GF_* 语义）：

```cmd
:: 一座 80m 山（home 北 300m/东 100m，半径 150m）+ 1.2km 方形围栏、顶盖 120m
java -jar drone-sim\...jar --terrain hill:300:100:150:80 --fence -600,-600:600,-600:600,600:-600,600:120
```

- **撞地保护**：位置低于地形（含 0.5m 判定带）→ `CRASHED` 终态（EMERGENCY
  心跳、拒绝一切命令、需重启进程）——GCS 能看到"坠机"这个事实本身
- **围栏越界**：出多边形或超顶盖 → 一次告警 + failsafe RTL（`GF_ACTION=RTL`）；
  凹多边形正确处理（射线法）

**飞行日志持久化**：`./flight-logs/flight-YYYY-MM-DD.jsonl`，遥测 1Hz 节流、
告警/任务/上下线即时落盘（不依赖有人开着 GCS 页面）。查询：

```
GET /api/v1/flightlog?day=2026-09-13&type=alert&sysid=1&limit=100
GET /api/v1/flightlog/track?sysid=1            # 某日完整轨迹（从遥测行重建）
```

骨架阶段选 JSON Lines 而非 SQLite：零依赖、可 grep、可 git diff；换数据库
是 `flightlog` 包一个包的事。

## GCS 完整化（P2）

**任务规划闭环**：
- 地图 **Shift+点击** 加航点（光标变十字准星），任务列表可删可清
- **任务回读**：`⬇ 读取机载任务` 走完整 MISSION_REQUEST_LIST→COUNT→
  REQUEST_INT→ITEM_INT→ACK 下载协议，回读结果载入草稿可继续编辑
- REST：`GET /api/v1/drones/{sysid}/mission`（与 upload 对称的 download）

**虚拟摇杆（MANUAL_CONTROL 直通）**：
- REST：`POST /api/v1/drones/{sysid}/joystick {"x":0,"y":700,"z":500,"r":0}`
  （x/y/r ∈ [-1000,1000]，z 油门 [0,1000]，500=悬停；fire-and-forget ~10Hz）
- 前端：按住拖动的触控板式摇杆 + 垂直油门滑条（右栏），松手 2 秒后飞控
  自动回 **position hold**（模拟 RC 失控保护）
- drone-sim 侧：MANUAL 模式与 MISSION/RTL/HOLD 互斥（自动驾驶优先，与
  PX4 模式切换语义一致）；体轴→地轴变换复用 v2 加速度限制积分器，
  手动飞行同样有真实的加速斜坡与倾转姿态

前端构建注记：沙箱内 esbuild 的 stdio-pipe 策略不可用，前端回归用
`gcs-web/scripts/check-frontend.cjs`（@babel/parser 全量语法 + import 图
校验）；完整 `vite build` 在无限制环境中执行（CI/本机均可）。

## 智能感知闭环（P3）

**合成目标世界**（`--targets`，分号分隔多个目标）：

```cmd
:: 一辆 8m/s 向东的车 + 一个原地物体 + 一个 1.3m/s 行人
java -jar drone-sim\...jar --targets "vehicle:22.5925,113.9360:14:90:0.5;static:22.5907,113.9355" --http-port 18080
```

vehicle/pedestrian 有真实的运动学（恒速+随机游走转向），与无人机共用同
一仿真时钟。`--http-port` 起一个 loopback 真值通道（JDK 内置 HttpServer，
零依赖）：`GET /targets`（目标真值）、`GET /camera/shots`（影像元数据）。

**相机/云台模型**（`CameraModel`）：1920×1080、90° FOV 针孔模型 + 二轴
云台（pitch 0=垂直向下/90=水平，yaw 任意）。"拍照"= 把目标世界正投影到
像平面（含机体姿态与云台指向的完整旋转链），影像即元数据——模拟世界里
不存在要渲染的像素。触发走 **MAV_CMD_IMAGE_START_CAPTURE(2000)**，仅在
飞行中接受（与真实相机一致）。

**地理定位解算**（云端 `GeolocationSolver`）：正投影的精确逆运算——像素
(u,v) + 内参 + 机体姿态 + 云台角 + 高度 → 反解视线 → 与地面求交 → 经纬
度。四个互逆单测锁定（nadir/带姿态/斜视云台/中心像素，误差 <1e-9°）。

**闭环管线**（`POST /api/v1/vision/drones/{sysid}/capture` 一发全链）：

```
拍照(2000) → ACK → 等 truth 上新帧 → 逐目标逆解算经纬度 → 与 /targets 真值比对误差
```

e2e 实测：60m 悬停拍东偏 49m 目标 → 检出于 (1792,515)px → 解算定位
**误差 0.0m**（逆解算与真值完全一致——数学闭环自洽的直接证据）。
骨架阶段的"检测器"是投影本身（相机看到什么即检出什么）；换真实 CV 模型
只需替换目标清单来源，定位/比对链路不变。

## 相机协议 v2（Batch A：GCS 会话 + 照片事件）

drone-sim 现在扮演完整 MAVLink 相机角色（消息 ID/LEN/CRC 全部对齐官方
c_library_v2 生成头，非手工估值）：

| 消息 | ID | LEN | CRC_EXTRA | 触发方式 |
|---|---|---|---|---|
| CAMERA_INFORMATION | 259 | 237 | 92 | MAV_CMD 518 / 512(param1=259) |
| CAMERA_SETTINGS | 260 | 14 | 146 | MAV_CMD 520 / 512(param1=260) |
| CAMERA_CAPTURE_STATUS | 262 | 23 | 12 | MAV_CMD 521 / 512(param1=262) |
| CAMERA_IMAGE_CAPTURED | 263 | 255 | 133 | 每次成功拍照自动广播 |
| CAMERA_FOV_STATUS | 271 | 53 | 22 | （编码就绪，可按需接周期推送） |

- 会话语义按官方 Camera Protocol v2：GCS 发 REQUEST_* 命令 → 相机即时
  回应对应消息；CAMERA_IMAGE_CAPTURED 的 `image_index` 与 CAPTURE_STATUS
  的 `image_count` 同步迭代，`file_url` 填真值 HTTP 地址。
- 任务内拍照：mission 项 `cmd:"capture"`（转成 MAV_CMD 2000 航点项），
  到点即拍——环绕任务的逐站影像就是它。
- 透传通道：`POST /drones/{id}/commands {"type":"raw","cmd":518,"p1".."p7"}`
  可发任意 MAV_CMD（相机会话即由它驱动）。

## 自动重定向环绕（Batch A：orbit 闭环）

`POST /api/v1/vision/drones/{sysid}/orbit`
`{"lat":22.5916,"lon":113.9345,"radiusM":25,"altM":60,"photos":4}`

云端生成 `[takeoff, (waypoint+capture)×N, rtl]` 圆弧任务 → 上传 →
ARM → startMission → 逐站拍照 → 逐站逆解算定位 → 喂跟踪器 → 返回
每站影像与航迹快照。约束：radiusM∈(0,500]、altM∈[20,120]、photos∈[2,12]。

**几何经验（e2e 调出来的，别踩）**：90°FOV@60m 竖直半幅只有 ±33.75m；
环点必须带 ~2.5s hold——到达瞬间机身仍带减速前倾（pitch≈10° 会把 nadir
画幅中心前甩 ~11m），悬停等姿态回平再拍，站站才能命中目标。

## 目标跟踪器（Batch A：跨帧关联）

`GET /api/v1/vision/drones/{sysid}/tracks` —— 逐目标航迹
（id/state/hits/lastSeen/predicted）。

- **关联**：最近邻 + 30m 门限（仿真目标帧间位移远小于此）；
- **预测**：匀速外推（两次观测的位置差 / 时间差）；
- **生命周期**：ACTIVE（持续更新）→ LOST（30s 无更新；查询可见，
  不会再复活——新检出开新航迹，诚实且简单）→ 超时清除。
- 每台无人机独立命名空间；e2e 中环绕 4 站对同一静态目标连续命中
  形成 hits=4 单航迹，交叉目标不串扰。

## 能力扩展（M0a–M9、4a）

十二个里程碑在骨架之上叠加了组网、环境、编队、喷洒、成像、硬件抽象、灾害应急通讯组网
与空地一体化应急指挥能力，均沿用既有 MAVLink/REST/WebSocket 三段式架构，新消息 ID 按
420–467、477–483 段分配。

### M0a — Mesh 组网落地

`link-sim` 新增一跳静态中继（`RelayConfig`/`RelayNode`），支持多跳转发——
命令经中继可达远端飞机，突破单跳视距限制。`scripts/e2e-mesh.ps1` 演示
单中继两跳链路下的完整任务流。

### M0b — 环境气象机制

引入环境模型（温度/湿度/天气/风力）与告警引擎 `EnvAlertEngine`（温度/湿度/
风力/能见度阈值告警），通过 `EnvironmentAlert`(421)/`EnvironmentStatus`(422)
MAVLink 消息下发。风偏修正与雨衰叠加进入链路损伤模型；REST 端点
`/api/v1/env/alerts` 暴露告警查询。

### M1 — 编队表演

`LedControlMsg`(420)+`LightPattern` 枚举驱动机载灯效；`FormationGeometry`
提供圆形/线形/V形/菱形队形几何，`FormationService` 管理创建→变换→解散状态机，
`FormationKeeper` 持续保持队形与位置修正。REST `/api/v1/formation/*` 下发指令，
WebSocket 以 1Hz 推送编队状态，前端 `FormationPanel.jsx` 可视化操控；
`scripts/e2e-formation.ps1` 端到端回归。

### M2 — 喷洒物流

执行器模型（`Actuator`/`SprayPump`/`Gripper`/`PayloadModel`）+ 喷洒/夹爪/载荷
MAVLink 消息(423–426)。`SprayTaskService` 调度喷洒任务，`DeliveryService`
编排物流配送序列；REST `/api/v1/spray/*` 与 `/api/v1/delivery/*` 对外暴露。

### M3 — 成像增强

`VisionSource` 统一投影/模拟视觉感知源，接入多光谱/热成像/深度多源数据
（`Multispectral`/`Thermal`/`Depth`）。`ObstacleDetector` 做障碍检测，
`ObstacleAvoidanceController` 按威胁等级映射避障命令（CRITICAL→悬停、HIGH→避障）。
对应 MAVLink 消息 430–434，REST `/api/v1/obstacle/*`、`/api/v1/multispectral/*`、
`/api/v1/thermal/*`，WebSocket 推送障碍报告，前端 `VisionPanel.jsx` 展示。

### M4 — 硬件抽象

引入相控阵雷达（`PhasedArrayRadar`）、旋翼气动（`RotorAerodynamics`）、
LiDAR（`LiDARSource`）、IMU（`ImuSource`）四类硬件抽象与各自 Simulated 实现，
MAVLink 消息 437–441（`RadarScan`/`RadarTarget`/`RotorTelemetry`/`LidarData`/`ImuData`）。
`VirtualDrone` 集成硬件层并支持物理模型切换（运动学↔气动），`ObstacleDetector`
融合 LiDAR 数据。REST `/api/v1/radar/*`、`/api/v1/rotor/*`、`/api/v1/lidar/*`、
`/api/v1/imu/*`，WebSocket 以 1Hz 推送硬件数据。

### M5 — 应急 Mesh 自愈组网

AODV-lite 多跳动态路由（`MeshRouter`/`RouteTable`/`NeighborTable`/`RreqCache`），
支持路由发现、自愈重构、链路质量评估。MAVLink 消息 450–454（MeshHeartbeat/
RouteRequest/RouteReply/RouteError/NeighborTable），`link-sim` 新增 `MultiHopRelayConfig`
多跳中继配置。REST `/api/v1/mesh/*`，前端 `MeshTopologyPanel.jsx` 可视化拓扑。

### M6 — 移动基站载荷抽象

无人机搭载 LTE/WiFi/LoRa 基站载荷（`CellTowerFactory`/`CoverageArea`/`HandoverManager`），
支持覆盖区计算、终端接入管理、越区切换。MAVLink 消息 455–458（CellTowerStatus/
Config/Handover/GroundTerminalRegister），REST `/api/v1/celltowers/*`，
前端 `CellTowerPanel.jsx` 可视化基站拓扑。

### M7 — 星-空-地多层级中继

LEO 卫星 + HAPS 高空平台 + Mesh 三层级中继（`HierarchicalRouter`/`LeoConstellation`/
`HapsRelayNode`），支持卫星过境窗口预测、层级路由决策、链路切换。MAVLink 消息 459–461
（SatLinkStatus/SatPassSchedule/HierarchicalRouteDecision），REST `/api/v1/satlink/*`，
前端 `SatLinkPanel.jsx` 可视化中继链路。

### M8 — 复杂地形适配

山地/森林/沼泽/城市等地形分类与 RF 衰减建模（`TerrainGrid`/`TerrainClassifier`/
`EnhancedRadioEnvironment`/`FlightConstraintChecker`），支持地形变化监测、
飞行约束检查、覆盖范围地形衰减。MAVLink 消息 462–464（TerrainTypeMap/
TerrainUpdate/FlightRestriction），REST `/api/v1/terrain/*`，
前端 `TerrainMapPanel.jsx` 可视化地形地图。

### M9 — 应急任务编排（全流程闭环）

将 M5–M8 能力编排为完整应急响应工作流：灾区测绘 → 覆盖规划 → 组网部署 →
持续服务 → 自愈重构。编排引擎（`OrchestrationEngine`）驱动五阶段状态机，
覆盖优化算法（`CoverageOptimizer`）贪心+局部优化部署方案，动态重构器
（`DynamicReconfigurator`）处理无人机损毁/电量不足，优先级调度器
（`PriorityScheduler`）四级抢占式调度（搜救>指挥>测绘>常规），场景预设
（`ScenarioPresetFactory`）支持地震/泥石流/火灾一键启动。MAVLink 消息 465–467
（EmergencyMissionPlan/CoverageOptimization/EmergencyPriority），
REST `/api/v1/emergency/*`，前端 `EmergencyOrchPanel.jsx` 可视化编排进度。

### 4a — 空地一体化应急指挥

在 M9 应急编排之上接入安防硬件与报警联动，形成"空（无人机）地（安防设备）一体"
应急指挥闭环。

**ONVIF 安防设备接入**（`surveillance` 包）：兼容海康威视/大华/宇视三大安防硬件
供应商协议，支持设备发现、RTSP 视频流拉取、PTZ 云台控制、事件订阅。

**报警联动编排引擎**（`alarm` 包）：报警事件接收 → 联动规则匹配 → 自动触发
无人机侦察任务（起飞 → 飞往报警位置 → 盘旋侦察 → 实时回传 → 返航）。

**GCS 视频融合面板**：`SurveillancePanel`（设备列表/多画面分屏/PTZ 控制）+
`AlarmPanel`（SSE 实时报警/联动规则管理/一键应急响应）。

**MAVLink 报警消息**：`AlarmTriggerMsg`(477)/`AlarmAckMsg`(478)/
`SurveillanceStatusMsg`(479)。

**应急指挥工作流**：六阶段（接报 → 研判 → 部署 → 执行 → 评估 → 总结），
一键应急响应自动走完全流程。

### P2 — 灾害应急通讯组网扩展

在 M5–M9 与 4a 应急能力之上，P2 进一步扩展灾害场景下的通讯组网与搜救指挥能力，
覆盖 QoS 保障、分簇路由、异构链路桥接、Budget 模式、丐版 Mesh、热源搜救等 11 个子模块，
均沿用既有 MAVLink/REST/WebSocket 三段式架构，新消息 ID 按 480–483 段分配。

**QoS 优先级队列 + 分簇路由**：灾害场景下通讯资源极度受限，QoS 引擎按业务优先级
（搜救 > 指挥 > 测绘 > 常规）分配带宽与转发资源；分簇路由将无人机群按地理/拓扑
自动分簇，簇头负责簇内聚合与簇间转发，减少全局路由开销。MAVLink 消息
`QoSRouteDecisionMsg`(480) / `ClusterFormationMsg`(481) 下发路由决策与簇 formation。

**异构链路桥接 + 灾区通信隔离**：灾害现场往往存在 WiFi/LTE/LoRa/卫星等多种链路
碎片化覆盖，异构链路桥接层自动探测可用链路并按策略切换/聚合；灾区通信隔离确保
灾区内部通讯不被外部干扰，同时允许指定通道对外回传。LoRa 回传通道作为窄带备用链路，
在主链路全部中断时保障最低限度指令传达。

**灾害通信监控 + 灾害态势面板**：实时监控灾区链路质量、节点存活、带宽利用率等指标，
统一态势感知面板在 GCS 端以可视化方式呈现灾区通讯拓扑、链路状态、节点健康度，
为指挥决策提供数据支撑。

**厂商协议适配层（海康/大华/宇视/ONVIF）**：统一适配主流安防硬件厂商协议，
通过 ONVIF 标准接口 + 厂商私有协议扩展，实现设备发现、视频拉取、PTZ 控制、
事件订阅的统一抽象，灾害场景下快速接入现有安防基础设施。

**Emergency Budget 模式**：针对灾害应急资源受限场景，定义两级预算模式：
- **EMERGENCY_TOY（应急百元级）**：极低成本配置，ESP-NOW + AMG8833 + LED/蜂鸣器，
  适合快速部署的小规模搜救
- **EMERGENCY_STANDARD（应急千元级）**：标准成本配置，LoRa Mesh + 多链路桥接 +
  卫星中继，适合中等规模灾区持续通讯保障

**复杂地形飞行约束**：在 M8 地形适配基础上增强灾害场景特有约束——地震后建筑倒塌
导致遮挡模型动态更新、泥石流改变地形高程、火灾烟尘影响能见度与传感器精度，
飞行约束检查器实时感知地形变更并调整限飞区/安全高度。

**卫星中继增强（天通/铱星/星链）**：在 M7 多层级中继基础上扩展三类卫星中继：
天通卫星（高轨，稳定覆盖但高延迟）、铱星（低轨，低延迟但需过境窗口）、
星链（低轨星座，带宽最优但需终端适配），按灾区位置与可用窗口自动选择最优卫星链路。

**空地协同指挥流程**：六阶段指挥流程（接报 → 研判 → 部署 → 执行 → 评估 → 总结）
在 4a 基础上深化，支持空（无人机侦察/中继）地（安防设备/地面终端）协同，
一键应急响应自动编排全流程。

**丐版 Mesh 路由（ESP-NOW/LoRa）**：针对 Budget 模式的极简 Mesh 实现，
ESP-NOW 用于近距离低延迟机间通讯（百元级），LoRa 用于远距离窄带通讯（千元级），
均支持多跳转发与自愈重构，是 M5 AODV-lite 的轻量化替代方案。

**AMG8833 热源搜救 + LED/蜂鸣器控制**：AMG8833 红外热传感器阵列（8×8 像素）
用于灾害废墟下热源检测与人员搜救；LED/蜂鸣器控制提供机载声光指引，
帮助地面搜救人员定位无人机与标记发现的目标。MAVLink 消息 `BuzzerControlMsg`(483)
下发蜂鸣器开关/频率/时长指令。

**GCS Emergency UI 适配**：前端 Emergency UI 适配 Budget 模式与灾害态势面板，
在 `EmergencyOrchPanel` 基础上扩展 Budget 模式切换、丐版 Mesh 拓扑可视化、
热源搜救标记、声光控制面板等交互组件。

### MAVLink 消息 ID 分配（420–467、477–483 段）

| 范围 | 里程碑 | 消息 |
|---|---|---|
| 420 | M1 | LedControlMsg |
| 421–422 | M0b | EnvironmentAlert, EnvironmentStatus |
| 423–426 | M2 | SprayStatus, SprayCommand, GripperCommand, PayloadStatus |
| 430–434 | M3 | ObstacleReport, MultispectralData, ThermalData, DepthData, VisionDetection |
| 437–441 | M4 | RadarScan, RadarTarget, RotorTelemetry, LidarData, ImuData |
| 450–454 | M5 | MeshHeartbeat, MeshRouteRequest, MeshRouteReply, MeshRouteError, MeshNeighborTable |
| 455–458 | M6 | CellTowerStatus, CellTowerConfig, CellHandover, GroundTerminalRegister |
| 459–461 | M7 | SatLinkStatus, SatPassSchedule, HierarchicalRouteDecision |
| 462–464 | M8 | TerrainTypeMap, TerrainUpdate, FlightRestriction |
| 465–467 | M9 | EmergencyMissionPlan, CoverageOptimization, EmergencyPriority |
| 477–479 | 4a | AlarmTriggerMsg, AlarmAckMsg, SurveillanceStatusMsg |
| 480 | P2 | QoSRouteDecisionMsg |
| 481 | P2 | ClusterFormationMsg |
| 482 | P2 | DisasterModeStatusMsg |
| 483 | P2 | BuzzerControlMsg |

## 飞行日志（flightlog，JSONL 落盘）

`GET /api/v1/flightlog?day=2026-09-13&type=alert&sysid=1&limit=100`
（另有 `/flightlog/track`）——按 UTC 日一文件（`flight-logs/` 可配
`aerofleet.flightlog.dir`），telemetry 节流 1s/机，alert/mission 即时
写。纯文件、可 grep；换 SQLite/Postgres 是包内替换。B3 补齐 7 个单测
（@TempDir 往返/节流/过滤/轨迹重构/NaN 容忍）。

## API 摘要（/api/v1）

- `GET /drones` 机队列表 · `GET /drones/{id}/telemetry` 遥测快照 · `GET /drones/{id}/track` 轨迹
- `POST /drones/{id}/mission` 上传任务 `{"items":[{"cmd":"waypoint|takeoff|rtl|capture","lat":22.59,"lon":113.93,"alt":50,"holdTime":2}]}`
- `POST /drones/{id}/commands` 命令 `{"type":"arm|disarm|start_mission|rtl|takeoff","alt":30}`；`{"type":"raw","cmd":518,"p1".."p7"}` 透传任意 MAV_CMD
- `POST /drones/{id}/joystick` 手动控制 `{"x","y","z","r"}`（MANUAL_CONTROL 透传）
- `POST /vision/drones/{id}/capture` 一发全链拍照定位 · `POST /vision/drones/{id}/orbit` 环绕闭环 · `GET /vision/drones/{id}/tracks` 航迹查询
- `GET /flightlog` 飞行日志查询 · WebSocket `/ws/telemetry`：`{"type":"telemetry"|"status"|"alert", ...}`（1Hz 快照节流）
- `POST /auth/login` 登录获取 JWT · `POST /auth/refresh` 刷新令牌
- `GET /geofence/zones` 围栏区域 CRUD · `POST /geofence/check` 手动围栏检查
- `POST /drone-lock/{id}/lock` 远程锁机 · `POST /drone-lock/{id}/unlock` 解锁
- `POST /alarms/events` 报警事件接收 · `GET /alarms/stream` SSE 实时报警推送
- `POST /emergency-command` 应急指挥 · `POST /emergency-command/{id}/one-click` 一键应急响应
- `GET /v1/emergency/orch/*` 应急编排 · `GET /v1/emergency/scenarios` 场景预设
- `POST /v1/formation` 编队创建 · `POST /v1/formation/{id}/transition` 队形变换 · `POST /v1/formation/{id}/lights` 灯光控制
- `POST /v1/spray` 喷洒任务 · `POST /v1/delivery` 配送任务
- `GET /v1/mesh/topology` Mesh 拓扑 · `GET /v1/celltowers` 基站状态 · `GET /v1/sat-link/status` 卫星链路
- `GET /v1/terrain/map` 地形图 · `POST /v1/terrain/build` 地形建图
- `POST /scheduling/tasks` 集群调度 · `GET /v1/squad/roles` 角色状态
- `GET /ai/decisions` AI 决策监控 · `POST /edge/results` 边缘计算结果提交
- `GET /twin/state/{id}` 数字孪生 · `GET /twin/predict/{id}` 轨迹预测
- `GET /v1/env-alerts` 环境告警查询 · `GET /audit/logs` 审计日志（需 ADMIN）
- `GET /license/info` License 信息 · `POST /license/activate` 激活 License
- `GET /surveillance/devices` 安防设备 · `POST /surveillance/rapid-deploy` 一键布控
- `GET /tracking/{id}/track` 飞行追踪 · `GET /tracking/lost` 失联无人机列表
- `GET /health` 健康监控 · `POST /inspection` 巡检任务
- `GET /mapping` 测绘任务 · `POST /show` 表演管理
- `POST /voicecmd` 语音指令 · `GET /citytwin` 城市孪生
- `GET /scenario/templates` 场景模板 · `POST /scenario/launch` 场景启动
- `GET /v1/qos/decisions` QoS 路由决策 · `POST /v1/qos/priority` 优先级设置
- `GET /v1/cluster/formation` 分簇拓扑 · `POST /v1/cluster/reconfigure` 簇重构
- `GET /v1/disaster/status` 灾害模式状态 · `POST /v1/disaster/budget` Budget 模式切换
- `POST /v1/buzzer/control` 蜂鸣器控制 · `GET /v1/thermal/search` 热源搜救

> 完整 API 文档详见 [docs/api-reference.md](docs/api-reference.md)，共 54 个 Controller、155+ REST 端点。

## 硬件替换指南（“缺斤少两”补齐之路）

1. **买真硬件**：Pixhawk 6C 飞控（约 ¥1000）+ 机架电机桨叶电池（约 ¥1500），或直接买 PX4 预装整机。
2. **接线不变**：真飞控通过数传模块（如 Holybro SiK）或 ESP32 Bridge 以 **MAVLink over UDP** 发往 `cloud-backend` 的 14550 端口——协议和模拟器一模一样。
3. **一步验证**：先跑 `mavlink-core` 单测（`mvn -pl mavlink-core test`），再接真机；若 CRC 全绿，链路即通。
4. **地面站与云端零改动**。唯一要新增的是真机的失控保护参数（RTL 高度、低电量阈值），在 PX4 参数里配，不在代码里。

### PX4 兼容层（对真固件的协议差异已消化）

| 差异点 | PX4 行为 | AeroFleet 应对 |
|---|---|---|
| 任务拉取请求 | 回 `MISSION_REQUEST`(43) 而非 51 | PendingAcks 双消息 ID 登记 waiter |
| MISSION_COUNT 目标 | 拒绝 broadcast(0) | `targetSystem()` 取注册表真实 sysid |
| MISSION_START(300) | 部分版本 UNSUPPORTED | 回退 `DO_SET_MODE`(176) auto 模式 |
| 心跳 custom_mode | PX4 nav_state 枚举 | `px4NavStateLabel()` 完整映射 |

SITL（真固件软件在环）接入步骤见 [docs/sitl-integration.md](docs/sitl-integration.md)。

## 端口约定（与 PX4/ArduPilot 生态一致）

| 端口 | 用途 |
|---|---|
| 14540/udp | 飞控(模拟器)侧 MAVLink |
| 14550/udp | GCS/云端侧 MAVLink（与 QGroundControl 默认一致） |
| 8080/tcp | 云端 REST + WebSocket |
| 5173/tcp | GCS 开发服务器 |

## 多机命令路由（P0 修复后的正确语义）

- REST 路径里的 sysid 是**强约束**：`POST /drones/7/mission` 只会发给 7 号机；
  未注册过的 sysid 直接拒绝（不能把命令发给陌生设备）
- 出站命令按**路由表**（sysid→源地址，从入帧学习）逐机投递，不再依赖 lastPeer
- 应答关联按 `(消息ID, 判别符, sysid)` 三元组：A 机的 COMMAND_ACK 永远不会
  完成 B 机命令的 future——并发双机命令/传任务不串线

## 代码结构

```
NexusSky/
├── mavlink-core/        协议栈（无依赖，可直接复用到任何 Java 项目）
│   ├── MavlinkFrame / MavlinkParser / MavlinkCrc / MavlinkMessageInfo
│   ├── messages/        标准 MAVLink 消息 + 扩展消息（420–483 段）
│   ├── enums/MavEnums  官方枚举常量
│   └── transport/      UDP 传输
├── drone-sim/           虚拟无人机（状态机 + 任务协议服务端 + 物理引擎 v2）
├── link-sim/            链路损伤代理（延迟/丢包/带宽/分区 + Mesh 中继）
├── cloud-backend/       Spring Boot 单体（网关/机队/任务/推送/安全/编排）
│   └── 33 个功能包：api, security, alarm, surveillance, mission, orch,
│       scheduling, twin, edge, ai, geofence, drone, health, inspection,
│       scenario, mapping, show, voicecmd, citytwin, commadapt, autodispatch,
│       tracking, flightlog, telemetry, gateway, config, metrics, tenant,
│       audit, license, delivery2, vision
├── gcs-web/             React GCS（39 个前端组件）
├── scripts/             start-all.cmd / e2e-smoke.sh / 23 个脚本
├── docker-compose.yml   Linux 下一键编排
├── docs/                14 篇技术文档
└── .github/workflows/   CI（单测 + 前端构建 + E2E 冒烟）
```

## 测试规模

| 模块 | 单测数 |
|---|---|
| `mavlink-core` | 185 |
| `drone-sim` | 350+ |
| `link-sim` | 20+ |
| `cloud-backend` | 1030+ |
| **总计** | **1587（全部通过，0 failures）** |

## 已知边界（骨架的诚实声明）

- 模拟器使用简化气动模型（物理引擎 v2 已加入加速度/协调转弯/bank/姿态，但非真飞控级气动）
- 微服务/K8s 暂不引入：模块化单体已够当前规模，拆分时机见设计文档讨论
- MAVLink 核心消息 + 相机协议族（259/260/262/263/271）+ 扩展消息（420–483）；接真机时按需在 `MavlinkMessageInfo` + `messages/` 扩展
- 链路签名（MAVLink v2 signing）未实现，模拟器与真机的 UDP 通信在局域网内是明文
- **检测器是投影可见性**（简化是有意的）：接入真实 CV 模型的替换点在
  `CaptureService` 第 2 步——把 truth HTTP 的目标清单换成模型输出
  （u,v,kind 三元组），解算/比对/跟踪链路零改动。骨架阶段这一简化让
  端到端闭环可全量回归，代价是没有误检/漏检的真实分布。
- **前端本地只做静态检查**：vite build 在本仓开发沙箱里被 stdio 管道
  限制挡住（EPERM），本地用 `gcs-web/scripts/check-frontend.cjs`
  （Babel 语法 + import 图）把关；**真实构建在 CI 跑**（`npm run build`）。
  改前端后推 CI 验证，别信本地静态检查的"绿"就万事大吉。
- **安全认证已实现**：JWT 令牌 + Spring Security + 多租户隔离 + 审计日志 + License 管理；
  dev-mode 白名单便于本地开发
- **持久化已部分实现**：飞行日志 JSONL 落盘、围栏/追踪/安防设备等支持持久化测试；
  主数据仍为内存态，换数据库是包内替换

## 集成过程中踩过的坑（对后来者有价值）

1. **UDP 双端互等死锁**：模拟器（和 PX4 真机一致）等 GCS 先发心跳才记录对端地址，
   被动监听的后端永远收不到遥测。解法：`UdpMavlinkTransport.enablePeerDiscovery()`——
   后端启动后每秒发 GCS HEARTBEAT 到飞控端口，收到回包即切换正常链路。
2. **Spring 6 参数名反射**：不用 `spring-boot-starter-parent` 做父 pom 时，
   `-parameters` 编译开关丢失，`@PathVariable` 必须显式写名字（或像本仓库在根 pom
   给 compiler-plugin 配 `<parameters>true</parameters>`）。
3. **Mission Protocol 回环竞态**：真实飞控在串口链路上几十毫秒才回 MISSION_REQUEST，
   本机 UDP 回环 5ms 内就回。等待方若"先发后注册 waiter"，响应正好落在注册前的空窗
   被丢弃。不变式：**waiter 必须先于触发响应的那次发送注册**（见
   `DroneCommandService.uploadMission`）。
4. **Windows 编码**：PowerShell `Set-Content` 会写 UTF-8 BOM，JDK 17 把 BOM 当非法字符；
   CMD 嵌套执行带中文注释的批处理会乱码——所以 Java 源码全 ASCII、脚本优先 ps1。
