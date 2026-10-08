# NexusSky · 天枢 — 无人机智能操控系统

> **NexusSky（天枢）**：Nexus = 枢纽/连接点——这套系统的本质是**连接**：飞机 ↔ 云端 ↔
> 地面站，一条链路穿越 WiFi / LTE / 数传电台 / 卫星；Sky = 天空疆域。中文名"天枢"
> 取自北斗七星之首，主掌天机运转——无人机智能操控的中枢，亦暗合北斗导航的意象。

> **定位**：在"没有硬件、没有团队"的条件下，搭建一套**还能跑起来的**无人机智能控制系统骨架。
> 真飞机的位置被一台**软件模拟器**顶替。帧编解码与链路签名（MAVLink v2 signing）与官方
> 规范逐字节对等（签名有 pymavlink 已知答案向量把关，见"已知边界"），消息集是常用子集且
> 未与真机联调过——未来接 Pixhawk 时替换的是数据源，但每条消息仍需按真固件行为验证，
> 不是"插上就能跑"。

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
| `mavlink-core` | 纯 Java 17 | MAVLink v1/v2 二进制协议栈（帧/CRC/消息编解码/UDP 传输，标准消息 + M0a–P2 扩展消息 30000–30063，共 51 条），**457 个单测，CRC 与官方逐字节一致** | 不需要换——PX4 原生说 MAVLink |
| `drone-sim` | 纯 Java 17 | 虚拟四轴：任务上传(Mission Protocol)、ARM/起飞/航点飞行/RTL 状态机、遥测 1-5Hz 广播 | 换成真飞控，UDP 端口不变 |
| `cloud-backend` | Spring Boot 3.5 | MAVLink 设备网关、机队注册表、任务上传客户端、REST API（358 端点）、WebSocket 推送、JWT 安全认证、多租户隔离、应急编排引擎 | 不需要换 |
| `gcs-web` | React 18 + MapLibre | Web 地面站：实时地图轨迹、飞行仪表 HUD、任务规划、命令下发、告警流、编队/喷洒/安防/应急等 38 个功能面板 | 不需要换 |
| `link-sim` | 纯 Java 17 | MAVLink/UDP 链路损伤代理：延迟/Gilbert-Elliot 突发丢包/令牌桶带宽/周期分区四引擎 + 7 种链路画像 + 一跳/多跳静态中继 | 不需要换——损伤模型本身就是要保留的实验变量 |
| `regulator-sim` | 纯 Java 17 + JDK HttpServer | 模拟 CAAC UOM 监管平台 HTTP API（`/api/verify\|activate\|cancel\|telemetry\|records` 五端点 + `--delay-ms`/`--error-rate` 故障注入），独立进程，供 regulator 包集成测试与本地开发 | 不需要换——对接端是真 UOM 平台 |
| `sdk-java` | 纯 Java 11 | Java 客户端 SDK（drones / missions / flightLogs 三域 + 20 余便捷方法），12 单测 | 不需要换 |
| `sdk-python` | Python 3 | Python 客户端 SDK（drones / missions / flightlog + exceptions），7 文件、**0 单测** | 不需要换 |

> Maven reactor 含前 6 个 Java 模块（见根 `pom.xml` 的 `<modules>`）；`gcs-web` 与
> `sdk-python` 不在 reactor 内（前者由 npm 构建，后者由 `setup.py` 打包）。

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
java -jar drone-sim\target\aerofleet-drone-sim-0.1.0-SNAPSHOT-shaded.jar --scenario gps-loss:30:20

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
告警/任务/上下线即时落盘（不依赖有人开着 GCS 页面）；默认保留 30 天
（`aerofleet.flightlog.retention-days`，每天 03:30 清理，`<=0` 关闭）。查询：

```
GET /api/v1/flightlog?day=2026-09-13&type=alert&sysid=1&limit=100
GET /api/v1/flightlog/track?sysid=1            # 某日完整轨迹（从遥测行重建）
```

骨架阶段选 JSON Lines 而非 SQLite：零依赖、可 grep、可 git diff。现在两种模式
并存：默认纯文件，`aerofleet.flightlog.persist-to-db=true` 改写 `flight_log` 表。
DB 写**不在调用线程上执行**——入 `BatchedWriteQueue`，由单个 writer 线程成批 `saveAll`
（`write-batch-size=50`、`write-flush-ms=200` 可配）；落库失败或队列满则整批就地回退
JSONL，既不丢账也不背压生产者。代价是异步：刚写入的事件最多晚一个 flush 间隔才可读到。
（批处理走显式 `JdbcTemplate.batchUpdate` 多行插入，不依赖 Hibernate 的批处理——
`flight_log.id` 是 IDENTITY 主键，Hibernate 为取回生成键必须逐行执行；改序列则会被 prod 的
`ddl-auto=validate` 判成 missing sequence，实测过。）

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
`{"lat":22.5916,"lon":113.9346,"radiusM":25,"altM":60,"photos":4}`

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
30000–30047、30057–30063 段分配。

### M0a — Mesh 组网落地

`link-sim` 新增一跳静态中继（`RelayConfig`/`RelayNode`），支持多跳转发——
命令经中继可达远端飞机，突破单跳视距限制。`scripts/e2e-mesh.ps1` 演示
单中继两跳链路下的完整任务流。

### M0b — 环境气象机制

引入环境模型（温度/湿度/天气/风力）与告警引擎 `EnvAlertEngine`（温度/湿度/
风力/能见度阈值告警），通过 `EnvironmentAlert`(30001)/`EnvironmentStatus`(30002)
MAVLink 消息下发。风偏修正与雨衰叠加进入链路损伤模型；REST 端点
`/api/v1/env/alerts` 暴露告警查询。

### M1 — 编队表演

`LedControlMsg`(30000)+`LightPattern` 枚举驱动机载灯效；`FormationGeometry`
提供圆形/线形/V形/菱形队形几何，`FormationService` 管理创建→变换→解散状态机，
`FormationKeeper` 持续保持队形与位置修正。REST `/api/v1/formation/*` 下发指令，
WebSocket 以 1Hz 推送编队状态，前端 `FormationPanel.jsx` 可视化操控；
`scripts/e2e-formation.ps1` 端到端回归。

### M2 — 喷洒物流

执行器模型（`Actuator`/`SprayPump`/`Gripper`/`PayloadModel`）+ 喷洒/夹爪/载荷
MAVLink 消息(30003–30006)。`SprayTaskService` 调度喷洒任务，`DeliveryService`
编排物流配送序列；REST `/api/v1/spray/*` 与 `/api/v1/delivery/*` 对外暴露。

### M3 — 成像增强

`VisionSource` 统一投影/模拟视觉感知源，接入多光谱/热成像/深度多源数据
（`Multispectral`/`Thermal`/`Depth`）。`ObstacleDetector` 做障碍检测，
`ObstacleAvoidanceController` 按威胁等级映射避障命令（CRITICAL→悬停、HIGH→避障）。
对应 MAVLink 消息 30010–30014，REST `/api/v1/obstacle/*`、`/api/v1/multispectral/*`、
`/api/v1/thermal/*`，WebSocket 推送障碍报告，前端 `VisionPanel.jsx` 展示。

### M4 — 硬件抽象

引入相控阵雷达（`PhasedArrayRadar`）、旋翼气动（`RotorAerodynamics`）、
LiDAR（`LiDARSource`）、IMU（`ImuSource`）四类硬件抽象与各自 Simulated 实现，
MAVLink 消息 30017–30021（`RadarScan`/`RadarTarget`/`RotorTelemetry`/`LidarData`/`ImuData`）。
`VirtualDrone` 集成硬件层并支持物理模型切换（运动学↔气动），`ObstacleDetector`
融合 LiDAR 数据。REST `/api/v1/radar/*`、`/api/v1/rotor/*`、`/api/v1/lidar/*`、
`/api/v1/imu/*`，WebSocket 以 1Hz 推送硬件数据。

