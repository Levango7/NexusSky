# NexusSky 新功能扩展方案

> 日期：2026-09-22
> 范围：空地一体化应急指挥集成 + 丐版低成本无人机方案
> 前置文档：`.codeartsdoer/specs/emergency_comm_networking/spec.md`（灾害应急通讯组网扩展需求规格）

---

## 一、空地一体化应急指挥集成方案

### 1.1 现有能力审查

#### 1.1.1 已实现的安防监控能力（4a 里程碑）

| 能力 | 实现位置 | 状态 |
| --- | --- | --- |
| ONVIF 设备发现 | `OnvifClient.discoverDevices()` | ✅ 模拟实现 |
| 设备注册/管理 | `SurveillanceDeviceRegistry` + JPA 持久化 | ✅ |
| RTSP 流获取 | `OnvifClient.getRtspUrl()` | ✅ 模拟实现 |
| PTZ 云台控制 | `OnvifClient.ptzControl()` | ✅ 模拟实现 |
| 事件订阅 | `OnvifClient.subscribeEvents()` | ✅ 模拟实现 |
| 一键布控 | `RapidDeployService.scanAndDeploy()` | ✅ |
| 报警事件接收 | `AlarmController` + `AlarmEventStore` | ✅ |
| 报警联动引擎 | `AlarmLinkageEngine` + `AlarmLinkageRule` | ✅ |
| 报警→无人机调度 | `AlarmToOrchBridge` | ✅ |
| MAVLink 报警消息 | `AlarmTriggerMsg(477)` / `AlarmAckMsg(478)` / `SurveillanceStatusMsg(479)` | ✅ |
| 应急指挥工作流 | `OneClickEmergencyResponse` 六阶段 | ✅ |
| GCS 视频面板 | `SurveillancePanel` + `AlarmPanel` | ✅ |

#### 1.1.2 已实现的应急组网能力（M5-M9）

| 能力 | 里程碑 | 状态 |
| --- | --- | --- |
| AODV-lite 多跳自愈路由 | M5 | ✅ |
| 移动基站载荷（LTE/WiFi/LoRa） | M6 | ✅ |
| 星-空-地多层级中继 | M7 | ✅ |
| 复杂地形适配 | M8 | ✅ |
| 应急任务编排 | M9 | ✅ |

#### 1.1.3 空地一体化差距分析

| 差距 | 描述 | 影响 |
| --- | --- | --- |
| 安防设备与 mesh 组网割裂 | 安防设备（布控球）仅通过 ONVIF/REST 接入，不参与 mesh 组网 | 灾区断网时安防设备失联 |
| 报警联动缺乏 QoS 保障 | 报警事件经 REST 推送，无优先级保障 | 灾区拥塞时报警延迟 |
| 视频流与无人机侦察无协同 | 安防视频与无人机航拍各自独立 | 无法形成空地协同态势 |
| 安防设备无离线自治能力 | 安防设备依赖云端 REST API | 断网后安防设备无法报警 |
| 缺乏统一态势感知面板 | 安防面板与应急编排面板分离 | 指挥员需切换多个面板 |
| 无多厂商协议深度适配 | ONVIF 模拟实现，未适配厂商私有协议 | 真实设备接入需补充 |

### 1.2 安防监控协议适配方案

#### 1.2.1 厂商私有协议适配层

现有 `OnvifClient` 为模拟实现，真实部署需适配三大厂商私有协议：

| 厂商 | 私有协议 | 适配内容 | 优先级 |
| --- | --- | --- | --- |
| 海康威视 | ISAPI + SDK | 设备发现、RTSP 流（hikvision://）、事件回调（ISAPI Event）、PTZ（ISAPI PTZ） | 高 |
| 大华 | DHSDK + ONVIF | 设备发现、RTSP 流、事件回调（DHSDK Event）、PTZ | 中 |
| 宇视 | UNIVIEW SDK + ONVIF | 设备发现、RTSP 流、事件回调、PTZ | 中 |

