package io.aerofleet.sim;

import io.aerofleet.mavlink.MavlinkFrame;
import io.aerofleet.mavlink.enums.MavEnums;
import io.aerofleet.mavlink.messages.Attitude;
import io.aerofleet.mavlink.messages.CommandAck;
import io.aerofleet.mavlink.messages.CommandLong;
import io.aerofleet.mavlink.messages.GlobalPositionInt;
import io.aerofleet.mavlink.messages.GpsRawInt;
import io.aerofleet.mavlink.messages.Heartbeat;
import io.aerofleet.mavlink.messages.HomePosition;
import io.aerofleet.mavlink.messages.LedControlMsg;
import io.aerofleet.mavlink.messages.MavlinkMessage;
import io.aerofleet.mavlink.messages.MissionAckMsg;
import io.aerofleet.mavlink.messages.MissionCountMsg;
import io.aerofleet.mavlink.messages.MissionCurrent;
import io.aerofleet.mavlink.messages.MissionItemInt;
import io.aerofleet.mavlink.messages.MissionRequestInt;
import io.aerofleet.mavlink.messages.Statustext;
import io.aerofleet.mavlink.messages.RadioStatus;
import io.aerofleet.mavlink.messages.SysStatus;
import io.aerofleet.mavlink.messages.SystemTimeMsg;
import io.aerofleet.mavlink.messages.VfrHud;
import io.aerofleet.mavlink.transport.UdpMavlinkTransport;
import io.aerofleet.mavlink.messages.EnvironmentStatus;
import io.aerofleet.mavlink.messages.ObstacleReportMsg;
import io.aerofleet.mavlink.messages.RadarScanMsg;
import io.aerofleet.mavlink.messages.RadarTargetMsg;
import io.aerofleet.mavlink.messages.RotorTelemetryMsg;
import io.aerofleet.mavlink.messages.LidarDataMsg;
import io.aerofleet.mavlink.messages.ImuDataMsg;
import io.aerofleet.mavlink.messages.MeshHeartbeatMsg;
import io.aerofleet.mavlink.messages.MeshRouteRequestMsg;
import io.aerofleet.mavlink.messages.MeshRouteReplyMsg;
import io.aerofleet.mavlink.messages.MeshRouteErrorMsg;
import io.aerofleet.mavlink.enums.ScanMode;
import io.aerofleet.sim.mesh.MeshRouter;
import io.aerofleet.sim.orch.OrchestrationEngine;
import io.aerofleet.sim.satrelay.SatRelayEngine;

import java.io.IOException;
import java.net.SocketAddress;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * The virtual drone: wires UDP MAVLink transport, Mission Protocol server logic,
 * COMMAND_LONG handling, and the 20 Hz physics/telemetry tick loop together.
 *
 * Frame listeners run on the transport receive thread; the tick loop runs on a
 * scheduler thread. Shared state is guarded by the intrinsic lock of this object.
 */
public final class VirtualDrone implements AutoCloseable {

    public static final int COMPONENT_ID = 1;      // MAV_COMP_ID_AUTOPILOT
    public static final long TICK_MS = 50;          // 20 Hz
    public static final int GCS_SYSID = 255;        // MAV_SYSTEM_GCS
    /** 默认无人机质量（kg，气动模型用）。 */
    private static final double DRONE_MASS_KG = 1.5;

    private final SimConfig config;
    private final UdpMavlinkTransport transport;
    private final MissionStore missions;
    private final DronePhysics physics;
    private final ScheduledExecutorService scheduler;
    private final AtomicInteger frameSeq = new AtomicInteger(0);
    private final long bootUnixMs = System.currentTimeMillis();
    /** Fault-injection controller (inert when --scenario none). */
    private final ScenarioController scenario;
    /** Autopilot failsafe layer (link loss / battery crit / GPS loss). */
    private final FailsafeController failsafe;
    /** Analytic terrain (flat world when --terrain absent). */
    private final TerrainModel terrain;
    /** RF link geometry (E1): RSSI vs distance + terrain occlusion. */
    private final RadioEnvironment radio;
    /** Geofence polygon + ceiling (disabled when --fence absent). */
    private final GeoFence fence;
    /** Synthetic ground-target world (empty when --targets absent). */
    private final TargetSimulator groundTargets;
    /** Ground-truth HTTP sidecar (null when --http-port absent). */
    private final TargetStateServer truthServer;
    /** Mapping camera + gimbal (P3 imaging chain). */
    private final CameraModel camera = CameraModel.defaultMappingCamera();
    /** Recent shot metadata ring (served over the truth HTTP). */
    private final java.util.ArrayDeque<CameraModel.Shot> shots = new java.util.ArrayDeque<>();
    private static final int MAX_SHOTS = 50;
    /** Rendered JPEG bytes per frameSeq (E2: real image payload). */
    private final java.util.Map<Long, byte[]> shotJpegs = new java.util.concurrent.ConcurrentHashMap<>();
    private static final int MAX_JPEGS = 50;
    /** Set once when the GPS-loss event fires, cleared on recovery. */
    private boolean gpsLossAnnounced = false;
    /** Epoch ms of the last received GCS packet (drives the datalink failsafe). */
    private long lastGcsRxMs = 0;
    /** 灯光状态（FR-12）：volatile 保证接收线程写与 tick 线程读可见性。 */
    private volatile LedState ledState = LedState.off();
    /**
     * 环境气象模型（M0b，FR-01~34）：null 当 !config.envEnabled（既有行为不变，DFX 4.5）。
     * 由构造器创建，tickOnce 每 tick 调 evolve + 风注入 + 温度因子 + 告警，1Hz 调 sendEnvironmentStatus。
     */
    private final EnvironmentModel envModel;
    /** 环境模型启用标志（config.envEnabled 的快照，避免 tickOnce 每次读 config）。 */
    private final boolean envEnabled;
    /**
     * P2-2: 复用的风向量数组，避免 tickOnce 每 tick 分配 new double[2]。
     * tickOnce 在 synchronized(this) 内由 tick 线程独占访问，无需额外同步。
     */
    private final double[] reusableScenarioWind = new double[2];
    private final double[] reusableEnvWind = new double[2];
    /**
     * M3 感知成像增强数据源（FR-04/FR-12/FR-14）：null 表示未注入，不产生感知上报（DFX 4.5）。
     * 由 setter 注入（供 e2e 脚本/配置注入），tickOnce 5Hz 分频调用 obstacleDetector.detect()。
     */
    private DepthSource depthSource = null;
    private ObstacleDetector obstacleDetector = null;
    /** 避障启用标志（volatile 保证接收线程写与 tick 线程读可见性）。 */
    private volatile boolean obstacleEnabled = false;
    /**
     * M4 硬件抽象数据源（FR-01/FR-07/FR-12/FR-15）：null 表示未注入，不产生硬件上报（DFX 4.5）。
     * 由 setter 注入（供 e2e 脚本/配置注入），tickOnce 按各自频率分频调用。
     */
    // volatile 保证 setter 线程写与 tickOnce 线程读的可见性（M4 代码审查 #1）
    private volatile PhasedArrayRadar radar = null;
    private volatile RadarScanConfig radarConfig = null;
    private volatile RotorAerodynamics rotorAero = null;
    private volatile RotorConfig rotorConfig = null;
    private volatile LiDARSource lidarSource = null;
    private volatile ImuSource imuSource = null;
    /** 热成像数据源（FR-04）：null 表示未注入，不产生热成像上报（DFX 4.5）。 */
    private volatile ThermalSource thermalSource = null;
    /** 丐版模式快照（config.budgetMode）：null=完整版，TOY/STANDARD/ADVANCED/EMERGENCY_TOY/EMERGENCY_STANDARD=降级模式。 */
    private final BudgetMode budgetMode;
    /**
     * 丐版超声波传感器（budget toy/standard 模式自动实例化，null 表示未启用）。
     * 作为 {@link DepthSource} 的丐版实现，驱动 {@link #obstacleDetector} 避障。
     */
    private final UltrasonicSource ultrasonicSource;
    /**
     * 丐版红外阵列热源（budget toy/standard 模式自动实例化，null 表示未启用）。
     * 作为 {@link ThermalSource} 的丐版实现，替代高端热成像相机。
     */
    private final BudgetThermalSource budgetThermalSource;
    /**
     * 丐版光流定位数据源（budget toy 模式自动实例化，null 表示未启用）。
     * 用于无 GPS 环境下的位置估计，替代 LiDAR 定位。
     */
    private final OpticalFlowSource opticalFlowSource;
    /** 雷达启用标志（volatile 保证接收线程写与 tick 线程读可见性）。 */
    private volatile boolean radarEnabled = false;
    /**
     * 物理模型切换（FR-10/FR-37）："kinematics"=运动学（默认，既有行为不变），
     * "aero"=气动模型。volatile 保证接收线程写与 tick 线程读可见性。
     */
    private volatile String physicsModel = "kinematics";
    /**
     * M4 代码审查 #8：最近一次气动计算结果缓存。
     * tickOnce 物理模型切换块调 rotorAero.compute() 后存于此，
     * sendRotorTelemetry 5Hz 复用此缓存而非重新计算。volatile 保证 tick 线程写与遥测线程读可见性。
     */
    private volatile RotorAerodynamics.RotorAeroResult lastAeroResult = null;
    /** 雷达扫描分频计数器（按 scanPeriodMs 周期触发）。 */
    private long lastRadarScanMs = 0;
    /**
     * M2 喷洒泵（FR-07~FR-11/FR-16~FR-18）：null 当 !config.actuatorsEnabled（既有行为不变，DFX 4.5）。
     * 由构造器创建，tickOnce 每 tick 调 sprayPump.tick(dt)，2Hz 调 sendSprayStatus()。
     */
    private final SprayPump sprayPump;
    /**
     * M2 抛投器（FR-19~FR-21）：null 当 !config.actuatorsEnabled（既有行为不变，DFX 4.5）。
     * 由构造器创建，tickOnce 每 tick 调 gripper.tick(dt)，1Hz 调 sendPayloadStatus()。
     */
    private final Gripper gripper;
    /** 执行机构启用标志（config.actuatorsEnabled 的快照，避免 tickOnce 每次读 config）。 */
    private final boolean actuatorsEnabled;
    /** 药量低告警去重（FR-09，warning/critical 各触发一次）。 */
    private boolean sprayLowWarned = false;
    private boolean sprayLowCriticalWarned = false;
    /** 侧风禁喷告警去重（FR-17，触发一次后等恢复后重新去重）。 */
    private boolean crosswindWarned = false;
    /**
     * M5 应急 mesh 路由引擎（FR-01~30）：null 当 !config.meshEnabled（既有行为不变，DFX 4.5）。
     * 由构造器创建，tickOnce 每 tick 调 meshRouter.tick()，在 onFrame 中分发 mesh 消息。
     */
    private final MeshRouter meshRouter;
    /** mesh 路由启用标志（config.meshEnabled 的快照，避免 tickOnce 每次读 config）。 */
    private final boolean meshEnabled;
    /**
     * M7 星-空-地多层级中继引擎（FR-5.1~5.5）：null 当 !config.satRelayEnabled（既有行为不变，DFX 4.5）。
     * 由构造器创建，tickOnce 每 tick 调 satRelayEngine.tick()，产出 459/460/461 消息。
     */
    private final SatRelayEngine satRelayEngine;
    /** sat-relay 引擎启用标志（config.satRelayEnabled 的快照，避免 tickOnce 每次读 config）。 */
    private final boolean satRelayEnabled;
    /**
     * M8 复杂地形适配引擎（FR-01~33）：null 当 !config.terrainAdaptEnabled（既有行为不变，DFX 4.5）。
     * 由构造器创建，terrainGrid 可由外部建图后注入。
     */
    private final io.aerofleet.sim.terrain.TerrainGrid terrainGrid;
    private final io.aerofleet.sim.terrain.EnhancedRadioEnvironment enhancedRadio;
    private final io.aerofleet.sim.terrain.FlightConstraintChecker flightConstraintChecker;
    private final io.aerofleet.sim.terrain.TerrainChangeMonitor terrainChangeMonitor;
    /** 地形适配启用标志（config.terrainAdaptEnabled 的快照）。 */
    private final boolean terrainAdaptEnabled;
    /**
     * M6 移动基站载荷（FR-CT-01~06）：null 当 !config.cellTowerConfig.enabled（既有行为不变，DFX 4.5）。
     * 由构造器创建，tickOnce 每 tick 调 cellTower.tick()，1Hz 调 sendCellTowerStatus()。
     */
    private final io.aerofleet.sim.celltower.CellTowerPayload cellTower;
    /** 基站载荷启用标志（config.cellTowerConfig.enabled 的快照）。 */
    private final boolean celltowerEnabled;
    /** 基站状态广播分频时间戳。 */
    private volatile long lastCellTowerStatusMs = 0;
    /**
     * M9 应急任务编排引擎（FR-01~33）：null 当 !config.orchConfig.enabled（既有行为不变，DFX 4.5）。
     * 由构造器创建，tickOnce 每 tick 调 orchEngine.tick() 驱动持续服务阶段。
     * 集成接口传 null，表示 M5-M8 模块未直接连接，实际集成通过 Cloud API 和 MAVLink 消息完成。
     */
    private OrchestrationEngine orchEngine;

    // guarded-by-this flight state
    private FlightState state = FlightState.INIT;
    private String lastStatus = null;
    private double holdSecondsLeft = 0;
    private int currentSeq = 0;                    // mission item being flown
    private int lastAnnouncedSeq = -1;
    private boolean missionNotifiedComplete = false;
    private boolean batteryWarned = false;

