# NexusSky 天枢 — 一页纸产品介绍

> **多机协同指挥调度云平台** | 面向无人机集群的数字化协同中间件

---

## ⚠ 当前成熟度（请先读这一段）

**本项目处于 PoC / 私有化交付准备阶段，不对外开源（专有许可，见根目录 `LICENSE`），且不是可直接交付飞行的产品。** 下面几条不是免责套话，
是决定「能不能用」的事实：

1. **真机未验证。** 飞控侧目前由软件模拟器（`drone-sim`）代替。硬件适配层
   （`mavlink-core/.../hardware/`）有 PX4 / ArduPilot 适配器且能建立 UDP 链路，但：
   串口 `serial://` **未实现**（`ArduPilotAdapter.java:94-97` 直接 `return false`，
   需要 jSerialComm 之类的库）；`tcp://` 前缀实际打开的是 **UDP** 套接字
   （`:88`，代码注释 `:81/:87` 已自陈是骨架期用 UDP 顶替）。PX4 SITL 脚本已写好
   但从未运行过。**所有「对接 PX4/ArduPilot」的说法目前只到协议层自洽，不等于飞过真机。**
2. **「边缘 AI 推理」不是模型推理。** 全仓无任何 ML 运行时（ONNX / TensorFlow /
   PyTorch / OpenCV 均无）。感知链路的默认数据源是**模拟器真值**，不是从像素做检测。
   我们做的是确定性的经典算法 + 一条**已就绪的接缝**：可切到真实像素的 BlobDetector，
   或接外部推理服务（`ExternalVisionSource`，出厂关闭）。**接入真实模型是待办，不是现状。**
3. **安防厂商对接是协议层骨架。** ONVIF / 海康 / 大华 / 宇视的协议抽象、REST、前端
   面板都是真的，但三个厂商适配器是**模拟实现**（不发起真实 DHSDK/ONVIF 调用），
   **未做现场联调**。
4. **自主决策是规则与搜索，不是学习。** 集群调度用的是真遗传算法（PMX 交叉 + 锦标赛
   选择 + 精英保留），冲突避免是 4D 常速外推，轨迹预测是卡尔曼滤波——都是扎实的
   经典算法，但没有任何学习成分。
5. **License 门禁的档位绑定已在验证期落地（2026-10-06 收口）。** 2026-10-02 起
   验签失败**拒绝启动**（fail-closed，见 `LicenseService.loadLicense()`）；2026-10-05 起
   模块映射由 `LicenseModuleMap` 全量登记（覆盖 50 个 API 前缀 → 5 个模块），
   未登记前缀按 `MODULE_UNCLASSIFIED` 哨兵**默认拒绝**，由
   `LicenseModuleCoverageTest` 反射扫描 `*Controller.java` 守卫。
   **2026-10-06 补上此前缺失的一环**：`validateLicense` 原先只判 `active` + `expiry`，
   完全不看模块集合——签发侧虽有 `LicenseTier.mismatchOf` 自检，但三条绕过路径
   （原始 `generateLicenseKey` 直传 modules / `LicenseTier` 引入前的旧授权 /
   持有签发密钥的一方构造任意集合）畅通，"某档客户实际能启用哪些模块"仍是约定而非强制。
   现要求模块集合**恰好等于**某档（`LicenseService.tierBindingViolation`，
   由 `aerofleet.license.enforce-tier-binding` 默认 true 控制），守卫为
   `LicenseTierBindingEnforcementTest`（16 例，含超集必须判红、逃生阀必须可用、
   `displayName(null)` 不得 NPE）。**至此三档定价在代码层可执行。**
   **剩余边界**：① ⚠️ **`LicenseTier` 与 `PRODUCT-POSITIONING.md` §5 对"每档含哪些能力"
   的定义不一致，且这是本轮强制绑定让它变成一个真实故障面**——详见下方第 6 条；
   ② 价格表不入代码，仍需人工与 §5 同步；
   ③ 逃生阀 `enforce-tier-binding=false` 若被打开，定价即退回约定，启动会打 WARN。