**适配架构**：

```
┌──────────────────────────────────────┐
│        SurveillanceController        │
│         (统一 REST API)              │
└──────────┬───────────────────────────┘
           │
┌──────────┴───────────────────────────┐
│       VendorAdapterRegistry          │
│    (厂商适配器注册与路由)              │
└──┬──────────┬──────────┬─────────────┘
   │          │          │
   ▼          ▼          ▼
┌─────────┐ ┌─────────┐ ┌───────────┐
│HikAdapter│ │DahuaAdapter│ │UniviewAdapter│
│ISAPI+SDK │ │DHSDK     │ │UNIVIEW SDK │
└─────────┘ └─────────┘ └───────────┘
```

**关键设计**：
- `VendorAdapter` 接口统一抽象：`discover()` / `getStreamUrl()` / `ptzControl()` / `subscribeEvents()` / `getCapabilities()`
- 各厂商适配器实现各自私有协议，对外暴露统一接口
- `VendorAdapterRegistry` 按 `Vendor` 枚举路由到对应适配器
- 现有 `OnvifClient` 作为 ONVIF 标准协议的 fallback 适配器保留

#### 1.2.2 离线自治能力扩展

灾害断网场景下，安防设备需具备离线自治能力：

| 能力 | 实现方案 | 优先级 |
| --- | --- | --- |
| 本地报警缓存 | 安防设备/布控球本地缓存报警事件，网络恢复后批量上传 | 高 |
| LoRa 回传通道 | 布控球通过 LoRa 模块将报警事件回传至无人机 mesh 网络 | 中 |
| 边缘 AI 触发 | 布控球内置 AI 检测（人形/车辆/火灾），检测到异常自动报警 | 中 |
| 声光报警联动 | 报警触发布控球声光报警，引导无人机前往确认 | 低 |

**LoRa 回传通道设计**：

```
[布控球 + LoRa模块] ──LoRa 433MHz──> [无人机 LoRa基站载荷(M6)] ──mesh──> [指挥中心]
```

- 布控球加装 SX1278 LoRa 模块（~8元），433MHz 免许可频段
- 报警事件经 LoRa 发送至最近的无人机 LoRa 基站载荷
- 无人机 mesh 网络将报警事件路由至指挥中心
- 指挥中心通过 `AlarmController` 接收并触发联动

### 1.3 空地协同指挥流程

#### 1.3.1 空地协同指挥六阶段流程

```
阶段1: 接报 ───> 阶段2: 研判 ───> 阶段3: 部署 ───> 阶段4: 执行 ───> 阶段5: 评估 ───> 阶段6: 总结
  │              │                │                │                │               │
  │ 安防设备报警  │ 空地态势融合    │ 无人机+安防     │ 实时监控+       │ 效果评估      │ 报告生成
  │ 人工报警      │ 威胁等级评定    │ 联合部署        │ 动态调整        │ 覆盖率评估    │ 归档
  │ 卫星遥感      │ 资源可用性评估  │                │                │               │
```

**阶段1 - 接报**：
- 安防设备报警事件经 `AlarmLinkageEngine` 接收
- 人工报警经 `AlarmController` REST API 接收
- 卫星遥感灾害检测经 M7 `SatLinkStatus` 接收
- 所有报警源统一进入 `AlarmEventStore`

**阶段2 - 研判**：
- 空地态势融合：安防摄像头视频流 + 无人机航拍画面 + 地图数据
- 威胁等级评定：基于 `AlarmEvent.Severity`（INFO/MINOR/MAJOR/CRITICAL）
- 资源可用性评估：可用无人机数量/电量/位置 + 可用安防设备/在线状态
- 自动推荐响应方案：基于 `AlarmLinkageRule` 匹配 + M9 `ScenarioPresetFactory`

