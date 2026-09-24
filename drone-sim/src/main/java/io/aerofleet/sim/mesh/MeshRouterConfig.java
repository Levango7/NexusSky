package io.aerofleet.sim.mesh;

import java.net.InetSocketAddress;

/**
 * Mesh 路由引擎配置（M5 应急 mesh，数据约束 7.8）。
 * <p>
 * 所有字段 final 不可变。承载所有可配置参数：HELLO 周期、邻居超时、路由生命周期、
 * 最大跳数、度量权重、拓扑上报周期、mesh 组播地址与 cloud-backend 地址、
 * 传输层类型（UDP/LoRa）及 LoRa 物理层参数。
 * <p>
 * 默认值（design.md §2.1.2 配置项取值策略）：
 * <pre>
 * helloIntervalMs         = 1000 ms
 * neighborTimeoutMs       = 5000 ms
 * routeLifetimeMs         = 10000 ms
 * maxHops                 = 15（固定）
 * metricW1 (hopCount)     = 1.0
 * metricW2 (RSSI)         = 0.5
 * metricW3 (delay)        = 0.1
 * metricReevalThreshold   = 0.5
 * topologyReportIntervalMs = 2000 ms
 * meshGroupAddress        = 239.0.0.1:14550
 * cloudBackendAddress     = null（不上报）
 * transportType           = UDP（默认 UDP，可选 LoRa）
 * loRaFrequencyMHz        = 433.0
 * loRaSpreadingFactor     = 7
 * loRaBandwidthKHz        = 125
 * loRaCodingRate          = 5（4/5）
 * loRaTxPowerDbm          = 20
 * loRaMaxPayloadBytes     = 50
 * </pre>
 */
public final class MeshRouterConfig {

    /** 传输层类型。 */
    public enum TransportType {
        /** MAVLink over UDP（默认，高带宽低延迟）。 */
        UDP,
        /** MAVLink over LoRa（低成本远距离，SX1278 433MHz，自动分片/重组）。 */
        LORA
    }

    /** HELLO 广播周期（ms）。 */
    public final long helloIntervalMs;
    /** 邻居超时阈值（ms）。 */
    public final long neighborTimeoutMs;
    /** 路由表项生命周期（ms）。 */
    public final long routeLifetimeMs;
    /** 最大跳数（固定 15）。 */
    public final int maxHops;
    /** 度量权重 W1（hopCount）。 */
    public final double metricW1;
    /** 度量权重 W2（RSSI）。 */
    public final double metricW2;
    /** 度量权重 W3（delay）。 */
    public final double metricW3;
    /** 度量重评估阈值（变化超过此值触发主备切换）。 */
    public final double metricReevalThreshold;
    /** 拓扑快照上报周期（ms）。 */
    public final long topologyReportIntervalMs;
    /** mesh 组播地址（HELLO/RREQ 广播目标）。 */
    public final InetSocketAddress meshGroupAddress;
    /** cloud-backend 地址（拓扑快照单播目标，null 表示不上报）。 */
    public final InetSocketAddress cloudBackendAddress;
    /** 传输层类型（UDP 或 LoRa），默认 UDP。 */
    public final TransportType transportType;
    /** LoRa 频率（MHz），如 433.0，仅 transportType=LORA 时生效。 */
    public final double loRaFrequencyMHz;
    /** LoRa 扩频因子（7-12），仅 transportType=LORA 时生效。 */
    public final int loRaSpreadingFactor;
    /** LoRa 带宽（kHz），如 125，仅 transportType=LORA 时生效。 */
    public final int loRaBandwidthKHz;
    /** LoRa 编码率分母（5 表示 4/5, 8 表示 4/8），仅 transportType=LORA 时生效。 */
    public final int loRaCodingRate;
    /** LoRa 发射功率（dBm），如 20，仅 transportType=LORA 时生效。 */
    public final int loRaTxPowerDbm;
    /** LoRa 单帧最大载荷（字节，含 3 字节分片头），仅 transportType=LORA 时生效。 */
    public final int loRaMaxPayloadBytes;
    /** 是否启用动态 MAX_HOPS 调整（FR-18），默认 false 保持向后兼容。 */
    public final boolean dynamicMaxHopsEnabled;