### M5 — 应急 Mesh 自愈组网

AODV-lite 多跳动态路由（`MeshRouter`/`RouteTable`/`NeighborTable`/`RreqCache`），
支持路由发现、自愈重构、链路质量评估。MAVLink 消息 30030–30034（MeshHeartbeat/
RouteRequest/RouteReply/RouteError/NeighborTable），`link-sim` 新增 `MultiHopRelayConfig`
多跳中继配置。REST `/api/v1/mesh/*`，前端 `MeshTopologyPanel.jsx` 可视化拓扑。

### M6 — 移动基站载荷抽象

无人机搭载 LTE/WiFi/LoRa 基站载荷（`CellTowerFactory`/`CoverageArea`/`HandoverManager`），
支持覆盖区计算、终端接入管理、越区切换。MAVLink 消息 30035–30038（CellTowerStatus/
Config/Handover/GroundTerminalRegister），REST `/api/v1/celltowers/*`，
前端 `CellTowerPanel.jsx` 可视化基站拓扑。

### M7 — 星-空-地多层级中继

LEO 卫星 + HAPS 高空平台 + Mesh 三层级中继（`HierarchicalRouter`/`LeoConstellation`/
`HapsRelayNode`），支持卫星过境窗口预测、层级路由决策、链路切换。MAVLink 消息 30039–30041
（SatLinkStatus/SatPassSchedule/HierarchicalRouteDecision），REST `/api/v1/satlink/*`，
前端 `SatLinkPanel.jsx` 可视化中继链路。

### M8 — 复杂地形适配

山地/森林/沼泽/城市等地形分类与 RF 衰减建模（`TerrainGrid`/`TerrainClassifier`/
`EnhancedRadioEnvironment`/`FlightConstraintChecker`），支持地形变化监测、
飞行约束检查、覆盖范围地形衰减。MAVLink 消息 30042–30044（TerrainTypeMap/
TerrainUpdate/FlightRestriction），REST `/api/v1/terrain/*`，
前端 `TerrainMapPanel.jsx` 可视化地形地图。

### M9 — 应急任务编排（全流程闭环）

将 M5–M8 能力编排为完整应急响应工作流：灾区测绘 → 覆盖规划 → 组网部署 →
持续服务 → 自愈重构。编排引擎（`OrchestrationEngine`）驱动五阶段状态机，
覆盖优化算法（`CoverageOptimizer`）贪心+局部优化部署方案，动态重构器
（`DynamicReconfigurator`）处理无人机损毁/电量不足，优先级调度器
（`PriorityScheduler`）四级抢占式调度（搜救>指挥>测绘>常规），场景预设
（`ScenarioPresetFactory`）支持地震/泥石流/火灾一键启动。MAVLink 消息 30045–30047
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

**MAVLink 报警消息**：`AlarmTriggerMsg`(30057)/`AlarmAckMsg`(30058)/
`SurveillanceStatusMsg`(30059)。

**应急指挥工作流**：六阶段（接报 → 研判 → 部署 → 执行 → 评估 → 总结），
一键应急响应自动走完全流程。

### P2 — 灾害应急通讯组网扩展

在 M5–M9 与 4a 应急能力之上，P2 进一步扩展灾害场景下的通讯组网与搜救指挥能力，
覆盖 QoS 保障、分簇路由、异构链路桥接、Budget 模式、丐版 Mesh、热源搜救等 11 个子模块，
均沿用既有 MAVLink/REST/WebSocket 三段式架构，新消息 ID 按 30060–30063 段分配。

**QoS 优先级队列 + 分簇路由**：灾害场景下通讯资源极度受限，QoS 引擎按业务优先级
（搜救 > 指挥 > 测绘 > 常规）分配带宽与转发资源；分簇路由将无人机群按地理/拓扑
自动分簇，簇头负责簇内聚合与簇间转发，减少全局路由开销。MAVLink 消息
`QoSRouteDecisionMsg`(30060) / `ClusterFormationMsg`(30061) 下发路由决策与簇 formation。

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
帮助地面搜救人员定位无人机与标记发现的目标。MAVLink 消息 `BuzzerControlMsg`(30063)
下发蜂鸣器开关/频率/时长指令。

**GCS Emergency UI 适配**：前端 Emergency UI 适配 Budget 模式与灾害态势面板，
在 `EmergencyOrchPanel` 基础上扩展 Budget 模式切换、丐版 Mesh 拓扑可视化、
热源搜救标记、声光控制面板等交互组件。

### P3 — 动态 MAX_HOPS 集成与真实卫星接入预留

**动态 MAX_HOPS 集成（FR-18）**：`DynamicMaxHops` 根据网络节点数动态调整最大跳数，
避免小网络过度广播、大网络路由不足——≤20 节点 → 15 跳, 21–50 节点 → 20 跳,
>50 节点 → 25 跳, 硬上限 30 跳。通过 `dynamicMaxHopsEnabled` 配置项控制
（默认 `false`，向后兼容），开启后 mesh 路由的 TTL/hopCount 上限随网络规模
自适应伸缩，而非固定值。

**真实卫星接入预留**：为天通（高轨稳定覆盖）、铱星（低轨低延迟）、星链（低轨星座
带宽最优）三种卫星通信系统创建了占位实现类（`TiantongSatLinkProvider`/
`IridiumSatLinkProvider`/`StarlinkSatLinkProvider`），实现统一的
`SatLinkProvider` 接口，为未来真实卫星硬件接入预留接口——替换占位为真实驱动时，
上层路由/中继/链路切换逻辑零改动。
（2026-10-01 更正：原文写的类名 `*SatellitePlaceholder` 与接口名 `SatelliteLink`
在代码中均不存在，实际为 `*SatLinkProvider` / `SatLinkProvider`；`SatLinkProvider`
的 Javadoc 里引用的 `TiantongSatProvider` 等三个类名也已过期。三个占位类的每个方法
都抛 `UnsupportedOperationException("真实星链接入尚未实现，请使用 SimulatedSatLinkProvider")`，
仿真请用 `SimulatedSatLinkProvider`——**抛错而非返回假数据**是这里正确的做法。）

### MAVLink 消息 ID 分配（私有方言段 30000–30099）

> 2026-10 治理搬迁：自定义消息原用 msgId 420-483，位于 common.xml 官方分配带
> 300-10000 内（420/437/440 已与官方 RADIO_RC_CHANNELS / AVAILABLE_MODES_MONITOR /
> ILLUMINATOR_STATUS 实锤冲突），全部 51 条已等差平移 +29580 至私有方言段
> 30000-30099（30064-30099 为增长预留）。CRC_EXTRA 与 payload 布局不变；
> `scripts/mavlink-compatibility-check.py --self-test` 内嵌 392 个官方已分配
> msgId 快照逐条核对无冲突。

