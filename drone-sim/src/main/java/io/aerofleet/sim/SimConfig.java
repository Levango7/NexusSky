package io.aerofleet.sim;

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

    private SimConfig(int port, int sysid, double lat, double lon, double speed,
                      String name, String scenario, String bindIp, boolean failsafe,
                      String terrain, String fence, String targets, int httpPort,
                      boolean envEnabled, String envScenario, long envSeed,
                      double envWindMax, double[] envTempRange,
                      boolean actuatorsEnabled, double sprayCapacity,
                      double sprayRateMax, double gripperPayloadMax,
                      double sprayCrosswindMax) {
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
    }

    /** Defaults: Shenzhen University Town area, 8 m/s cruise, port 14540, sysid 1. */
    public static SimConfig defaults() {
        return new SimConfig(14540, 1, 22.5907, 113.9345, 8.0, "AF-SIM-01",
                "none", "0.0.0.0", true, "flat", "off", "none", 0,
                false, "calm", 0, 50, new double[]{-40, 55},
                false, 20.0, 2000.0, 10.0, 6.0);
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
        return new SimConfig(port, sysid, lat, lon, speed, name, scenario, bindIp,
                failsafe, terrain, fence, targets, httpPort,
                envEnabled, envScenario, envSeed, envWindMax, envTempRange,
                actuatorsEnabled, sprayCapacity, sprayRateMax, gripperPayloadMax,
                sprayCrosswindMax);
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
    }
}
