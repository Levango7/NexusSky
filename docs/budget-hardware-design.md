# NexusSky 丐版硬件方案设计

> 目标：在百元至千元级硬件上实现 NexusSky 多机协同调度的核心能力，
> 不能低成本实现的找替代方案，做到"能用的先上，不能的找平替"。

## 1. 方案总览

| 级别 | 预算 | 核心能力 | 适用场景 |
|------|------|----------|----------|
| **百元级** | ~100 RMB | IMU 悬停 + WiFi 图传 + 超声波避障 | 室内编队演示、教育 |
| **千元级** | ~1000 RMB | GPS 航点 + LoRa Mesh + MAVLink 接入 | 户外多机协同、巡检 |
| **进阶版** | ~2000 RMB | 千元级 + 光流 + 红外阵列 + 双频 GPS | 精准农业、应急侦察 |

## 2. 百元级方案（~100 RMB）

### 2.1 BOM 清单

| 组件 | 型号 | 单价(元) | 功能 |
|------|------|----------|------|
| 主控 | ESP32-S3 DevKit | 10 | 飞控 + MAVLink 桥接 |
| IMU | MPU6050 | 5 | 陀螺+加速度计 |
| 气压计 | BMP280 | 3 | 定高 |
| 航拍 | ESP32-CAM 模块 | 15 | 640×480 MJPEG WiFi 图传 |
| 避障 | HC-SR04 超声波 ×2 | 4 | 前向+下向测距 |
| 电机 | 空心杯 8520 ×4 | 8 | 微型动力 |
| 桨 | 65mm 反牙 ×4 | 2 | 推力 |
| 电池 | 3.7V 600mAh | 8 | ~5 分钟续航 |
| 机架 | 玩形玩具架 | 5 | 结构 |
| 分线板 | 万能板 | 3 | 连接 |
| **合计** | | **~63 元** | |

### 2.2 能力与限制

**能做：**
- IMU 姿态稳定悬停（PID 角速度环 + 角度环）
- 气压计定高（±0.5m）
- WiFi 实时图传（~50m 距离，30fps）
- 超声波前向避障（2m 范围）
- ESP32 上运行 MAVLink 精简协议（HEARTBEAT + ATTITUDE + COMMAND_LONG）
- 多机 WiFi 组网（ESP-NOW 协议，~200m）

**不能做 → 替代：**
- GPS 定位 → 纯 IMU 积分推算（漂移 ~1m/s，仅适合短时室内）
- 自主航点飞行 → 光流定位（PMW3901，~15 元可加，精度 0.1m）
- 远距离通信 → WiFi Mesh 多跳中继

### 2.3 NexusSky 适配

```
SimConfig:
  --budget=toy          # 百元级模式
  --sensors=imu,baro,ultrasonic,cam  # 启用传感器
  --no-gps              # 无 GPS
  --comm=wifi-mesh      # WiFi ESP-NOW 组网
```

VirtualDrone 行为变化：
- `gpsSource = null` → 位置由 IMU 积分推算
- `radarSource = null` → 超声波替代（UltrasonicSource）
- `lidarSource = null` → 气压计定高
- `thermalSource = null` → 可见光摄像头
- `commType = WIFI_ESPNOW` → WiFi Mesh 通信

## 3. 千元级方案（~1000 RMB）

### 3.1 BOM 清单

| 组件 | 型号 | 单价(元) | 功能 |
|------|------|----------|------|
| 飞控 | F405 V3 Wing | 35 | ArduPilot/iNav，MAVLink 原生 |
| GPS | NEO-M8N | 20 | 北斗+GPS+GLONASS 三模，2-3m CEP |
| ESC | 4合1 30A BLHeli_S | 45 | 电机驱动 |
| 电机 | 2207 2300KV ×4 | 55 | 动力 |
| 桨 | 5寸 ×4 | 10 | 推力 |
| 机架 | F450 通用 | 35 | 4 轴结构 |
| 电池 | 4S 1500mAh LiPo | 45 | ~8 分钟续航 |
| 图传 | 5.8G 25mW VTX+Cam | 60 | FPV 实时航拍 |
| 接收机 | FS-iA6B | 20 | 遥控 |
| LoRa | SX1278 433MHz | 10 | Mesh 远距离通信 |
| 伴侣机 | ESP32-S3 | 10 | MAVLink 桥接 + LoRa 驱动 |
| 避障 | VL53L0X ToF ×2 | 6 | 前向+下向精确测距 |
| 罗盘 | QMC5883L | 5 | 航向 |
| **合计** | | **~356 元** | |

