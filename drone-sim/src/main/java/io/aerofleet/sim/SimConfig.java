package io.aerofleet.sim;

import io.aerofleet.sim.mesh.MeshRouterConfig;
import io.aerofleet.sim.orch.OrchestrationConfig;
import io.aerofleet.sim.satrelay.SatRelayConfig;

/**
 * Command line configuration for the virtual drone simulator.
 * Parsed from simple --key=value / --key value style arguments.
 */
public final class SimConfig {

    public final int port;
    public final int sysid;
    public final double lat;
    public final double lon;
    public final double speed;
    public final String name;
    /** Fault-injection spec, see {@link ScenarioController}; "none" when absent. */
    public final String scenario;
    /** Bind IP: a 127.x.y.z address gives this drone its own "network segment" identity. */
    public final String bindIp;
    /** Autopilot failsafe layer (link loss / battery crit / GPS loss) on/off. */
    public final boolean failsafe;
    /** Terrain spec: "hill:north:east:radius:height" comma-joined, or absent = flat. */
    public final String terrain;
    /** Geofence spec: "n1,e1:n2,e2:...[:ceilingM]" or absent = disabled. */
    public final String fence;
    /** Synthetic ground targets: "kind:lat,lon[:speed[:heading[:turn]]],..." */
    public final String targets;
    /** Ground-truth HTTP port (0 = disabled). */
    public final int httpPort;
    // ---- M0b 环境气象参数（FR-01/03/05）----
    /** 环境模型启用开关（--env）。false 时 VirtualDrone.envModel=null，既有行为不变（DFX 4.5）。 */
    public final boolean envEnabled;
    /** 气象场景名（--env-scenario，默认 calm）。 */
    public final String envScenario;
    /** 假数据源伪随机种子（--env-seed，默认 0，确定性可复现 FR-05）。 */
    public final long envSeed;
    /** 风速上限 m/s（--env-wind-max，默认 50）。 */
    public final double envWindMax;
    /** 温度范围 [low, high] °C（--env-temp-range，默认 [-40, 55]）。 */
    public final double[] envTempRange;
    // ---- M2 执行机构参数（FR-01，DFX 4.4 配置可追溯）----
    /** 执行机构启用开关（--actuators）。false 时 VirtualDrone.sprayPump/gripper=null，既有行为不变（DFX 4.5）。 */
    public final boolean actuatorsEnabled;
    /** 药箱容量 L（--spray-capacity，默认 20）。 */
    public final double sprayCapacity;
    /** 最大流量 mL/s（--spray-rate-max，默认 2000）。 */
    public final double sprayRateMax;
    /** 最大负载 kg（--gripper-payload-max，默认 10）。 */
    public final double gripperPayloadMax;
    /** 侧风禁喷阈值 m/s（--spray-crosswind-max，默认 6）。 */
    public final double sprayCrosswindMax;
    // ---- M5 应急 mesh 自愈组网参数（FR-01~30）----
    /** mesh 路由引擎启用开关（--mesh）。false 时 VirtualDrone.meshRouter=null，既有行为不变（DFX 4.5）。 */
    public final boolean meshEnabled;
    /** mesh 路由引擎配置（meshEnabled=true 时由 MeshRouterConfig.parse 解析，否则 defaults）。 */
    public final MeshRouterConfig meshRouterConfig;
    // ---- M7 星-空-地多层级中继参数（FR-5.1~5.5）----
    /** sat-relay 引擎启用开关（--sat-relay）。false 时 VirtualDrone.satRelayEngine=null，既有行为不变（DFX 4.5）。 */
    public final boolean satRelayEnabled;
    /** sat-relay 引擎配置（satRelayEnabled=true 时由 SatRelayConfig.parse 解析，否则 defaults）。 */
    public final SatRelayConfig satRelayConfig;
    // ---- M8 复杂地形适配参数（FR-01~33）----
    /** 地形适配启用开关（--terrain-adapt）。false 时 VirtualDrone.terrainAdapt 相关对象=null，既有行为不变（DFX 4.5）。 */
    public final boolean terrainAdaptEnabled;
    /** 地形网格分辨率 m（--terrain-grid-resolution，默认 100）。 */
    public final double terrainGridResolution;
    // ---- M6 移动基站载荷抽象参数（FR-CT-01~06）----
    /** 基站载荷配置（--celltower 开关 + 子参数）。null 或 enabled=false 时 VirtualDrone.cellTower=null，既有行为不变（DFX 4.5）。 */
    public final io.aerofleet.sim.celltower.CellTowerSimConfig cellTowerConfig;
    // ---- M9 应急任务编排参数（FR-01~33）----
    /** 应急任务编排配置（--orch 开关 + 子参数）。enabled=false 时 VirtualDrone.orchEngine=null，既有行为不变（DFX 4.5）。 */
    public final OrchestrationConfig orchConfig;
    // ---- 丐版模式参数（budget）----
    /** 丐版模式："toy"(百元级) | "standard"(千元级) | "advanced"(进阶) | null(完整版，既有行为不变 DFX 4.5)。 */
    public final String budgetMode;

