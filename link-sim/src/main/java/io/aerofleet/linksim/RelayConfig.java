package io.aerofleet.linksim;

import java.net.InetSocketAddress;

/**
 * 中继模式配置（FR-01~04，数据约束 6.1）。
 *
 * 承载一跳静态中继所需的两端口、两链路画像与两个运行时学习的对端地址。
 * 纯数据载体 + 参数解析 + usage 披露，无运行时转发逻辑（转发由 {@link RelayNode} 承载）。
 *
 * <p>地址学习语义（learn-once，design.md §2.1.3.1）：{@code gcsAddr}/{@code droneAddr}
 * 仅在为 {@code null} 时由接收循环首次赋值，已锁定后不被不同源地址覆盖。
 * 两个字段标记 {@code volatile} 以保证接收线程写、发送线程读之间的可见性。
 */
final class RelayConfig {

    /** 中继面向 GCS/后端侧默认端口。 */
    static final int DEFAULT_GCS_PORT = 14541;
    /** 中继面向远端飞机侧默认端口。 */
    static final int DEFAULT_RELAY_PORT = 14543;
    /** 默认链路画像，沿用现有点对点模式默认值。 */
    static final String DEFAULT_PROFILE = "lte";

    /** 中继面向 GCS/后端侧监听端口。 */
    final int gcsPort;
    /** 中继面向远端飞机侧监听端口。 */
    final int relayPort;
    /** 上行（GCS -> 飞机）链路画像。 */
    final LinkProfile uplink;
    /** 下行（飞机 -> GCS）链路画像。 */
    final LinkProfile downlink;

    /** 运行时学习到的 GCS/后端对端地址（volatile，跨接收/发送线程）。初始为 null。 */
    volatile InetSocketAddress gcsAddr;
    /** 运行时学习到的远端飞机对端地址（volatile，跨接收/发送线程）。初始为 null。 */
    volatile InetSocketAddress droneAddr;

    private RelayConfig(int gcsPort, int relayPort, LinkProfile uplink, LinkProfile downlink) {
        this.gcsPort = gcsPort;
        this.relayPort = relayPort;
        this.uplink = uplink;
        this.downlink = downlink;
        // gcsAddr / droneAddr 默认 null，由 RelayNode 接收循环 learn-once 赋值
    }

    /**
     * 解析 {@code --relay} 模式子参数；校验失败打印 {@link #usage()} 并 {@code exit(1)}。
     *
     * <p>识别参数：{@code --gcs-port} / {@code --relay-port} / {@code --uplink-profile}
     * / {@code --downlink-profile}；忽略 {@code --relay} 本身（模式开关由 LinkSimMain 预扫描处理）。
     * 未指定端口回退 {@link #DEFAULT_GCS_PORT} / {@link #DEFAULT_RELAY_PORT}；
     * 未指定画像回退 {@link #DEFAULT_PROFILE}。
     *
     * @param args 原始命令行（含 {@code --relay}）
     * @return 校验通过的 RelayConfig 实例
     */
    static RelayConfig parse(String[] args) {
        int gcsPort = DEFAULT_GCS_PORT;
        int relayPort = DEFAULT_RELAY_PORT;
        String uplinkName = DEFAULT_PROFILE;
        String downlinkName = DEFAULT_PROFILE;

        for (int i = 0; i < args.length; i++) {
            String a = args[i];
            if (a.equals("--relay")) {
                // 模式开关，由 LinkSimMain 预扫描处理，此处跳过
                continue;
            } else if (a.equals("--gcs-port") && i + 1 < args.length) {
                gcsPort = parsePort(args[++i], "--gcs-port");
            } else if (a.equals("--relay-port") && i + 1 < args.length) {
                relayPort = parsePort(args[++i], "--relay-port");
            } else if (a.equals("--uplink-profile") && i + 1 < args.length) {
                uplinkName = args[++i];
            } else if (a.equals("--downlink-profile") && i + 1 < args.length) {
                downlinkName = args[++i];
            }
        }

        // 校验端口范围
        validatePort(gcsPort, "--gcs-port");
        validatePort(relayPort, "--relay-port");
        // 校验端口互异
        if (gcsPort == relayPort) {
            System.out.println("[mesh-relay] gcs-port and relay-port must differ: "
                    + "gcs-port=" + gcsPort + " relay-port=" + relayPort);
            usage();
            System.exit(1);
            return null; // 不可达，exit 已终止
        }

        // 校验画像名（沿用现有 LinkSimMain.java:51-57 报错模式）
        LinkProfile uplink = LinkProfile.of(uplinkName);
        if (uplink == null) {
            System.out.println("[mesh-relay] unknown profile: " + uplinkName);
            System.out.println("[mesh-relay] available: " + LinkProfile.names());
            usage();
            System.exit(1);
            return null;
        }
        LinkProfile downlink = LinkProfile.of(downlinkName);
        if (downlink == null) {
            System.out.println("[mesh-relay] unknown profile: " + downlinkName);
            System.out.println("[mesh-relay] available: " + LinkProfile.names());
            usage();
            System.exit(1);
            return null;
        }

        return new RelayConfig(gcsPort, relayPort, uplink, downlink);
    }

    /** 解析单个端口整数，失败打印 usage 并 exit(1)。 */
    private static int parsePort(String raw, String flag) {
        try {
            return Integer.parseInt(raw);
        } catch (NumberFormatException e) {
            System.out.println("[mesh-relay] invalid " + flag + ": " + raw);
            usage();
            System.exit(1);
            return -1; // 不可达
        }
    }

    /** 校验端口范围 [1,65535]，失败打印 usage 并 exit(1)。 */
    private static void validatePort(int port, String flag) {
        if (port < 1 || port > 65535) {
            System.out.println("[mesh-relay] " + flag + " out of range [1,65535]: " + port);
            usage();
            System.exit(1);
        }
    }

    /**
     * 完整披露所有 relay 参数及默认值（DFX 4.4 配置可追溯）。
     */
    static void usage() {
        System.out.println("[mesh-relay] usage: link-sim --relay"
                + " [--gcs-port N] [--relay-port N]"
                + " [--uplink-profile P] [--downlink-profile P]");
        System.out.println("[mesh-relay]   --gcs-port        relay port facing GCS/backend (default "
                + DEFAULT_GCS_PORT + ")");
        System.out.println("[mesh-relay]   --relay-port      relay port facing remote drone (default "
                + DEFAULT_RELAY_PORT + ")");
        System.out.println("[mesh-relay]   --uplink-profile  uplink (gcs->drone) profile (default "
                + DEFAULT_PROFILE + "): " + LinkProfile.names());
        System.out.println("[mesh-relay]   --downlink-profile downlink (drone->gcs) profile (default "
                + DEFAULT_PROFILE + "): " + LinkProfile.names());
    }
}