> 不含遥控器（FlySky FS-i6 ~120 元）和充电器（~50 元），全套 ~530 元。

### 3.2 能力

- GPS 自主航点飞行（ArduPilot MAVLink）
- 北斗+GPS 双模定位（精度 2-3m，10Hz 更新）
- LoRa Mesh 组网（433MHz，2-5km 开阔环境）
- 5.8G FPV 实时图传（带录像）
- ToF 激光避障（4m 精确测距）
- 气压计定高 + 罗盘定向
- MAVLink 协议原生接入 NexusSky GCS
- 多机协同调度（通过 LoRa Mesh 中继指令）

### 3.3 NexusSky 适配

```
SimConfig:
  --budget=standard     # 千元级模式
  --sensors=gps,imu,baro,compass,tof,cam  # 启用传感器
  --comm=lora-mesh      # LoRa Mesh 组网
  --fence=-500,-500:500,-500:500,500:-500,500:120  # 电子围栏
```

VirtualDrone 行为：
- `gpsSource = NeoM8N` → GPS 定位
- `radarSource = null` → ToF 替代
- `commType = LORA_MESH` → LoRa 通信
- `meshEnabled = true` → Mesh 路由启用

## 4. 进阶版方案（~2000 RMB）

在千元级基础上增加：

| 增配组件 | 型号 | 单价(元) | 功能 |
|----------|------|----------|------|
| 光流 | PMW3901 | 15 | 室内精确定位 0.1m |
| 红外阵列 | MLX90640 | 120 | 32×24 热成像 |
| 双频 GPS | NEO-M9N | 35 | L1+L5 双频，1m CEP |
| 光学吊舱 | 两轴舵机+Cam | 30 | 简易云台 |
| **增配合计** | | **~200 元** | |

## 5. 替代方案矩阵

### 5.1 传感器替代

| 原方案 | 原成本 | 丐版替代 | 替代成本 | 性能对比 | 适用场景 |
|--------|--------|----------|----------|----------|----------|
| **有源相控阵雷达** | 万元+ | 超声波 HC-SR04 阵列(4×) | 8元 | 无扫描/短距2m/无测速 | 室内避障 |
| | | ToF VL53L0X 阵列(4×) | 12元 | 点测4m/无扫描 | 近距避障 |
| | | 光流 PMW3901 | 15元 | 仅下视/室内0.1m | 室内定位 |
| | | ESP32 雷达(FMCW实验) | 20元 | 简易FMCW/短距 | 研究实验 |
| **高精度 LiDAR** | 万元+ | 旋转超声波 SR04 | 20元 | 2D扫描/5m/低分辨率 | 简易建图 |
| | | ToF VL53L1X 旋转 | 25元 | 2D扫描/4m/中分辨率 | 近距建图 |
| **多光谱相机** | 万元+ | 普通Cam+滤光片轮 | 25元 | 4通道/无定标 | 粗略NDVI |
| **热成像** | 万元+ | MLX90640 32×24 | 120元 | 低分辨率/8fps | 人员搜救 |
| | | AMG8833 8×8 | 60元 | 极低分辨率/10fps | 热源检测 |

### 5.2 通信替代

| 原方案 | 原成本 | 丐版替代 | 替代成本 | 性能对比 |
|--------|--------|----------|----------|----------|
| **卫星通信** | 万元+ | LoRa SX1278 433MHz | 8元 | 50kbps/2-5km/无全球覆盖 |
| | | WiFi ESP-NOW | 0(板载) | 1Mbps/200m/低延迟 |
| | | 4G LTE 模块(Air724UG) | 30元 | 10Mbps/全覆盖/需SIM |
| **氮化镓功放** | 万元+ | LoRa PA+LNA | 15元 | 20dBm/低功耗 |
| **专链图传** | 千元+ | 5.8G 模拟VTX 25mW | 50元 | 标清/200m/低延迟 |
| | | ESP32-CAM WiFi | 15元 | VGA/50m/中延迟 |

