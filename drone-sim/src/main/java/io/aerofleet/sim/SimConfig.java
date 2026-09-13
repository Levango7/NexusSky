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

    private SimConfig(int port, int sysid, double lat, double lon, double speed,
                      String name, String scenario, String bindIp, boolean failsafe,
                      String terrain, String fence, String targets, int httpPort) {
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
    }

    /** Defaults: Shenzhen University Town area, 8 m/s cruise, port 14540, sysid 1. */
    public static SimConfig defaults() {
        return new SimConfig(14540, 1, 22.5907, 113.9345, 8.0, "AF-SIM-01",
                "none", "0.0.0.0", true, "flat", "off", "none", 0);
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
        return new SimConfig(port, sysid, lat, lon, speed, name, scenario, bindIp,
                failsafe, terrain, fence, targets, httpPort);
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
    }
}