6. **✅ 定价档位已裁决并落地（2026-10-07，"基础版需要扎实"）**
   - **原矛盾**：`PRODUCT-POSITIONING.md` §5.1 承诺基础版含 **M5 mesh + M9 编排**，
     而 `LicenseTier` 把 mesh 放完整版、M9 放应急版 ⇒ 按 §5 卖基础版，客户付费的
     `/api/v1/mesh/*` 与 M9 编排会被 403（档位绑定在验证期强制后，这是交付事故）。
   - **裁决与落地**：采纳方案 A，但**不是**把 `network`/`emergency` 整块下移
     （那会让基础版连 5G 基站、卫星中继、ONVIF 安防一起白送，应急版与基础版同集合、
     档位梯子塌掉、无法定价）。改为**把两个大模块各拆一半**，
     `ALL_MODULES` 由 5 扩为 7：
     | 档位 | 模块 | 客户拿到什么 |
     |---|---|---|
     | 基础版 | `core`+`fleet`+**`mesh`**+**`orch`** | GCS OEM + 机队作业 + M5 mesh + M9 编排 |
     | 应急版 | 基础版 + `emergency` | + 4a 空地一体化指挥 + ONVIF 安防联动 |
     | 完整版 | 应急版 + `network`+`advanced` | + 基站/卫星中继 + 数字孪生/AI |
   - **守卫**：新增 `LicenseModuleSplitMigrationTest`（7 例）钉死边界 ——
     mesh/orch 必须归基础版、基站卫星与 ONVIF 必须留在上档、三档必须互异、
     每个模块必须有前缀。变异验证：把 `/api/v1/mesh` 改回 `NETWORK` 后 3 例立即转红
     （"拆分做一半"会导致付了钱、license 也认了、请求却 403）。
   - **已知且故意的不兼容**：模块名进签名载荷，拆分**前**签发的授权不再对应任何档位，
     **必须重新签发**。刻意不给旧集合加兼容映射——旧集合里 `network` 含 mesh、
     `emergency` 含 M9 编排，与新边界不再等价，兼容等于把定价边界改回错的那一侧。
   - 逐项对照表与拆分后三档定义见 `PRODUCT-POSITIONING.md` **§5.1.1**（唯一对照入口）。

哪些是**已经扎实**的：MAVLink v1/v2 协议栈（v2 签名有真实 pymavlink 参考实现生成的
已知答案向量逐字节把关）、链路损伤仿真（Gilbert-Elliot + 令牌桶）、机载 failsafe 语义、
RBAC 默认拒绝、审计哈希链，以及 **4374 个后端单测**与 CI 覆盖率门禁。

---

## 是什么？

一套**协议中立（限 MAVLink 一族）**的无人机集群协同中间件：云端设备网关 + 机队注册 +
任务协议 + Web 地面站 + 三个模拟器（飞行 / 链路 / 监管平台）。设计前提是
**「数据源可替换」**——将来接 Pixhawk/PX4 真机时替换的是数据源与传输层，不是架构。

## 核心能力（附实现状态）

| # | 能力 | 实现状态 |
|---|---|---|
| 1 | **集群智能调度** | ✅ 真遗传算法任务分配 + 4D 冲突避免。局限：目标函数是手工加权和，非最优求解 |
| 2 | **应急 mesh 自愈组网** | ✅ AODV-lite 路由真实现。局限：真实卫星接入是**占位**——`StarlinkSatLinkProvider` 等三个类每个方法都抛 `UnsupportedOperationException`，需改用 `SimulatedSatLinkProvider`（此处设计正确：抛错而非返回假数据） |
| 3 | **数字孪生 + 轨迹预测** | ✅ 状态镜像 + 卡尔曼（CV/CA/CT + χ² 置信椭圆）。局限：预测入口只吃单帧快照，滤波的更新环尚未被走通 |
| 4 | **感知与 CV 评测** | ⚠️ **不是 AI 推理**。默认读模拟器真值；真实像素 BlobDetector 与外部推理接缝已就绪但未接模型 |
| 5 | **协议中立** | ✅ `mavlink-core` 可独立复用，v2 签名经外部参考实现验证。自定义消息 2026-10 已搬迁至私有方言段 30000-30063（旧 420-483 段位于官方分配带 300-10000 内且 420/437/440 已实锤冲突；现经 392 条官方方言 msgId 快照核对无冲突） |
| 6 | **合规链路** | ✅ 远程 ID（C2，12900–12915 段，官方值）、电子围栏起飞前拦截、UOM 监管上报对接（对端为模拟器） |

## 技术栈

Java 17 + Spring Boot 3.5 + React 18 + MapLibre + MAVLink v1/v2 + PostgreSQL + Redis
+ Docker + K8s/Helm + Prometheus/Grafana/Loki

## 交付方式

> ✅ **定价口径已统一（2026-10-07 裁决）**：本页与 `docs/PRODUCT-POSITIONING.md` §5
> 的中间件授权档位（20-50 / 50-100 / 100-200 万/年）现已对齐代码强制行为。
> **唯一对照入口是 `PRODUCT-POSITIONING.md` §5.1.1**（档位 ↔ 代码模块逐项对应）。
> 报价前请核对该节，并注意拆分前签发的旧 license 需重新签发。

- **SDK 授权**：协议栈 + 技术支持（档位见 PRODUCT-POSITIONING §5）
- **私有部署**：全模块 + 部署 + 培训
- **行业方案**：平台 + 定制 + 运维

## 适用客户

二线无人机厂商 / 行业解决方案商 / eVTOL 企业 / 应急管理机构。
**共同前提**：客户方需能承担真机对接与现场联调，或与我方联合完成。

## 适合的第一次接触方式

不要约飞行演示——**没有真机**。可演示的是：3 机模拟编队、链路损伤下的任务流自愈、
机载 failsafe 自主返航、合规拦截链、CV 评测接缝。详见 `docs/demo-scenarios.md`。

## 联系方式

GitHub: https://github.com/Levango7/NexusSky
详细能力边界与实现状态见 `docs/PRODUCT-POSITIONING.md`（§1.4 阶段诚实声明）。
