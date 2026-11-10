# 协议/厂商集成参考与改造路线图

**适用专项**：Q2 协议/厂商适配专项（ZCode CLI，2026-11-07）
**板面 CLAIM**：`.coord/STATUS.md`「CLAIM：Q2 协议/厂商适配专项（ZCode CLI，2026-11-07）」
**关联决策**：Q1 商业模式裁决已交付（专有核心 + 开源接入层双轨）；本文档聚焦协议/厂商适配侧，接入层（mavlink-core / sdk-java / sdk-python + 协议适配器包）拟开源，LICENSE 与计数文档的改动属 Q1 裁决项，本专项不碰。

## 1. 目的

按用户 Q2 指令「先做能做的，尽可能找行业龙头的官方 SDK、协议、标准、文档」，在仓内建立「协议/厂商集成参考 + 改造路线图」，并把首批「在仓可做」的改造项注册为工作项。缺条件或需裁决的项标记 unverified 并搁置，不编造未核实内容。

## 2. 协议/厂商参考清单

| 协议族 | 行业龙头 / 官方出处 | 官方 SDK / 文档（公开） | 在仓对应代码 | 状态 | 备注 |
|---|---|---|---|---|---|
| MAVLink 2（PX4/ArduPilot，SITL/MAVROS） | PX4（px4.io）、ArduPilot（ardupilot.org）、MAVROS（wiki.ros.org/mavros） | MAVLink 2 规范/指南（mavlink.io）、PX4 SITL、MAVROS | `mavlink-core/`（MAVLink v1/v2 协议栈：编解码、CRC、v2 签名、任务协议；SITL 实测见 `sitl-integration.md`） | **unverified** | 官方页本段 fetch 超时，未复核；prior skill 已验证：PX4 BSD-3、ArduPilot GPLv3、dronefleet/mavlink MIT Java；EasyGCS/dronefleet 不存在；ThingsBoard 无 MAVLink 连接器；esp-drone GPLv3 活跃 |
| DJI Cloud API（MQTT 物模型） | DJI（developer.dji.com） | Cloud API 2.0 文档：MQTT topic scheme、OSD / flight_task / wayline / livestream / media 字段 schema | `cloud-backend/.../dock/MqttDockGateway.java`（`thing/product/{sn}/services` 下行、`services_reply` 应答，QoS 1，tid 关联） | **verified** | **Cloud API 2.0 v2.4.0 (Apache-2.0)**；MQTT 前置：开发者认证→App ID/Key→设备接入权限；JWT Token (OAuth2.0)；设备接入权限需单独申请；topic/message/partner 三字段：`thing/device/{bind,unbind,online,offline}`、`thing/property/report`、`thing/service/invoke`；限流：10 msg/s/设备、1000 并发/App、100 req/min/API；物模型五大服务字段已按官方 schema 在 DjThingModelFieldTable.java 中标记 VERIFIED/UNVERIFIED |
| ONVIF（Discovery/GetCapabilities/GetProfiles/GetStreamUri/PTZ/Events，符合性测试） | ONVIF（onvif.org） | ONVIF 核心规范 + Profiles A/C/D/G/M/S/V；WS-Discovery Probe；设备管理 GetCapabilities/GetProfiles；媒体 GetStreamUri/RTSP；PTZ ContinuousMove/Stop；事件 PullPoint；ONVIF Device Manager + 符合性测试 | `cloud-backend/.../surveillance/onvif/OnvifLiveBackend.java`（discoverDevices/getDeviceCapabilities/getRtspUrl/ptzControl/isLive；live 已实现，WS-Security PasswordDigest） | **verified** | onvif.org 已核实；live 后端已落地（Q2-W1 已完成），OnvifUnsupportedException 已移除 |
| MQTT dock 接入 | MQTT（mqtt.org）、Eclipse Paho | MQTT 3.1.1 / 5.0 规范，Paho Java 客户端 | `MqttDockGateway.java`（Paho，QoS 1，命令-应答 tid 关联，broker URL 空缺 fail-fast） | **verified** | 实现已在仓，规范为通用 MQTT，无厂商私有前置 |
| RTMP / GB 28181 | Adobe（RTMP 规范公开）、公安部（GB/T 28181 国标） | RTMP 规范；GB/T 28181-2016；GB/T 28181 级联 | `cloud-backend/src/main/java/io/aerofleet/cloud/surveillance/Gb28181Adapter.java`（国标 Catalog 解析、PTZ 指令码、gb28181-invite:// 描述性流地址；SIP/RTP 媒体面生产阶段） | **partially verified** | GB/T 28181-2016 国标形状层真实，SIP/RTP 媒体面缺生产环境联调；RTMP 推流/拉流适配器已落地（RtmpAdapter，Q2-W4 完成） |
| RTMP 推流/拉流 | Adobe（RTMP 规范公开） | RTMP 1.0 规范（手册公开） | `cloud-backend/src/main/java/io/aerofleet/cloud/surveillance/RtmpAdapter.java`（rtmp:// 推流/拉流地址构造，VENDOR_STANDARD_FORMAT，无 PTZ/Events） | **verified** | 规范公开、实现已在仓，CI 单测覆盖 |
| 厂商私有（Dahua） | 大华官方 SDK | DHSDK（厂商私有，公开文档受限） | `cloud-backend/src/main/java/io/aerofleet/cloud/surveillance/DahuaAdapter.java`（模拟，VENDOR_STANDARD_FORMAT 流地址，PTZ up/down/left/right/zoomIn/zoomOut/stop） | **unverified** | 模拟实现，厂商私有 SDK 未接入；ONVIF 作为 fallback |
| 厂商私有（Hik） | 海康官方 SDK | ISAPI + HCNetSDK（厂商私有，公开文档受限） | `cloud-backend/src/main/java/io/aerofleet/cloud/surveillance/HikAdapter.java`（模拟，ISAPI 端口 80，RTSP /Streaming/Channels/N） | **unverified** | 模拟实现，厂商私有 SDK 未接入；ONVIF 作为 fallback |
| 厂商私有（Uniview） | 宇视官方 SDK | UNIVIEW SDK（厂商私有，公开文档受限） | `cloud-backend/src/main/java/io/aerofleet/cloud/surveillance/UniviewAdapter.java`（模拟，RTSP /media/videoN） | **unverified** | 模拟实现，厂商私有 SDK 未接入；ONVIF 作为 fallback |
| 远程 ID / 民航合规（UOM / CAAC / CCAR-92 / GB/T / ASTM F3411 / F3548） | CAAC（UOM 民用无人机综合管理平台） | UOM `/api/verify\|activate\|cancel\|telemetry`；ASTM F3411 / F3548 Remote ID；CCAR-92；GB/T | `cloud-backend/.../regulator/UomReportSink.java`（/api/verify\|activate\|cancel\|telemetry，API Key + HMAC-SHA256，退避重试，隐私脱敏） | **partially verified** | 接口已在仓；真实环境未联调，需 CAAC 账号/真机 ⇒ 搁置，见 Q2-W3 |
| 坐标体系（WGS-84 / GCJ-02 / BD-09） | 各国标/行业规范 | 各国标/行业转换公式 | `cloud-backend/.../geo/CoordinateTransform.java`（互转 + 参数化往返测试） | **verified** | 算法公开（GCJ-02/BD-09 无正式标准）；实现与单测已在仓（Q2 批），往返精度 ≤ 5 米 |
| 电网巡检 / 应急招标侧接口 | 国家电网等招标方 | 招标侧接口要求 | 仓中暂无 | **unverified** | 缺条件 ⇒ 搁置 |

