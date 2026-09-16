package io.aerofleet.linksim;

import java.net.InetSocketAddress;

/**
 * 多跳中继配置（M5 应急 mesh，FR-06~08a，DFX 5.5.1/5.5.2）。
 * <p>
 * 扩展 {@link RelayConfig}，新增多跳开关、最大跳数与 mesh 组播地址。
 * 保持 M0a 既有配置解析不变（{@code multiHopEnabled=false} 时完全退化为一跳透明转发）。
 * <p>
 * 数据约束：{@code maxHops} 固定 15，{@code meshGroupAddress} 默认 239.0.0.1:14550。
 */
public final class MultiHopRelayConfig {

    /** 最大跳数固定值（FR-08）。 */
    public static final int MAX_HOPS = 15;
    /** 默认 mesh 组播地址。 */
    public static final InetSocketAddress DEFAULT_MESH_GROUP =
            new InetSocketAddress("239.0.0.1", 14550);

    /** 多跳中继启用开关（默认 false，--multi-hop）。 */
    public final boolean multiHopEnabled;
    /** 最大跳数（固定 15）。 */
    public final int maxHops;
    /** mesh 组播地址（默认 239.0.0.1:14550，--mesh-group）。 */
    public final InetSocketAddress meshGroupAddress;

    public MultiHopRelayConfig(boolean multiHopEnabled, int maxHops,
                               InetSocketAddress meshGroupAddress) {
        this.multiHopEnabled = multiHopEnabled;
        this.maxHops = maxHops;
        this.meshGroupAddress = meshGroupAddress;
    }

    /** 默认配置：多跳禁用。 */
    public static MultiHopRelayConfig defaults() {
        return new MultiHopRelayConfig(false, MAX_HOPS, DEFAULT_MESH_GROUP);
    }

    /**
     * CLI 解析：从 --key value 对中提取多跳配置。
     * <p>
     * 识别参数：{@code --multi-hop}（开关）/ {@code --mesh-group host:port}。
     *
     * @param args 原始命令行
     * @return 解析后的配置（未指定项使用默认值）
     */
    public static MultiHopRelayConfig parse(String[] args) {
        boolean multiHopEnabled = false;
        int maxHops = MAX_HOPS;
        InetSocketAddress meshGroupAddress = DEFAULT_MESH_GROUP;

        for (int i = 0; i < args.length; i++) {
            String a = args[i];
            if (a.equals("--multi-hop")) {
                multiHopEnabled = true;
            } else if (a.equals("--mesh-group") && i + 1 < args.length) {
                meshGroupAddress = parseAddr(args[++i]);
            }
        }
        return new MultiHopRelayConfig(multiHopEnabled, maxHops, meshGroupAddress);
    }

    /** 解析 host:port 为 InetSocketAddress。 */
    private static InetSocketAddress parseAddr(String s) {
        int colon = s.lastIndexOf(':');
        if (colon < 0) {
            return new InetSocketAddress(s, 14550);
        }
        String host = s.substring(0, colon);
        int port = Integer.parseInt(s.substring(colon + 1));
        return new InetSocketAddress(host, port);
    }

    @Override
    public String toString() {
        return "MultiHopRelayConfig{multiHop=" + multiHopEnabled
                + ", maxHops=" + maxHops + ", meshGroup=" + meshGroupAddress + "}";
    }
}