    public MeshRouterConfig(long helloIntervalMs, long neighborTimeoutMs, long routeLifetimeMs,
                            int maxHops, double metricW1, double metricW2, double metricW3,
                            double metricReevalThreshold, long topologyReportIntervalMs,
                            InetSocketAddress meshGroupAddress, InetSocketAddress cloudBackendAddress) {
        this(helloIntervalMs, neighborTimeoutMs, routeLifetimeMs, maxHops,
                metricW1, metricW2, metricW3, metricReevalThreshold, topologyReportIntervalMs,
                meshGroupAddress, cloudBackendAddress,
                TransportType.UDP, 433.0, 7, 125, 5, 20, 50, false);
    }

    /**
     * 全参数构造（含传输层类型与 LoRa 物理层参数）。
     *
     * @param transportType         传输层类型，UDP 或 LORA，默认 UDP
     * @param loRaFrequencyMHz      LoRa 频率（MHz），仅 LORA 时生效
     * @param loRaSpreadingFactor   LoRa 扩频因子（7-12）
     * @param loRaBandwidthKHz      LoRa 带宽（kHz）
     * @param loRaCodingRate        LoRa 编码率分母（5 或 8）
     * @param loRaTxPowerDbm        LoRa 发射功率（dBm）
     * @param loRaMaxPayloadBytes   LoRa 单帧最大载荷（字节，&gt; 3）
     */
    public MeshRouterConfig(long helloIntervalMs, long neighborTimeoutMs, long routeLifetimeMs,
                            int maxHops, double metricW1, double metricW2, double metricW3,
                            double metricReevalThreshold, long topologyReportIntervalMs,
                            InetSocketAddress meshGroupAddress, InetSocketAddress cloudBackendAddress,
                            TransportType transportType, double loRaFrequencyMHz,
                            int loRaSpreadingFactor, int loRaBandwidthKHz, int loRaCodingRate,
                            int loRaTxPowerDbm, int loRaMaxPayloadBytes) {
        this(helloIntervalMs, neighborTimeoutMs, routeLifetimeMs, maxHops,
                metricW1, metricW2, metricW3, metricReevalThreshold, topologyReportIntervalMs,
                meshGroupAddress, cloudBackendAddress,
                transportType, loRaFrequencyMHz, loRaSpreadingFactor, loRaBandwidthKHz,
                loRaCodingRate, loRaTxPowerDbm, loRaMaxPayloadBytes, false);
    }