### 5.3 定位替代

| 原方案 | 原成本 | 丐版替代 | 替代成本 | 精度 |
|--------|--------|----------|----------|------|
| **RTK 厘米级** | 万元+ | NEO-M8N 单点 | 20元 | 2-3m |
| | | NEO-M9N 双频 | 35元 | ~1m |
| | | UWB 基站+标签 | 100元 | 0.1m(室内) |
| **视觉定位** | 千元+ | 光流 PMW3901 | 15元 | 0.1m(室内) |
| | | 超声波定高 | 2元 | 0.02m(下视) |

## 6. NexusSky 软件适配方案

### 6.1 SimConfig Budget 模式

新增 `--budget` 参数，三档可选：

```java
// SimConfig 新增字段
public final String budgetMode;  // "toy" | "standard" | "advanced" | null

// 解析逻辑
if ("toy".equals(budgetMode)) {
    // 百元级：禁用 GPS/雷达/热成像/多光谱
    // 通信降级为 WiFi Mesh
    // 避障降级为超声波
} else if ("standard".equals(budgetMode)) {
    // 千元级：启用 GPS/ToF/LoRa Mesh
    // 禁用雷达/热成像/多光谱
} else if ("advanced".equals(budgetMode)) {
    // 进阶版：启用光流/红外阵列/双频GPS
}
```

### 6.2 传感器降级架构

```
                    ┌─────────────────┐
                    │  VirtualDrone   │
                    └────┬────────────┘
                         │
              ┌──────────┼──────────┐
              ▼          ▼          ▼
     ┌────────────┐ ┌─────────┐ ┌──────────┐
     │ SensorHub  │ │ CommHub │ │ MissionHub│
     └─────┬──────┘ └────┬────┘ └──────────┘
           │              │
     ┌─────┴──────┐ ┌────┴────┐
     │ Full Mode  │ │ LoRa    │
     │ - Radar    │ │ WiFi    │
     │ - LiDAR    │ │ 4G LTE  │
     │ - Thermal  │ │         │
     │ - MultiSpec│ │         │
     ├────────────┤ │         │
     │ Budget Mode│ │         │
     │ - Ultrasonic│ │        │
     │ - ToF      │ │         │
     │ - OpticalFlow│ │       │
     │ - VisibleCam│ │        │
     └────────────┘ └─────────┘
```

### 6.3 丐版 MAVLink 消息子集

百元级 ESP32 上只需实现以下 MAVLink 消息：

| msgId | 消息 | 用途 | 必须 |
|-------|------|------|------|
| 0 | HEARTBEAT | 心跳/在线状态 | ✅ |
| 30 | ATTITUDE | 姿态角 | ✅ |
| 1 | SYS_STATUS | 电池/负载 | ✅ |
| 33 | GLOBAL_POSITION_INT | GPS 位置 | 千元+ |
| 76 | COMMAND_LONG | 指令下发 | ✅ |
| 77 | COMMAND_ACK | 指令应答 | ✅ |
| 147 | BATTERY_STATUS | 电池详情 | 可选 |
| 253 | STATUSTEXT | 状态文本 | 可选 |

百元级无需实现：雷达扫描(420+)、热成像(430+)、多光谱(437+)、
编排引擎(465+)、集群调度(468+) 等扩展消息。

### 6.4 GCS 丐版 UI

gcs-web 添加 budget 模式判断，隐藏不可用面板：

```jsx
// App.jsx 适配
const budgetMode = config.budget;  // "toy" | "standard" | null

// 百元级：仅显示 遥测 + 航拍 + 简易地图
// 千元级：增加 编队 + Mesh拓扑 + 航点
// 进阶版：全功能
```

### 6.5 LoRa Mesh 通信适配

现有 MeshRouter 已支持 AODV-lite 路由，LoRa 适配层：

```
┌─────────────┐    ┌──────────────┐    ┌─────────────┐
│  MAVLink    │───>│ LoRaTransport│───>│ SX1278 433  │
│  Frame     │    │ (分片/重组)  │    │  RF Module  │
└─────────────┘    └──────────────┘    └─────────────┘
```