**阶段3 - 部署**：
- 无人机部署：M9 `CoverageOptimizer` 计算最优部署方案
- 安防设备部署：`RapidDeployService` 一键布控 + LoRa 回传配置
- 联合部署：无人机基站载荷（M6）覆盖安防设备区域，保障通信
- 优先级分配：搜救通讯 > 指挥通讯 > 灾区测绘 > 常规巡检

**阶段4 - 执行**：
- 实时监控：安防视频流 + 无人机航拍 + mesh 拓扑 + 基站覆盖
- 动态调整：无人机损毁 → M9 `DynamicReconfigurator` 重构 + 安防设备补盲
- QoS 保障：报警联动帧走 EMERGENCY 优先级队列
- 空地协同：安防设备检测到目标 → 无人机前往确认 → 无人机航拍回传 → 安防 PTZ 跟踪

**阶段5 - 评估**：
- 覆盖率评估：M9 `CoverageOptimizer` 计算当前覆盖率
- 连通率评估：mesh 拓扑连通性 + 安防设备在线率
- 响应时效评估：从报警到响应的时间统计
- 资源消耗评估：无人机电量消耗 + 安防设备状态

**阶段6 - 总结**：
- 自动生成应急响应报告
- 事件时间线归档（`flight-logs` JSONL）
- 联动规则优化建议
- 经验教训记录

#### 1.3.2 空地协同关键交互

**安防报警 → 无人机自动侦察**：

```
安防设备检测异常
    │
    ▼
AlarmEvent 生成 (AlarmLinkageEngine)
    │
    ├──> 匹配 AlarmLinkageRule
    │         │
    │         ▼
    │    AlarmToOrchBridge 转换为无人机任务
    │         │
    │         ▼
    │    M9 OrchestrationEngine 启动编排
    │         │
    │         ├──> 无人机起飞 → 飞往报警位置
    │         ├──> 盘旋侦察 → 实时回传画面
    │         ├──> 安防 PTZ 联动跟踪目标
    │         └──> 返航
    │
    └──> AlarmTriggerMsg(477) 广播到 mesh
              │
              ▼
         GCS AlarmPanel 实时显示
```

**无人机侦察 → 安防 PTZ 联动**：

```
无人机航拍发现目标
    │
    ▼
GeolocationSolver 解算目标经纬度
    │
    ▼
云端将目标位置发送给最近的安防设备
    │
    ▼
安防设备 PTZ 自动转向目标位置
    │
    ▼
安防视频流 + 无人机航拍画面 同屏显示
```

### 1.4 统一态势感知面板

现有 `SurveillancePanel`（安防）和 `EmergencyOrchPanel`（应急编排）分离，需整合为统一态势感知面板：

**面板布局**：

```
┌─────────────────────────────────────────────────────────────┐
│                    空地一体化应急指挥面板                      │
├──────────┬──────────────────────────┬───────────────────────┤
│ 安防设备  │    空地协同地图           │  无人机列表            │
│ 列表     │    (MapLibre)             │  (ID/电量/位置/角色)   │
│          │                          │                       │
│ 在线/离线 │  安防摄像头位置+覆盖范围   │  报警事件列表          │
│ 视频流    │  无人机位置+航迹          │  (SSE实时推送)         │
│ PTZ控制   │  mesh拓扑连线            │                       │
│          │  基站覆盖区域             │  联动规则管理          │
│          │  灾区边界                 │                       │
│          │  地形高程                 │  一键应急响应          │
├──────────┴──────────────────────────┴───────────────────────┤
│  视频融合区：安防视频流(4分屏) + 无人机航拍画面(2分屏)         │
├─────────────────────────────────────────────────────────────┤
│  编排进度时间线 + 覆盖率/连通率仪表盘 + 优先级任务队列         │
└─────────────────────────────────────────────────────────────┘
```