| 范围 | 里程碑 | 消息 |
|---|---|---|
| 30000 | M1 | LedControlMsg |
| 30001–30002 | M0b | EnvironmentAlert, EnvironmentStatus |
| 30003–30006 | M2 | SprayStatus, SprayCommand, GripperCommand, PayloadStatus |
| 30010–30014 | M3 | ObstacleReport, MultispectralData, ThermalData, DepthData, VisionDetection |
| 30017–30021 | M4 | RadarScan, RadarTarget, RotorTelemetry, LidarData, ImuData |
| 30030–30034 | M5 | MeshHeartbeat, MeshRouteRequest, MeshRouteReply, MeshRouteError, MeshNeighborTable |
| 30035–30038 | M6 | CellTowerStatus, CellTowerConfig, CellHandover, GroundTerminalRegister |
| 30039–30041 | M7 | SatLinkStatus, SatPassSchedule, HierarchicalRouteDecision |
| 30042–30044 | M8 | TerrainTypeMap, TerrainUpdate, FlightRestriction |
| 30045–30047 | M9 | EmergencyMissionPlan, CoverageOptimization, EmergencyPriority |
| 30048–30050 | M10 | TaskAssignmentMsg, ConflictAlertMsg, TaskStatusMsg |
| 30051–30052 | M11 | DecisionEventMsg, AdaptivePathMsg |
| 30053–30054 | M12 | EdgeTaskStatusMsg, SensorFusionDataMsg |
| 30055–30056 | M13 | TwinStateSyncMsg, PredictionResultMsg |
| 30057–30059 | 4a | AlarmTriggerMsg, AlarmAckMsg, SurveillanceStatusMsg |
| 30060 | P2 | QoSRouteDecisionMsg |
| 30061 | P2 | ClusterFormationMsg |
| 30062 | P2 | DisasterModeStatusMsg |
| 30063 | P2 | BuzzerControlMsg |

> 上表 14 行覆盖全部 **51 条**自定义消息（M0b/M1/M2/M3/M4/M5/M6/M7/M8/M9/M10/M11/
> M12/M13/4a/P2），与 `MavlinkMessageInfo.EXTENDED_INFOS` 中 30000–30099 段的 51 个
> 条目一一对应。此前本表漏列 30048–30056（9 条），即 M10–M13 全部消息；RID 用的
> `OPEN_DRONE_ID_*`（12900–12915）是官方 msgId，不计入本表。

## 飞行日志（flightlog，JSONL 落盘）

`GET /api/v1/flightlog?day=2026-09-13&type=alert&sysid=1&limit=100`
（另有 `/flightlog/track`）——按**本地**日期一文件（`flight-logs/` 可配
`aerofleet.flightlog.dir`），telemetry 节流 1s/机，alert/mission 即时
写。`aerofleet.flightlog.persist-to-db=true` 时改写 `flight_log` 表：写经有界队列
交给单个 writer 线程成批提交，DB 失败或队列满都整批回退 JSONL（读路径同样回退）。
`aerofleet.flightlog.retention-days=30`（默认）每天 03:30 删过期数据：
DB 行按精确时刻、JSONL 按文件名日期整天删，`<=0` 关闭清理。
纯文件、可 grep；换 SQLite/Postgres 是包内替换。B3 补齐 7 个单测
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
- `GET /v1/env-alerts` 环境告警查询 · `GET /v1/audit/logs` 审计日志（需 ADMIN）
- `GET /license/info` License 信息 · `POST /license/activate` 激活 License
- `GET /surveillance/devices` 安防设备 · `POST /surveillance/rapid-deploy` 一键布控
- `GET /video-fusion/surveillance/streams` 安防流列表 · `GET /video-fusion/drone/feeds` 无人机画面 · `GET|POST /video-fusion/recording[/{id}/start|stop]` 录制登记
- `GET /tracking/{id}/track` 飞行追踪 · `GET /tracking/lost` 失联无人机列表
- `GET /health` 健康监控 · `POST /inspection` 巡检任务
- `GET /mapping` 测绘任务 · `POST /show` 表演管理
- `POST /voicecmd` 语音指令 · `GET /citytwin` 城市孪生
- `GET /scenario/templates` 场景模板 · `POST /scenario/launch` 场景启动
- `GET /v1/qos/decisions` QoS 路由决策 · `POST /v1/qos/priority` 优先级设置
- `GET /v1/cluster/formation` 分簇拓扑 · `POST /v1/cluster/reconfigure` 簇重构
- `GET /v1/disaster/status` 灾害模式状态 · `POST /v1/disaster/budget` Budget 模式切换
- `POST /v1/buzzer/control` 蜂鸣器控制 · `GET /v1/thermal/search` 热源搜救

> 完整 API 文档详见 [docs/api-reference.md](docs/api-reference.md)，共 67 个 @RestController、358 REST 端点。

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

## 能力腿 e2e 的实测状态（2026-10-06 实测，非推断）

`scripts/` 下 28 个 `e2e-*` 脚本中，**7 个进 CI 门禁**（`e2e-smoke` /
`e2e-fault-linux` / `e2e-failsafe-linux` / `e2e-vision-linux`，以及 2026-10-07
转正的 `e2e-capability` 三条腿）。

**2026-10-06 首轮实测**（真 Linux / WSL2 内核，对着当时的 `master` 后端）发现
两条腿存在 API 漂移、一条腿语义误解：

| 脚本 | 首轮实测 | 红因 |
|---|---|---|
| `e2e-emergency.sh` | 7/9 | 末尾 2 条断言**属脚本语义误解**：把"一键应急"当成走完全流程，而 `oneClick` 的契约只到 `EXECUTING`（评估与总结是独立端点 `/evaluate`、`/close`） |
| `e2e-spray.sh` | 0/7 | **API 漂移**：端点前缀、请求体字段、状态枚举全部对不上；另有 `/spray/gripper` 指向不存在的端点 |
| `e2e-hardware.sh` | 4/9 | **API 漂移**：雷达载荷字段改名；模型切换端点 `/hardware/physics-model` 全仓不存在；IMU 是扁平字段而非嵌套对象 |

**2026-10-07 已按当前 API 重写并转硬门禁**（去掉 `continue-on-error`）。三条腿
在真 Linux 下连续两次全绿：**emergency 31 项 / spray 20 项 / hardware 16 项，共
67 项断言 0 失败**；CI run `37624477248` 上 25/25 job 全绿。

重写中删掉了**指向不存在端点的凭空断言**，并把"无数据"按契约处理而非假装通过：

- `/hardware/physics-model` 与 `/spray/gripper` 在仓内均不存在（原脚本凭空断言），
  动力侧改用真实存在的 `/rotor/config` + `/rotor/telemetry`，夹爪由 drone-sim 单测覆盖。
- LiDAR/IMU 返回 404 是**符合契约**的：drone-sim 默认不注入 `LiDARSource`/`ImuSource`。
  此时断言"404 契约正确"并跳过字段断言，真实载荷断言需显式装配 Source 或真机。
- 机载拒收（`MAV_RESULT=3`，drone-sim 未实现该自定义 MAV_CMD）只告警不判失败——
  REST 侧契约是"命令已下发并拿到回执"，机载语义需机载实现后另测。

> 附带修掉一个真实后端缺陷：`POST /api/v1/spray` 此前**恒返回 500**
> （`WaypointListConverter` 反序列化切串越界 → 事务 rollback-only）。详见 CHANGELOG。