LoRa 参数：
- 频率：433MHz（免许可）
- 带宽：125kHz
- 扩频因子：SF7（高速）~ SF12（远距离）
- 编码率：4/5
- 有效载荷：~50 bytes/帧（MAVLink 精简帧 < 50 bytes）
- MAVLink 大帧分片传输（LoRaTransport 内部处理）

## 7. 成本-能力对照表

| 能力 | 百元级 | 千元级 | 进阶版 | 完整版 |
|------|--------|--------|--------|--------|
| 姿态稳定悬停 | ✅ | ✅ | ✅ | ✅ |
| GPS 定位 | ❌ | ✅ 2-3m | ✅ 1m | ✅ RTK cm |
| 航点飞行 | ❌ | ✅ | ✅ | ✅ |
| 实时图传 | ✅ WiFi | ✅ 5.8G | ✅ 5.8G | ✅ 光电吊舱 |
| 避障 | ✅ 超声波 | ✅ ToF | ✅ ToF+光流 | ✅ 雷达 |
| Mesh 组网 | ✅ WiFi | ✅ LoRa 5km | ✅ LoRa 5km | ✅ 卫星 |
| 多机协同 | ✅ 3-5机 | ✅ 10-20机 | ✅ 20-50机 | ✅ 100+ |
| 热成像 | ❌ | ❌ | ✅ 32×24 | ✅ 640×480 |
| 多光谱 | ❌ | ❌ | ❌ | ✅ |
| LiDAR 建图 | ❌ | ❌ | ❌ | ✅ |
| 应急编排 | ❌ | ✅ 基础 | ✅ | ✅ 全量 |
| 数字孪生 | ❌ | ✅ 位置镜像 | ✅ | ✅ 全量 |

## 8. 实施路线

### Phase 1：SimConfig Budget 模式（1-2 天）
- [ ] SimConfig 添加 `--budget` 参数解析
- [ ] VirtualDrone 根据 budgetMode 禁用/降级传感器
- [ ] 模拟器验证百元/千元模式可运行

### Phase 2：LoRa Transport 适配（2-3 天）
- [ ] LoRaTransport 类（MAVLink 帧分片/重组）
- [ ] MeshRouter 适配 LoRa 参数（低带宽、高延迟）
- [ ] 模拟器验证 LoRa Mesh 多跳中继

### Phase 3：传感器降级（2-3 天）
- [ ] UltrasonicSource（超声波数据源）
- [ ] OpticalFlowSource（光流数据源）
- [ ] BudgetThermalSource（MLX90640 红外阵列）
- [ ] ObstacleDetector 适配降级传感器

### Phase 4：GCS 丐版 UI（1-2 天）
- [ ] gcs-web budget 模式判断
- [ ] 隐藏不可用面板，简化显示
- [ ] 百元级极简控制界面

### Phase 5：ESP32 伴侣机固件（5-7 天，独立项目）
- [ ] MAVLink 精简协议实现
- [ ] WiFi ESP-NOW Mesh 组网
- [ ] 超声波/IMU 传感器驱动
- [ ] ESP32-CAM 图传桥接

## 9. 采购清单（淘宝/华强北参考）

### 千元级全套（~530 元）
1. F405 V3 飞控 ×1 — 搜索"F405 V3 wing"
2. NEO-M8N GPS 模块 ×1 — 搜索"NEO-M8N 北斗"
3. 4合1 30A ESC ×1 — 搜索"BLHeli_S 30A 4合1"
4. 2207 2300KV 电机 ×4 — 搜索"2207 2300kv"
5. 5寸正反桨 ×2套 — 搜索"5寸 桨 51"
6. F450 机架 ×1 — 搜索"F450 机架"
7. 4S 1500mAh 电池 ×1 — 搜索"4S 1500mAh lipo"
8. 5.8G VTX+摄像头 ×1套 — 搜索"5.8G 图传 25mw"
9. FS-iA6B 接收机 ×1 — 搜索"iA6B 接收机"
10. SX1278 LoRa 模块 ×1 — 搜索"SX1278 433 LoRa"
11. ESP32-S3 开发板 ×1 — 搜索"ESP32-S3 devkit"
12. VL53L0X ToF 模块 ×2 — 搜索"VL53L0X 激光测距"