**关键功能**：
1. **视频融合**：安防摄像头 RTSP 流 + 无人机航拍画面同屏显示，支持画中画
2. **目标关联**：安防检测目标 → 无人机航拍确认 → 地图标注 → PTZ 跟踪
3. **报警联动可视化**：报警事件 → 联动规则匹配 → 无人机调度 → 执行进度
4. **mesh 拓扑叠加**：mesh 节点/链路/基站覆盖/卫星链路在地图上叠加显示
5. **一键应急响应**：选择场景预设 → 输入灾区坐标 → 自动走完六阶段流程

---

## 二、丐版低成本无人机方案

### 2.1 现有硬件抽象层（M4）能力审查

M4 已实现四类硬件抽象：

| 硬件类型 | 抽象接口 | 模拟实现 | MAVLink 消息 |
| --- | --- | --- | --- |
| 相控阵雷达 | `PhasedArrayRadar` | `SimulatedRadar` | RadarScan(437)/RadarTarget(438) |
| 旋翼气动 | `RotorAerodynamics` | `SimulatedRotorAerodynamics` | RotorTelemetry(439) |
| LiDAR | `LiDARSource` | `SimulatedLiDARSource` | LidarData(440) |
| IMU | `ImuSource` | `SimulatedImuSource` | ImuData(441) |

**M4 关键设计原则**：软件层协议抽象 + 模拟器假数据源，未来接真硬件替换数据源而非架构。

### 2.2 现有丐版硬件方案审查（`docs/budget-hardware-design.md`）

已有方案定义了三档丐版配置：

| 级别 | 预算 | 核心能力 | 已有文档 |
| --- | --- | --- | --- |
| 百元级 | ~63元 | IMU悬停+WiFi图传+超声波避障 | ✅ BOM清单+适配方案 |
| 千元级 | ~356元 | GPS航点+LoRa Mesh+MAVLink接入 | ✅ BOM清单+适配方案 |
| 进阶版 | ~766元 | 千元级+光流+红外阵列+双频GPS | ✅ BOM清单+适配方案 |

**已有方案的关键内容**：
- `SimConfig --budget=toy/standard/advanced` 参数
- 传感器降级架构（SensorHub Full/Budget Mode）
- 丐版 MAVLink 消息子集
- LoRa Mesh 通信适配（LoRaTransport 分片/重组）
- GCS 丐版 UI（隐藏不可用面板）
- 替代方案矩阵（雷达→超声波/ToF、卫星→LoRa、RTK→NEO-M8N）

### 2.3 丐版方案扩展：灾害应急场景适配

现有丐版方案为通用场景设计，需扩展灾害应急场景适配：

#### 2.3.1 灾害应急丐版配置方案

**百元级灾害应急配置（~80元）**：

| 组件 | 型号 | 单价 | 灾害应急用途 |
| --- | --- | --- | --- |
| 主控 | ESP32-S3 DevKit | 10 | 飞控 + MAVLink 桥接 |
| IMU | MPU6050 | 5 | 姿态稳定 |
| 气压计 | BMP280 | 3 | 定高 |
| 航拍 | ESP32-CAM | 15 | 灾区侦察图传 |
| 避障 | HC-SR04 ×2 | 4 | 废墟近距避障 |
| 电机 | 8520 ×4 | 8 | 微型动力 |
| 桨 | 65mm ×4 | 2 | 推力 |
| 电池 | 3.7V 600mAh | 8 | ~5分钟续航 |
| 机架 | 玩形架 | 5 | 结构 |
| **LoRa** | **SX1278 433MHz** | **8** | **应急mesh组网** |
| **LED** | **WS2812 ×4** | **4** | **搜救信号灯** |
| **蜂鸣器** | **有源蜂鸣器** | **2** | **声光报警** |
| **合计** | | **~74元** | |

**千元级灾害应急配置（~400元）**：