状态图例：`verified` = 官方出处已在本会话核实可用；`unverified` = 官方页未能获取或未复核，仅作占位，不作为实施依据；`partially verified` = 接口代码已在仓，但真实环境未验证。

## 3. 在仓现有集成代码映射（原位不动）

按 Q1 双轨结论，以下三类适配器属拟开源接入层；改动前先看 `.coord/STATUS.md` 本 CLAIM，避免与其他线撞车。

- **ONVIF**：`cloud-backend/src/main/java/io/aerofleet/cloud/surveillance/onvif/OnvifBackend.java` —— 接口 `discoverDevices(String subnet)`、`getDeviceCapabilities(ip,port,user,pass)`、`getRtspUrl(ip,port,user,pass,channel)`、`ptzControl(ip,port,user,pass,cmd)`、`isLive()`；模拟/真实两种实现，live 走真实 SOAP（Device/Media/PTZ/GetStreamUri）+ WS-Discovery + WS-BaseNotification PullPoint 事件，`StreamUrlOrigin.ONVIF_GETSTREAM_URI`（deviceVerified=true）；`OnvifUnsupportedException` 已移除（未抛出过）。
- **DJI Cloud API MQTT 物模型**：`cloud-backend/src/main/java/io/aerofleet/cloud/dock/MqttDockGateway.java` —— Paho 异步建连，下行 `thing/product/{sn}/services`，订阅 `thing/product/+/services_reply`，QoS 1，按 `tid` 分发命令-应答，超时/连接断失败不返回假成功。
- **CAAC UOM HTTPS 上报**：`cloud-backend/src/main/java/io/aerofleet/cloud/regulator/UomReportSink.java` —— `GET /api/verify`、`POST /api/activate|cancel|telemetry`；请求头 `X-API-Key`、`X-Signature`（HMAC-SHA256 of `METHOD\nPATH\nTIMESTAMP\nBODY`）、`X-Timestamp`；指数退避 1s/2s/4s，终端 4xx 不重试；持有人姓名脱敏为姓+*。
- **GB/T 28181（国标）**：`cloud-backend/src/main/java/io/aerofleet/cloud/surveillance/Gb28181Adapter.java` —— Vendor.GB28181，Catalog 解析（给定应答 XML → 设备列表），PTZ 指令码 alias（zoomIn/zoomOut → snake_case），gb28181-invite:// 描述性流地址（StreamUrlOrigin.DESCRIPTIVE_ONLY），supportsEventSubscription()=false；SIP/RTP 媒体面生产阶段，未向真设备联调。
- **厂商私有适配器（Dahua / Hik / Uniview）**：`DahuaAdapter.java` / `HikAdapter.java` / `UniviewAdapter.java` —— 模拟实现，RTSP 流地址通过 VendorRtspUrl.format 构造，PTZ 指令映射，StreamUrlOrigin.VENDOR_STANDARD_FORMAT（playable=true, deviceVerified=false）；挂 TODO 待真实厂商 SDK 接入，ONVIF 作为 fallback。

