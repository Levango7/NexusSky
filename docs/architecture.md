# NexusSky 系统架构

> **版本**：v1.0 | **日期**：2026-09-17

---

## 一、系统全景图

```mermaid
graph TB
    subgraph "无人机端"
        PX4[PX4 Autopilot]
        AP[ArduPilot]
        CUSTOM[自研飞控]
    end

    subgraph "NexusSky 平台"
        subgraph "协议层"
            MAV[mavlink-core<br/>MAVLink v1/v2 编解码]
        end

        subgraph "云后端 cloud-backend"
            GW[UDP 网关<br/>TelemetryIngestService]
            REG[DeviceRegistry<br/>设备注册+心跳]
            API[REST API<br/>/api/**]
            WS[WebSocket<br/>/ws/**]
            SEC[SecurityConfig<br/>JWT+Spring Security]
            LIC[License 拦截器]
            TEN[多租户隔离]
            M10[M10 集群调度]
            M11[M11 自主决策]
            M12[M12 边缘协同]
            M13[M13 数字孪生]
            M5[M5-M9 应急组网]
            M1[M1-M4 行业应用]
        end

        subgraph "模拟器 drone-sim"
            VD[VirtualDrone<br/>物理引擎 v2]
            ENV[环境气象模型]
            AI[AI 决策引擎]
            EDGE[边缘计算节点]
            TWIN[轨迹预测器]
        end

        subgraph "链路仿真 link-sim"
            IMP[ImpairmentEngine<br/>延迟/丢包/带宽]
            MESH[Mesh 中继]
        end

        subgraph "前端 gcs-web"
            GCS[React 地面站<br/>实时监控+任务管理]
        end
    end

    PX4 -->|MAVLink/UDP| GW
    AP -->|MAVLink/UDP| GW
    CUSTOM -->|MAVLink/UDP| GW

    GW --> MAV
    GW --> REG
    GW --> M10
    GW --> M11
    GW --> M12
    GW --> M13
    GW --> M5
    GW --> M1

    API --> SEC
    API --> LIC
    API --> TEN
    WS --> REG

    GCS -->|REST/WS| API
    GCS -->|WebSocket| WS

    VD --> ENV
    VD --> AI
    VD --> EDGE
    VD --> TWIN
```

---

## 二、数据流图

```mermaid
sequenceDiagram
    participant Drone as 无人机(PX4)
    participant GW as UDP 网关
    participant Ingest as TelemetryIngestService
    participant Reg as DeviceRegistry
    participant WS as WebSocket
    participant GCS as 地面站

    Drone->>GW: MAVLink 帧 (HEARTBEAT)
    GW->>Ingest: decode + route by msgId
    Ingest->>Reg: update DroneSnapshot (sysid)
    Reg->>WS: push snapshot JSON
    WS->>GCS: 实时遥测推送

    Drone->>GW: MAVLink 帧 (GLOBAL_POSITION_INT)
    GW->>Ingest: decode
    Ingest->>Reg: update lat/lon/alt
    Reg->>WS: push position update
    WS->>GCS: 地图位置更新

    GCS->>GW: REST POST /api/mission/upload
    GW->>Drone: MAVLink MISSION_ITEM
    Drone->>GW: MISSION_ACK
    GW->>GCS: 200 OK
```

---

## 三、模块依赖图

```mermaid
graph LR
    subgraph "Maven 多模块"
        POM[pom.xml<br/>父 POM]
        MAV[mavlink-core]
        SIM[drone-sim]
        LINK[link-sim]
        CLOUD[cloud-backend]
        WEB[gcs-web]
    end

    POM --> MAV
    POM --> SIM
    POM --> LINK
    POM --> CLOUD
    POM --> WEB

    SIM --> MAV
    CLOUD --> MAV
    LINK --> MAV
    WEB --> CLOUD
```

---

## 四、部署架构图

### 4.1 单机部署（docker-compose）