| 组件 | 型号 | 单价 | 灾害应急用途 |
| --- | --- | --- | --- |
| 飞控 | F405 V3 | 35 | ArduPilot MAVLink |
| GPS | NEO-M8N | 20 | 北斗+GPS 定位 |
| ESC | 4合1 30A | 45 | 电机驱动 |
| 电机 | 2207 2300KV ×4 | 55 | 动力 |
| 桨 | 5寸 ×4 | 10 | 推力 |
| 机架 | F450 | 35 | 4轴结构 |
| 电池 | 4S 1500mAh | 45 | ~8分钟续航 |
| 图传 | 5.8G 25mW VTX+Cam | 60 | FPV 灾区侦察 |
| 接收机 | FS-iA6B | 20 | 遥控 |
| LoRa | SX1278 433MHz | 10 | Mesh 远距离通信 |
| 伴侣机 | ESP32-S3 | 10 | MAVLink桥接+LoRa驱动 |
| 避障 | VL53L0X ToF ×2 | 6 | 废墟精确测距 |
| 罗盘 | QMC5883L | 5 | 航向 |
| **红外** | **AMG8833 8×8** | **60** | **热源搜救检测** |
| **LED** | **WS2812 ×8** | **8** | **搜救信号灯** |
| **蜂鸣器** | **大功率蜂鸣器** | **5** | **声光报警** |
| **合计** | | **~429元** | |

#### 2.3.2 灾害应急功能保留/裁剪/替代矩阵

| 功能 | 百元级 | 千元级 | 进阶版 | 完整版 | 灾害应急必要性 |
| --- | --- | --- | --- | --- | --- |
| 姿态稳定悬停 | ✅ | ✅ | ✅ | ✅ | 必须 - 废墟飞行需精准悬停 |
| GPS定位 | ❌ | ✅ 2-3m | ✅ 1m | ✅ RTK cm | 必须 - 灾区坐标定位 |
| 航点飞行 | ❌ | ✅ | ✅ | ✅ | 必须 - 自动侦察航线 |
| 实时图传 | ✅ WiFi | ✅ 5.8G | ✅ 5.8G | ✅ 光电吊舱 | 必须 - 灾区实时侦察 |
| 避障 | ✅ 超声波 | ✅ ToF | ✅ ToF+光流 | ✅ 雷达 | 必须 - 废墟飞行安全 |
| Mesh组网 | ✅ WiFi | ✅ LoRa 5km | ✅ LoRa 5km | ✅ 卫星 | 必须 - 断网应急组网 |
| 多机协同 | ✅ 3-5机 | ✅ 10-20机 | ✅ 20-50机 | ✅ 100+ | 必须 - 多机搜救 |
| 热成像 | ❌ | ❌ | ✅ 32×24 | ✅ 640×480 | 搜救必需 - 人员热源检测 |
| 多光谱 | ❌ | ❌ | ❌ | ✅ | 低 - 灾区非必需 |
| LiDAR建图 | ❌ | ❌ | ❌ | ✅ | 低 - 灾区非必需 |
| 应急编排 | ❌ | ✅ 基础 | ✅ | ✅ 全量 | 必须 - 自动化响应 |
| 数字孪生 | ❌ | ✅ 位置镜像 | ✅ | ✅ 全量 | 中 - 灾区态势感知 |
| 搜救信号灯 | ✅ LED | ✅ LED | ✅ LED | ✅ | 必须 - 引导地面搜救 |
| 声光报警 | ✅ 蜂鸣器 | ✅ 蜂鸣器 | ✅ | ✅ | 必须 - 引导被困人员 |
| 红外热源检测 | ❌ | ✅ AMG8833 | ✅ MLX90640 | ✅ | 搜救必需 - 废墟下人员 |

**裁剪原则**：
1. **必须保留**：姿态稳定、避障、图传、Mesh组网、搜救信号灯/声光报警
2. **条件保留**：GPS（百元级无GPS，仅限室内/短距）、热成像（千元级加AMG8833）
3. **可裁剪**：多光谱、LiDAR建图、RTK厘米定位、光电吊舱
4. **替代方案**：雷达→超声波/ToF、卫星→LoRa、RTK→NEO-M8N