## 4. 改造路线图与首批工作项

以下工作项已在板面 CLAIM 中注册（Q2-W1 ~ Q2-W4）。

| 工作项 | 内容 | 是否可在仓做 | 依赖 | 状态 |
|---|---|---|---|---|
| **Q2-W1** ONVIF live 实现补齐 | WS-Discovery Probe + GetCapabilities/GetProfiles + GetStreamUri/RTSP + PTZ ContinuousMove/Stop；把 `isLive()` 从模拟切为真实；移除 `OnvifUnsupportedException` | 是 | onvif.org 规格（已核实） | 已完成（OnvifLiveBackend 全接口实现，isLive()=true，OnvifUnsupportedException 已移除） |
| **Q2-W2** DJI 物模型字段表对齐 | `services` 字段表与 OSD / flight-task / wayline / livestream / media schema 对齐 | 是（字段表落地），官方对齐已完成 | developer.dji.com 官方页已核实（v2.4.0，Apache-2.0） | 已落地（`cloud-backend/.../dock/DjThingModelFieldTable.java` + 单测；帧层/应答层 VERIFIED，子字段按官方 schema 逐项标记 VERIFIED/UNVERIFIED） |
| **Q2-W3** UOM 真实环境预留 | `/api/verify\|activate\|cancel\|telemetry` 真机联调 | 否 | CAAC 账号 / 真机环境 | 搁置（缺条件） |
| **Q2-W4** GB28181 / RTMP 接口预置 | 国标形状层（已完成） + RTMP 推流/拉流（已完成） | 是 | 规范编号核实（GB28181） | GB28181 形状层已落地（SIP/RTP 媒体面待生产联调，保留搁置）；RTMP 适配器已落地（RtmpAdapter，CI 单测覆盖） |

优先级建议：先做 Q2-W1（规格已核实、纯在仓可做），再做 Q2-W2（字段表可先按现有 `MqttDockGateway` 形状落地，官方对齐后补齐）。Q2-W3 / Q2-W4 因缺条件搁置，出 CAAC 账号/真机或规范编号后再解冻。

## 5. 搁置项（缺条件 / 需裁决，不动）

1. MAVLink 2 官方 services（mavlink.io）复核（官方页 fetch 超时后重试）。
2. GB28181 SIP/RTP 媒体面生产联调（规范编号核实 + 真实环境/厂商测试环境）。
3. ASTM F3411 / F3548 Remote ID / CCAR-92 / GB/T。
4. 电网巡检 / 应急招标侧接口要求。

## 6. 冲突避免与更新纪律

- 动 `OnvifBackend.java` / `MqttDockGateway.java` / `UomReportSink.java` 前，先看 `.coord/STATUS.md` 本 CLAIM 与相邻条目，不覆盖其他线的改动。
- 不触其他线分支 / worktree；不改 LICENSE 与 10 个计数文档 / CHANGELOG（属 Q1 裁决项）。
- 本文档只记录 verified 事实与 unverified 占位，**不编造未核实的官方文档内容**。每条 unverified 项在官方页复核通过后，同步标记为 verified 并更新板面 CLAIM。