    /**
     * 全参数构造（含传输层类型、LoRa 物理层参数与动态 MAX_HOPS 开关）。
     *
     * @param dynamicMaxHopsEnabled  是否启用动态 MAX_HOPS 调整（FR-18），默认 false
     */
    public MeshRouterConfig(long helloIntervalMs, long neighborTimeoutMs, long routeLifetimeMs,
                            int maxHops, double metricW1, double metricW2, double metricW3,
                            double metricReevalThreshold, long topologyReportIntervalMs,
                            InetSocketAddress meshGroupAddress, InetSocketAddress cloudBackendAddress,
                            TransportType transportType, double loRaFrequencyMHz,
                            int loRaSpreadingFactor, int loRaBandwidthKHz, int loRaCodingRate,
                            int loRaTxPowerDbm, int loRaMaxPayloadBytes,
                            boolean dynamicMaxHopsEnabled) {
        // P1-fix(Major 11): 参数验证，非法值抛 IllegalArgumentException
        if (helloIntervalMs <= 0) {
            throw new IllegalArgumentException("helloIntervalMs must be > 0, got " + helloIntervalMs);
        }
        if (neighborTimeoutMs <= 0) {
            throw new IllegalArgumentException("neighborTimeoutMs must be > 0, got " + neighborTimeoutMs);
        }
        if (routeLifetimeMs <= 0) {
            throw new IllegalArgumentException("routeLifetimeMs must be > 0, got " + routeLifetimeMs);
        }
        if (maxHops <= 0) {
            throw new IllegalArgumentException("maxHops must be > 0, got " + maxHops);
        }
        if (metricW1 < 0) {
            throw new IllegalArgumentException("metricW1 must be >= 0, got " + metricW1);
        }
        if (metricW2 < 0) {
            throw new IllegalArgumentException("metricW2 must be >= 0, got " + metricW2);
        }
        if (metricW3 < 0) {
            throw new IllegalArgumentException("metricW3 must be >= 0, got " + metricW3);
        }
        if (metricReevalThreshold < 0) {
            throw new IllegalArgumentException("metricReevalThreshold must be >= 0, got " + metricReevalThreshold);
        }
        if (topologyReportIntervalMs <= 0) {
            throw new IllegalArgumentException("topologyReportIntervalMs must be > 0, got " + topologyReportIntervalMs);
        }
        if (meshGroupAddress == null) {
            throw new IllegalArgumentException("meshGroupAddress must not be null");
        }
        if (transportType == null) {
            throw new IllegalArgumentException("transportType must not be null");
        }
        // LoRa 参数验证（仅 LORA 模式下严格校验，UDP 模式下仅记录默认值）
        if (loRaSpreadingFactor < 7 || loRaSpreadingFactor > 12) {
            throw new IllegalArgumentException(
                "loRaSpreadingFactor must be 7-12, got " + loRaSpreadingFactor);
        }
        if (loRaMaxPayloadBytes <= 3) {
            throw new IllegalArgumentException(
                "loRaMaxPayloadBytes must be > 3 (header size), got " + loRaMaxPayloadBytes);
        }
        this.helloIntervalMs = helloIntervalMs;
        this.neighborTimeoutMs = neighborTimeoutMs;
        this.routeLifetimeMs = routeLifetimeMs;
        this.maxHops = maxHops;
        this.metricW1 = metricW1;
        this.metricW2 = metricW2;
        this.metricW3 = metricW3;
        this.metricReevalThreshold = metricReevalThreshold;
        this.topologyReportIntervalMs = topologyReportIntervalMs;
        this.meshGroupAddress = meshGroupAddress;
        this.cloudBackendAddress = cloudBackendAddress;
        this.transportType = transportType;
        this.loRaFrequencyMHz = loRaFrequencyMHz;
        this.loRaSpreadingFactor = loRaSpreadingFactor;
        this.loRaBandwidthKHz = loRaBandwidthKHz;
        this.loRaCodingRate = loRaCodingRate;
        this.loRaTxPowerDbm = loRaTxPowerDbm;
        this.loRaMaxPayloadBytes = loRaMaxPayloadBytes;
        this.dynamicMaxHopsEnabled = dynamicMaxHopsEnabled;
    }

    /** 默认配置。 */
    public static MeshRouterConfig defaults() {
        return new MeshRouterConfig(
                1000L, 5000L, 10000L, 15,
                1.0, 0.5, 0.1, 0.5,
                2000L,
                new InetSocketAddress("239.0.0.1", 14550),
                null);
    }

    /** 默认配置（含动态 MAX_HOPS 开关）。 */
    public static MeshRouterConfig defaults(boolean dynamicMaxHopsEnabled) {
        return new MeshRouterConfig(
                1000L, 5000L, 10000L, 15,
                1.0, 0.5, 0.1, 0.5,
                2000L,
                new InetSocketAddress("239.0.0.1", 14550),
                null,
                TransportType.UDP, 433.0, 7, 125, 5, 20, 50,
                dynamicMaxHopsEnabled);
    }