#### 2.3.3 丐版方案对软件架构的影响

**1. SimConfig Budget 模式扩展**：

```java
// 新增灾害应急模式
public final String budgetMode;  // "toy" | "standard" | "advanced" | "emergency-toy" | "emergency-standard"

if ("emergency-toy".equals(budgetMode)) {
    // 百元级灾害应急：WiFi Mesh + 超声波避障 + LED/蜂鸣器 + ESP32-CAM
    // 无 GPS、无热成像、无航点飞行
    // 适合：室内/废墟近距侦察、信号灯引导
} else if ("emergency-standard".equals(budgetMode)) {
    // 千元级灾害应急：LoRa Mesh + ToF避障 + GPS航点 + AMG8833热源检测 + LED/蜂鸣器
    // 适合：户外灾区侦察、搜救热源检测、mesh组网中继
}
```

**2. 传感器降级与灾害场景适配**：

| 传感器 | Full Mode | Emergency-Standard | Emergency-Toy |
| --- | --- | --- | --- |
| RadarSource | PhasedArrayRadar | ToF VL53L0X | HC-SR04 超声波 |
| ThermalSource | 640×480 热成像 | AMG8833 8×8 | ❌ 无 |
| GpsSource | RTK 厘米级 | NEO-M8N 2-3m | ❌ 无（IMU积分） |
| CommType | 卫星+LTE+WiFi | LoRa Mesh | WiFi ESP-NOW |
| MeshRouter | AODV-lite 全功能 | AODV-lite 简化 | ESP-NOW 广播 |

**3. 丐版 Mesh 路由简化**：

百元级 ESP32 无法运行完整 AODV-lite，需简化为：
- **ESP-NOW 广播模式**：所有节点广播所有帧，无路由表、无 RREQ/RREP
- ** hopCount 守卫**：仍保留 hopCount 限制（MAX_HOPS=5），防止无限广播
- **节点数限制**：最多 5-8 个节点（ESP-NOW 带宽限制）
- **数据帧精简**：仅传 HEARTBEAT + ATTITUDE + COMMAND_LONG + 自定义精简帧

千元级 LoRa Mesh 可运行简化版 AODV-lite：
- **LoRaTransport 分片**：MAVLink 帧分片为 ~50 bytes LoRa 帧
- **HELLO 间隔放宽**：LoRa 带宽窄，HELLO 间隔从 1s 放宽至 5s
- **邻居超时放宽**：从 5s 放宽至 15s（LoRa 链路延迟高）
- **MAX_HOPS=10**：LoRa 链路跳数限制更严格

**4. 丐版 GCS UI 灾害模式**：

| 模式 | 显示面板 | 隐藏面板 |
| --- | --- | --- |
| Emergency-Toy | 遥测+航拍+简易地图+LED控制+蜂鸣器 | 编队/喷洒/雷达/热成像/多光谱/LiDAR/数字孪生 |
| Emergency-Standard | 遥测+航拍+地图+航点+Mesh拓扑+热源检测+LED/蜂鸣器 | 编队/喷洒/雷达/多光谱/LiDAR |
| Full | 全部39个面板 | 无 |

**5. 灾害应急丐版对 M9 编排引擎的影响**：

M9 `CoverageOptimizer` 需感知丐版硬件能力差异：

| 优化参数 | Full Mode | Emergency-Standard | Emergency-Toy |
| --- | --- | --- | --- |
| 最大覆盖半径 | 5km（LTE基站） | 2km（LoRa基站） | 200m（WiFi） |
| 最大节点数 | 100+ | 20 | 5-8 |
| 最大续航 | 30min | 8min | 5min |
| 避障距离 | 100m（雷达） | 4m（ToF） | 2m（超声波） |
| 搜救能力 | 热成像640×480 | 热源8×8 | LED+蜂鸣器 |
| 编排复杂度 | 全量 | 简化（无卫星层级） | 最简（仅WiFi单跳） |