### 百元级全套（~63 元）
1. ESP32-S3 开发板 ×1 — 10 元
2. MPU6050 模块 ×1 — 5 元
3. BMP280 模块 ×1 — 3 元
4. ESP32-CAM 模块 ×1 — 15 元
5. HC-SR04 超声波 ×2 — 4 元
6. 8520 空心杯电机 ×4 — 8 元
7. 65mm 桨 ×4 — 2 元
8. 3.7V 600mAh 电池 ×1 — 8 元
9. 玩具环形机架 ×1 — 5 元
10. 万能板 ×1 — 3 元
---

## 10. 详细成本核算（含工具/运费/耗材）

### 10.1 百元级 TCO（总拥有成本）

| 项目 | 金额(元) | 说明 |
|------|----------|------|
| **BOM 元器件** | 63 | 上表合计 |
| 焊接工具 | 0 | 假设已有（否则电烙铁+焊锡+助焊剂 ~30 元） |
| 杜邦线/排针 | 5 | 连接用 |
| 热熔胶/扎带 | 3 | 固定走线 |
| 运费（淘宝包邮） | 0 | 大部分组件包邮 |
| 充电器 | 8 | USB 充电座（3.7V 1S 锂电） |
| **TCO 合计** | **~79 元** | 假设已有基础焊接工具 |
| **TCO（含工具）** | **~109 元** | 从零开始含焊接工具 |

### 10.2 千元级 TCO

| 项目 | 金额(元) | 说明 |
|------|----------|------|
| **BOM 元器件** | 356 | 上表合计 |
| 遥控器 | 120 | FlySky FS-i6（6 通道） |
| 充电器 | 50 | B6 充电器（2-6S 锂电平衡充） |
| 航拍存储卡 | 15 | 32G TF 卡 |
| 备用桨 | 10 | 5 寸正反桨 ×2 套备用 |
| 扎带/魔术贴 | 5 | 走线固定 |
| 运费 | 10 | 部分组件不包邮 |
| **TCO 合计** | **~566 元** | 含遥控器+充电器 |
| **TCO（含焊接工具）** | **~596 元** | 从零开始 |

### 10.3 进阶版 TCO

| 项目 | 金额(元) | 说明 |
|------|----------|------|
| **千元级 TCO** | 566 | 基础 |
| 光流 PMW3901 | 15 | 室内定位 |
| 红外 MLX90640 | 120 | 热成像 |
| 双频 NEO-M9N | 35 | 替换 M8N（+15 元差价） |
| 光学吊舱 | 30 | 两轴舵机+摄像头 |
| **TCO 合计** | **~766 元** | |

### 10.4 隐性成本

| 项目 | 百元级 | 千元级 | 进阶版 |
|------|--------|--------|--------|
| 续航时间 | ~5 min | ~8 min | ~8 min |
| 电池循环寿命 | ~100 次 | ~300 次 | ~300 次 |
| 桨损耗（每 50 次更换） | 2 元/次 | 10 元/次 | 10 元/次 |
| 年运营成本（100 次飞行） | ~20 元 | ~50 元 | ~50 元 |
| 维修概率（炸机） | 高 | 中 | 中 |
| 单次炸机维修成本 | ~15 元 | ~80 元 | ~120 元 |

## 11. 采购链接（淘宝搜索 URL）

> 以下为淘宝搜索链接，直接点击即可搜索。价格随市场波动，仅供参考。
> 建议从销量高+评价好的店铺购买，优先选择"包邮+7天无理由"。

### 11.1 百元级采购链接

| 组件 | 搜索链接 | 参考价 |
|------|----------|--------|
| ESP32-S3 | https://s.taobao.com/search?q=ESP32-S3+devkit | 8-12 元 |
| MPU6050 | https://s.taobao.com/search?q=MPU6050+模块 | 4-6 元 |
| BMP280 | https://s.taobao.com/search?q=BMP280+气压计 | 2-4 元 |
| ESP32-CAM | https://s.taobao.com/search?q=ESP32-CAM+摄像头 | 12-18 元 |
| HC-SR04 | https://s.taobao.com/search?q=HC-SR04+超声波 | 1.5-2.5 元 |
| 8520 电机 | https://s.taobao.com/search?q=8520+空心杯+电机 | 1.5-2 元/个 |
| 65mm 桨 | https://s.taobao.com/search?q=65mm+桨+反牙 | 0.5 元/个 |
| 1S 锂电 | https://s.taobao.com/search?q=3.7V+600mAh+1S+锂电 | 6-10 元 |