    private SimConfig(int port, int sysid, double lat, double lon, double speed,
                      String name, String scenario, String bindIp, boolean failsafe,
                      String terrain, String fence, String targets, int httpPort,
                      boolean envEnabled, String envScenario, long envSeed,
                      double envWindMax, double[] envTempRange,
                      boolean actuatorsEnabled, double sprayCapacity,
                      double sprayRateMax, double gripperPayloadMax,
                      double sprayCrosswindMax,
                       boolean meshEnabled, MeshRouterConfig meshRouterConfig,
                        boolean satRelayEnabled, SatRelayConfig satRelayConfig,
                         boolean terrainAdaptEnabled, double terrainGridResolution,
                          io.aerofleet.sim.celltower.CellTowerSimConfig cellTowerConfig,
                          OrchestrationConfig orchConfig,
                          String budgetMode) {
        this.port = port;
        this.sysid = sysid;
        this.lat = lat;
        this.lon = lon;
        this.speed = speed;
        this.name = name;
        this.scenario = scenario;
        this.bindIp = bindIp;
        this.failsafe = failsafe;
        this.terrain = terrain;
        this.fence = fence;
        this.targets = targets;
        this.httpPort = httpPort;
        this.envEnabled = envEnabled;
        this.envScenario = envScenario;
        this.envSeed = envSeed;
        this.envWindMax = envWindMax;
        this.envTempRange = envTempRange;
        this.actuatorsEnabled = actuatorsEnabled;
        this.sprayCapacity = sprayCapacity;
        this.sprayRateMax = sprayRateMax;
        this.gripperPayloadMax = gripperPayloadMax;
        this.sprayCrosswindMax = sprayCrosswindMax;
        this.meshEnabled = meshEnabled;
        this.meshRouterConfig = meshRouterConfig;
        this.satRelayEnabled = satRelayEnabled;
        this.satRelayConfig = satRelayConfig;
        this.terrainAdaptEnabled = terrainAdaptEnabled;
        this.terrainGridResolution = terrainGridResolution;
        this.cellTowerConfig = cellTowerConfig;
        this.orchConfig = orchConfig;
        this.budgetMode = budgetMode;
    }

    /** Defaults: Shenzhen University Town area, 8 m/s cruise, port 14540, sysid 1. */
    public static SimConfig defaults() {
        return new SimConfig(14540, 1, 22.5907, 113.9345, 8.0, "AF-SIM-01",
                "none", "0.0.0.0", true, "flat", "off", "none", 0,
                false, "calm", 0, 50, new double[]{-40, 55},
                false, 20.0, 2000.0, 10.0, 6.0,
                false, MeshRouterConfig.defaults(),
                false, SatRelayConfig.defaults(),
                false, 100.0,
                io.aerofleet.sim.celltower.CellTowerSimConfig.defaults(),
                OrchestrationConfig.defaults(),
                null);
    }