    public VirtualDrone(SimConfig config) throws IOException {
        this.config = config;
        this.transport = new UdpMavlinkTransport(config.bindIp, config.port);
        this.missions = new MissionStore(config.sysid);
        this.physics = new DronePhysics(config.lat, config.lon, 0.0, config.speed);
        this.scenario = new ScenarioController(config.scenario);
        this.failsafe = config.failsafe
                ? FailsafeController.defaults() : new FailsafeController(false, 0, 0);
        this.terrain = TerrainModel.parse(config.terrain);
        this.fence = GeoFence.parse(config.fence);
        // RF link geometry (E1): GCS mast at home, 1.5 m antenna. RSSI is
        // range-correct and terrain-shadowed; reported via RADIO_STATUS.
        this.radio = new RadioEnvironment(this.terrain, 0, 0, 1.5);
        this.groundTargets = TargetSimulator.parse(config.lat, config.lon, config.targets);
        this.truthPort = config.httpPort;
        if (config.httpPort > 0) {
            this.truthServer = new TargetStateServer(groundTargets, config.httpPort,
                    () -> {
                        // Snapshot under the same lock the tick loop uses.
                        synchronized (this) {
                            return new java.util.ArrayList<>(shots);
                        }
                    },
                    this::currentRssiDbm,
                    shotJpegs::get);
            try {
                this.truthServer.start();
            } catch (java.io.IOException e) {
                SimLog.warn("ground-truth HTTP failed to start on port "
                        + config.httpPort + ": " + e.getMessage());
                throw new RuntimeException(e);
            }
        } else {
            this.truthServer = null;
        }
        SimLog.info("terrain: " + terrain.summary() + " | fence: " + fence.summary()
                + " | targets: " + groundTargets.size());
        this.lastGoodLat = config.lat;
        this.lastGoodLon = config.lon;
        // M0b 环境气象模型创建（FR-01）：config.envEnabled 时创建，否则 null（DFX 4.5 既有行为不变）
        if (config.envEnabled) {
            EnvironmentSource source = new SimulatedEnvSource(
                    EnvScenario.of(config.envScenario), config.envSeed);
            EnvAlertEngine alertEngine = new EnvAlertEngine(EnvThresholds.defaults(), 5000);
            this.envModel = new EnvironmentModel(source, alertEngine, config.envSeed);
            this.envEnabled = true;
            SimLog.info("env model enabled: scenario=" + config.envScenario
                    + " seed=" + config.envSeed);
        } else {
            this.envModel = null;
            this.envEnabled = false;
        }
        // M2 执行机构创建（FR-01）：config.actuatorsEnabled 时创建 SprayPump + Gripper，否则 null（DFX 4.5 既有行为不变）
        if (config.actuatorsEnabled) {
            // SprayPump 注入 physics（速度耦合）+ envModel（漂移补偿，可为 null）
            this.sprayPump = new SprayPump(
                    config.sprayCapacity,       // L
                    config.sprayRateMax,        // mL/s
                    5.0,                        // 喷幅 m（默认 5，可后续由任务参数覆盖）
                    config.sprayCrosswindMax,   // m/s
                    5.0,                        // 参考速度 m/s
                    3.0,                        // 参考风速 m/s
                    physics,
                    envModel);
            this.gripper = new Gripper(config.gripperPayloadMax);
            this.actuatorsEnabled = true;
            SimLog.info("actuators enabled: spray-capacity=" + config.sprayCapacity
                    + "L spray-rate-max=" + config.sprayRateMax + "mL/s"
                    + " gripper-payload-max=" + config.gripperPayloadMax + "kg"
                    + " spray-crosswind-max=" + config.sprayCrosswindMax + "m/s");
        } else {
            this.sprayPump = null;
            this.gripper = null;
            this.actuatorsEnabled = false;
        }
        // M5 mesh 路由引擎创建（FR-01）：config.meshEnabled 时创建，否则 null（DFX 4.5 既有行为不变）
        if (config.meshEnabled) {
            this.meshRouter = new MeshRouter(config.sysid, config.meshRouterConfig, transport);
            this.meshEnabled = true;
            SimLog.info("mesh router enabled: sysid=" + config.sysid
                    + " config=" + config.meshRouterConfig);
        } else {
            this.meshRouter = null;
            this.meshEnabled = false;
        }
        // M7 sat-relay 引擎创建（FR-5.1）：config.satRelayEnabled 时创建，否则 null（DFX 4.5 既有行为不变）
        if (config.satRelayEnabled) {
            this.satRelayEngine = new SatRelayEngine(config.sysid, config.satRelayConfig,
                    transport, config.meshRouterConfig.cloudBackendAddress);
            this.satRelayEnabled = true;
            SimLog.info("sat-relay engine enabled: sysid=" + config.sysid
                    + " config=" + config.satRelayConfig);
        } else {
            this.satRelayEngine = null;
            this.satRelayEnabled = false;
        }
        // M8 地形适配引擎创建（FR-01）：config.terrainAdaptEnabled 时创建，否则 null（DFX 4.5 既有行为不变）
        if (config.terrainAdaptEnabled) {
            this.terrainAdaptEnabled = true;
            this.terrainGrid = null;  // 由外部建图后注入
            this.enhancedRadio = new io.aerofleet.sim.terrain.EnhancedRadioEnvironment(
                    this.radio, null, io.aerofleet.sim.terrain.EnhancedRadioEnvironment.DEFAULT_FREQ_MHZ,
                    config.lat, config.lon, 1.5);
            this.flightConstraintChecker = null;  // 由外部注入限飞区后创建
            this.terrainChangeMonitor = null;  // 由外部注入地形图后创建
            SimLog.info("terrain adapt enabled: grid-resolution=" + config.terrainGridResolution + "m");
        } else {
            this.terrainAdaptEnabled = false;
            this.terrainGrid = null;
            this.enhancedRadio = null;
            this.flightConstraintChecker = null;
            this.terrainChangeMonitor = null;
        }
        // M6 移动基站载荷创建（FR-CT-01）：config.cellTowerConfig.enabled 时创建，否则 null（DFX 4.5 既有行为不变）
        if (config.cellTowerConfig != null && config.cellTowerConfig.enabled) {
            io.aerofleet.sim.celltower.CellTowerSimConfig ctCfg = config.cellTowerConfig;
            this.cellTower = io.aerofleet.sim.celltower.CellTowerFactory.create(
                    ctCfg.cellType, config.sysid, ctCfg.txPowerDbm, ctCfg.maxTerminals,
                    ctCfg.frequencyChannel, this.radio, this.terrain,
                    ctCfg.signalThresholdDbm, ctCfg.loadBalanceThreshold, ctCfg.heartbeatTimeoutMs);
            this.celltowerEnabled = true;
            SimLog.info("celltower enabled: sysid=" + config.sysid + " config=" + ctCfg);
        } else {
            this.cellTower = null;
            this.celltowerEnabled = false;
        }
        // M9 应急任务编排引擎创建（FR-01）：config.orchConfig.enabled 时创建，否则 null（DFX 4.5 既有行为不变）
        if (config.orchConfig.enabled) {
            this.orchEngine = new OrchestrationEngine(config.orchConfig, null, null, null, null);
            // 集成接口传 null，表示 M5-M8 模块未直接连接
            // 实际集成通过 Cloud API 和 MAVLink 消息完成
            SimLog.info("orchestration engine enabled: sysid=" + config.sysid
                    + " config=" + config.orchConfig);
        } else {
            this.orchEngine = null;
        }
        // 丐版模式处理（budget）：根据 budgetMode 实例化丐版传感器并自动装配避障/热成像
        // DFX 4.5：null 或 ADVANCED 时既有行为不变（不实例化丐版传感器）
        this.budgetMode = config.budgetMode;
        if (BudgetMode.TOY == budgetMode) {
            // toy 模式（百元级）：超声波避障 + 红外阵列热源 + 光流定位
            // 替代雷达/LiDAR/高端热成像，不使用 GPS/多光谱
            this.ultrasonicSource = new UltrasonicSource();
            this.budgetThermalSource = new BudgetThermalSource();
            this.opticalFlowSource = new OpticalFlowSource();
            // 自动装配：ObstacleDetector 使用超声波作为 DepthSource
            // safety=2.0m, emergency=0.5m（适配超声波 4m 量程）
            this.depthSource = this.ultrasonicSource;
            this.obstacleDetector = new ObstacleDetector(this.ultrasonicSource, 2.0, 0.5);
            this.thermalSource = this.budgetThermalSource;
            SimLog.info("Budget mode: toy (ultrasonic+opticalflow+budget-thermal, no radar/lidar)");
        } else if (BudgetMode.STANDARD == budgetMode) {
            // standard 模式（千元级）：超声波作为 DepthSource 补充（双冗余避障）+ 红外阵列热源
            // 保留 GPS + LoRa（既有通信链路不变）
            this.ultrasonicSource = new UltrasonicSource();
            this.budgetThermalSource = new BudgetThermalSource();
            this.opticalFlowSource = null;
            // 自动装配：ObstacleDetector 使用超声波作为 DepthSource
            // 外部可通过 setDepthSource 注入高端 DepthSource 作为补充（双冗余）
            this.depthSource = this.ultrasonicSource;
            this.obstacleDetector = new ObstacleDetector(this.ultrasonicSource, 2.0, 0.5);
            this.thermalSource = this.budgetThermalSource;
            SimLog.info("Budget mode: standard (ultrasonic+budget-thermal+GPS+LoRa)");
        } else if (BudgetMode.ADVANCED == budgetMode) {
            // advanced 模式：使用全部高端传感器（既有行为不变，DFX 4.5）
            this.ultrasonicSource = null;
            this.budgetThermalSource = null;
            this.opticalFlowSource = null;
            SimLog.info("Budget mode: advanced (all sensors)");
        } else {
            // null = 完整版（既有行为不变，DFX 4.5）
            this.ultrasonicSource = null;
            this.budgetThermalSource = null;
            this.opticalFlowSource = null;
        }
        this.scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "drone-sim-tick");
            t.setDaemon(true);
            return t;
        });
        transport.addFrameListener(this::onFrame);
    }

    /** Start the tick loop; first tick immediately marks STANDBY. */
    public synchronized void start() {
        this.state = FlightState.STANDBY;
        // M5 mesh 路由引擎启动（FR-09）
        if (meshEnabled) {
            meshRouter.start();
        }
        // M7 sat-relay 引擎启动（FR-5.1）
        if (satRelayEnabled) {
            satRelayEngine.start();
        }
        scheduler.scheduleAtFixedRate(this::tickSafe, TICK_MS, TICK_MS, TimeUnit.MILLISECONDS);
    }

    @Override
    public void close() {
        // M5 mesh 路由引擎关闭（FR-22a 主动退出：发 RERR + 停 HELLO）
        if (meshEnabled) {
            meshRouter.close();
        }
        // M7 sat-relay 引擎关闭
        if (satRelayEnabled) {
            satRelayEngine.close();
        }
        scheduler.shutdownNow();
        // P0: 关闭 ground-truth HTTP sidecar，避免端口和线程泄漏
        if (truthServer != null) {
            truthServer.stop();
        }
        transport.close();
    }

    // ------------------------------------------------------------------
    // inbound MAVLink frames
    // ------------------------------------------------------------------

    private void onFrame(MavlinkFrame frame) {
        // Link-loss scenario black-holes BOTH directions, like a real radio
        // link going down: inbound GCS packets are dropped too, so the
        // datalink failsafe (silence timer) sees exactly what a real vehicle
        // would see. Without this the 1Hz GCS heartbeat would keep the
        // failsafe quiet while only telemetry was black-holed.
        double bootSec = (System.currentTimeMillis() - bootUnixMs) / 1000.0;
        if (scenario.linkLost(bootSec)) {
            return;
        }
        // Any GCS packet (heartbeat, mission traffic, commands) counts as
        // "the datalink is alive" for the failsafe layer.
        lastGcsRxMs = System.currentTimeMillis();
        // Accept frames from any GCS sysid; decode only messages we act on.
        MavlinkMessage msg;
        try {
            msg = MavlinkMessage.decode(frame);
        } catch (RuntimeException e) {
            return; // malformed payload for its type: drop quietly
        }
        if (msg == null) {
            return;
        }
        try {
            synchronized (this) {
                if (msg instanceof MissionCountMsg mc) {
                    handleMissionCount(mc);
                } else if (msg instanceof MissionItemInt item) {
                    handleMissionItem(item);
                } else if (msg instanceof io.aerofleet.mavlink.messages.MissionRequestList rl) {
                    handleMissionRequestList(rl, frame.getSystemId());
                } else if (msg instanceof io.aerofleet.mavlink.messages.MissionRequestInt mr) {
                    // Download direction: GCS asks for stored item #seq.
                    handleMissionItemRequest(mr, frame.getSystemId());
                } else if (msg instanceof CommandLong cmd) {
                    handleCommandLong(cmd, frame.getSystemId());
                } else if (msg instanceof io.aerofleet.mavlink.messages.ManualControl mc) {
                    handleManualControl(mc);
                } else if (msg instanceof Heartbeat) {
                    // A GCS heartbeat means somebody is listening: push home once.
                    onFirstPeerSeen();
                } else if (msg instanceof LedControlMsg led) {
                    // FR-12 灯光控制消息：更新 ledState + 回 COMMAND_ACK
                    handleLedControl(led);
                }
                // M5 mesh 路由消息分发（msgId 450-453，FR-01~22a）
                // 注意 MeshNeighborTableMsg(454) 不在 drone-sim 内部处理（仅发送不接收）
                if (meshEnabled) {
                    java.net.InetSocketAddress meshSrcAddr =
                            (java.net.InetSocketAddress) transport.getLastPeer();
                    int meshRssi = (int) Math.round(currentRssiDbm());
                    if (msg instanceof MeshHeartbeatMsg mhb) {
                        meshRouter.onMeshHeartbeat(mhb, meshSrcAddr, meshRssi);
                    } else if (msg instanceof MeshRouteRequestMsg mrq) {
                        meshRouter.onRouteRequest(mrq, meshSrcAddr, meshRssi, frame.getSystemId());
                    } else if (msg instanceof MeshRouteReplyMsg mrp) {
                        meshRouter.onRouteReply(mrp, meshSrcAddr, meshRssi, frame.getSystemId());
                    } else if (msg instanceof MeshRouteErrorMsg mer) {
                        meshRouter.onRouteError(mer, meshSrcAddr, frame.getSystemId());
                    }
                }
                // M6 移动基站载荷消息分发（msgId 456-458，FR-CT-05 / FR-TERM-06 / FR-HO-03）
                if (celltowerEnabled) {
                    if (msg instanceof io.aerofleet.mavlink.messages.CellTowerConfigMsg ctc) {
                        handleCellTowerConfig(ctc, frame.getSystemId());
                    } else if (msg instanceof io.aerofleet.mavlink.messages.GroundTerminalRegisterMsg gtr) {
                        handleGroundTerminalRegister(gtr);
                    } else if (msg instanceof io.aerofleet.mavlink.messages.CellHandoverMsg chm) {
                        handleCellHandover(chm);
                    }
                }
            }
        } catch (IOException e) {
            SimLog.error("send failed", e);
        }
    }

    // ------------------------------------------------------------------
    // Mission Protocol (server role)
    // ------------------------------------------------------------------

    /** GCS requested stored item #seq during a download: serve it. */
    private void handleMissionItemRequest(
            io.aerofleet.mavlink.messages.MissionRequestInt mr, int gcsSysid)
            throws IOException {
        if (missions.isUploading()) {
            // An upload session is active on this link: a REQUEST_INT arriving
            // now belongs to it (loss recovery re-request), not a download.
            return;
        }
        MissionItemInt item = missions.get(mr.seq);
        if (item != null) {
            // Re-target the stored item at the requesting GCS.
            send(new MissionItemInt(gcsSysid, COMPONENT_ID, item.seq, item.frame,
                    item.command, item.current, item.autocontinue,
                    item.param1, item.param2, item.param3, item.param4,
                    item.x, item.y, item.z, item.missionType));
        } else {
            // Unknown seq: end the transfer with an error ack.
            send(new MissionAckMsg(gcsSysid, COMPONENT_ID,
                    MavEnums.MAV_MISSION_INVALID, mr.missionType, 0));
        }
    }

    /**
     * GCS wants to READ the stored mission (mission download): reply with a
     * MISSION_COUNT of what we have; the GCS then requests items by seq.
     */
    private void handleMissionRequestList(io.aerofleet.mavlink.messages.MissionRequestList rl,
                                          int gcsSysid) throws IOException {
        if (state == FlightState.CRASHED) {
            send(new MissionAckMsg(gcsSysid, COMPONENT_ID,
                    MavEnums.MAV_MISSION_DENIED, rl.missionType, 0));
            return;
        }
        int count = missions.hasMission() ? missions.size() : 0;
        SimLog.info("MISSION_REQUEST_LIST: replying count=" + count);
        send(new MissionCountMsg(count, gcsSysid, COMPONENT_ID, rl.missionType, 0));
    }

    private void handleMissionCount(MissionCountMsg mc) throws IOException {
        // Mission upload is allowed both before and after arming (PX4 behavior).
        if (mc.count <= 0) {
            SimLog.info("MISSION_COUNT 0 received: clearing mission");
            missions.abortUpload();
            send(new MissionAckMsg(config.sysid, COMPONENT_ID,
                    MavEnums.MAV_MISSION_ACCEPTED, mc.missionType, 0));
            return;
        }
        int first = missions.beginUpload(mc.count);
        SimLog.info("MISSION_COUNT received: " + mc.count + " items, requesting seq 0");
        if (first < 0) {
            send(new MissionAckMsg(config.sysid, COMPONENT_ID,
                    MavEnums.MAV_MISSION_NO_SPACE, mc.missionType, 0));
            missions.abortUpload();
            return;
        }
        lastUploadProgressMs = System.currentTimeMillis();
        sendMissionRequest(first, mc.missionType);
    }

    /** Loss recovery: while an upload session is open, re-request the current
     *  seq every 3s without progress (a GCS behind a lossy link resends items). */
    private void reRequestIfStalled() throws IOException {
        if (!missions.isUploading()) {
            return;
        }
        long now = System.currentTimeMillis();
        if (now - lastUploadProgressMs > 3_000) {
            int want = missions.nextExpectedSeq();
            SimLog.info("upload stalled, re-requesting seq " + want);
            sendMissionRequest(want, 0);
            lastUploadProgressMs = now;
        }
    }

    private long lastUploadProgressMs = 0;

    private void handleMissionItem(MissionItemInt item) throws IOException {
        int next = missions.onItem(item);
        lastUploadProgressMs = System.currentTimeMillis();
        if (next < 0) {
            // All items received: accept and store.
            SimLog.info("mission upload complete: " + missions.size() + " items stored");
            SimLog.info(missions.summary());
            send(new MissionAckMsg(config.sysid, COMPONENT_ID,
                    MavEnums.MAV_MISSION_ACCEPTED, 0, 0));
            missions.commit();
            missionNotifiedComplete = false;
            pushStatus(MavEnums.MAV_SEVERITY_INFO,
                    "Mission uploaded: " + missions.size() + " waypoints");
        } else {
            if (item.seq != next) {
                SimLog.info("duplicate/out-of-order item seq=" + item.seq
                        + ", re-requesting seq=" + next);
            }
            sendMissionRequest(next, 0);
        }
    }

    private void sendMissionRequest(int seq, int missionType) throws IOException {
        send(new MissionRequestInt(seq, config.sysid, COMPONENT_ID, missionType));
    }

    // ------------------------------------------------------------------
    // COMMAND_LONG handling
    // ------------------------------------------------------------------

    private void handleCommandLong(CommandLong cmd, int senderSysid) throws IOException {
        int result;
        switch (cmd.command) {
            case MavEnums.MAV_CMD_COMPONENT_ARM_DISARM -> result = handleArmDisarm(cmd);
            case MavEnums.MAV_CMD_NAV_TAKEOFF -> result = handleTakeoff(cmd);
            case MavEnums.MAV_CMD_MISSION_START -> result = handleMissionStart();
            case MavEnums.MAV_CMD_NAV_RETURN_TO_LAUNCH -> result = handleRtl();
            case MavEnums.MAV_CMD_NAV_LAND -> {
                // Not required, but easy: descend in place from wherever we are.
                result = handleLand();
            }
            case io.aerofleet.mavlink.enums.MavEnums.MAV_CMD_IMAGE_START_CAPTURE -> {
                // Camera trigger: capture a shot of the synthetic world.
                result = handleImageCapture(senderSysid);
            }
            case io.aerofleet.mavlink.enums.MavEnums.MAV_CMD_REQUEST_CAMERA_INFORMATION -> {
                sendCameraInformation(senderSysid);
                result = MavEnums.MAV_RESULT_ACCEPTED;
            }
            case io.aerofleet.mavlink.enums.MavEnums.MAV_CMD_REQUEST_CAMERA_SETTINGS -> {
                sendCameraSettings(senderSysid);
                result = MavEnums.MAV_RESULT_ACCEPTED;
            }
            case io.aerofleet.mavlink.enums.MavEnums.MAV_CMD_REQUEST_CAMERA_CAPTURE_STATUS -> {
                sendCameraCaptureStatus(senderSysid);
                result = MavEnums.MAV_RESULT_ACCEPTED;
            }
            case io.aerofleet.mavlink.enums.MavEnums.MAV_CMD_REQUEST_MESSAGE -> {
                // Generic once-shot request: param1 names the message id.
                int wanted = Math.round(cmd.param1);
                if (wanted == io.aerofleet.mavlink.messages.CameraInformation.ID) {
                    sendCameraInformation(senderSysid);
                    result = MavEnums.MAV_RESULT_ACCEPTED;
                } else if (wanted == io.aerofleet.mavlink.messages.CameraSettings.ID) {
                    sendCameraSettings(senderSysid);
                    result = MavEnums.MAV_RESULT_ACCEPTED;
                } else if (wanted == io.aerofleet.mavlink.messages.CameraCaptureStatus.ID) {
                    sendCameraCaptureStatus(senderSysid);
                    result = MavEnums.MAV_RESULT_ACCEPTED;
                } else {
                    SimLog.info("REQUEST_MESSAGE for unsupported id " + wanted);
                    result = MavEnums.MAV_RESULT_UNSUPPORTED;
                }
            }
            // M0b 环境配置命令（FR-28）：310=set-wind / 311=set-weather / 312=set-thresholds
            case 310 -> result = handleEnvSetWind(cmd, senderSysid);
            case 311 -> result = handleEnvSetWeather(cmd, senderSysid);
            case 312 -> result = handleEnvSetThresholds(cmd, senderSysid);
            // M2 喷洒/抛投控制命令（FR-14/FR-20/FR-21，spec.md §4.3 命令 id 分配）
            case 320 -> result = handleSprayControl(cmd, senderSysid);
            case 321 -> result = handleGripperControl(cmd, senderSysid);
            case 322 -> result = handlePayloadQuery(senderSysid);
            // M4 硬件配置命令（FR-03/FR-26，420=radar config / 421=rotor config）
            case 420 -> result = handleRadarConfig(cmd, senderSysid);
            case 421 -> result = handleRotorConfigCmd(cmd, senderSysid);
            default -> {
                SimLog.info("unsupported command " + cmd.command);
                result = MavEnums.MAV_RESULT_UNSUPPORTED;
            }
        }
        send(new CommandAck(cmd.command, result, 255, 0, senderSysid, 0));
    }

    /**
     * MAV_CMD_IMAGE_START_CAPTURE: "take a photo" - project the synthetic
     * world through the current drone pose into the image plane and store
     * the shot metadata. Accepted only in flight (like a real camera that
     * cannot shoot from the ground). Every successful shot BROADCASTS a
     * CAMERA_IMAGE_CAPTURED (the protocol's authoritative photo event).
     */
    private int handleImageCapture(int gcsSysid) throws java.io.IOException {
        if (!state.armed()) {
            return MavEnums.MAV_RESULT_DENIED;
        }
        CameraModel.Shot shot = camera.capture(physics.north(), physics.east(),
                physics.alt(), physics.rollRad(), physics.pitchRad(), physics.yawRad(),
                groundTargets, config.lat, config.lon);
        // E4: a photo costs energy (camera + gimbal + storage load).
        physics.drainForPhoto();
        // Debug line kept: one line per shot is cheap and this chain (sim pose
        // -> projection -> detected targets) is the one thing e2e cannot
        // inspect any other way.
        SimLog.info(String.format(
                "IMAGE inputs: drone n=%.2f e=%.2f alt=%.2f r=%.3f p=%.3f y=%.3f; %d targets in world (first: %s)",
                physics.north(), physics.east(), physics.alt(),
                Math.toDegrees(physics.rollRad()), Math.toDegrees(physics.pitchRad()),
                Math.toDegrees(physics.yawRad()),
                groundTargets.size(),
                groundTargets.isEmpty() ? "none"
                        : String.format("n=%.2f e=%.2f",
                                groundTargets.listTargets().get(0).north,
                                groundTargets.listTargets().get(0).east)));
        shots.add(shot);
        while (shots.size() > MAX_SHOTS) {
            shots.removeFirst();
        }
        // E2: render the shot to a real JPEG so the detector can eat pixels.
        byte[] jpeg = ShotImageWriter.render(shot);
        shotJpegs.put(shot.frameSeq, jpeg);
        while (shotJpegs.size() > MAX_JPEGS) {
            shotJpegs.keySet().stream().min(Long::compareTo).ifPresent(shotJpegs::remove);
        }
        broadcastImageCaptured(shot);
        SimLog.info("IMAGE captured: frame " + shot.frameSeq + ", "
                + shot.targets.size() + " target(s) in frame");
        return MavEnums.MAV_RESULT_ACCEPTED;
    }

    // ------------------------------------------------------------------
    // Camera Protocol v2 server role
    // ------------------------------------------------------------------

    /** Camera identity: AeroFleet synthetic mapping camera. */
    private void sendCameraInformation(int gcsSysid) throws java.io.IOException {
        send(new io.aerofleet.mavlink.messages.CameraInformation(
                physics.bootMillis(),
                0x01000001L,                     // v1.0.0
                3.5f, 5.6f, 3.15f,               // focal/sensor from the 90deg FOV model
                0x03FFL,                          // captures images + video-ish flags
                camera.imageWidth, camera.imageHeight,
                0, "AeroFleet", "SIM-CAM-90", 0,
                "", 0, 0));
    }

    private void sendCameraSettings(int gcsSysid) throws java.io.IOException {
        send(new io.aerofleet.mavlink.messages.CameraSettings(
                physics.bootMillis(), 0, 0f, 0f, 0));
    }

    private void sendCameraCaptureStatus(int gcsSysid) throws java.io.IOException {
        send(new io.aerofleet.mavlink.messages.CameraCaptureStatus(
                physics.bootMillis(), 0f, 0L, 1024.0f,
                0, 0, (int) camera.frameCount(), 0));
    }

    /**
     * Broadcast the photo event after each capture: position, attitude
     * quaternion, image index, and the truth-HTTP URL as the "file".
     */
    private void broadcastImageCaptured(CameraModel.Shot shot) throws java.io.IOException {
        double[] ll = TargetSimulator.neToLatLonStatic(config.lat, config.lon,
                physics.north(), physics.east());
        send(new io.aerofleet.mavlink.messages.CameraImageCaptured(
                shot.timeMs * 1000L,
                physics.bootMillis(),
                (int) Math.round(ll[0] * 1e7),
                (int) Math.round(ll[1] * 1e7),
                0,                                 // MSL alt: ground is 0 in the sim world
                (int) Math.round(shot.altM * 1000),
                attitudeQuaternion(),
                (int) shot.frameSeq,
                0, 1,                              // camera 0, capture ok
                "http://127.0.0.1:" + truthPort + "/camera/shots"));
    }

    /** RPY (aerospace ZYX, radians) -> quaternion (w, x, y, z). */
    private float[] attitudeQuaternion() {
        double cr = Math.cos(physics.rollRad() / 2), sr = Math.sin(physics.rollRad() / 2);
        double cp = Math.cos(physics.pitchRad() / 2), sp = Math.sin(physics.pitchRad() / 2);
        double cy = Math.cos(physics.yawRad() / 2), sy = Math.sin(physics.yawRad() / 2);
        return new float[]{
                (float) (cr * cp * cy + sr * sp * sy),
                (float) (sr * cp * cy - cr * sp * sy),
                (float) (cr * sp * cy + sr * cp * sy),
                (float) (cr * cp * sy - sr * sp * cy)};
    }

    /** Truth-HTTP port for file URLs (0 when the sidecar is off). */
    private int truthPort;

    private int handleArmDisarm(CommandLong cmd) {
        // A crashed vehicle rejects everything until a process restart.
        if (state == FlightState.CRASHED) {
            pushStatus(MavEnums.MAV_SEVERITY_ERROR, "Vehicle crashed: reboot required");
            return MavEnums.MAV_RESULT_DENIED;
        }
        boolean wantArm = cmd.param1 > 0.5f;
        if (wantArm) {
            if (state.armed()) {
                return MavEnums.MAV_RESULT_ACCEPTED; // idempotent
            }
            state = FlightState.ARMED;
            SimLog.info("vehicle ARMED");
            pushStatus(MavEnums.MAV_SEVERITY_INFO, "Armed");
            return MavEnums.MAV_RESULT_ACCEPTED;
        }
        // disarm
        if (!state.armed()) {
            return MavEnums.MAV_RESULT_ACCEPTED; // already disarmed
        }
        if (isFlying()) {
            SimLog.warn("disarm denied while flying (alt=" + String.format("%.1f", physics.alt()) + "m)");
            pushStatus(MavEnums.MAV_SEVERITY_WARNING, "Disarm denied: vehicle is flying");
            return MavEnums.MAV_RESULT_DENIED;
        }
        state = FlightState.STANDBY;
        SimLog.info("vehicle DISARMED");
        pushStatus(MavEnums.MAV_SEVERITY_INFO, "Disarmed");
        return MavEnums.MAV_RESULT_ACCEPTED;
    }

    private int handleTakeoff(CommandLong cmd) {
        if (!state.armed()) {
            SimLog.warn("takeoff rejected: not armed");
            return MavEnums.MAV_RESULT_DENIED;
        }
        float targetAlt = cmd.param7;
        if (targetAlt <= 0) {
            targetAlt = 10.0f; // sensible default if GCS sends 0
        }
        physics.holdAt(targetAlt);
        // Stay in ARMED (hover climb) unless already in mission.
        if (state == FlightState.ARMED) {
            pushStatus(MavEnums.MAV_SEVERITY_INFO,
                    "Takeoff to " + String.format("%.1f", (double) targetAlt) + "m");
            SimLog.info("takeoff: climbing to " + targetAlt + " m");
        }
        return MavEnums.MAV_RESULT_ACCEPTED;
    }

    private int handleMissionStart() {
        if (!state.armed()) {
            SimLog.warn("mission start rejected: not armed");
            pushStatus(MavEnums.MAV_SEVERITY_WARNING, "Mission start denied: not armed");
            return MavEnums.MAV_RESULT_DENIED;
        }
        if (!missions.hasMission()) {
            SimLog.warn("mission start rejected: no mission");
            pushStatus(MavEnums.MAV_SEVERITY_WARNING, "Mission start denied: no mission");
            return MavEnums.MAV_RESULT_DENIED;
        }
        state = FlightState.MISSION;
        currentSeq = 0;
        lastAnnouncedSeq = -1;
        holdSecondsLeft = 0;
        missionNotifiedComplete = false;
        beginCurrentLeg();
        SimLog.info("mission START: flying " + missions.size() + " items");
        pushStatus(MavEnums.MAV_SEVERITY_INFO, "Mission started");
        return MavEnums.MAV_RESULT_ACCEPTED;
    }

    private int handleRtl() {
        if (!state.armed()) {
            SimLog.warn("RTL rejected: not armed");
            return MavEnums.MAV_RESULT_DENIED;
        }
        startRtl();
        return MavEnums.MAV_RESULT_ACCEPTED;
    }

    private int handleLand() {
        if (!state.armed()) {
            return MavEnums.MAV_RESULT_DENIED;
        }
        physics.holdAt(0.0);
        return MavEnums.MAV_RESULT_ACCEPTED;
    }

    // ------------------------------------------------------------------
    // flight control helpers
    // ------------------------------------------------------------------

    /** Enter RTL: climb to a safe altitude if needed, then fly home and land. */
    private void startRtl() {
        state = FlightState.RTL;
        double rtlAlt = Math.max(physics.alt(), 15.0);
        physics.setTarget(0, 0, rtlAlt);
        SimLog.info("RTL: returning home (target alt " + String.format("%.1f", rtlAlt) + "m)");
        pushStatus(MavEnums.MAV_SEVERITY_INFO, "Return to launch");
    }

    /** Mission leg initialization: set physics target from the current item. */
    private void beginCurrentLeg() {
        MissionItemInt item = missions.get(currentSeq);
        if (item == null) {
            SimLog.warn("mission item " + currentSeq + " missing, finishing mission");
            finishMission();
            return;
        }
        switch (item.command) {
            case MavEnums.MAV_CMD_NAV_TAKEOFF -> {
                // Hold position climb to item z.
                physics.holdAt(Math.max(item.z, 0.1));
                SimLog.info("wp " + item.seq + ": TAKEOFF to " + item.z + "m");
            }
            case MavEnums.MAV_CMD_NAV_WAYPOINT -> {
                double tn = GeoUtil.north(config.lat, config.lon, item.lat(), item.lon());
                double te = GeoUtil.east(config.lat, config.lon, item.lat(), item.lon());
                physics.setTarget(tn, te, Math.max(item.z, 0.0));
                SimLog.info("wp " + item.seq + ": fly to lat=" + item.lat()
                        + " lon=" + item.lon() + " alt=" + item.z + "m");
            }
            case MavEnums.MAV_CMD_NAV_RETURN_TO_LAUNCH -> {
                startRtl();
            }
            case MavEnums.MAV_CMD_NAV_LAND -> {
                double tn = GeoUtil.north(config.lat, config.lon, item.lat(), item.lon());
                double te = GeoUtil.east(config.lat, config.lon, item.lat(), item.lon());
                physics.setTarget(tn, te, 0.0);
            }
            case MavEnums.MAV_CMD_IMAGE_START_CAPTURE -> {
                // In-mission camera trigger (an orbit waypoint's photo item):
                // shoot immediately on reaching this item.
                try {
                    handleImageCapture(0);
                } catch (java.io.IOException e) {
                    SimLog.warn("in-mission capture failed: " + e.getMessage());
                }
                SimLog.info("wp " + item.seq + ": camera triggered in mission");
            }
            default -> {
                // Non-nav item (jump, delay...): no motion target; the tick
                // loop will announce it once and advance immediately.
                SimLog.info("wp " + item.seq + ": non-nav command " + item.command + ", skipping");
            }
        }
    }


    private void finishMission() {
        missionNotifiedComplete = true;
        SimLog.info("mission complete");
        pushStatus(MavEnums.MAV_SEVERITY_INFO, "Mission complete");
        startRtl();
    }

    // ------------------------------------------------------------------
    // 20 Hz tick loop
    // ------------------------------------------------------------------

    private void tickSafe() {
        try {
            synchronized (this) {
                tickOnce();
            }
        } catch (Throwable t) {
            SimLog.error("tick failed", t);
        }
    }

    private void tickOnce() throws IOException {
        // P1-opt: 缓存一次时间戳，避免同一 tick 内多次 JNI 调用 System.currentTimeMillis()。
        long nowMs = System.currentTimeMillis();
        double dt = TICK_MS / 1000.0;
        double bootSec = (nowMs - bootUnixMs) / 1000.0;

        // Fault injection: wind pushes the vehicle; link loss silences ALL
        // outbound MAVLink (telemetry black-hole, exactly like a lost radio).
        // P2-2: 写入复用数组，避免每 tick 分配 new double[2]。
        scenario.windVector(bootSec, reusableScenarioWind);
        if (reusableScenarioWind[0] != 0 || reusableScenarioWind[1] != 0) {
            physics.applyWind(dt, reusableScenarioWind[0], reusableScenarioWind[1]);
        }

        // M0b 环境气象注入（FR-08/09/11/23）：envModel 启用时叠加环境风 + 温度因子 + 告警双通道下传。
        // 与场景风独立叠加（applyWind 被调两次，残差泄漏 0.25 不变）；null 时跳过（DFX 4.5）。
        if (envModel != null && envEnabled) {
            envModel.evolve(dt);
            envModel.windVector(reusableEnvWind);
            physics.applyWind(dt, reusableEnvWind[0], reusableEnvWind[1]);
            physics.setTempDrainFactor(envModel.tempDrainFactor());
            // 告警双通道下传（FR-23）：EnvironmentAlert 消息 + STATUSTEXT（pushStatus 复用）
            for (EnvAlert a : envModel.checkAlerts()) {
                send(a.toMessage());
                pushStatus(a.severity(), a.text());
            }
        }

        // M4 物理模型切换（FR-10/FR-37）：
        // model=aero 时调 rotorAero.compute() → applyAeroThrust() 积分；
        // model=kinematics 时调 physics.tick(dt)（既有，FR-37）。
        // 气动模型异常 try-catch → 回退 physics.tick(dt) + WARN 日志（异常 5.2.2）。
        // M4 代码审查 #8：计算结果缓存到 lastAeroResult，供 sendRotorTelemetry 5Hz 复用。
        if ("aero".equals(physicsModel) && rotorAero != null && rotorConfig != null) {
            try {
                RotorAerodynamics.RotorAeroResult aeroResult = rotorAero.compute(
                        rotorConfig, new RotorAerodynamics.FlightState(
                                DRONE_MASS_KG, physics.vz(), physics.groundSpeed(),
                                physics.pitchRad(), physics.rollRad()));
                lastAeroResult = aeroResult;
                applyAeroThrust(aeroResult, dt);
            } catch (Exception e) {
                SimLog.warn("aero model failed, fallback to kinematics: " + e.getMessage());
                lastAeroResult = null;
                physics.tick(dt);
            }
        } else {
            physics.tick(dt);
        }
        advanceStateMachine(dt);
        reRequestIfStalled();

        // M2 执行机构 tick 驱动（FR-01/FR-07/FR-19）：actuatorsEnabled 时每个 tick 驱动 SprayPump + Gripper。
        // 在物理 tick 后调用，SprayPump 读取 physics.groundSpeed() 做流量耦合（FR-10）。
        if (actuatorsEnabled) {
            sprayPump.tick(dt);
            gripper.tick(dt);
            // 药量告警去重（FR-09）：warning 15% / critical 5% 各触发一次
            checkSprayChemicalAlerts();
            // 侧风禁喷告警去重（FR-17）
            checkCrosswindAlert();
        }

        // The synthetic-target world moves with the same clock as the drone.
        if (!groundTargets.isEmpty()) {
            groundTargets.tick(dt);
        }

        // Ground collision: terminal state. Checked before anything else -
        // a wreck must stop all further flight logic immediately.
        if (state != FlightState.CRASHED
                && terrain.collided(physics.north(), physics.east(), physics.alt())) {
            state = FlightState.CRASHED;
            physics.clearTarget();
            SimLog.error("CRASHED: terrain collision at n=" + physics.north()
                    + " e=" + physics.east() + " alt=" + physics.alt());
            pushStatus(MavEnums.MAV_SEVERITY_EMERGENCY, "Terrain collision - vehicle crashed");
        }

        // Geofence: leaving the polygon or busting the ceiling triggers the
        // fence failsafe (PX4 GF_ACTION=RTL default).
        if (state.armed() && state != FlightState.CRASHED) {
            GeoFence.Violation v = fence.violationAt(
                    physics.north(), physics.east(), physics.alt());
            if (v != null && !fenceViolationActive) {
                fenceViolationActive = true;
                SimLog.warn("FAILSAFE: geofence violation (" + v + ") -> RTL");
                pushStatus(MavEnums.MAV_SEVERITY_CRITICAL,
                        "Geofence violation (" + v + "), returning home");
                startRtl();
            } else if (v == null) {
                fenceViolationActive = false;
            }
        }

        // Autopilot failsafe evaluation (before the link black-hole so the
        // vehicle reacts even while the GCS sees nothing).
        runFailsafe();

        // Manual-stick timeout: revert to position hold when sticks go quiet.
        runManualTimeout();

        // M5 mesh 路由引擎 tick 驱动（FR-09/11/21 周期驱动）：
        // meshEnabled 时每个 tick 调 meshRouter.tick()，并更新本节点位置/电量供 HELLO 组装。
        if (meshEnabled) {
            meshRouter.updateState(
                    (int) Math.round(physics.lat() * 1e7),
                    (int) Math.round(physics.lon() * 1e7),
                    (int) Math.round(physics.alt() * 1000),
                    physics.batteryRemainingPct());
            meshRouter.tick(nowMs);
        }

        // M7 sat-relay 引擎 tick 驱动（FR-5.1 周期驱动）：
        // satRelayEnabled 时每个 tick 调 satRelayEngine.tick()，更新地面点位置供可见性计算。
        if (satRelayEnabled) {
            satRelayEngine.updateGroundPosition(physics.lat(), physics.lon());
            satRelayEngine.tick(nowMs);
        }

        // M6 移动基站载荷 tick 驱动（FR-CT-01 / FR-NFR-PERF-04）：
        // celltowerEnabled 时每个 tick 调 cellTower.tick()，更新位姿，1Hz 调 sendCellTowerStatus()。
        // 异常 try-catch 隔离，不影响 tick 主循环（FR-NFR-REL-01）。
        if (celltowerEnabled) {
            try {
                cellTower.updatePosition(
                        (int) Math.round(physics.lat() * 1e7),
                        (int) Math.round(physics.lon() * 1e7),
                        (int) Math.round(physics.alt() * 1000));
                cellTower.tick(nowMs);
                // 1Hz 状态广播

                if (nowMs - lastCellTowerStatusMs >= 1000) {
                    lastCellTowerStatusMs = nowMs;
                    sendCellTowerStatus();
                }
            } catch (Exception e) {
                SimLog.warn("celltower tick failed: " + e.getMessage());
            }
        }

        if (scenario.linkLost(bootSec)) {
            // Black-hole: skip every outbound message while the link is down.
            // Heartbeat watchdog on the cloud side must flag the drone offline.
            return;
        }
        telemetryRates(dt);

        // GPS loss: degrade the reported fix and warn once per outage.
        boolean gpsLost = scenario.gpsLost(bootSec);
        if (gpsLost && !gpsLossAnnounced) {
            gpsLossAnnounced = true;
            SimLog.warn("GPS fix lost (scenario)");
            pushStatus(MavEnums.MAV_SEVERITY_CRITICAL, "GPS fix lost");
        } else if (!gpsLost && gpsLossAnnounced) {
            gpsLossAnnounced = false;
            SimLog.info("GPS fix restored (scenario)");
            pushStatus(MavEnums.MAV_SEVERITY_NOTICE, "GPS fix restored");
        }
        checkBattery();

        // M9 应急任务编排引擎 tick 驱动（FR-01 周期驱动）：
        // orchEngine 启用时每个 tick 调 orchEngine.tick()，驱动持续服务阶段超时检查。
        if (orchEngine != null) {
            orchEngine.tick(nowMs);
        }
    }

    /** True while the scenario black-holes the link (telemetry senders consult this). */
    private boolean linkDown() {
        return scenario.linkLost((System.currentTimeMillis() - bootUnixMs) / 1000.0);
    }

    // ------------------------------------------------------------------
    // autopilot failsafe layer
    // ------------------------------------------------------------------

    /**
     * Evaluate the failsafe triggers once per tick and apply the reaction.
     * Pilot commands received over the (still working) link override the
     * failsafe state until the next trigger edge.
     */
    private void runFailsafe() {
        double bootSec = (System.currentTimeMillis() - bootUnixMs) / 1000.0;
        int batteryPct = physics.batteryRemainingPct();
        if (scenario.batteryFault(bootSec)) {
            batteryPct = (int) scenario.batteryFaultPct(bootSec);
        }
        boolean gpsLost = scenario.gpsLost(bootSec);
        FailsafeController.Action action = failsafe.evaluate(
                state, lastGcsRxMs, System.currentTimeMillis(), batteryPct, gpsLost);
        switch (action) {
            case ENTER_RTL -> {
                // Remember where the mission stood so MISSION_CURRENT keeps
                // reporting a sensible seq after the failsafe RTL.
                if (state == FlightState.MISSION) {
                    missionNotifiedComplete = false;
                }
                SimLog.warn("FAILSAFE: datalink/battery critical -> RTL");
                pushStatus(MavEnums.MAV_SEVERITY_CRITICAL, "Failsafe RTL engaged");
                startRtl();
            }
            case ENTER_HOLD -> {
                // Hover at the current spot; the pre-emergency state is
                // remembered so the mission can resume when GPS returns.
                resumeAfterHold = state;
                SimLog.warn("FAILSAFE: GPS lost -> HOLD (hover)");
                pushStatus(MavEnums.MAV_SEVERITY_CRITICAL, "Failsafe: GPS lost, holding");
                physics.holdAt(physics.alt());
                state = FlightState.HOLD;
            }
            case RESUME_MISSION -> {
                FlightState back = resumeAfterHold;
                resumeAfterHold = null;
                if (back == FlightState.MISSION && missions.hasMission()) {
                    SimLog.info("FAILSAFE: GPS restored -> resume mission at seq " + currentSeq);
                    pushStatus(MavEnums.MAV_SEVERITY_NOTICE, "GPS restored, resuming mission");
                    state = FlightState.MISSION;
                    beginCurrentLeg();
                } else {
                    // Was armed-hover before the loss: return to plain ARMED.
                    SimLog.info("FAILSAFE: GPS restored -> back to ARMED");
                    state = FlightState.ARMED;
                }
            }
            case NONE -> { /* nothing to do */ }
        }
    }

    /** State to return to when a HOLD (GPS-loss failsafe) ends. */
    private FlightState resumeAfterHold;

    /** Last MANUAL_CONTROL arrival; 2s of silence reverts to position hold. */
    private long lastManualRxMs;

    /** Geofence violation edge flag (announce/RTL once per violation). */
    private boolean fenceViolationActive;

    /**
     * Virtual joystick: axes -1000..1000 (z: throttle 0..1000). Valid while
     * armed and not in an autopilot mode (MISSION/RTL/HOLD take precedence -
     * exactly like a PX4 mode switch).
     */
    private void handleManualControl(io.aerofleet.mavlink.messages.ManualControl mc) {
        lastManualRxMs = System.currentTimeMillis();
        if (state == FlightState.CRASHED) {
            return;
        }
        if (!state.armed()) {
            return; // sticks on the ground do nothing (like a real FC)
        }
        if (state == FlightState.MISSION || state == FlightState.RTL
                || state == FlightState.HOLD) {
            return; // autopilot modes own the vehicle
        }
        if (state == FlightState.ARMED) {
            state = FlightState.MANUAL;
            pushStatus(MavEnums.MAV_SEVERITY_NOTICE, "Manual control active");
            SimLog.info("MANUAL control active");
        }
        // Axis mapping (m/s and rad/s), ~8 m/s full deflection like cruise.
        double fwd = (mc.y / 1000.0) * 8.0;
        double right = (mc.x / 1000.0) * 8.0;
        double climb = ((mc.z - 500) / 500.0) * 3.0;
        double yawRate = -(mc.r / 1000.0) * 1.8;
        physics.setManualVelocity(fwd, right, climb, yawRate);
    }

    /** Revert to hover when the sticks go quiet (radio-like RC timeout). */
    private void runManualTimeout() {
        if (state == FlightState.MANUAL
                && System.currentTimeMillis() - lastManualRxMs > 2000) {
            state = FlightState.ARMED;
            physics.clearManual();
            physics.clearTarget();
            pushStatus(MavEnums.MAV_SEVERITY_WARNING, "Manual input timeout - position hold");
            SimLog.info("MANUAL timeout: position hold");
        }
    }

    private void advanceStateMachine(double dt) {
        switch (state) {
            case STANDBY, INIT, ARMED -> {
                // Hover targets are managed by commands; nothing periodic to do.
            }
            case MISSION -> advanceMission(dt);
            case RTL -> advanceRtl();
            case HOLD -> {
                // Hovering: position hold is handled by physics.holdAt; the
                // failsafe layer moves us out of HOLD when the fix returns.
            }
            default -> {
            }
        }
    }

    private void advanceMission(double dt) {
        MissionItemInt item = missions.get(currentSeq);
        if (item == null) {
            finishMission();
            return;
        }
        // Phase 1: hold at the waypoint for param1 seconds after arrival.
        if (holdSecondsLeft > 0) {
            holdSecondsLeft -= dt;
            return;
        }
        // Phase 2: wait until physics reaches this item's target.
        if (physics.hasTarget() && !physics.targetReached()) {
            return;
        }
        // Phase 3: waypoint reached (or instant item): announce once, then advance.
        if (item.seq != lastAnnouncedSeq) {
            lastAnnouncedSeq = item.seq;
            boolean needsHold = item.command == MavEnums.MAV_CMD_NAV_WAYPOINT && item.param1 > 0;
            if (needsHold) {
                holdSecondsLeft = item.param1;
                SimLog.info("wp " + item.seq + " reached, holding " + item.param1 + "s");
            } else {
                SimLog.info("wp " + item.seq + " reached");
            }
            pushStatus(MavEnums.MAV_SEVERITY_INFO, "Waypoint " + item.seq + " reached");
            if (needsHold) {
                return; // hold at the spot, next pass will consume holdSecondsLeft
            }
        }
        if (currentSeq + 1 < missions.size()) {
            currentSeq++;
            beginCurrentLeg();
        } else {
            finishMission();
        }
    }

    private void advanceRtl() {
        if (physics.alt() > ACCEPT_NEAR_GROUND) {
            // Still airborne: target is home at safe altitude, then descend.
            if (physics.targetReached()) {
                if (physics.alt() > RTL_DESCEND_SWITCH) {
                    // Arrived above home: start descending.
                    physics.holdAt(0.0);
                    SimLog.info("RTL: arrived home, descending");
                } else {
                    physics.setTarget(0, 0, 0.0);
                }
            }
        } else {
            // On the ground: mission over, disarm.
            state = FlightState.STANDBY;
            physics.clearTarget();
            SimLog.info("RTL: landed at home, disarm");
            pushStatus(MavEnums.MAV_SEVERITY_INFO, "Landed at home, disarmed");
        }
    }

    /**
     * True when the vehicle is committed to flight: executing a mission/RTL,
     * already airborne, or holding/climbing to a target well above the ground
     * (e.g. takeoff accepted and climbing). Disarm is denied in this state.
     */
    private boolean isFlying() {
        if (state.flying() || physics.alt() > AIRBORNE_ALT) {
            return true;
        }
        return physics.hasTarget() && physics.targetAlt() > AIRBORNE_ALT;
    }

    /** Above this altitude the vehicle counts as airborne (disarm denied). */
    private static final double AIRBORNE_ALT = 0.3;
    /** Above this altitude RTL first flies home, below it the target is the ground. */
    private static final double RTL_DESCEND_SWITCH = 0.5;
    private static final double ACCEPT_NEAR_GROUND = 0.15;

    // ------------------------------------------------------------------
    // telemetry scheduling (rate control on the 20 Hz tick)
    // ------------------------------------------------------------------

    private long tickCount;

    private void telemetryRates(double dt) throws IOException {
        tickCount++;
        // 1 Hz: every 20 ticks
        if (tickCount % 20 == 0) {
            sendHeartbeat();
            sendSysStatus();
            sendGpsRawInt();
            sendSystemTime();
            sendRadioStatus();
            sendLedStatus();
            // M0b 环境状态上报（FR-24，1Hz）：envModel 启用时下传 ENVIRONMENT_STATUS
            if (envModel != null && envEnabled) {
                sendEnvironmentStatus();
            }
            // M2 负载状态上报（FR-29，1Hz）：actuatorsEnabled 时下传 PAYLOAD_STATUS
            if (actuatorsEnabled) {
                sendPayloadStatus();
            }
            // M4 LiDAR 数据 1Hz（FR-21）：lidarSource 注入时下传 LidarDataMsg(440)
            if (lidarSource != null) {
                sendLidarData();
            }
            // M4 雷达扫描（FR-06/FR-18/FR-19）：radar 启用时按 scanPeriodMs 周期扫描
            if (radar != null && radarEnabled && radarConfig != null) {
                runRadarScan();
            }
        }
        // 5 Hz: every 4 ticks
        if (tickCount % 4 == 0) {
            sendGlobalPosition();
            sendAttitude();
            // M3 避障检测 5Hz（FR-14）：obstacleDetector 启用时调 detect() → ObstacleReportMsg(430) 上报
            // 与既有 5Hz 遥测同分频，未注入时不产生感知上报（DFX 4.5）
            if (obstacleDetector != null && obstacleEnabled) {
                runObstacleDetection();
            }
            // M4 气动遥测 5Hz（FR-20）：rotorAero 启用时下传 RotorTelemetryMsg(439)
            if (rotorAero != null && rotorConfig != null && "aero".equals(physicsModel)) {
                sendRotorTelemetry();
            }
        }
        // M4 IMU 数据 10Hz（FR-22）：imuSource 注入时下传 ImuDataMsg(441)
        if (imuSource != null && tickCount % 2 == 0) {
            sendImuData();
        }
        // 2 Hz: every 10 ticks
        if (tickCount % 10 == 0) {
            sendVfrHud();
            // M2 喷洒状态上报（FR-26，2Hz）：actuatorsEnabled 时下传 SPRAY_STATUS
            if (actuatorsEnabled) {
                sendSprayStatus();
            }
        }
        // 2 Hz during mission
        if (state == FlightState.MISSION && tickCount % 10 == 0) {
            sendMissionCurrent();
        }
    }

    // ------------------------------------------------------------------
    // individual telemetry messages
    // ------------------------------------------------------------------

    private void sendHeartbeat() throws IOException {
        send(new Heartbeat(state.px4NavState(), MavEnums.MAV_TYPE_QUADROTOR, MavEnums.MAV_AUTOPILOT_PX4,
                state.baseMode(), state.mavState()));
    }

    private void sendSysStatus() throws IOException {
        double bootSec = (System.currentTimeMillis() - bootUnixMs) / 1000.0;
        double voltage = physics.batteryVoltage();
        int pct = physics.batteryRemainingPct();
        if (scenario.batteryFault(bootSec)) {
            // Fault: cell failure - voltage sags and remaining % jumps to the
            // scripted critical level regardless of flight time so far.
            pct = (int) scenario.batteryFaultPct(bootSec);
            voltage = DronePhysics.VOLT_EMPTY - 0.15;
        }
        int load = state.flying() ? 600 : 350;
        send(new SysStatus(0, 0, 0, load, (int) (voltage * 1000),
                state.armed() ? 180 : 0, pct));
    }

    // ------------------------------------------------------------------
    // RADIO_STATUS (E1): link quality as geometry sees it
    // ------------------------------------------------------------------

    /** RSSI below this for this long -> one low-link WARNING (throttled). */
    private static final double LINK_WARN_DBM = -90.0;
    private static final long LINK_WARN_SUSTAIN_MS = 5_000;
    private static final long LINK_WARN_THROTTLE_MS = 60_000;
    /** Last RSSI sample [dBm] and low-link state for the warning logic. */
    private double lastRssiDbm = 0;
    private long lowLinkSinceMs;
    private long lastLinkWarnMs;

    /**
     * 1 Hz link report: the radio module's view of the downlink. rssi is
     * SiK-style raw (2x dB), remrssi mirrors it (symmetric link at this
     * abstraction), txbuf is free (UDP has no real buffer pressure), noise
     * is a quiet channel. Also drives the low-link warning: sustained
     * -90 dBm for 5 s emits one WARNING, re-armed after 60 s.
     */
    private void sendRadioStatus() throws IOException {
        double dbm = radio.rssiDbm(physics.north(), physics.east(), physics.alt());
        lastRssiDbm = dbm;
        long now = System.currentTimeMillis();
        if (dbm < LINK_WARN_DBM) {
            if (lowLinkSinceMs == 0) {
                lowLinkSinceMs = now;
            } else if (now - lowLinkSinceMs > LINK_WARN_SUSTAIN_MS
                    && now - lastLinkWarnMs > LINK_WARN_THROTTLE_MS) {
                lastLinkWarnMs = now;
                SimLog.warn(String.format("link quality poor: %.0f dBm", dbm));
                pushStatus(MavEnums.MAV_SEVERITY_WARNING,
                        String.format("Link quality poor: %.0f dBm - check range/terrain", dbm));
            }
        } else {
            lowLinkSinceMs = 0;
        }
        int raw = RadioEnvironment.toSikUnits(dbm);
        send(new RadioStatus(raw, raw, 100, 20, 20, 0, 0));
    }

    /** Truth HTTP exposes the same RSSI for e2e assertions (debug aid). */
    public double currentRssiDbm() {
        return lastRssiDbm;
    }

    private void sendGlobalPosition() throws IOException {
        double bootSec = (System.currentTimeMillis() - bootUnixMs) / 1000.0;
        int hdg = (int) Math.round(Math.toDegrees(DronePhysics.normalizeAngle(physics.yawRad())) * 100);
        // With GPS noise the reported fix wanders; with GPS loss it freezes at
        // the last good spot (an EKF without external aid coasts on dead-reckoning,
        // the position simply stops updating for the fault test's purpose).
        double outLat;
        double outLon;
        if (scenario.gpsLost(bootSec)) {
            outLat = lastGoodLat;
            outLon = lastGoodLon;
        } else {
            double noise = scenario.gpsNoiseRadius(bootSec);
            outLat = physics.reportedLat(noise);
            outLon = physics.reportedLon(noise);
            lastGoodLat = outLat;
            lastGoodLon = outLon;
        }
        // Reported altitude carries the barometer drift scenario offset;
        // a bad baro makes the GCS altitude read slowly diverge from truth.
        double altReported = physics.alt() + scenario.baroDriftM(bootSec);
        send(new GlobalPositionInt((int) physics.bootMillis(),
                (int) Math.round(outLat * 1e7),
                (int) Math.round(outLon * 1e7),
                (int) Math.round(altReported * 1000),
                (int) Math.round(altReported * 1000),
                (int) Math.round(vxNorth() * 100),
                (int) Math.round(vyEast() * 100),
                (int) Math.round(-physics.vz() * 100), // NED: down positive
                hdg == 0 ? 0 : hdg % 36000));
    }

    /** Last valid GPS fix, frozen during GPS loss. */
    private double lastGoodLat;
    private double lastGoodLon;

    private void sendAttitude() throws IOException {
        double bootSec = (System.currentTimeMillis() - bootUnixMs) / 1000.0;
        // Sensor-fault scenarios corrupt the REPORTED attitude only; the
        // vehicle keeps flying on true values (an EKF with a biased gyro).
        double biasRad = Math.toRadians(scenario.imuBiasDeg(bootSec));
        double magRad = Math.toRadians(scenario.magWanderDeg(bootSec));
        send(new Attitude((int) physics.bootMillis(),
                (float) (physics.rollRad() + biasRad * 0.3),
                (float) (physics.pitchRad() + biasRad * 0.2),
                (float) DronePhysics.normalizeAngle(physics.yawRad() + biasRad + magRad),
                0f, 0f, 0f));
    }

    private void sendVfrHud() throws IOException {
        double bootSec = (System.currentTimeMillis() - bootUnixMs) / 1000.0;
        int throttle = state.flying() ? 55 : (state.armed() ? 10 : 0);
        double altReported = physics.alt() + scenario.baroDriftM(bootSec);
        send(new VfrHud((float) physics.groundSpeed(), (float) physics.groundSpeed(),
                (float) altReported, (float) physics.vz(), physics.headingDeg(), throttle));
    }

    private void sendGpsRawInt() throws IOException {
        double bootSec = (System.currentTimeMillis() - bootUnixMs) / 1000.0;
        boolean lost = scenario.gpsLost(bootSec);
        double noise = lost ? 0 : scenario.gpsNoiseRadius(bootSec);
        // fixType 0 = NO_FIX, satellites drop to 0 while lost.
        send(new GpsRawInt(System.currentTimeMillis() * 1000L,
                (int) Math.round((lost ? lastGoodLat : physics.reportedLat(noise)) * 1e7),
                (int) Math.round((lost ? lastGoodLon : physics.reportedLon(noise)) * 1e7),
                (int) Math.round(physics.alt() * 1000),
                lost ? (short) 9999 : 90,
                lost ? (short) 9999 : 80,
                (int) Math.round(physics.groundSpeed() * 100),
                (int) Math.round(Math.toDegrees(DronePhysics.normalizeAngle(physics.yawRad())) * 100),
                lost ? 0 : 3,
                lost ? 0 : 12, 0));
    }

    private void sendSystemTime() throws IOException {
        send(new SystemTimeMsg((System.currentTimeMillis() - bootUnixMs) * 1000L,
                (int) physics.bootMillis()));
    }

    private void sendMissionCurrent() throws IOException {
        int missionState = switch (state) {
            case MISSION -> MavEnums.MISSION_STATE_ACTIVE;
            case RTL -> missionNotifiedComplete
                    ? MavEnums.MISSION_STATE_COMPLETE : MavEnums.MISSION_STATE_ACTIVE;
            // Failsafe hover pauses the mission until GPS returns.
            case HOLD -> MavEnums.MISSION_STATE_PAUSED;
            default -> missions.hasMission()
                    ? MavEnums.MISSION_STATE_NOT_STARTED : MavEnums.MISSION_STATE_NO_MISSION;
        };
        send(new MissionCurrent(currentSeq, missions.size(), missionState, 0,
                0, 0, 0));
    }

    /** Send HOME_POSITION once a peer connects (common GCS convenience). */
    private void maybeSendHome() throws IOException {
        send(new HomePosition((int) Math.round(config.lat * 1e7),
                (int) Math.round(config.lon * 1e7), 0));
    }

    // ------------------------------------------------------------------
    // LED control (FR-12) + status text + battery warning
    // ------------------------------------------------------------------

    /**
     * FR-12 灯光命令处理：更新 ledState + 记日志 + 回 COMMAND_ACK。
     * 在 onFrame 的 synchronized block 内调用，与既有消息处理一致。
     */
    private void handleLedControl(LedControlMsg led) throws IOException {
        ledState = new LedState(
                led.on, led.pattern, led.brightness, led.freq,
                led.phaseStartUs, led.colorR, led.colorG, led.colorB);
        String patternName = (led.pattern >= 0
                && led.pattern < io.aerofleet.mavlink.enums.LightPattern.values().length)
                ? io.aerofleet.mavlink.enums.LightPattern.values()[led.pattern].name()
                : ("#" + led.pattern);
        SimLog.info("LED: on=" + led.on + " pattern=" + patternName
                + " brightness=" + led.brightness + "% freq=" + led.freq + "Hz"
                + " rgb=" + led.colorR + "/" + led.colorG + "/" + led.colorB);
        // 回 COMMAND_ACK（灯光命令 fire-and-forget，但模拟器回 ACK 便于后端聚合）
        send(new CommandAck(LedControlMsg.ID, MavEnums.MAV_RESULT_ACCEPTED, 255, 0, 0, 0));
    }

    // ------------------------------------------------------------------
    // M0b 环境配置命令（FR-28，command 310/311/312）
    // ------------------------------------------------------------------

    /** MAV_CMD 310：设置风速/风向（FR-28）。param1=风速 m/s，param2=风向 deg。 */
    private int handleEnvSetWind(CommandLong cmd, int senderSysid) {
        if (envModel == null) return MavEnums.MAV_RESULT_UNSUPPORTED;
        if (senderSysid != GCS_SYSID) return MavEnums.MAV_RESULT_DENIED;
        double speed = cmd.param1;
        double dir = cmd.param2;
        if (speed < 0 || speed > 50 || dir < 0 || dir >= 360) {
            return MavEnums.MAV_RESULT_DENIED;
        }
        envModel.overrideWind(speed, dir);
        return MavEnums.MAV_RESULT_ACCEPTED;
    }

    /** MAV_CMD 311：设置天气/降雨率（FR-28）。param1=weatherCode[0-4]，param2=rainRate[0-255]。 */
    private int handleEnvSetWeather(CommandLong cmd, int senderSysid) {
        if (envModel == null) return MavEnums.MAV_RESULT_UNSUPPORTED;
        if (senderSysid != GCS_SYSID) return MavEnums.MAV_RESULT_DENIED;
        int weatherCode = (int) Math.round(cmd.param1);
        int rainRate = (int) Math.round(cmd.param2);
        if (weatherCode < 0 || weatherCode > 4 || rainRate < 0 || rainRate > 255) {
            return MavEnums.MAV_RESULT_DENIED;
        }
        envModel.overrideWeather(Weather.of(weatherCode), rainRate);
        return MavEnums.MAV_RESULT_ACCEPTED;
    }

    /** MAV_CMD 312：设置告警阈值（FR-28）。param1=windWarn，param2=windCrit。 */
    private int handleEnvSetThresholds(CommandLong cmd, int senderSysid) {
        if (envModel == null) return MavEnums.MAV_RESULT_UNSUPPORTED;
        if (senderSysid != GCS_SYSID) return MavEnums.MAV_RESULT_DENIED;
        double windWarn = cmd.param1;
        double windCrit = cmd.param2;
        if (windWarn >= windCrit) {
            return MavEnums.MAV_RESULT_DENIED;
        }
        envModel.overrideThresholds(windWarn, windCrit);
        return MavEnums.MAV_RESULT_ACCEPTED;
    }

    /**
     * FR-12 灯光状态上报（1Hz，复用 STATUSTEXT 通道）。
     * 未开灯不上报（DFX 4.5 既有模拟器行为不变）。
     */
    private void sendLedStatus() throws IOException {
        LedState s = ledState;
        if (!s.on) {
            return;  // 未开灯不上报
        }
        String status = String.format("LED:on:%d:%d:%d:%d:%d",
                s.pattern, s.colorR, s.colorG, s.colorB, s.brightness);
        send(new Statustext(MavEnums.MAV_SEVERITY_INFO, status, 0, 0));
    }

    /**
     * M0b 环境状态上报（FR-24，1Hz）：下传 ENVIRONMENT_STATUS 消息。
     * 由 telemetryRates 1Hz 分频块调用，envModel != null 时生效。
     */
    private void sendEnvironmentStatus() throws IOException {
        send(envModel.toStatusMessage());
    }

    // ------------------------------------------------------------------
    // M2 喷洒/抛投控制命令（FR-14/FR-20/FR-21，command 320/321/322）
    // ------------------------------------------------------------------

    /**
     * MAV_CMD 320：喷洒控制（FR-14）。
     * param1=command 枚举（0=ENABLE/1=DISABLE/2=SET_RATE/3=EMERGENCY_STOP），
     * param2=targetRate mL/s，param3=sprayWidth cm。
     */
    private int handleSprayControl(CommandLong cmd, int senderSysid) {
        if (sprayPump == null) return MavEnums.MAV_RESULT_UNSUPPORTED;
        if (senderSysid != GCS_SYSID) return MavEnums.MAV_RESULT_DENIED;
        int subCmd = (int) Math.round(cmd.param1);
        switch (subCmd) {
            case 0 -> {  // ENABLE
                sprayPump.enable();
                return MavEnums.MAV_RESULT_ACCEPTED;
            }
            case 1 -> {  // DISABLE
                sprayPump.disable();
                return MavEnums.MAV_RESULT_ACCEPTED;
            }
            case 2 -> {  // SET_RATE
                double rate = cmd.param2;
                if (rate < 0 || rate > sprayPump.rateMax()) {
                    return MavEnums.MAV_RESULT_DENIED;  // 流量双向校验（FR-03 安全性）
                }
                sprayPump.setRate(rate);
                return MavEnums.MAV_RESULT_ACCEPTED;
            }
            case 3 -> {  // EMERGENCY_STOP
                sprayPump.emergencyStop();
                SimLog.warn("Spray emergency stop invoked");
                return MavEnums.MAV_RESULT_ACCEPTED;
            }
            default -> {
                return MavEnums.MAV_RESULT_UNSUPPORTED;
            }
        }
    }

    /**
     * MAV_CMD 321：抛投控制（FR-20/FR-21）。
     * param1=command 枚举（0=GRAB/1=RELEASE/2=RESET），
     * param2=payloadId，param3=payloadWeight kg，param4=payloadVolume L。
     */
    private int handleGripperControl(CommandLong cmd, int senderSysid) {
        if (gripper == null) return MavEnums.MAV_RESULT_UNSUPPORTED;
        if (senderSysid != GCS_SYSID) return MavEnums.MAV_RESULT_DENIED;
        int subCmd = (int) Math.round(cmd.param1);
        switch (subCmd) {
            case 0 -> {  // GRAB
                int payloadId = (int) Math.round(cmd.param2);
                double weightKg = cmd.param3;
                double volumeL = cmd.param4;
                PayloadItem item = new PayloadItem(payloadId, weightKg, volumeL, 0);
                boolean ok = gripper.grab(item);
                if (!ok) {
                    SimLog.warn("Gripper grab rejected: state=" + gripper.gripperState()
                            + " weight=" + weightKg + "kg max=" + gripper.payloadMax() + "kg");
                    return MavEnums.MAV_RESULT_DENIED;
                }
                return MavEnums.MAV_RESULT_ACCEPTED;
            }
            case 1 -> {  // RELEASE
                double[] ll = currentLatLon();
                boolean ok = gripper.release(ll[0], ll[1]);
                if (!ok) {
                    SimLog.warn("Gripper release rejected: state=" + gripper.gripperState());
                    return MavEnums.MAV_RESULT_DENIED;
                }
                return MavEnums.MAV_RESULT_ACCEPTED;
            }
            case 2 -> {  // RESET
                gripper.reset();
                return MavEnums.MAV_RESULT_ACCEPTED;
            }
            default -> {
                return MavEnums.MAV_RESULT_UNSUPPORTED;
            }
        }
    }

    /** MAV_CMD 322：负载查询（FR-34）→ 立即发送 PAYLOAD_STATUS 消息。 */
    private int handlePayloadQuery(int senderSysid) throws java.io.IOException {
        if (gripper == null) return MavEnums.MAV_RESULT_UNSUPPORTED;
        sendPayloadStatus();
        return MavEnums.MAV_RESULT_ACCEPTED;
    }

    // ------------------------------------------------------------------
    // M2 喷洒/负载遥测上报（FR-26/FR-29）
    // ------------------------------------------------------------------

    /**
     * FR-26 2Hz 喷洒状态上报：下传 SPRAY_STATUS(423) 消息。
     * 由 telemetryRates 2Hz 分频块调用，actuatorsEnabled 时生效。
     */
    private void sendSprayStatus() throws IOException {
        send(new io.aerofleet.mavlink.messages.SprayStatus(
                sprayPump.getState().enabled(),
                (int) Math.round(sprayPump.actualRate()),
                (int) Math.round(sprayPump.remainingChemical()),
                (int) Math.round(sprayPump.coveragePercent()),
                sprayPump.lowChemical(),
                (int) Math.round(sprayPump.driftOffsetAngle() * 100),  // cdeg
                (int) Math.round(sprayPump.flowCorrection() * 100)));  // %
    }

    /**
     * FR-29 1Hz 负载状态上报：下传 PAYLOAD_STATUS(426) 消息。
     * 由 telemetryRates 1Hz 分频块调用，actuatorsEnabled 时生效。
     */
    private void sendPayloadStatus() throws IOException {
        PayloadModel payload = gripper.payload();
        send(new io.aerofleet.mavlink.messages.PayloadStatus(
                gripper.gripperState().ordinal(),
                (int) Math.round(payload.totalWeight() * 1000),  // cg（克，×10 实际是 cg=centigram，这里用 g×10=cg）
                (int) Math.round(payload.totalVolume() * 100),   // cL（厘升，×10）
                0,  // remainingSites（配送站点数由 cloud-backend DeliverySequence 管理，sim 侧不持有）
                0,  // currentSiteIndex
                0));  // dropAccuracyCm
    }

    /**
     * FR-09 药量低告警去重：chemicalPercent < 15 → WARNING；< 5 → CRITICAL。
     * 由 tickOnce 在 actuatorsEnabled 块内调用。
     */
    private void checkSprayChemicalAlerts() {
        double pct = sprayPump.chemicalPercent();
        if (pct < 5.0 && !sprayLowCriticalWarned) {
            sprayLowCriticalWarned = true;
            sprayLowWarned = true;  // critical 隐含 warning
            SimLog.warn(String.format("Spray chemical CRITICAL: %.1f%% < 5%%", pct));
            pushStatus(MavEnums.MAV_SEVERITY_CRITICAL,
                    String.format("Spray chemical critical: %.1f%%", pct));
        } else if (pct < 15.0 && !sprayLowWarned) {
            sprayLowWarned = true;
            SimLog.warn(String.format("Spray chemical LOW: %.1f%% < 15%%", pct));
            pushStatus(MavEnums.MAV_SEVERITY_WARNING,
                    String.format("Spray chemical low: %.1f%%", pct));
        }
    }

    /**
     * FR-17 侧风禁喷告警去重：crosswindPaused 变为 true 时触发 WARNING。
     * 由 tickOnce 在 actuatorsEnabled 块内调用。
     */
    private void checkCrosswindAlert() {
        if (sprayPump.crosswindPaused() && !crosswindWarned) {
            crosswindWarned = true;
            SimLog.warn("Crosswind no-spray: wind speed exceeds threshold, spray paused");
            pushStatus(MavEnums.MAV_SEVERITY_WARNING,
                    "Crosswind no-spray: spray paused");
        } else if (!sprayPump.crosswindPaused() && crosswindWarned) {
            crosswindWarned = false;
            SimLog.info("Crosswind no-spray cleared: spray resumed");
        }
    }

    /** 当前无人机经纬度（投放位置记录用）。 */
    private double[] currentLatLon() {
        double bootSec = (System.currentTimeMillis() - bootUnixMs) / 1000.0;
        double noise = scenario.gpsNoiseRadius(bootSec);
        return new double[]{physics.reportedLat(noise), physics.reportedLon(noise)};
    }

    // ------------------------------------------------------------------
    // M3 感知成像增强（FR-12/FR-14）：避障检测 + 感知数据源注入
    // ------------------------------------------------------------------

    /**
     * FR-14 5Hz 避障检测：调 obstacleDetector.detect() → ObstacleReportMsg(430) 上报。
     * 异常 try-catch + WARN 日志，不中断 tick 循环（异常 5.4.2）。
     */
    private void runObstacleDetection() {
        try {
            ObstacleDetector.ObstacleReport report = obstacleDetector.detect();
            // 经 ObstacleReportMsg(430) 上报至 cloud-backend
            float dist = report.distance() == Double.MAX_VALUE
                    ? Float.MAX_VALUE : (float) report.distance();
            send(new ObstacleReportMsg(
                    dist,
                    (float) report.directionDeg(),
                    System.currentTimeMillis(),
                    report.threat().ordinal(),
                    report.type().ordinal(),
                    config.sysid));
        } catch (Exception e) {
            SimLog.warn("obstacle detect failed: " + e.getMessage());
        }
    }

    /**
     * 注入深度数据源（FR-12）。null 表示不启用深度感知。
     * <p>
     * budget toy 模式下忽略注入（toy 模式强制使用超声波避障，DFX 4.5）；
     * standard 模式下允许注入高端 DepthSource 作为双冗余补充（超声波仍作为主避障源）。
     */
    public void setDepthSource(DepthSource depthSource) {
        if (BudgetMode.TOY == budgetMode) {
            SimLog.warn("Budget mode toy: ultrasonic forced, ignoring setDepthSource");
            return;
        }
        this.depthSource = depthSource;
    }

    /**
     * 注入避障检测器（FR-13/FR-14）。null 表示不启用避障检测。
     * <p>
     * budget toy 模式下忽略注入（toy 模式自动装配超声波避障，DFX 4.5）；
     * standard 模式下允许注入自定义检测器（覆盖超声波默认检测器）。
     */
    public void setObstacleDetector(ObstacleDetector obstacleDetector) {
        if (BudgetMode.TOY == budgetMode) {
            SimLog.warn("Budget mode toy: ultrasonic detector forced, ignoring setObstacleDetector");
            return;
        }
        this.obstacleDetector = obstacleDetector;
    }

    /** 启用/禁用避障检测（FR-14）。 */
    public void setObstacleEnabled(boolean enabled) {
        this.obstacleEnabled = enabled;
    }

    /** 当前避障启用状态。 */
    public boolean isObstacleEnabled() {
        return obstacleEnabled;
    }

    // ------------------------------------------------------------------
    // M4 硬件抽象（FR-01~FR-22）：数据源注入 + 气动切换 + 硬件数据上报
    // ------------------------------------------------------------------

    /** 注入相控阵雷达（FR-01）。null 表示不启用雷达。budget toy/standard 模式下忽略注入（DFX 4.5）。 */
    public void setRadar(PhasedArrayRadar radar) {
        if (BudgetMode.TOY == budgetMode || BudgetMode.STANDARD == budgetMode) {
            SimLog.warn("Budget mode " + budgetMode.cliValue() + ": radar disabled, ignoring setRadar");
            return;
        }
        this.radar = radar;
    }

    /** 注入雷达扫描配置（FR-03）。 */
    public void setRadarConfig(RadarScanConfig radarConfig) {
        this.radarConfig = radarConfig;
    }

    /** 启用/禁用雷达扫描（FR-06）。 */
    public void setRadarEnabled(boolean enabled) {
        this.radarEnabled = enabled;
    }

    /** 注入旋翼气动模型（FR-07）。null 表示不启用气动模型。 */
    public void setRotorAero(RotorAerodynamics rotorAero) {
        this.rotorAero = rotorAero;
    }

    /** 注入旋翼气动配置（FR-26）。 */
    public void setRotorConfig(RotorConfig rotorConfig) {
        this.rotorConfig = rotorConfig;
    }

    /** 注入 LiDAR 数据源（FR-12）。null 表示不启用 LiDAR。budget toy 模式下忽略注入（DFX 4.5）。 */
    public void setLidarSource(LiDARSource lidarSource) {
        if (BudgetMode.TOY == budgetMode) {
            SimLog.warn("Budget mode toy: lidar disabled, ignoring setLidarSource");
            return;
        }
        this.lidarSource = lidarSource;
    }

    /** 注入 IMU 数据源（FR-15）。null 表示不启用 IMU。 */
    public void setImuSource(ImuSource imuSource) {
        this.imuSource = imuSource;
    }

    /** 注入热成像数据源（FR-04）。null 表示不启用热成像。budget toy/standard 模式下忽略注入（DFX 4.5）。 */
    public void setThermalSource(ThermalSource thermalSource) {
        if (BudgetMode.TOY == budgetMode || BudgetMode.STANDARD == budgetMode) {
            SimLog.warn("Budget mode " + budgetMode.cliValue() + ": thermal disabled, ignoring setThermalSource");
            return;
        }
        this.thermalSource = thermalSource;
    }

    /** 当前热成像数据源（供 tickOnce 读取，null 表示未注入）。 */
    public ThermalSource getThermalSource() {
        return thermalSource;
    }

    // ------------------------------------------------------------------
    // 丐版模式（budget）传感器查询接口（供集成测试/监控验证装配状态）
    // ------------------------------------------------------------------

    /** 当前 budget 模式（null=完整版，TOY/STANDARD/ADVANCED/EMERGENCY_TOY/EMERGENCY_STANDARD=丐版模式）。 */
    public BudgetMode getBudgetMode() {
        return budgetMode;
    }

    /** 当前深度数据源（供测试验证，null 表示未注入）。 */
    public DepthSource getDepthSource() {
        return depthSource;
    }

    /** 当前避障检测器（供测试验证，null 表示未注入）。 */
    public ObstacleDetector getObstacleDetector() {
        return obstacleDetector;
    }

    /** 丐版超声波传感器（budget toy/standard 模式自动实例化，null 表示未启用）。 */
    public UltrasonicSource getUltrasonicSource() {
        return ultrasonicSource;
    }

    /** 丐版红外阵列热源（budget toy/standard 模式自动实例化，null 表示未启用）。 */
    public BudgetThermalSource getBudgetThermalSource() {
        return budgetThermalSource;
    }

    /** 丐版光流定位数据源（budget toy 模式自动实例化，null 表示未启用）。 */
    public OpticalFlowSource getOpticalFlowSource() {
        return opticalFlowSource;
    }

    /**
     * 切换物理模型（FR-10/FR-37）。
     *
     * @param model "kinematics"=运动学（默认），"aero"=气动模型
     */
    public void setPhysicsModel(String model) {
        if (!"kinematics".equals(model) && !"aero".equals(model)) {
            throw new IllegalArgumentException(
                    "physicsModel must be 'kinematics' or 'aero', got " + model);
        }
        this.physicsModel = model;
    }

    /** 当前物理模型。 */
    public String getPhysicsModel() {
        return physicsModel;
    }

    /**
     * FR-10 气动模型推力积分：将气动计算结果应用到 physics。
     * <p>
     * M4 代码审查 #7：当前为设计简化阶段，气动模型仅产生遥测上报（RotorTelemetryMsg），
     * 不影响物理积分。此处仍调用 physics.tick(dt) 维持运动学积分。
     * 真实实现应通过 result 中的推力 → 加速度 → 速度积分，但 DronePhysics.java 不修改约束，
     * 故气动推力暂不注入物理模型。result 参数已缓存到 lastAeroResult 供遥测复用（审查 #8）。
     *
     * @param result 气动计算结果（各旋翼推力/转速/功耗 + 汇总），当前仅用于缓存
     * @param dt     时间步长（秒）
     */
    private void applyAeroThrust(RotorAerodynamics.RotorAeroResult result, double dt) {
        // 简化：总推力 → 等效爬升率 → 通过 physics.holdAt 维持高度
        // 真实实现应通过 force → acceleration → velocity 积分
        // 此处保持 physics.tick 的运动学积分，气动仅用于遥测上报
        physics.tick(dt);
    }

    /**
     * FR-06 雷达扫描：调 radar.scan() → RadarTargetMsg(438) + RadarScanMsg(437) 上报。
     * 按 scanPeriodMs 周期触发（由 telemetryRates 1Hz 分频块调用时检查周期）。
     */
    private void runRadarScan() {
        long now = System.currentTimeMillis();
        if (now - lastRadarScanMs < radarConfig.scanPeriodMs()) {
            return;
        }
        lastRadarScanMs = now;
        try {
            // 从 TargetSimulator 获取合成目标并转换为 SyntheticTarget
            java.util.List<SyntheticTarget> targets = new java.util.ArrayList<>();
            for (var t : groundTargets.listTargets()) {
                String kindStr = switch (t.kind) {
                    case VEHICLE -> "vehicle";
                    case PEDESTRIAN -> "person";
                    case STATIC -> "building";
                };
                double velN = t.speedMps * Math.cos(t.headingRad);
                double velE = t.speedMps * Math.sin(t.headingRad);
                targets.add(new SyntheticTarget(t.id, t.north, t.east, 0, velN, velE, 0, kindStr));
            }
            java.util.List<RadarTargetReport> reports = radar.scan(radarConfig, targets);
            // 上报每个目标 → RadarTargetMsg(438)
            for (RadarTargetReport r : reports) {
                send(new RadarTargetMsg(
                        r.targetId(), (float) r.distance(), (float) r.azimDeg(),
                        (float) r.elevDeg(), (float) r.radialVelocity(), (float) r.rcs(),
                        r.trackState().ordinal(), config.sysid,
                        r.timestamp()));
            }
            // 上报扫描状态 → RadarScanMsg(437)
            send(new RadarScanMsg(
                    radarConfig.mode().ordinal(),
                    (float) radarConfig.azimCenter(), (float) radarConfig.elevCenter(),
                    radarConfig.scanPeriodMs(), reports.size(), config.sysid, now));
        } catch (Exception e) {
            SimLog.warn("radar scan failed: " + e.getMessage());
        }
    }

    /**
     * FR-20 气动遥测上报 5Hz：下传 RotorTelemetryMsg(439)。
     * <p>
     * M4 代码审查 #8：复用 tickOnce 缓存的 lastAeroResult 而非重新调 rotorAero.compute()，
     * 避免 20Hz tick + 5Hz 遥测重复计算。缓存为空时跳过本次上报。
     */
    private void sendRotorTelemetry() {
        try {
            RotorAerodynamics.RotorAeroResult result = lastAeroResult;
            if (result == null) {
                return;  // 尚未计算或已回退运动学，跳过
            }
            // 上报第一个旋翼的遥测 + 总推力/总功耗
            if (!result.rotors().isEmpty()) {
                RotorAerodynamics.RotorResult r0 = result.rotors().get(0);
                send(new RotorTelemetryMsg(
                        r0.rotorIndex(), (float) r0.rpm(), (float) r0.thrust(),
                        (float) r0.powerConsumption(), (float) result.totalThrust(),
                        (float) result.totalPower(), config.sysid));
            }
        } catch (Exception e) {
            SimLog.warn("rotor telemetry failed: " + e.getMessage());
        }
    }

    /**
     * FR-21 LiDAR 数据上报 1Hz：下传 LidarDataMsg(440)。
     */
    private void sendLidarData() {
        try {
            LiDARSource.LidarStats stats = lidarSource.pointCloudStats();
            send(new LidarDataMsg(
                    (float) stats.nearestDistance(), stats.pointCount(),
                    (float) stats.density(), (float) stats.avgIntensity(), config.sysid));
        } catch (Exception e) {
            SimLog.warn("lidar data failed: " + e.getMessage());
        }
    }

    /**
     * FR-22 IMU 数据上报 10Hz：下传 ImuDataMsg(441)。
     */
    private void sendImuData() {
        try {
            ImuSource.ImuSample s = imuSource.sample();
            send(new ImuDataMsg(
                    (float) s.accelX(), (float) s.accelY(), (float) s.accelZ(),
                    (float) s.gyroX(), (float) s.gyroY(), (float) s.gyroZ(),
                    (float) s.magX(), (float) s.magY(), (float) s.magZ(),
                    (float) s.tempC(), config.sysid));
        } catch (Exception e) {
            SimLog.warn("imu data failed: " + e.getMessage());
        }
    }

    /**
     * MAV_CMD 420：雷达扫描配置命令（FR-03）。
     * param1=mode, param2=azimCenter, param3=azimWidth, param4=elevCenter,
     * param5=beamWidth, param6=range, param7=scanPeriodMs。
     */
    private int handleRadarConfig(CommandLong cmd, int senderSysid) {
        if (radar == null) return MavEnums.MAV_RESULT_UNSUPPORTED;
        if (senderSysid != GCS_SYSID) return MavEnums.MAV_RESULT_DENIED;
        try {
            int modeOrdinal = (int) Math.round(cmd.param1);
            if (modeOrdinal < 0 || modeOrdinal > 2) return MavEnums.MAV_RESULT_DENIED;
            ScanMode mode = ScanMode.values()[modeOrdinal];
            RadarScanConfig cfg = new RadarScanConfig(
                    config.sysid, mode, cmd.param2, cmd.param3, cmd.param4,
                    cmd.param5, cmd.param6, (int) Math.round(cmd.param7), true);
            this.radarConfig = cfg;
            this.radarEnabled = true;
            SimLog.info("radar config via command: mode=" + mode + " range=" + cmd.param6 + "m");
            return MavEnums.MAV_RESULT_ACCEPTED;
        } catch (IllegalArgumentException e) {
            return MavEnums.MAV_RESULT_DENIED;
        }
    }

    /**
     * MAV_CMD 421：旋翼气动配置命令（FR-26）。
     * param1=rotorCount, param2=diameter, param3=pitch, param4=maxRpm, param5=airDensity。
     */
    private int handleRotorConfigCmd(CommandLong cmd, int senderSysid) {
        if (rotorAero == null) return MavEnums.MAV_RESULT_UNSUPPORTED;
        if (senderSysid != GCS_SYSID) return MavEnums.MAV_RESULT_DENIED;
        try {
            RotorConfig cfg = new RotorConfig(
                    config.sysid, (int) Math.round(cmd.param1), cmd.param2, cmd.param3,
                    cmd.param4, cmd.param5);
            this.rotorConfig = cfg;
            SimLog.info("rotor config via command: rotors=" + (int) Math.round(cmd.param1)
                    + " diameter=" + cmd.param2 + "m");
            return MavEnums.MAV_RESULT_ACCEPTED;
        } catch (IllegalArgumentException e) {
            return MavEnums.MAV_RESULT_DENIED;
        }
    }

    private void pushStatus(int severity, String text) {
        try {
            send(new Statustext(severity, text, 0, 0));
            lastStatus = text;
        } catch (IOException e) {
            SimLog.error("statustext send failed", e);
        }
    }

    private void checkBattery() throws IOException {
        double bootSec = (System.currentTimeMillis() - bootUnixMs) / 1000.0;
        int pct = physics.batteryRemainingPct();
        if (scenario.batteryFault(bootSec)) {
            pct = (int) scenario.batteryFaultPct(bootSec);
        }
        if (pct <= 20 && !batteryWarned) {
            batteryWarned = true;
            SimLog.warn("battery low: " + pct + "%");
            pushStatus(MavEnums.MAV_SEVERITY_WARNING, "Battery low: " + pct + "%");
        }
    }

    // ------------------------------------------------------------------
    // helpers
    // ------------------------------------------------------------------

    /** Horizontal velocity NED north component (m/s). */
    private double vxNorth() {
        return physics.groundSpeed() * Math.cos(physics.yawRad());
    }

    /** Horizontal velocity NED east component (m/s). */
    private double vyEast() {
        return physics.groundSpeed() * Math.sin(physics.yawRad());
    }

    /** Telemetry goes to the last known peer (learned from any inbound packet). */
    private void send(MavlinkMessage msg) throws IOException {
        MavlinkFrame frame = msg.toFrame(config.sysid, COMPONENT_ID,
                frameSeq.getAndIncrement() & 0xFF);
        SocketAddress peer = transport.getLastPeer();
        if (peer != null) {
            transport.send(frame, peer);
        }
    }

    /** True when a GCS has been seen (used for one-shot home position push). */
    private boolean homeSent;

    /** Called from onFrame when a heartbeat from a GCS arrives for the first time. */
    private void onFirstPeerSeen() {
        if (homeSent) {
            return;
        }
        homeSent = true;
        try {
            maybeSendHome();
        } catch (IOException e) {
            SimLog.error("home position send failed", e);
        }
    }

    public synchronized FlightState state() {
        return state;
    }

    public UdpMavlinkTransport transport() {
        return transport;
    }

    // ------------------------------------------------------------------
    // M6 移动基站载荷（FR-CT-01~06 / FR-TERM-01~07 / FR-HO-01~07）
    // ------------------------------------------------------------------

    /**
     * 处理基站配置指令（FR-CT-05 制式切换）。
     * 仅接受来自 GCS（sysid=255）的配置（FR-NFR-SEC-01）。
     */
    private void handleCellTowerConfig(io.aerofleet.mavlink.messages.CellTowerConfigMsg msg, int senderSysid) {
        if (senderSysid != GCS_SYSID) {
            SimLog.warn("celltower config rejected: unauthorized sender sysid=" + senderSysid);
            return;
        }
        try {
            cellTower.applyConfig(msg);
        } catch (Exception e) {
            SimLog.warn("celltower applyConfig failed: " + e.getMessage());
        }
    }

    /**
     * 处理终端注册请求（FR-TERM-06）。
     */
    private void handleGroundTerminalRegister(io.aerofleet.mavlink.messages.GroundTerminalRegisterMsg msg) {
        try {
            io.aerofleet.sim.celltower.TerminalType type =
                    io.aerofleet.sim.celltower.TerminalType.fromOrdinal(msg.terminalType);
            io.aerofleet.sim.celltower.GroundTerminal terminal =
                    io.aerofleet.sim.celltower.GroundTerminal.fromRegister(
                            msg.terminalId, type, msg.gpsLat, msg.gpsLon, System.currentTimeMillis());
            io.aerofleet.sim.celltower.AccessResult result = cellTower.handleRegister(terminal);
            SimLog.info("celltower register: terminal=" + msg.terminalId
                    + " result=" + result.success + " code=" + result.errorCode);
        } catch (IllegalArgumentException e) {
            SimLog.warn("celltower register rejected: " + e.getMessage());
        }
    }

    /**
     * 处理漫游切换信令（FR-HO-03 目标机侧）。
     */
    private void handleCellHandover(io.aerofleet.mavlink.messages.CellHandoverMsg msg) {
        // 简化处理：目标机收到后尝试注册终端
        SimLog.info("celltower handover received: terminal=" + msg.terminalId
                + " from=" + msg.fromSysid + " to=" + msg.toSysid);
    }

    /**
     * 发送基站状态广播（FR-CAP-05 / FR-MSG-02）。
     */
    private void sendCellTowerStatus() throws IOException {
        if (cellTower == null || cellTower.currentCoverage() == null) {
            return;
        }
        io.aerofleet.sim.celltower.CoverageArea cov = cellTower.currentCoverage();
        int utilPct = (int) Math.round(cellTower.capacityUtilization() * 100);
        io.aerofleet.mavlink.messages.CellTowerStatusMsg status =
                new io.aerofleet.mavlink.messages.CellTowerStatusMsg(
                        config.sysid,
                        cellTower.cellType().ordinalCode(),
                        cov.centerLatE7,
                        cov.centerLonE7,
                        (int) cov.radiusM,
                        cellTower.connectedTerminals(),
                        utilPct);
        send(status);
    }
}