### 11.2 千元级采购链接

| 组件 | 搜索链接 | 参考价 |
|------|----------|--------|
| F405 V3 飞控 | https://s.taobao.com/search?q=F405+V3+wing+飞控 | 30-40 元 |
| NEO-M8N | https://s.taobao.com/search?q=NEO-M8N+北斗+GPS | 18-25 元 |
| 4合1 ESC | https://s.taobao.com/search?q=BLHeli_S+30A+4合1+ESC | 40-50 元 |
| 2207 电机 | https://s.taobao.com/search?q=2207+2300kv+电机 | 12-15 元/个 |
| 5 寸桨 | https://s.taobao.com/search?q=5寸+桨+51+正反 | 5 元/套 |
| F450 机架 | https://s.taobao.com/search?q=F450+机架+四轴 | 30-40 元 |
| 4S 电池 | https://s.taobao.com/search?q=4S+1500mAh+lipo+电池 | 40-50 元 |
| 5.8G 图传 | https://s.taobao.com/search?q=5.8G+图传+25mw+套装 | 50-70 元 |
| FS-iA6B | https://s.taobao.com/search?q=FlySky+iA6B+接收机 | 18-25 元 |
| SX1278 | https://s.taobao.com/search?q=SX1278+433MHz+LoRa | 8-12 元 |
| VL53L0X | https://s.taobao.com/search?q=VL53L0X+激光测距 | 2.5-3.5 元 |
| QMC5883L | https://s.taobao.com/search?q=QMC5883L+罗盘 | 4-6 元 |

### 11.3 进阶版增购链接

| 组件 | 搜索链接 | 参考价 |
|------|----------|--------|
| PMW3901 | https://s.taobao.com/search?q=PMW3901+光流 | 12-18 元 |
| MLX90640 | https://s.taobao.com/search?q=MLX90640+红外+阵列 | 100-140 元 |
| NEO-M9N | https://s.taobao.com/search?q=NEO-M9N+双频+GPS | 30-40 元 |

## 12. 组装指南（简版）

### 12.1 百元级组装（~1 小时）

```
步骤 1: 焊接 ESP32-S3 排针（10 min）
步骤 2: 连接 MPU6050 → I2C (SDA=GPIO8, SCL=GPIO9)（5 min）
步骤 3: 连接 BMP280 → I2C（并联）（2 min）
步骤 4: 连接 ESP32-CAM → UART2 (RX=GPIO16, TX=GPIO17)（5 min）
步骤 5: 焊接 8520 电机 → ESP32 PWM 引脚（GPIO 10-13）（15 min）
步骤 6: 连接 HC-SR04 ×2 → GPIO (TRIG/ECHO)（10 min）
步骤 7: 固定到机架（热熔胶）（10 min）
步骤 8: 烧录 ESP32 固件（Arduino IDE / PlatformIO）（10 min）
步骤 9: 配对遥控（WiFi AP 模式配置）（5 min）
```

**接线图（文字版）：**
```
ESP32-S3 引脚分配：
  GPIO8  → MPU6050 SDA (I2C)
  GPIO9  → MPU6050 SCL (I2C)
  GPIO16 → ESP32-CAM TX (UART2 RX)
  GPIO17 → ESP32-CAM RX (UART2 TX)
  GPIO10 → 电机 1 PWM (FL)
  GPIO11 → 电机 2 PWM (FR)
  GPIO12 → 电机 3 PWM (RL)
  GPIO13 → 电机 4 PWM (RR)
  GPIO4  → HC-SR04 #1 TRIG (前向)
  GPIO5  → HC-SR04 #1 ECHO
  GPIO6  → HC-SR04 #2 TRIG (下向)
  GPIO7  → HC-SR04 #2 ECHO
  3.3V   → MPU6050 VCC, BMP280 VCC
  5V     → ESP32-CAM VCC, HC-SR04 VCC
  GND    → 公共地
```

### 12.2 千元级组装（~3 小时）