    /**
     * Parse --key value pairs; unknown keys print usage and fall back to defaults.
     * Supports both "--key=value" and "--key value" forms.
     */
    public static SimConfig parse(String[] args) {
        int port = 14540;
        int sysid = 1;
        double lat = 22.5907;
        double lon = 113.9345;
        double speed = 8.0;
        String name = "AF-SIM-01";
        String scenario = "none";
        String bindIp = "0.0.0.0";
        boolean failsafe = true;
        String terrain = "flat";
        String fence = "off";
        String targets = "none";
        int httpPort = 0;
        // M0b 环境气象参数默认值（FR-01/03/05）
        boolean envEnabled = false;
        String envScenario = "calm";
        long envSeed = 0;
        double envWindMax = 50;
        double[] envTempRange = new double[]{-40, 55};
        // M2 执行机构参数默认值（FR-01）
        boolean actuatorsEnabled = false;
        double sprayCapacity = 20.0;
        double sprayRateMax = 2000.0;
        double gripperPayloadMax = 10.0;
        double sprayCrosswindMax = 6.0;
        // M5 mesh 路由参数默认值（FR-01）
        boolean meshEnabled = false;
        // mesh 子参数先收集，构造期统一解析
        java.util.List<String> meshArgs = new java.util.ArrayList<>();
        // M7 sat-relay 路由参数默认值（FR-5.1）
        boolean satRelayEnabled = false;
        // sat-relay 子参数先收集，构造期统一解析
        java.util.List<String> satRelayArgs = new java.util.ArrayList<>();
        // M8 地形适配参数默认值（FR-01）
        boolean terrainAdaptEnabled = false;
        double terrainGridResolution = 100.0;
        // M6 移动基站载荷参数默认值（FR-CT-01）
        boolean celltowerEnabled = false;
        String cellTypeStr = "LTE";
        int cellTxPower = 20;
        int cellMaxTerminals = 200;
        int cellFreq = 1;
        double cellSignalThreshold = -80.0;
        double cellHandoverThreshold = -80.0;
        double cellLoadBalanceThreshold = 0.8;
        long cellHeartbeatTimeoutMs = 30_000L;
        // 丐版模式默认值：null = 完整版（既有行为不变，DFX 4.5）
        String budgetMode = null;

        for (int i = 0; i < args.length; i++) {
            String arg = args[i];
            String key;
            String value;
            int eq = arg.indexOf('=');
            if (arg.startsWith("--") && eq > 2) {
                key = arg.substring(2, eq);
                value = arg.substring(eq + 1);
            } else if (arg.startsWith("--") && i + 1 < args.length) {
                key = arg.substring(2);
                value = args[++i];
            } else {
                SimLog.warn("Ignoring unknown argument: " + arg);
                continue;
            }
            // M5 mesh 子参数收集（除 --mesh 开关外，其余 mesh-* 参数透传给 MeshRouterConfig.parse）
            if (key.startsWith("mesh-") || key.equals("cloud-backend")) {
                meshArgs.add("--" + key + "=" + value);
                continue;
            }
            // M7 sat-relay 子参数收集（除 --sat-relay 开关外，其余 sat-* 参数透传给 SatRelayConfig.parse）
            if (key.startsWith("sat-") && !key.equals("sat-relay")) {
                satRelayArgs.add("--" + key + "=" + value);
                continue;
            }
            try {
                switch (key) {
                    case "port" -> port = Integer.parseInt(value);
                    case "sysid" -> sysid = Integer.parseInt(value);
                    case "lat" -> lat = Double.parseDouble(value);
                    case "lon" -> lon = Double.parseDouble(value);
                    case "speed" -> speed = Double.parseDouble(value);
                    case "name" -> name = value;
                    case "scenario" -> scenario = value;
                    case "bind-ip" -> bindIp = value;
                    case "failsafe" -> failsafe = !value.equalsIgnoreCase("off")
                            && !value.equalsIgnoreCase("false");
                    case "terrain" -> terrain = value;
                    case "fence" -> fence = value;
                    case "targets" -> targets = value;
                    case "http-port" -> httpPort = Integer.parseInt(value);
                    // M0b 环境气象参数（FR-01/03/05）
                    case "env" -> envEnabled = true;
                    case "env-scenario" -> envScenario = value;  // EnvScenario.of 校验在 VirtualDrone 构造期
                    case "env-seed" -> envSeed = Long.parseLong(value);
                    case "env-wind-max" -> envWindMax = Double.parseDouble(value);
                    case "env-temp-range" -> {
                        String[] parts = value.split(":");
                        if (parts.length == 2) {
                            envTempRange = new double[]{
                                    Double.parseDouble(parts[0]), Double.parseDouble(parts[1])};
                        } else {
                            SimLog.warn("Invalid --env-temp-range: " + value + " (using default -40:55)");
                        }
                    }
                    // M2 执行机构参数（FR-01，DFX 4.4 配置可追溯）
                    case "actuators" -> actuatorsEnabled = true;
                    case "spray-capacity" -> sprayCapacity = Double.parseDouble(value);
                    case "spray-rate-max" -> sprayRateMax = Double.parseDouble(value);
                    case "gripper-payload-max" -> gripperPayloadMax = Double.parseDouble(value);
                    case "spray-crosswind-max" -> sprayCrosswindMax = Double.parseDouble(value);
                    // M5 mesh 路由参数（FR-01，DFX 4.4 配置可追溯）
                    case "mesh" -> meshEnabled = true;
                    // M7 sat-relay 路由参数（FR-5.1，DFX 4.4 配置可追溯）
                    case "sat-relay" -> satRelayEnabled = true;
                    // M8 地形适配参数（FR-01，DFX 4.4 配置可追溯）
                    case "terrain-adapt" -> terrainAdaptEnabled = true;
                    case "terrain-grid-resolution" -> terrainGridResolution = Double.parseDouble(value);
                    // M6 移动基站载荷参数（FR-CT-01，DFX 4.4 配置可追溯）
                    case "celltower" -> celltowerEnabled = true;
                    case "cell-type" -> cellTypeStr = value;
                    case "cell-tx-power" -> cellTxPower = Integer.parseInt(value);
                    case "cell-max-terminals" -> cellMaxTerminals = Integer.parseInt(value);
                    case "cell-freq" -> cellFreq = Integer.parseInt(value);
                    case "cell-signal-threshold" -> cellSignalThreshold = Double.parseDouble(value);
                    case "cell-handover-threshold" -> cellHandoverThreshold = Double.parseDouble(value);
                    case "cell-load-balance-threshold" -> cellLoadBalanceThreshold = Double.parseDouble(value);
                    case "cell-heartbeat-timeout-ms" -> cellHeartbeatTimeoutMs = Long.parseLong(value);
                    // 丐版模式参数（budget，DFX 4.4 配置可追溯）
                    case "budget" -> {
                        if ("toy".equals(value) || "standard".equals(value) || "advanced".equals(value)) {
                            budgetMode = value;
                        } else {
                            SimLog.warn("Invalid --budget: " + value + " (expected toy|standard|advanced, ignoring)");
                        }
                    }
                    default -> {
                        SimLog.warn("Unknown option --" + key);
                        printUsage();
                    }
                }
            } catch (NumberFormatException e) {
                SimLog.warn("Invalid value for --" + key + ": " + value + " (using default)");
            }
        }
        if (speed <= 0) {
            SimLog.warn("Speed must be positive, using default 8.0 m/s");
            speed = 8.0;
        }
        if (sysid <= 0 || sysid > 255) {
            SimLog.warn("Sysid out of range 1..255, using default 1");
            sysid = 1;
        }
        // FR-07 异常场景：药箱容量非法 → 警告 + 默认 20L
        if (sprayCapacity <= 0) {
            SimLog.warn("spray-capacity must be positive, using default 20.0 L");
            sprayCapacity = 20.0;
        }
        if (sprayRateMax <= 0) {
            SimLog.warn("spray-rate-max must be positive, using default 2000.0 mL/s");
            sprayRateMax = 2000.0;
        }
        if (gripperPayloadMax <= 0) {
            SimLog.warn("gripper-payload-max must be positive, using default 10.0 kg");
            gripperPayloadMax = 10.0;
        }
        // M5 mesh 配置解析（meshEnabled=true 时解析子参数，否则 defaults）
        MeshRouterConfig meshRouterConfig = meshEnabled
                ? MeshRouterConfig.parse(meshArgs.toArray(new String[0]))
                : MeshRouterConfig.defaults();
        // M7 sat-relay 配置解析（satRelayEnabled=true 时解析子参数，否则 defaults）
        SatRelayConfig satRelayConfig = satRelayEnabled
                ? SatRelayConfig.parse(satRelayArgs.toArray(new String[0]))
                : SatRelayConfig.defaults();
        return new SimConfig(port, sysid, lat, lon, speed, name, scenario, bindIp,
                failsafe, terrain, fence, targets, httpPort,
                envEnabled, envScenario, envSeed, envWindMax, envTempRange,
                actuatorsEnabled, sprayCapacity, sprayRateMax, gripperPayloadMax,
                sprayCrosswindMax,
                meshEnabled, meshRouterConfig,
                satRelayEnabled, satRelayConfig,
                terrainAdaptEnabled, terrainGridResolution,
                io.aerofleet.sim.celltower.CellTowerSimConfig.of(
                        celltowerEnabled, cellTypeStr, cellTxPower, cellMaxTerminals, cellFreq,
                        cellSignalThreshold, cellHandoverThreshold,
                        cellLoadBalanceThreshold, cellHeartbeatTimeoutMs),
                OrchestrationConfig.defaults(),
                budgetMode);
    }