### 2.4 丐版方案实施路线

| Phase | 内容 | 工期 | 依赖 |
| --- | --- | --- | --- |
| Phase 1 | SimConfig Emergency Budget 模式 | 1-2天 | 现有 `--budget` 参数 |
| Phase 2 | 丐版 Mesh 路由简化（ESP-NOW/LoRa） | 3-5天 | M5 MeshRouter |
| Phase 3 | AMG8833 热源检测数据源 | 2-3天 | M3 ThermalSource |
| Phase 4 | LED/蜂鸣器控制 MAVLink 消息 | 1-2天 | M1 LedControlMsg |
| Phase 5 | GCS Emergency UI 适配 | 2-3天 | 现有 budget UI |
| Phase 6 | M9 编排引擎丐版约束感知 | 3-5天 | M9 CoverageOptimizer |
| Phase 7 | ESP32 伴侣机固件（独立项目） | 7-10天 | Phase 1-6 |

---

## 三、总结与优先级排序

### 3.1 空地一体化应急指挥集成

| 优先级 | 任务 | 依赖 | 工期 |
| --- | --- | --- | --- |
| P0 | 厂商私有协议适配层（VendorAdapter） | 现有 OnvifClient | 5-7天 |
| P0 | 空地协同指挥六阶段流程完善 | 现有 OneClickEmergencyResponse | 3-5天 |
| P1 | LoRa 回传通道（布控球→无人机mesh） | M6 LoRa基站载荷 | 3-5天 |
| P1 | 统一态势感知面板 | 现有 SurveillancePanel + EmergencyOrchPanel | 5-7天 |
| P2 | 安防设备离线自治（本地缓存+边缘AI） | 现有 AlarmEventStore | 5-7天 |
| P2 | 视频融合（安防RTSP+无人机航拍同屏） | 现有 SurveillancePanel | 3-5天 |

### 3.2 丐版低成本无人机方案

| 优先级 | 任务 | 依赖 | 工期 |
| --- | --- | --- | --- |
| P0 | SimConfig Emergency Budget 模式 | 现有 --budget 参数 | 1-2天 |
| P0 | 丐版 Mesh 路由简化 | M5 MeshRouter | 3-5天 |
| P1 | AMG8833 热源检测数据源 | M3 ThermalSource | 2-3天 |
| P1 | LED/蜂鸣器搜救信号控制 | M1 LedControlMsg | 1-2天 |
| P1 | GCS Emergency UI 适配 | 现有 budget UI | 2-3天 |
| P2 | M9 编排引擎丐版约束感知 | M9 CoverageOptimizer | 3-5天 |
| P2 | ESP32 伴侣机固件 | Phase 1-6 | 7-10天 |

### 3.3 灾害应急通讯组网扩展

| 优先级 | 任务 | 依赖 | 工期 |
| --- | --- | --- | --- |
| P0 | QoS 优先级队列 | M5 MeshRouter | 3-5天 |
| P0 | 灾害模式自动激活/退出 | M5+M8+M9 | 2-3天 |
| P0 | 分簇路由 | M5 MeshRouter | 5-7天 |
| P1 | 异构链路桥接 | M5+M6+link-sim | 5-7天 |
| P1 | 多路径冗余路由 | M5 MeshRouter | 3-5天 |
| P1 | 自适应路由度量 | M5+M8 | 2-3天 |
| P2 | 多卫星最优选择 | M7 HierarchicalRouter | 3-5天 |
| P2 | 卫星链路质量预测 | M7 LeoConstellation | 3-5天 |
| P2 | NLOS中继自动选址 | M5+M8 | 3-5天 |
| P3 | 动态MAX_HOPS | M5 MeshRouter | 1天 |
| P3 | 真实卫星接入预留 | M7 SatLinkProvider | 2-3天 |