```
步骤 1: F450 机架组装（电机+桨+ESC）（45 min）
步骤 2: 焊接 4合1 ESC 到电机（30 min）
步骤 3: 安装飞控 F405 V3（减震垫）（10 min）
步骤 4: 连接 ESC → 飞控（接线+焊锡）（15 min）
步骤 5: 安装 GPS M8N（机架后臂）（5 min）
步骤 6: 安装罗盘 QMC5883L（远离电机/ESC）（5 min）
步骤 7: 安装 VTX+摄像头（机架前部）（10 min）
步骤 8: 安装接收机 iA6B（机架侧板）（5 min）
步骤 9: 连接 ESP32-S3 伴侣机（飞控 TELEM2 → ESP32 UART）（15 min）
步骤 10: 连接 SX1278 LoRa → ESP32 (SPI)（10 min）
步骤 11: 安装 VL53L0X ToF ×2（前向+下向）（10 min）
步骤 12: 连接电池→配电板（5 min）
步骤 13: ArduPilot 固件烧录+配置（30 min）
步骤 14: 遥控器对频+电调校准+IMU校准（20 min）
步骤 15: NexusSky MAVLink 连接测试（10 min）
```

## 13. 安全与法规注意事项

### 13.1 频率与法规

| 设备 | 频率 | 功率 | 法规要求 |
|------|------|------|----------|
| WiFi ESP-NOW | 2.4 GHz | <20 dBm | 免许可（SRRC 已认证模块） |
| LoRa 433 MHz | 433-434 MHz | <17 dBm | 免许可（微功率短距离） |
| 5.8G 图传 | 5.725-5.850 GHz | 25 mW (14 dBm) | 免许可（<25 mW） |
| 遥控 2.4 GHz | 2.4 GHz | <20 dBm | 免许可（FHSS） |

> ⚠️ **5.8G 图传**：25 mW 在国内合法，但部分国家限制为 25 mW 以下。
> ⚠️ **LoRa 433 MHz**：中国允许 470-510 MHz（LoRa 常用频段），433 MHz 在中国处于灰色地带（工业频段），建议优先使用 470 MHz 模块。

### 13.2 电池安全

| 电池类型 | 注意事项 |
|----------|----------|
| 1S 锂电（百元级） | 充电电压 4.2V，过放 <3.0V 损坏，建议用专用充电座 |
| 4S 锂电（千元级） | 平衡充电，充电电压 16.8V，存储电压 15.2V（长期不用） |
| 通用 | 不短路、不穿刺、不浸水、充电时有人看管、远离易燃物 |

### 13.3 飞行安全

- **百元级**：仅限室内/无风环境，5 m 范围内有人看护
- **千元级**：户外飞行须遵守《民用无人机驾驶员管理规定》
  - 实名登记（250 g 以上须登记）
  - 禁飞区不飞（机场/军事区/人口密集区）
  - 视距内飞行（VFR）
  - 高度 <120 m（400 ft）
- **进阶版**：同千元级，热成像使用须注意隐私法规

## 14. 与成品方案对比

| 方案 | 价格 | 优势 | 劣势 |
|------|------|------|------|
| **百元级 DIY** | ~79 元 | 极低成本、可定制、学习价值 | 续航短、无 GPS、需焊接 |
| Tello EDU | ~800 元 | 开箱即用、SDK 完善、稳定 | 不可改装、闭源、续航 13 min |
| **千元级 DIY** | ~566 元 | GPS+LoRa+MAVLink、可接入 NexusSky | 需组装调试、有学习曲线 |
| DJI Mini 4K | ~2000 元 | 4K 航拍、稳定、开箱即用 | 闭源、不可 Mesh、不可多机协同 |
| **进阶版 DIY** | ~766 元 | 热成像+双频 GPS+全功能 | 组件多、调试复杂 |
| DJI Mavic 3T | ~15000 元 | 640×512 热成像、RTK、全功能 | 闭源、不可多机协同、昂贵 |

> **结论**：千元级 DIY 是 NexusSky 多机协同的最佳性价比方案，
> 以 DJI Mini 4K 1/4 的价格获得 GPS+LoRa Mesh+MAVLink 全接入能力。
> 百元级适合教育/演示，进阶版适合应急救援场景。