    public static void printUsage() {
        System.out.println("[sim] usage: drone-sim [--port N] [--sysid N] [--lat D] [--lon D]"
                + " [--speed M] [--name STR] [--scenario SPEC] [--bind-ip IP]"
                + " [--failsafe on|off] [--terrain SPEC] [--fence SPEC]"
                + " [--targets SPEC] [--http-port N]");
        System.out.println("[sim]   --port     UDP bind port (default 14540)");
        System.out.println("[sim]   --sysid   MAVLink system id (default 1)");
        System.out.println("[sim]   --lat      start latitude (default 22.5907)");
        System.out.println("[sim]   --lon      start longitude (default 113.9345)");
        System.out.println("[sim]   --speed    cruise speed m/s (default 8.0)");
        System.out.println("[sim]   --name     vehicle model name (default AF-SIM-01)");
        System.out.println("[sim]   --bind-ip  local bind IP, e.g. 127.0.0.2 = own segment");
        System.out.println("[sim]   --failsafe autopilot failsafe layer, off for A/B tests");
        System.out.println("[sim]   --terrain  hill:northM:eastM:radiusM:heightM comma-joined");
        System.out.println("[sim]   --fence    n,e:n,e:...[:ceilingM] polygon around home");
        System.out.println("[sim]   --targets  kind:lat,lon[:speed[:heading[:turn]]],...");
        System.out.println("[sim]              kinds: vehicle | pedestrian | static");
        System.out.println("[sim]   --http-port ground-truth HTTP port (e.g. 18080)");
        System.out.println("[sim]   --scenario fault spec: kind:offsetSec[:durationSec[:param]]");
        System.out.println("[sim]              kinds (comma-joinable): gps-loss, link-loss,");
        System.out.println("[sim]              battery-fault, wind, gps-noise, imu-bias,");
        System.out.println("[sim]              baro-drift, mag-interference");
        System.out.println("[sim]              e.g. --scenario gps-loss:30:20,wind:0:9999:6");
        // M0b 环境气象参数说明（FR-01/03/05，DFX 4.4 配置可追溯）
        System.out.println("[sim]   --env            enable environment meteorology model (default off)");
        System.out.println("[sim]   --env-scenario   " + EnvScenario.names() + " (default calm)");
        System.out.println("[sim]   --env-seed       pseudo-random seed for deterministic env (default 0)");
        System.out.println("[sim]   --env-wind-max   wind speed cap m/s (default 50)");
        System.out.println("[sim]   --env-temp-range low:high °C (default -40:55)");
        // M2 执行机构参数说明（FR-01，DFX 4.4 配置可追溯）
        System.out.println("[sim]   --actuators            enable actuator layer: SprayPump + Gripper (default off)");
        System.out.println("[sim]   --spray-capacity       spray tank capacity L (default 20)");
        System.out.println("[sim]   --spray-rate-max       max spray rate mL/s (default 2000)");
        System.out.println("[sim]   --gripper-payload-max  max gripper payload kg (default 10)");
        System.out.println("[sim]   --spray-crosswind-max  crosswind no-spray threshold m/s (default 6)");
        // M5 mesh 路由参数说明（FR-01，DFX 4.4 配置可追溯）
        System.out.println("[sim]   --mesh                  enable mesh routing engine (default off)");
        System.out.println("[sim]   --mesh-hello-ms         HELLO broadcast interval ms (default 1000)");
        System.out.println("[sim]   --mesh-neighbor-timeout-ms  neighbor timeout ms (default 5000)");
        System.out.println("[sim]   --mesh-route-lifetime-ms    route lifetime ms (default 10000)");
        System.out.println("[sim]   --mesh-max-hops         max hops (default 15)");
        System.out.println("[sim]   --mesh-metric-w1        metric weight W1 hopCount (default 1.0)");
        System.out.println("[sim]   --mesh-metric-w2        metric weight W2 RSSI (default 0.5)");
        System.out.println("[sim]   --mesh-metric-w3        metric weight W3 delay (default 0.1)");
        System.out.println("[sim]   --mesh-reeval-threshold metric reeval threshold (default 0.5)");
        System.out.println("[sim]   --mesh-report-ms        topology report interval ms (default 2000)");
        System.out.println("[sim]   --mesh-group            mesh multicast group host:port (default 239.0.0.1:14550)");
        System.out.println("[sim]   --cloud-backend         cloud backend host:port for topology reports");
        // M7 sat-relay 参数说明（FR-5.1，DFX 4.4 配置可追溯）
        System.out.println("[sim]   --sat-relay                  enable sat-relay multi-layer engine (default off)");
        System.out.println("[sim]   --sat-elevation-threshold    visibility elevation threshold deg (default 10)");
        System.out.println("[sim]   --sat-hysteresis-ms          degradation hysteresis threshold ms (default 5000)");
        System.out.println("[sim]   --sat-strategy               routing strategy NEAR_FIRST/DELAY_OPTIMAL/BANDWIDTH_OPTIMAL/RELIABILITY_OPTIMAL");
        System.out.println("[sim]   --sat-constellation-size     LEO constellation size 10-100 (default 24)");
        System.out.println("[sim]   --sat-orbit-altitude         orbit altitude km 300-1200 (default 550)");
        System.out.println("[sim]   --sat-inclination            orbit inclination deg 0-180 (default 53)");
        System.out.println("[sim]   --sat-window-scan-step-ms    visibility window scan step ms (default 60000)");
        System.out.println("[sim]   --sat-link-report-ms         sat link status report interval ms (default 2000)");
        System.out.println("[sim]   --sat-pass-horizon-ms        pass schedule horizon ms (default 86400000)");
        // M6 移动基站载荷参数说明（FR-CT-01，DFX 4.4 配置可追溯）
        System.out.println("[sim]   --celltower                     enable cell tower payload (default off)");
        System.out.println("[sim]   --cell-type                     LTE/WIFI/LORA (default LTE)");
        System.out.println("[sim]   --cell-tx-power                 tx power dBm (default 20)");
        System.out.println("[sim]   --cell-max-terminals            max concurrent terminals (default 200)");
        System.out.println("[sim]   --cell-freq                     frequency channel (default 1)");
        System.out.println("[sim]   --cell-signal-threshold         access signal threshold dBm (default -80)");
        System.out.println("[sim]   --cell-handover-threshold       handover threshold dBm (default -80)");
        System.out.println("[sim]   --cell-load-balance-threshold   load balance threshold (default 0.8)");
        System.out.println("[sim]   --cell-heartbeat-timeout-ms     heartbeat timeout ms (default 30000)");
        // 丐版模式参数说明（budget，DFX 4.4 配置可追溯）
        System.out.println("[sim]   --budget              budget mode: toy|standard|advanced (default off = full)");
        System.out.println("[sim]              toy=ultrasonic+WiFi only, standard=GPS+ToF+LoRa, advanced=all sensors");
    }
}