    /**
     * CLI 解析：从 --key value 对中提取 mesh 配置。
     * <p>
     * 识别参数：{@code --mesh-hello-ms} / {@code --mesh-neighbor-timeout-ms} /
     * {@code --mesh-route-lifetime-ms} / {@code --mesh-max-hops} /
     * {@code --mesh-metric-w1} / {@code --mesh-metric-w2} / {@code --mesh-metric-w3} /
     * {@code --mesh-reeval-threshold} / {@code --mesh-report-ms} /
     * {@code --mesh-group} / {@code --cloud-backend} /
     * {@code --mesh-transport} (UDP|LORA) /
     * {@code --lora-freq} / {@code --lora-sf} / {@code --lora-bw} /
     * {@code --lora-cr} / {@code --lora-tx-power} / {@code --lora-max-payload}
     *
     * @param args 原始命令行
     * @return 解析后的配置（未指定项使用默认值）
     */
    public static MeshRouterConfig parse(String[] args) {
        MeshRouterConfig defaults = defaults();
        long helloIntervalMs = defaults.helloIntervalMs;
        long neighborTimeoutMs = defaults.neighborTimeoutMs;
        long routeLifetimeMs = defaults.routeLifetimeMs;
        int maxHops = defaults.maxHops;
        double metricW1 = defaults.metricW1;
        double metricW2 = defaults.metricW2;
        double metricW3 = defaults.metricW3;
        double metricReevalThreshold = defaults.metricReevalThreshold;
        long topologyReportIntervalMs = defaults.topologyReportIntervalMs;
        InetSocketAddress meshGroupAddress = defaults.meshGroupAddress;
        InetSocketAddress cloudBackendAddress = defaults.cloudBackendAddress;
        TransportType transportType = defaults.transportType;
        double loRaFrequencyMHz = defaults.loRaFrequencyMHz;
        int loRaSpreadingFactor = defaults.loRaSpreadingFactor;
        int loRaBandwidthKHz = defaults.loRaBandwidthKHz;
        int loRaCodingRate = defaults.loRaCodingRate;
        int loRaTxPowerDbm = defaults.loRaTxPowerDbm;
        int loRaMaxPayloadBytes = defaults.loRaMaxPayloadBytes;
        boolean dynamicMaxHopsEnabled = defaults.dynamicMaxHopsEnabled;

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
                continue;
            }
            try {
                switch (key) {
                    case "mesh-hello-ms" -> helloIntervalMs = Long.parseLong(value);
                    case "mesh-neighbor-timeout-ms" -> neighborTimeoutMs = Long.parseLong(value);
                    case "mesh-route-lifetime-ms" -> routeLifetimeMs = Long.parseLong(value);
                    case "mesh-max-hops" -> maxHops = Integer.parseInt(value);
                    case "mesh-metric-w1" -> metricW1 = Double.parseDouble(value);
                    case "mesh-metric-w2" -> metricW2 = Double.parseDouble(value);
                    case "mesh-metric-w3" -> metricW3 = Double.parseDouble(value);
                    case "mesh-reeval-threshold" -> metricReevalThreshold = Double.parseDouble(value);
                    case "mesh-report-ms" -> topologyReportIntervalMs = Long.parseLong(value);
                    case "mesh-group" -> meshGroupAddress = parseAddr(value);
                    case "cloud-backend" -> cloudBackendAddress = parseAddr(value);
                    case "mesh-transport" -> {
                        try {
                            transportType = TransportType.valueOf(value.toUpperCase());
                        } catch (IllegalArgumentException e) {
                            io.aerofleet.sim.SimLog.warn("Invalid mesh-transport: " + value + ", fallback to default");
                        }
                    }
                    case "lora-freq" -> loRaFrequencyMHz = Double.parseDouble(value);
                    case "lora-sf" -> loRaSpreadingFactor = Integer.parseInt(value);
                    case "lora-bw" -> loRaBandwidthKHz = Integer.parseInt(value);
                    case "lora-cr" -> loRaCodingRate = Integer.parseInt(value);
                    case "lora-tx-power" -> loRaTxPowerDbm = Integer.parseInt(value);
                    case "lora-max-payload" -> loRaMaxPayloadBytes = Integer.parseInt(value);
                    case "mesh-dynamic-max-hops" -> dynamicMaxHopsEnabled = Boolean.parseBoolean(value);
                    default -> { /* 忽略非 mesh 参数 */ }
                }
            } catch (NumberFormatException e) {
                io.aerofleet.sim.SimLog.warn("Invalid value for --" + key + ": " + value);
            }
        }
        // P1-fix(Major 11): parse 非法值回退默认值（CLI 友好）
        if (helloIntervalMs <= 0) {
            io.aerofleet.sim.SimLog.warn("Invalid helloIntervalMs=" + helloIntervalMs + ", fallback to default");
            helloIntervalMs = defaults.helloIntervalMs;
        }
        if (neighborTimeoutMs <= 0) {
            io.aerofleet.sim.SimLog.warn("Invalid neighborTimeoutMs=" + neighborTimeoutMs + ", fallback to default");
            neighborTimeoutMs = defaults.neighborTimeoutMs;
        }
        if (routeLifetimeMs <= 0) {
            io.aerofleet.sim.SimLog.warn("Invalid routeLifetimeMs=" + routeLifetimeMs + ", fallback to default");
            routeLifetimeMs = defaults.routeLifetimeMs;
        }
        if (maxHops <= 0) {
            io.aerofleet.sim.SimLog.warn("Invalid maxHops=" + maxHops + ", fallback to default");
            maxHops = defaults.maxHops;
        }
        if (metricW1 < 0) {
            io.aerofleet.sim.SimLog.warn("Invalid metricW1=" + metricW1 + ", fallback to default");
            metricW1 = defaults.metricW1;
        }
        if (metricW2 < 0) {
            io.aerofleet.sim.SimLog.warn("Invalid metricW2=" + metricW2 + ", fallback to default");
            metricW2 = defaults.metricW2;
        }
        if (metricW3 < 0) {
            io.aerofleet.sim.SimLog.warn("Invalid metricW3=" + metricW3 + ", fallback to default");
            metricW3 = defaults.metricW3;
        }
        if (metricReevalThreshold < 0) {
            io.aerofleet.sim.SimLog.warn("Invalid metricReevalThreshold=" + metricReevalThreshold + ", fallback to default");
            metricReevalThreshold = defaults.metricReevalThreshold;
        }
        if (topologyReportIntervalMs <= 0) {
            io.aerofleet.sim.SimLog.warn("Invalid topologyReportIntervalMs=" + topologyReportIntervalMs + ", fallback to default");
            topologyReportIntervalMs = defaults.topologyReportIntervalMs;
        }
        if (meshGroupAddress == null) {
            meshGroupAddress = defaults.meshGroupAddress;
        }
        if (loRaSpreadingFactor < 7 || loRaSpreadingFactor > 12) {
            io.aerofleet.sim.SimLog.warn("Invalid loRaSpreadingFactor=" + loRaSpreadingFactor + ", fallback to default");
            loRaSpreadingFactor = defaults.loRaSpreadingFactor;
        }
        if (loRaMaxPayloadBytes <= 3) {
            io.aerofleet.sim.SimLog.warn("Invalid loRaMaxPayloadBytes=" + loRaMaxPayloadBytes + ", fallback to default");
            loRaMaxPayloadBytes = defaults.loRaMaxPayloadBytes;
        }
        return new MeshRouterConfig(helloIntervalMs, neighborTimeoutMs, routeLifetimeMs,
                maxHops, metricW1, metricW2, metricW3, metricReevalThreshold,
                topologyReportIntervalMs, meshGroupAddress, cloudBackendAddress,
                transportType, loRaFrequencyMHz, loRaSpreadingFactor, loRaBandwidthKHz,
                loRaCodingRate, loRaTxPowerDbm, loRaMaxPayloadBytes, dynamicMaxHopsEnabled);
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
        return "MeshRouterConfig{hello=" + helloIntervalMs + "ms, neighborTimeout="
                + neighborTimeoutMs + "ms, routeLifetime=" + routeLifetimeMs
                + "ms, maxHops=" + maxHops + ", metricW1=" + metricW1
                + ", metricW2=" + metricW2 + ", metricW3=" + metricW3
                + ", reevalThreshold=" + metricReevalThreshold
                + ", reportInterval=" + topologyReportIntervalMs + "ms"
                + ", meshGroup=" + meshGroupAddress
                + ", cloudBackend=" + cloudBackendAddress
                + ", transport=" + transportType
                + ", loRa=[freq=" + loRaFrequencyMHz + "MHz, SF=" + loRaSpreadingFactor
                + ", BW=" + loRaBandwidthKHz + "kHz, CR=4/" + loRaCodingRate
                + ", txPower=" + loRaTxPowerDbm + "dBm, maxPayload=" + loRaMaxPayloadBytes + "B]"
                + ", dynamicMaxHops=" + dynamicMaxHopsEnabled
                + "}";
    }
}