> **方法学备注**：本次验证在 WSL2 真 Linux 内核下进行，因为本仓工作区被
> `core.autocrlf=true` 转成 CRLF，bash 会因 `set -euo pipefail\r` 报
> `invalid option name`。**仓库 blob 本身是 LF**（`git cat-file` 实测 0 个 CR 行），
> 故 Linux CI 不受影响。本机跑脚本需用 `tr -d '\r'` 副本，或用
> `git config core.autocrlf input`。为让该不变式不再依赖个人 git 配置，
> 已新增 `.gitattributes` 显式声明 `*.sh text eol=lf`。

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
│   ├── messages/        标准 MAVLink 消息 + 扩展消息（30000–30063 段）
│   ├── enums/MavEnums  官方枚举常量
│   └── transport/      UDP 传输
├── drone-sim/           虚拟无人机（状态机 + 任务协议服务端 + 物理引擎 v2）
├── link-sim/            链路损伤代理（延迟/丢包/带宽/分区 + Mesh 中继）
├── regulator-sim/       UOM 监管平台模拟器（5 个 HTTP 端点 + 延迟/错误率注入）
├── cloud-backend/       Spring Boot 单体（网关/机队/任务/推送/安全/编排）
│   └── 37 个功能包：ai, alarm, api, audit, autodispatch, citytwin, commadapt,
│       config, delivery2, drone, edge, flightlog, gateway, geofence, health,
│       inspection, license, mapping, metrics, mission, orch, regulator, rid,
│       scenario, scheduling, security, show, spi, surveillance, telemetry,
│       tenant, tracking, twin, vision, voicecmd, webhook, write
├── gcs-web/             React GCS（51 个组件文件，其中 49 个 .jsx 面板）
├── sdk-java/            Java 客户端 SDK
├── sdk-python/          Python 客户端 SDK
├── scripts/             start-all.cmd / e2e-smoke.sh / 50 个脚本（其中 28 个 e2e-*）
├── docker-compose.yml   Linux 下一键编排
├── docs/                32 篇文档
└── .github/workflows/   CI（单测矩阵 + 前端 + E2E + 集成 + 安全扫描 + 镜像 + 文档口径门禁）
```

## 测试规模

实测于 2026-10-06，`mvn -B -o clean test`（全 reactor，0 failures / 0 errors / 0 skipped）：

> **必须带 `clean`**：`target/surefire-reports/` 不会自清，`mvn test` 只覆盖本轮跑过的类，
> 于是已改名/删除的测试类留下的旧 XML 仍被计入——实测踩到过 drone-sim 虚高 1（本地 1392
> vs CI 1391）。脚本现在会剔除这类陈旧报告并**打印剔除清单**（静默剔除等于换个方式的错数），
> 但正确的取数姿势仍是 `clean test`。

| 模块 | 单测数 |
|---|---|
| `mavlink-core` | 457 |
| `drone-sim` | 1391 |
| `link-sim` | 117 |
| `cloud-backend` | 2420 |
| `sdk-java` | 12 |
| `regulator-sim` | 19 |
| **总计** | **4416** |

这张表由 `scripts/check-test-count-docs.py` 在 CI 里逐格核对 surefire 实测值——
**加测试而不改文档会直接让 CI 变红**。此前本仓的这个数字过期了两年多（长期写
3230），根因不是"忘了改"而是没有任何信号会变红：加测试不会让文档过期这件事变红。
脚本刻意**排除** `CHANGELOG.md` 与文档里追述旧值的句子——它们记的是"当时是什么
状态"，改写等于篡改记录。

注意：这些用例跑在 `test` profile（`dev-mode=true`、`rbac-enabled=false`——
`application-test.properties:8/:10` 显式设置，所以 base 默认翻 true 不影响它们），
即鉴权与租户面**不在其覆盖范围内**；链级鉴权/隔离证据在
`cloud-backend/src/test/java/io/aerofleet/cloud/security/chain/HttpAuthChainTest.java`
与 `api/ws/TelemetryWsTenantIsolationTest.java`（以 `dev-mode=false` 起完整过滤器链）。

### 前端测试（2026-10-01 建立）

此前前端**零测试**（`package.json` 无 test 脚本、`src` 下无测试文件，仅有
`scripts/check-frontend.cjs` 这个不含行为断言的语法/import 图检查器），
与后端 3869 例形成断层。现引入 vitest 5 + jsdom + Testing Library（lockfile 锁定 5.0.3）：

| 命令 | 作用 |
|---|---|
| `npm run test` | `vitest run`，CI 的 GCS Web job 门禁 |
| `npm run test:watch` | 监听模式 |
| `npm run test:coverage` | v8 覆盖率 |
| `npm run check` | 语法/import 图检查 + 单测 |

首批 **46 例**（5 个文件），刻意只覆盖三类**已确认缺陷**与相关契约：

- `test/droneSelection.test.js`（8）——`selected` 是**对象**而非机号这条契约。
  修复前 `MapView` 两处写 `selected === sysid`，恒 false，「选中机标记高亮」与
  「选中机轨迹渐变」两段代码从未执行过，且运行期零信号。
- `test/telemetryHistory.test.jsx`（7）——历史曾把所有机型的遥测塞进**同一个扁平
  数组**而消费端从不过滤 sysid，2 架机以上时曲线把不同飞机的数据交错画在一起。
  现按 sysid 分桶（每桶 300 点 = 5Hz×60s，与图表窗口配套）。
- `test/telemetryCharts.test.jsx`（7）——曲线渲染侧的 sysid 过滤，含「该机无数据时
  不借用别机数值」「实时点带 sysid 不被过滤掉」两条边界。
- `test/flightCommands.test.jsx`（18）——飞行命令二次确认。`arm`/`disarm`/`takeoff`/
  `start_mission`/`kill` 五个不可逆命令取消时不得下发；**`rtl` 刻意不确认**
  （应急回收动作不该被模态框挡住，QGC / Mission Planner 同样如此，此处有断言固化）。
- `test/joystickDisarm.test.jsx`（6）——摇杆上锁按钮的二次确认，含「取消时**不得**
  掐断正在进行的发送循环」这条顺序回归（确认必须早于停发，否则取消会留下
  摇杆死区而飞机仍在飞）。

覆盖率阈值暂未设：前端基线原为零，一上来卡阈值只会让 CI 立刻变红。
先由 CI 实测产出基线，之后按模块逐步抬高。

### 前端测试第二批：组件逻辑与单一真相源（2026-10-02）

第二批补 **72 例**（118 例总量）。做法是先把**与 React 无关的纯逻辑**从组件里
抽到 `src/utils/`，再对其做行为断言——挂载整个组件树才够得着的逻辑，恰恰是
最该被测的那部分。

新增模块：`utils/statusMeta.js`、`utils/battery.js`、`utils/geo.js`、`utils/format.js`。

抽出过程中发现两个此前没被记录的问题：

1. **电量分级有 6 份，且分成两套互不相同的阈值**——`DashboardPanel` / `DroneList` /
   `TelemetryCharts` / `TelemetryPanel` 用 20/40，`EmergencyOrchPanel` /
   `UnifiedCommandPanel` 用 15/30。同一架飞机在总览面板显示绿色、在指挥表格里
   显示黄色，操作员会以为数据不一致。已统一为 20/40，阈值集中在
   `BATT_CRIT_AT` / `BATT_WARN_AT`。
2. **`Number('') === 0`**——原 `battClass` / `battColor` 只判 `b == null`，
   空串电量会被当成 0% 显示成红色告警。`battLevel` 补上空串与 NaN 判定。

| 文件 | 例数 | 覆盖 |
|---|---|---|
| `test/battery.test.js` | 10 | 判级边界、三个访问器全域一致性、全域单调性 |
| `test/statusMeta.test.js` | 14 | 优先级/严重度规范化 +「返回值必定可查表」不变式 |
| `test/geoFormat.test.js` | 26 | 球面距离（1° 纬度 ≈ 111.19 km、广州→深圳 100 km 量级）、圆形布局（落圆周 / 角距均匀）、格式化与档位配色 |
| `test/singleSourceOfTruth.test.js` | 22 | **价值最高的一组**：逐函数扫描 `src/components` 禁止再出现本地定义；钉住 12 个引用方确实从 `utils` 导入；禁止内联电量阈值 |

最后这组是防「抽了 utils 又复制一份回去」被悄悄回退的——没有它，下次有人
复制一份不会有任何测试变红。

## 代码审查修复记录

6 轮收敛性审查（3 轮全量 + 2 轮验证 + 1 轮接线状态/口径专项），累计修复 52 个问题
（4 Critical + 12 Major + 5 Minor + 11 P1 + 20 新增）：

| 轮次 | Commit | 修复 | 要点 |
|---|---|---|---|
| Round 1 | `9364636` | 3C + 3M | AirGroundCoordinationService 内存清理/HTTP 状态码/无限循环；EdgeAiTrigger 空集合；CoverageOptimizer 参数传递；VideoFusionPanel 错误捕获 |
| Round 2 | `234fc8c` | 7M | 竞态条件 synchronized；时间差双操作数检查；降级矛盾；@RequireRole；flush 503；ONVIF 枚举映射；CoverageOptimizer synchronized |
| Round 3 | `920ab87` | 5m | 4 个 toLowerCase/toUpperCase 添加 Locale.ROOT；SurveillanceDeviceRegistry 构造器注入 |
| Round 4 | `7dd8014` | 1C + 2M | 降级模式 coordinationId="AGC-null" 记录互相覆盖；startAirGroundCoordination 竞态条件；阈值清理内存泄漏 |
| Round 5 | — | 验证收敛 | 无新发现，审查收敛结束 |
| Round 6 | Unreleased | 11 P1 + 20 新增 | 自主决策/边缘 AI 接线状态核实（未接线钉进 `AiAutonomyWiringTest`）、单测数口径与 CI 门禁（`check-test-count-docs.py`）——明细见 CHANGELOG「Unreleased — 第六轮审查」 |

## 已知边界（骨架的诚实声明）

- **机巢管控（F2）的传输与执行边界**：命令通道交付的是 DJI Cloud API **物模型形状**
  （`{tid,bid,timestamp,method,data}` services 语义）+ 可插拔 `DockGateway`；默认
  `transport=sim`（HTTP 回环到 drone-sim 的 `dock` 子命令），`transport=mqtt` 是生产
  seam（需 EMQX Broker，接口/配置已就位但未联调——与 C1/C2 "对端是模拟器"同一诚实口径）。
  换电是计时仿真（5s + 电量曲线），非机械臂时序；无人值守完成判定用遥测
  （曾起飞 + 相对高度回地），非任务状态机回调。
- 模拟器使用简化气动模型（物理引擎 v2 已加入加速度/协调转弯/bank/姿态，但非真飞控级气动）
- 微服务/K8s 暂不引入：模块化单体已够当前规模，拆分时机见设计文档讨论
- MAVLink 核心消息 + 相机协议族（259/260/262/263/271）+ 扩展消息（30000–30063）；接真机时按需在 `MavlinkMessageInfo` + `messages/` 扩展
- **链路签名（MAVLink v2 signing）自 2026-10-01 起与官方逐字节对等**：签名块 13 字节
  （LINK_ID 1 + TIMESTAMP 6 **小端** + SIGNATURE 6 = `sha256_48`，即
  `SHA-256(secret + 帧头至CRC + linkId + timestamp)` 前 6 字节），重放规则改为"同流严格递增
  + 新流最多落后 60 秒"。等价性由 pymavlink 生成的**已知答案向量**逐字节把关
  （`MavlinkSigningVectorTest`，向量生成器 `scripts/mavlink-signing-vectors.py`）。
  此前是 15 字节（HMAC-SHA256 取 8 字节 + 大端时间戳），与 PX4/pymavlink 混流既验不过签名
  又会因帧长差 2 字节错帧
- 签名**出厂仍是明文**：`mavlink.signing.enabled` 默认 false 且任何 profile 都未配置。
  而且接线此前是断的——backend 的四个签名注入点全是 `@Autowired(required=false)`，
  而裸 `@SpringBootApplication` 不扫 `io.aerofleet.mavlink.*` 包，故四个字段恒为 null、
  `UdpGateway.isSigningEnabled()` 恒 false，把开关打开也不签名（现在由
  `MavlinkSigningConfiguration` 按开关条件装配，`MavlinkSigningConfigurationTest` 盯住这条路径）。
  多机密钥（`key-store-path`）的 per-sysid 口令现已真正进入签名路径：backend 改为经
  `MavlinkSignerFactory` **按 sysid 取签名器**（旧实现只用密钥库取 linkId、口令仍取全局值），
  e2e 场景 5 用两把不同口令的机子端到端验过。仍未闭合：`MavlinkParser` 层仍只切帧不验签
  （验签在 `MavlinkMessage.decode(frame, signer)` 与 `UdpGateway.verifyFrame`），
  口令与密钥库都是明文、无轮换端点，接真机前需做端到端联调
- **检测器是投影可见性**（简化是有意的）：接入真实 CV 模型的替换点在
  `CaptureService` 第 2 步——把 truth HTTP 的目标清单换成模型输出
  （u,v,kind 三元组），解算/比对/跟踪链路零改动。骨架阶段这一简化让
  端到端闭环可全量回归，代价是没有误检/漏检的真实分布。
- **前端测试覆盖 218 例**（vitest 5.0.3，2026-10-01 首批 + 2026-10-02 诚实化轮 +
  2026-10-04 并入第二批组件逻辑测试 + M13 孪生同步消费 9 例 + 2026-10-05 M11
  决策三帧消费 13 例 + 2026-10-06 M10/4a 六帧消费 41 例（归一化 33 + 完备性守卫 8）；
  `npm run test` 实测
  218/218，15 个测试文件，分布在 `gcs-web/test/`（13）与 `gcs-web/src/`（2）两处）：
  `npm run test` 已在 CI 的 GCS Web job 门禁。
  **该数字已纳入 `scripts/check-test-count-docs.py` 的门禁**（2026-10-06 补，见下文
  「前端计数门禁」）——此前只有 Java surefire 受门禁约束，所以本行长期停留在 168
  而加测试不会让任何东西变红。
  选的是**已确认缺陷**加相关契约，不是全量覆盖——49 个 `.jsx` 组件里 **47 个零测试**，
  且最大的三个（`UnifiedCommandPanel.jsx` 1451 行 / `api.js` 1337 行 /
  `MapView.jsx` 1016 行）均无测试文件，只受 lint + build 保护。
  新增的 `FleetOpsPanel.jsx` 亦无组件级测试（其逻辑已全部下沉到
  `utils/schedulingFrames.js` 纯函数并直测——与第二批"先把与 React 无关的逻辑抽出来
  再测"的做法一致）。
  覆盖率阈值仍未设（`vitest.config.js:20-27` 显式注明理由：基线原为零，一上来卡阈值
  会让 CI 立刻变红）——但基线现已存在，该理由已不成立，抬阈值是待办。
  `vite build` 在本仓开发沙箱里曾被 stdio 管道限制挡住（EPERM），本地另用
  `gcs-web/scripts/check-frontend.cjs`（Babel 语法 + import 图）把关；`npm run check`
  把两者串起来。
- **「自主决策」advisory + 执行级均已接线（执行级默认关闭，ADAPT_PATH 执行级
  2026-10-04 补齐），「边缘 AI」两算法引擎已接线（被动观测、默认常开）**
  （2026-10-02 第六轮审查；2026-10-04 四步接线：advisory / 执行级 / 边缘 /
  ADAPT_PATH 任务改写）。
  M11 `io.aerofleet.sim.ai`（14 个类 3513 行）通过 `AutonomyAdvisor` 接入
  `VirtualDrone.tickOnce`：1Hz 评估态势，主决策类型**变化沿**经 STATUSTEXT 下发
  **建议**（RTL/AVOID=WARNING、EMERGENCY_LAND=CRITICAL、ADAPT_PATH=NOTICE，恢复时
  INFO 澄清一次），同一变化沿下发 `DECISION_EVENT(30051)`（该消息首次有了机载
  生产者——cloud-backend 的 WS 转发此前永远收不到实例）并交 `AutonomyExecutor`
  **门控执行**：RTL/EMERGENCY_LAND 走与 failsafe 同一条 RTL 程序、AVOID 压全局
  巡航限速 50%、ADAPT_PATH 走 `VirtualDrone.executeAdaptivePath` 任务改写。四条
   仲裁：`--autonomy-exec` **默认关闭**、**FailsafeController 永远优先**（任一触发沿
   激活即不抢杆并复位限速）、仅 ARMED/MISSION 可执行、变化沿驱动。ADAPT_PATH
   执行级（2026-10-04 接线；2026-10-05 复合任务尾）：对剩余任务的**头部连续
   NAV_WAYPOINT 段**（≥2 个）跑 `AdaptivePathStrategy.adaptPath` 真实三算法
   （风补偿 WCA / 能耗最优速度 / Dubins 30m 圆弧尖角平滑），尖角被插点改写时经
   `MissionStore.replaceTail` 用平滑段 + 原样后缀（仅重编 seq）替换任务尾部并
   重定位当前航段，同一拍公告 **`ADAPTIVE_PATH(30052)`**——该消息首次有了
   生产者，公告的是真实几何（新航点坐标取自平滑后路径第一点，非编造）；复合尾
   语义：拍照/悬停/RTL 等非航点指令是段终点，位置与参数一字不动，段之后的
   航点不再平滑（此前含任何非航点指令的尾整体跳过，一条拍照指令就废掉全部
   适配）；能耗最优速度经巡航限速因子表达，物理层 clamp [0.05,1.0] 故**只能
    降不能升**（顺风提速不可表达，刻意边界）；路径未被平滑改动时**不发 30052**
    （无变化不公告）。**gcs-web 已消费决策三帧**（2026-10-05）：cloud-backend 的
   WS 转发（decision-event 30051 / adaptive-path 30052 / edge-task-status 30053）
   此前在前端零消费——AI 为什么改航、改成了什么，操作员在 GCS 里不可见。现新增
   「AI 决策」面板（tab `aidecision`）：决策事件流（类型/原因/置信度/触发值）、
   自适应航迹改写（原航点 → 新航点坐标/新高度/风速风向/原因）、边缘任务
   （类型/耗时/结果大小/状态）三条事件流；`useWebSocket` 头插各留最近 50 条，
   单位换算与码表收口纯函数 `utils/aiDecision.js`（13 例 vitest 钉扎）。
   真正生效的应急执行链路仍是独立的
  `FailsafeController`（链路丢失 / 电量临界 / GPS 丢失 → RTL / HOLD）。其余策略 /
  规划器（避障 A 星/RRT、RTL 滑翔等）仍只被 ai 包内引用。M12 `io.aerofleet.sim.edge`（其中
  `SensorFusionEngine` 9 维 EKF 521 行、`VideoStreamAnalyzer`
  帧差 + 连通域 + 质心跟踪 506 行）经 `EdgeInferenceRunner` 接入同一遥测主循环：
  2Hz 喂 GPS（`reportedLat/Lon`，3m 噪声）/ IMU 速度 / LiDAR(注入式在环时) 观测，
   1Hz 下发 `SENSOR_FUSION_DATA(30054)`；2Hz 拍帧经 `ShotImageWriter.renderGray`
   （160×90 原始灰度，噪声 σ=6——JPEG 路径的 σ=12 帧差会点亮 ~8% 假运动）喂
   视频分析，检出即发 `EDGE_TASK_STATUS(30053)`（视频 / 融合各一路任务 id），
   已分类目标（vehicle/person）逐个补发 `VISION_DETECTION(30014)`（u/v 为
   160×90 渲染帧像素坐标 + kind + 置信度 + 质心跟踪 ID；unknown 亮区无形状
   归类依据，不占协议 kind 枚举，跳过——2026-10-05 起该消息有生产者）；
  `EdgeNode` 为机载侧任务登记簿。**被动观测、默认常开、无开关**：融合结果不回写
  `DronePhysics`（飞控仍用真值）、不驱动执行机构——与 M11 执行级的「默认关闭」
  刻意不对称（观测无风险，抢杆才有）。两处「自主」的准确含义是<b>规则 +
  排序 + 搜索</b>（阈值规则、融合权重、决策树、A 星 / RRT），<b>不含任何机器学习
  模型</b>——全仓 pom 无 onnxruntime / tensorflow / ONNX / OpenCV 任何依赖；
  「边缘 AI 推理」的准确含义是<b>经典 CV 图像处理</b>。
   安全阈值已收敛到 `FailsafeThresholds`（电池临界 22% / 链路丢失 15s，此前仓库里
   同一参数曾同时存在 22 / 25 / 20 三个值）。`drone-sim` 的 `AiAutonomyWiringTest`
    把接线状态钉成断言：DecisionEngine、`AdaptivePathStrategy`（2026-10-04 执行级
    接线）与 edge 两引擎「已接线」是正向断言（静默退线即判红），其余 7 个 ai 类
    「未接线」是期望状态（接线即判红并提示同步本节与竞品对比表）；2026-10-05
    起清单有**完备性守卫**——ai 包新增行为类不显式归类（未接线断言或已接线
    断言）即判红，5 个数据/值类型（AdaptivePathResult/DecisionContext/
    DecisionLogEntry/DecisionResult/FusedDecision，随已接线引擎被消费）显式
    白名单化——比在文档里写一句「注意」可靠，文档不会自己变红。
- **数字孪生「实时镜像」已接线**（2026-10-04；此前第六轮审查曾核实孪生恒为空）。
  `TwinSyncListener`（cloud-backend）监听 MAVLink 事件总线：SYS_STATUS(1) 记电量、
  SENSOR_FUSION_DATA(30054) 换算 **M12 EKF 融合态**（而非 GLOBAL_POSITION_INT 原始
  GPS——兑现 ROADMAP「M13 依赖 M12」）喂 `DigitalTwinService.syncTwin`，并以 1Hz
  节拍发布 TWIN_STATE_SYNC(30055)（此前全仓零生产者）经 /ws/telemetry 广播
  "twin-state-sync" 帧。**gcs-web 已消费该帧**（2026-10-04；数字孪生面板新增
  「实时孪生同步」卡片：每机孪生位置/航向/速度/电量/漂移 + 在线/失联判定，
  `useWebSocket` 按 sysid 分桶、单位换算收口纯函数 `utils/twinSync.js`，9 例
  vitest 钉扎）。**GPI 兜底**（2026-10-05）：无新鲜融合态的设备（从未收到
  30054 或距上次超过 3s——即未运行机载边缘栈 EdgeInferenceRunner，或边缘栈
  停发）由 `GLOBAL_POSITION_INT(33)` 原始 GPS 兜底喂孪生（alt 同为 AMSL、
   速度取 vx/vy/vz 合矢量、hdg=65535 未知回退 0 北向），融合态新鲜时 GPI 不
   竞争；此前「未运行边缘栈的设备不进孪生」的边界自此消除。**预测结果帧已产出**
   （2026-10-05）：/api/v1/twin/predict 在孪生有状态时把预测发布为
   `PREDICTION_RESULT(30056)`（此前全仓零生产者；时域末端点 + 轨迹点数 +
   置信度）以 "prediction-result" WS 帧广播；空轨迹不发布，compare 端点的
   内部 1 秒近似预测属虚实对比用途不发布。**仍存的语义边界**：
   `driftMeters` 是相邻两次同步的位移，非与独立实测的偏差（孪生与物理态云内同源）。
  - **自定义 MAV_CMD 已整带搬入私有区 30080-30099**（2026-10-04；原 310-312/
    320-322/420/421 位于官方/方言分配带，其中 420/421 与 ArduPilot 方言实锤冲突。
    常量收口 `MavEnums.MAV_CMD_NEXUS_*`，双测试钉扎：私有区守卫 + 真实 UDP
    派发往返）。**全部 4 条云端生产者已接通（2026-10-05）**：环境配置 3 条
    （30080-30082）由 `telemetry/EnvOverrideController` 发出——POST
    `/api/v1/env/{sysid}/wind|weather|thresholds`（OPERATOR），参数校验与
    drone-sim 机载校验同构（风速 0-50 m/s、风向 [0,360)、weatherCode 0-4、
    雨量 0-255、阈值 windWarnMps≥0 且 < windCritMps）；载荷查询（30085）由
    `mission/delivery/DeliveryController` 的 POST `/{id}/payload/query` 发出
    （按 delivery.sequence 取 targetSysid，机载立即回传 PAYLOAD_STATUS 30006）。
    原边界描述（环境命令仅机载本地控制面、载荷查询仅协议预留）就此作废。
  - **WS_TYPE_MAP 13 帧生产者收口**（2026-10-05）：/ws/telemetry 的 13 类转发
    帧现全部逐一核实有真实生产者——7 类出自 drone-sim/TwinSyncListener/视觉
    链路（decision-event/adaptive-path/edge-task-status/sensor-fusion/
    twin-state-sync/vision-detection/prediction-result），其余 6 类已于
    2026-10-05 全部接上云端生产者，**零协议预留**：
    task-assignment(30048)——`TaskAssignmentService` 在
    assign/assignTasks/reassignAll/pollNextTask 成功路径发布（REST
    `/scheduling/requests/{id}/assign` 等 + 新增 POST `/tasks/{id}/start`、
    `/tasks/{id}/complete`）；conflict-alert(30049)——新增
    `ConflictScanService`（5s 周期 + POST `/scheduling/conflicts/scan` 按需），
    对在线且有导航数据的无人机做 4D 轨迹预测两两扫描，逐冲突对发布；
    task-status(30050)——任务生命周期四态可达（ASSIGNED/IN_PROGRESS/
    COMPLETED/ABORTED，progress 0→100）；alarm-trigger(30057)——
    `AlarmLinkageEngine.processEvent` 落库后发布（deviceId u16 哈希承载源设备）；
    alarm-ack(30058)——AUTO_DISPATCH 派遣成功后逐受派无人机发布（承载
    DispatchedDrone 真实 sysid + etaSec）；surveillance-status(30059)——新增
    `SurveillanceStatusPusher`（1s 周期，无 WS 客户端跳过）逐设备发布。
    设备源帧（30057/30059）的租户路由经 `MavlinkMessageEvent` 显式租户字段
    （null+explicit=未归属→全局域），无人机源帧沿用 registry.tenantOf(sysid)。
    **诚实声明的不可达协议值与映射边界**：30048/30050 的 taskId 为字符串哈希
    映射至 u32（`taskIdToU32`，非可逆——消费方无法从帧反解 REST 面 taskId，
    需自持映射表，属既有协议改造决策）；TaskStatus FAILED(3) 无生命周期路径（调度
    模型无执行失败态）；ConflictType AIRSPACE(0) 不可达（机对几何扫描产出
    PATH/COLLISION，空域预约冲突不属机对扫描范畴）；SurveillanceStatus
    FAULT(2)/MAINTENANCE(3) 不可达（设备模型仅 ONLINE/OFFLINE）；
    surveillance-status 的 totalCameras 恒为 1（单 RTSP 通道模型，无多通道
    设备）、uptimeSec 为进程内计数（重启清零，firstSeenMs 不持久化）。
- **安全认证：RBAC 已默认拒绝**。JWT + API Key + Spring Security + 三态租户域
  （有归属=本租户 / 无归属+ADMIN=显式全局 / 无归属+非 ADMIN=看不到任何租户数据）+
  审计日志 + License 管理；dev-mode 白名单便于本地开发（默认 false）。
  2026-10-01 起 `RoleInterceptor` 已从"无注解即放行"翻为**无注解即 403**：358 个端点
  （193 GET/138 POST/13 PUT/14 DELETE）全部有显式声明——读=类级 `@RequireRole(OBSERVER)`、
  写=`OPERATOR`、配置/用户/密钥/租户/围栏/license 面=`ADMIN`，匿名入口只有登录与刷新两处
  `@PermitAll`；漏写注解由 `RbacEndpointCoverageTest` 反射逐个校验并判红，不靠文本扫描
  （awk 版会把签名里的 `@RequestBody` 当注解行，把 272 个未声明少报成 99）。
  两点别踩：角色层级向上满足（`userRole.ordinal() <= requiredRole.ordinal()`，ADMIN 能过任何门，
  所以翻转不会锁死管理员界面）；`@PermitAll` 只放开 RBAC，认证仍由 `anyRequest().authenticated()`
  把关（`SecurityConfig.java:76`）。开关：`aerofleet.security.rbac-enabled` base 默认 **true**
  （prod/staging 显式 true；dev 靠 `dev-mode=true` 旁路；test 显式 false），CI 集成腿 Pass B
  以四向取证：ADMIN 建用户 201、OBSERVER 打 /api/v1/audit/logs 403、
  OBSERVER 读 /api/v1/drones 200、OBSERVER 越级写 /api/v1/geofence/check 403。
  **License 已改 fail-closed（2026-10-02）**：配了 `aerofleet.license.key` 就等于声明本部署执行
  授权校验，此时解析或验签失败**拒绝启动**，不再降级为「全模块 + 设备无限制 + 永不过期」的
  dev license（那等于"被篡改的 key 反而拿到最宽松授权"）。没配 key 仍是开发版，开发/CI 路径不变。
  同一批还修掉两个让签名功能**从未成功过一次**的缺陷：签名曾覆盖 `licenseKey`（信封）本身，
  以及覆盖 `isExpired()` 这个**随时间变化**的派生量——后者叠加旧的 fail-open 会导致
  **License 一到期就自动提权成无限 dev license**。详见 `CHANGELOG.md` 该条目与
  `LicenseServiceFailClosedTest`(13 例，本模块首批测试)。
  **凭据加密密钥不再有 base 明文默认值**（2026-10-02）：此前 `aerofleet.encryption.key` 在
  base 里硬编码 `aerofleet-dev-encryption-key` 且 prod/staging 都未覆盖，等于用一把公开密钥
  加密安防设备口令与 webhook secret。现在 dev/test 各自声明，prod/staging 走
  `${AEROFLEET_ENCRYPTION_KEY}` 且无缺省  （与 jwt-secret 同一套 fail-fast）。
  设备/边缘上报四条腿（`edge/results`、`loRa/alarm`、`offline-alarm/batch-upload|flush`、
  `alarms/events`）要求 OPERATOR 档凭据，凭据有两条路（2026-10-03 收口"整部署一把共享
  key"项）：**运营态**是 per-device key——登记设备（`POST /api/v1/devices/{sysid}`）→
  归属租户（`PUT .../tenant`）→ ADMIN 签发 `POST /api/v1/auth/api-key {"name":...,"sysid":N}`，
  每机一密钥，`POST /{keyId}/rotate`（可带宽限期）与 `DELETE /{keyId}` 的撤销/轮换粒度=
  单台设备，认证侧 60s TTL 缓存在本 JVM 即时失效；**零状态引导**是注入
  `AEROFLEET_SECURITY_DEVICE_INGEST_API_KEY`（>=16 位）由 `DeviceIngestKeyBootstrapRunner`
  引导一条 `keyId=device-ingest` 的共享 API Key（库里只存哈希，留空完全不介入）——它只解决
  冷启动，不是每机一密钥的运营方案。IT Pass B 断言 7/8 已实测两条通路（共享 key 200/伪造
  key 401；per-device 签发→摄取→撤销→同 key 再摄取 401）
  prod 接真机还有一条硬前置：设备白名单（`aerofleet.udp.device-whitelist-enabled=true`，仅 prod）
  必须与注册表持久化（`aerofleet.device-registry.persist=true`）**成对**打开，并经
  `POST /api/v1/devices/{sysid}`（ADMIN）显式登记设备，撤销用同路径的 `DELETE`（内存条目与
  `devices` 行一起清掉，之后该 sysid 的帧重新被拒）。陌生 sysid 的帧在 `UdpGateway.onFrame`
  就被丢弃，而注册条目过去只由被放行的帧创建 —— 两者叠加曾让 prod 里任何真机都进不来
  （2026-10-01 修，回归腿是 IT Pass C 断言 7：登记 → 起 sim → `flight_log` 在真 PG 上落行）。
  登记时**记得带归属** `{"tenantId":N}`：`flight_log` 的行按设备归属盖租户戳
  （`FlightLogService.tenantForWrite` → `DeviceRegistry.tenantOf`），未归属设备产生的遥测
  对任何具体租户都读不到，只有无租户上下文的全局口径可见
- **持久化已部分实现**：飞行日志（JSONL 与 `flight_log` 表双模式，默认保留 30 天后自动清理）、
  审计日志（`audit_log` 表 + SHA-256 哈希链，默认仍纯内存、保留清理默认关闭）、
  围栏/追踪/安防设备等支持持久化测试；主数据仍为内存态，换数据库是包内替换
- **视频融合面板（`VideoFusionPanel`）后端已补齐**（2026-10-03 第七轮核查发现当时未实现，
  2026-10-06 补齐）：该面板（GCS `videofusion` 视图）调用的 4 个端点
  `/api/v1/video-fusion/surveillance/streams`、`/drone/feeds`、
  `/recording/{id}/start|stop` 此前在后端**没有对应 Controller**，面板运行期必然 404——
  而 `videofusion` 已在 `EMERGENCY_STANDARD_PANELS`（"应急千元级"）里作为**已定价能力**
  对外存在。现新增 `surveillance/VideoFusionController`（+ 5 个端点、12 例测试），
  数据源全部复用既有服务：安防流取 `SurveillanceDeviceRegistry.listDevices()`
  （租户过滤与 `/api/v1/surveillance/devices` 同源），无人机画面取 `DeviceRegistry.all()`
  + `VideoStreamService`（与 `/api/v1/video-stream` 同源）；RTSP 凭据同规则脱敏。
  前缀已在 `LicenseModuleMap` 登记为 `emergency`（视频/视觉属应急版），
  否则 prod 下会被模块门禁 403。
  **仍存的边界**：① 录制只是**状态登记**（`VideoFusionRecordingService`，内存态），
  不落盘不转码，故 `storageUrl` 如实为 `null`——不伪造产物路径；② 面板画面本身仍是
  `SIMULATED FEED` 噪声占位，骨架阶段没有真实像素渲染；③ 安防厂商适配器仍是模拟实现
  （见 `docs/product-brief.md` §⚠3）

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

## 许可与商业授权（License）

**本项目为专有软件（Proprietary Software），不是开源项目。** 全部源代码、二进制、
文档与设计文件的著作权归 NexusSky Team 所有，保留所有权利。完整条款见根目录
[`LICENSE`](./LICENSE)。

- **使用前提**：任何形式的使用（包括内部部署、二次开发、集成到贵方产品）
  均需获得书面商业授权。
- **授权粒度**：以功能模块为最小单位，与 `docs/PRODUCT-POSITIONING.md` §5 的三档
  定价方案对应：

  | 档位 | 授权模块（累进） | 说明 |
  |---|---|---|
  | 基础版 | `core` + `fleet` + `mesh` + `orch` | 设备接入、遥测、任务、航迹、围栏、RID、监管、租户、审计 + 机队作业（编队、喷洒、物流、集群调度）+ **M5 mesh 自愈组网** + **M9 应急编排** |
  | 应急版 | 基础版 + `emergency` | 增 4a 空地一体化指挥、告警联动、ONVIF 安防接入、视频融合 / 视觉 |
  | 完整版 | 应急版 + `network` + `advanced` | 增 5G 移动基站 / 卫星中继 / 链路适配（network）与数字孪生 / AI 决策（advanced） |

  > **档位是累进的**（应急版 ⊇ 基础版，完整版 ⊇ 应急版），与定价文档"应急版 = 基础版 + ..."的
  > 语义一致。
  >
  > 两处机器可校验真相源：
  > ① **前缀 → 模块**：`cloud-backend/.../license/LicenseModuleMap.java`，
  >    由 `LicenseModuleCoverageTest` 反射扫描全部 `*Controller.java` 守卫
  >    （未登记前缀按 `MODULE_UNCLASSIFIED` fail-closed）；
  > ② **档位 → 模块集合**：`LicenseTier.java`，且**在验证期强制**
  >    （`LicenseService.validateLicense` → `tierBindingViolation`：模块集合必须
  >    **恰好等于**某档，超集/缺项/未知模块一律判红），由
  >    `LicenseTierBindingEnforcementTest`（19 例）守卫；
  >    开关 `aerofleet.license.enforce-tier-binding` 默认 true。
>    **至此"某档客户实际能启用哪些模块"是代码保证，不再是配置约定。**
>    唯一仍需人工同步的是**价格表**（不入代码）：本表模块与
>    `docs/PRODUCT-POSITIONING.md` §5 的档位价格需同时改。
>
> **✅ 定价档位已裁决（2026-10-07，"基础版需要扎实"）**：此前 §5.1 把 mesh 与 M9 编排
>    承诺给基础版，而代码把 mesh 放完整版、M9 放应急版 ⇒ 按 §5 卖基础版会让客户付费的
>    `/api/v1/mesh/*` 与 M9 编排被 403。现已把 `network` 拆出 `mesh`、`emergency` 拆出
>    `orch`（`ALL_MODULES` 5 → 7），三档边界与 §5 逐字对齐；拆分**不是**把两个大模块
>    整块下移——那会让基础版连基站 / 卫星 / ONVIF 一起白送、档位梯子塌掉。
>    边界由 `LicenseModuleSplitMigrationTest`（7 例）钉死。
>    ⚠️ **升级需重签发**：模块名进签名载荷，拆分前签发的 license 不再对应任何档位。
>    详见 `docs/PRODUCT-POSITIONING.md` §5.1.1（唯一对照入口）。

- **未授权行为**：复制、分发、转售、反向工程、移除授权校验逻辑、超授权范围
  使用等，均属违约，许可方保留追究法律责任的权利。
- **第三方组件**：本软件包含或依赖的开源组件（如前端 `gcs-web/public/vendor/`
  下的 hls.js 等）继续受其原始许可约束，本许可不改变该等许可，亦不授予
  对本软件自身的额外权利。
- **商业授权咨询**：support@nexussky.io

### CI / 发布注意事项

`scripts/publish-pypi.*` 与 `scripts/publish-maven.*` 具备一键发布到公共仓库的能力。
发布后制品**不可撤回**，执行前务必确认 `sdk-python/setup.py` 与 `sdk-java/pom.xml`
的许可字段已是"Proprietary"（2026-10-05 已从误标的 Apache 2.0 修正）。
