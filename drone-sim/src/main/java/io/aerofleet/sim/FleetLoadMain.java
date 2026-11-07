package io.aerofleet.sim;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 容量基线工具的入口，挂在 {@code drone-sim} 的 fat jar 上（与 {@code dock} 子命令同级）。
 * <p>
 * 两个子命令分别跑在两个进程里，刻意不合并：
 * <ul>
 *   <li>{@code inject} —— 制造 MAVLink 负载（CPU 花在编码与发包上）</li>
 *   <li>{@code probe} —— 连 K 条 WS 并测时延（CPU 花在 JSON 解析与计时上）</li>
 * </ul>
 * 同进程跑会让注入循环的 CPU 抢占污染时延测量，测出来的数字不能用来对客户承诺。
 * <p>
 * 用法：
 * <pre>
 * java -jar drone-sim-*-shaded.jar fleetload inject --drones 100 --hz 10 --duration 90
 * java -jar drone-sim-*-shaded.jar fleetload probe  --clients 10 --duration 60 --out r.json
 * </pre>
 */
public final class FleetLoadMain {

    private static final String DEFAULT_TARGET = "127.0.0.1:14550";
    private static final String DEFAULT_WS = "ws://127.0.0.1:8080/ws/telemetry";

    private FleetLoadMain() {
    }

    public static void main(String[] args) throws Exception {
        if (args.length < 2 || !"fleetload".equals(args[0])) {
            usage();
            System.exit(2);
        }
        Map<String, String> opts = parse(args, 2);
        String mode = args[1];
        switch (mode) {
            case "inject" -> runInject(opts);
            case "probe" -> runProbe(opts);
            default -> {
                System.err.println("未知子命令: " + mode);
                usage();
                System.exit(2);
            }
        }
    }

    private static void runInject(Map<String, String> opts) throws IOException {
        int drones = intOpt(opts, "drones", 10);
        int hz = intOpt(opts, "hz", 10);
        long durationSec = intOpt(opts, "duration", 0);
        String target = opts.getOrDefault("target", DEFAULT_TARGET);
        String host = target;
        int port = 14550;
        int colon = target.lastIndexOf(':');
        if (colon > 0) {
            host = target.substring(0, colon);
            port = Integer.parseInt(target.substring(colon + 1));
        }

        FleetInjector injector = new FleetInjector(drones, hz, host, port,
                durationSec > 0 ? durationSec * 1000L : 0);
        Runtime.getRuntime().addShutdownHook(new Thread(injector::stop));

        System.out.printf("[inject] %d 架 × %d Hz → %s:%d（时长 %s）%n",
                drones, hz, host, port, durationSec > 0 ? durationSec + "s" : "直到 Ctrl+C");

        long start = System.currentTimeMillis();
        injector.run();
        long elapsed = System.currentTimeMillis() - start;
        long sent = injector.framesSent();
        double actualHz = elapsed > 0 ? sent * 1000.0 / elapsed / drones : 0;
        System.out.printf("[inject] 结束：共发 %d 帧，实际 %.2f Hz/架（目标 %d Hz）%n",
                sent, actualHz, hz);
    }

    private static void runProbe(Map<String, String> opts) throws Exception {
        int clients = intOpt(opts, "clients", 1);
        long durationSec = intOpt(opts, "duration", 30);
        String url = opts.getOrDefault("url", DEFAULT_WS);

        System.out.printf("[probe] %d 条连接 → %s（测量 %ds）%n", clients, url, durationSec);
        ObjectNode report = WsProbe.run(url, clients, durationSec * 1000L);

        String json = new ObjectMapper().writerWithDefaultPrettyPrinter()
                .writeValueAsString(report);
        String out = opts.get("out");
        if (out != null) {
            Files.write(Path.of(out), json.getBytes(StandardCharsets.UTF_8));
            System.out.println("[probe] 报告已写入 " + out);
        }
        System.out.println(json);
    }

    private static int intOpt(Map<String, String> opts, String key, int def) {
        String v = opts.get(key);
        return v == null ? def : Integer.parseInt(v);
    }

    private static Map<String, String> parse(String[] args, int from) {
        Map<String, String> map = new LinkedHashMap<>();
        for (int i = from; i < args.length; i++) {
            String a = args[i];
            if (!a.startsWith("--")) {
                continue;
            }
            String key = a.substring(2);
            if (i + 1 < args.length && !args[i + 1].startsWith("--")) {
                map.put(key, args[++i]);
            } else {
                map.put(key, "true");
            }
        }
        return map;
    }

    private static void usage() {
        System.err.println("""
                用法:
                  fleetload inject --drones N --hz H [--target host:port] [--duration S]
                  fleetload probe  --clients K [--url ws://...] [--duration S] [--out report.json]

                注意 --hz 不得超过 100（后端 max-frame-rate-per-sysid 默认 100）。
                probe 的 --clients 超过 aerofleet.ws.max-connections-per-ip（默认 10）时，
                多余连接会被后端拒绝——探针会把它计入 connectFailures，不会静默忽略。""");
    }
}
