package io.aerofleet.sim;

import java.util.concurrent.CountDownLatch;

/**
 * 机巢模拟器独立入口（F2）：与 DroneSimMain 平行，可单独运行。
 *
 * <pre>
 * # HTTP 回环模式（默认）：云端 transport=sim
 * java -jar aerofleet-drone-sim-0.1.0-SNAPSHOT-shaded.jar dock \
 *      --sn DOCK-001 [--name "A区机巢"] [--http-port 18081] \
 *      [--drone-sysid 9] [--osd-url http://127.0.0.1:18099/api/v1/docks/osd] \
 *      [--osd-period-ms 5000] [--temp-fixed 75]
 *
 * # MQTT 模式：云端 transport=mqtt（设备侧按 DJI 官方 topic/属性名上报）
 * java -jar aerofleet-drone-sim-0.1.0-SNAPSHOT-shaded.jar dock \
 *      --sn DOCK-001 --mqtt-url tcp://127.0.0.1:1883 \
 *      [--mqtt-username U] [--mqtt-password P] [--osd-period-ms 2000]
 * </pre>
 *
 * 无 --osd-url 时不主动推 OSD（纯被动模式，供单测/手工 curl 用）。
 * MQTT 模式下**不暴露** HTTP /services 端点——走错传输要立刻失败而不是悄悄成功。
 */
public final class DockSimMain {

    public static void main(String[] args) throws Exception {
        String sn = null;
        String name = "DockSim";
        int httpPort = 18081;
        int droneSysid = 9;
        String osdUrl = null;
        long osdPeriodMs = 5000;
        Double tempFixed = null;
        String mqttUrl = null;
        String mqttUser = null;
        String mqttPassword = null;

        for (int i = 0; i < args.length; i++) {
            switch (args[i]) {
                case "--sn" -> sn = next(args, ++i);
                case "--name" -> name = next(args, ++i);
                case "--http-port" -> httpPort = Integer.parseInt(next(args, ++i));
                case "--drone-sysid" -> droneSysid = Integer.parseInt(next(args, ++i));
                case "--osd-url" -> osdUrl = next(args, ++i);
                case "--osd-period-ms" -> osdPeriodMs = Long.parseLong(next(args, ++i));
                case "--temp-fixed" -> tempFixed = Double.parseDouble(next(args, ++i));
                case "--mqtt-url" -> mqttUrl = next(args, ++i);
                case "--mqtt-username" -> mqttUser = next(args, ++i);
                case "--mqtt-password" -> mqttPassword = next(args, ++i);
                default -> { /* 未知参数忽略：与 DroneSimMain 的宽松口径一致 */ }
            }
        }
        if (sn == null) {
            System.err.println("usage: dock --sn <SN> [--name N] [--http-port P] [--drone-sysid S]"
                    + " [--osd-url URL] [--osd-period-ms MS] [--temp-fixed C]"
                    + " [--mqtt-url tcp://host:1883] [--mqtt-username U] [--mqtt-password P]");
            System.exit(2);
        }

        CountDownLatch stop = new CountDownLatch(1);
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            SimLog.info("dock-sim shutting down");
            stop.countDown();
        }));

        if (mqttUrl != null) {
            // MQTT 模式：设备侧对端（官方 topic + 官方属性名），HTTP 仅留 /health、/osd
            DockSimCore core = new DockSimCore(droneSysid, tempFixed);
            try (DockSimMqttPeer peer = new DockSimMqttPeer(sn, core, mqttUrl,
                    mqttUser, mqttPassword, osdPeriodMs);
                 DockSim http = new DockSim(sn, name, httpPort, osdUrl, osdPeriodMs,
                         droneSysid, tempFixed, false)) {
                peer.start();
                http.start();
                SimLog.info("dock-sim running in MQTT mode. Press Ctrl+C to stop.");
                stop.await();
            }
            return;
        }

        try (DockSim dock = new DockSim(sn, name, httpPort, osdUrl, osdPeriodMs, droneSysid, tempFixed)) {
            dock.start();
            SimLog.info("dock-sim running. Press Ctrl+C to stop.");
            stop.await();
        }
    }

    private static String next(String[] args, int i) {
        if (i >= args.length) {
            throw new IllegalArgumentException("missing value for " + args[i - 1]);
        }
        return args[i];
    }
}