```mermaid
graph TB
    subgraph "Docker Host"
        DC[docker-compose]
        CLOUD_C[cloud-backend<br/>:8080]
        SIM_C[drone-sim<br/>:14540]
        WEB_C[gcs-web<br/>:5173]
        LINK_C[link-sim<br/>:14600]
    end

    DC --> CLOUD_C
    DC --> SIM_C
    DC --> WEB_C
    DC --> LINK_C

    WEB_C -->|proxy /api| CLOUD_C
    CLOUD_C -->|UDP 14550| SIM_C
    SIM_C -->|UDP 14540| LINK_C
```

### 4.2 K8s 部署（Helm）

```mermaid
graph TB
    subgraph "K8s Cluster"
        ING[Ingress<br/>nginx]
        DEPLOY_C[Deployment<br/>cloud-backend x2]
        DEPLOY_S[Deployment<br/>drone-sim x1]
        DEPLOY_W[Deployment<br/>gcs-web x2]
        SVC_C[Service<br/>cloud-backend]
        SVC_S[Service<br/>drone-sim]
        SVC_W[Service<br/>gcs-web]
        CM[ConfigMap<br/>application.properties]
        SEC_K[Secret<br/>jwt-secret]
        HPA[HPA<br/>cloud-backend]
    end

    ING --> SVC_W
    ING --> SVC_C
    SVC_C --> DEPLOY_C
    SVC_S --> DEPLOY_S
    SVC_W --> DEPLOY_W
    DEPLOY_C --> CM
    DEPLOY_C --> SEC_K
    DEPLOY_C --> HPA
```

---

## 五、安全架构

```mermaid
graph TB
    REQ[HTTP 请求]
    SEC_F[SecurityFilterChain<br/>Spring Security]
    LIC_F[LicenseInterceptor<br/>License 校验]
    TEN_F[TenantInterceptor<br/>租户上下文]
    RL_F[RateLimitFilter<br/>API 限流]
    CTRL[Controller]
    SVC[Service]
    JWT[JwtTokenProvider<br/>HMAC-SHA256]

    REQ --> SEC_F
    SEC_F -->|dev-mode=true| CTRL
    SEC_F -->|dev-mode=false| JWT
    JWT -->|validate| LIC_F
    LIC_F --> TEN_F
    TEN_F --> RL_F
    RL_F --> CTRL
    CTRL --> SVC
```

---

## 六、MAVLink 消息注册表

| msgId | 消息 | 里程碑 | 说明 |
|---|---|---|---|
| 0-240 | 标准 MAVLink | — | HEARTBEAT/ATTITUDE/GLOBAL_POSITION_INT 等 |
| 420 | LedControlMsg | M1 | 灯光控制 |
| 421-422 | EnvironmentAlert/Status | M0b | 环境气象 |
| 423-426 | Spray/Gripper/Payload | M2 | 喷洒物流 |
| 430-434 | Obstacle/Multispectral/Thermal/Depth/Vision | M3 | 成像增强 |
| 437-441 | Radar/Rotor/LiDAR/IMU | M4 | 硬件抽象 |
| 450-454 | MeshHeartbeat/RouteReq/Reply/Err/Neighbor | M5 | mesh 组网 |
| 455-458 | CellTower/Handover/TerminalRegister | M6 | 移动基站 |
| 459-461 | SatLink/PassSchedule/HierarchicalRoute | M7 | 星地中继 |
| 462-464 | TerrainType/Update/FlightRestriction | M8 | 地形适配 |
| 465-467 | EmergencyMission/Coverage/Priority | M9 | 应急编排 |
| 468-470 | TaskAssignment/ConflictAlert/TaskStatus | M10 | 集群调度 |
| 471-472 | DecisionEvent/AdaptivePath | M11 | 自主决策 |
| 473-474 | EdgeTaskStatus/SensorFusionData | M12 | 边缘计算 |
| 475-476 | TwinStateSync/PredictionResult | M13 | 数字孪生 |