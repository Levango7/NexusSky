package io.aerofleet.cloud.metrics;

import io.aerofleet.cloud.gateway.DeviceRegistry;
import io.aerofleet.cloud.mission.formation.FormationService;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.lang.Nullable;
import org.springframework.stereotype.Component;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;

/**
 * 自定义无人机集群可观测性指标收集器（Micrometer）。
 *
 * <p>指标清单：
 * <ul>
 *   <li>{@code aerofleet.drones.online}     — 当前在线无人机数（Gauge）</li>
 *   <li>{@code aerofleet.drones.offline}    — 离线无人机数（Gauge）</li>
 *   <li>{@code aerofleet.messages.received} — 接收的 MAVLink 消息总数（Counter，按 msgId 标签）</li>
 *   <li>{@code aerofleet.messages.decoded}  — 成功解码的消息数（Counter）</li>
 *   <li>{@code aerofleet.messages.errors}   — 解码错误数（Counter，桥接自 mavlink-core 的 CRC 失败增量）</li>
 *   <li>{@code aerofleet.udp.ingest.datagrams} — 收到的 UDP 数据报数（Counter，内核丢包对账基准）</li>
 *   <li>{@code aerofleet.udp.ingest.noise.bytes} — 跳过的噪声字节数（Counter，单位=字节，不是帧）</li>
 *   <li>{@code aerofleet.udp.ingest.dropped} — 摄入侧丢弃（Counter，按 reason 标签；前缀标明帧/包单位）</li>
 *   <li>{@code aerofleet.mesh.routes}       — mesh 路由表大小（Gauge，按 sysId 标签）</li>
 *   <li>{@code aerofleet.mesh.neighbors}    — mesh 邻居数（Gauge，按 sysId 标签）</li>
 *   <li>{@code aerofleet.orch.phase}        — 编排引擎当前阶段（Gauge，按 sysId 标签）</li>
 *   <li>{@code aerofleet.formation.active}  — 活跃编队数（Gauge）</li>
 *   <li>{@code aerofleet.celltower.connected} — 连接的地面终端数（Gauge，按 towerId 标签）</li>
 *   <li>{@code aerofleet.signing.verified}   — 验签通过的帧数（Counter）</li>
 *   <li>{@code aerofleet.signing.rejected}   — 验签拒绝的帧数（Counter，<b>按 reason 标签</b>）</li>
 *   <li>{@code aerofleet.signing.unsigned}   — 未签名但被放行的帧数（Counter，仅 reject-unsigned=false 时非零）</li>
 * </ul>
 *
 * <p>关于 {@code aerofleet.signing.*}（2026-10-11 补）：
 * <ul>
 *   <li>此前签名统计是 {@code UdpGateway} 里的纯内存计数器，每 60s 打一条
 *       {@code log.info} 然后清零。于是本系统最该被监控的安全计数器，
 *       对**已经接好的 Prometheus 端点是不可见的**——有人在爆破签名时
 *       {@code rejected} 在涨，但没有任何东西能据此告警。</li>
 *   <li>{@code rejected} 带 {@code reason} 标签而非裸计数，因为"拒了多少"不可行动，
 *       "因为什么被拒"才可行动：{@code unsigned} 是有客户端没升级签名，
 *       {@code no-key} 意味着**有帧带着有效签名但来源 sysid 不在密钥库里**（更值得警惕），
 *       {@code timestamp-replay} 是重放，{@code error} 是代码异常。五种取值，基数安全。</li>
 * </ul>
 *
 * <p>设计说明：
 * <ul>
 *   <li>无标签 Gauge（drones.online/offline、formation.active）优先从注入的
 *       {@link DeviceRegistry}/{@link FormationService} 实时计算；若未注入则回退到
 *       本地状态变量（由 {@code update*} 方法更新）。</li>
 *   <li>带标签 Gauge（mesh.routes/neighbors、orch.phase、celltower.connected）使用
 *       本地状态变量，由 {@code update*} 方法驱动；首次更新时自动注册对应标签的 Gauge。</li>
 *   <li>Counter（messages.received/decoded/errors）由 {@code record*} 方法驱动。</li>
 * </ul>
 */
@Component
public class DroneMetrics {

    private static final Logger log = LoggerFactory.getLogger(DroneMetrics.class);

    private final MeterRegistry meterRegistry;

    /** 可选注入：用于 drones.online/offline Gauge 实时计算。 */
    @Nullable
    private final DeviceRegistry deviceRegistry;

    /** 可选注入：用于 formation.active Gauge 实时计算。 */
    @Nullable
    private final FormationService formationService;

    // ===== 本地状态变量（回退数据源 / 带标签 Gauge 数据源）=====

    private final AtomicInteger onlineDrones = new AtomicInteger(0);
    private final AtomicInteger offlineDrones = new AtomicInteger(0);
    private final AtomicInteger activeFormations = new AtomicInteger(0);

    /** sysId → mesh 路由表大小。 */
    private final ConcurrentHashMap<Integer, AtomicInteger> meshRoutesBySysId = new ConcurrentHashMap<>();
    /** sysId → mesh 邻居数。 */
    private final ConcurrentHashMap<Integer, AtomicInteger> meshNeighborsBySysId = new ConcurrentHashMap<>();
    /** sysId → 编排引擎当前阶段。 */
    private final ConcurrentHashMap<Integer, AtomicInteger> orchPhaseBySysId = new ConcurrentHashMap<>();
    /** towerId → 连接的地面终端数。 */
    private final ConcurrentHashMap<Integer, AtomicInteger> celltowerConnectedByTowerId = new ConcurrentHashMap<>();

    /** 已注册的带标签 Gauge 标识，避免重复注册。 */
    private final Set<String> registeredGauges = ConcurrentHashMap.newKeySet();

    // ===== Counter =====

    /** msgId → 接收消息 Counter（按 msgId 标签动态创建）。 */
    private final ConcurrentHashMap<Integer, Counter> messageReceivedCounters = new ConcurrentHashMap<>();
    private final Counter messageDecodedCounter;
    private final Counter messageErrorCounter;
    private final Counter udpDatagramsCounter;
    private final Counter udpNoiseBytesCounter;
    /** reason → 摄入侧丢弃 Counter（按 reason 标签动态创建；取值集合固定，基数安全）。 */
    private final ConcurrentHashMap<String, Counter> ingestDroppedByReason = new ConcurrentHashMap<>();
    private final Counter signingVerifiedCounter;
    private final Counter signingUnsignedCounter;
    /** reason → 验签拒绝 Counter（按 reason 标签动态创建，取值集合固定，基数安全）。 */
    private final ConcurrentHashMap<String, Counter> signingRejectedByReason = new ConcurrentHashMap<>();

    public DroneMetrics(MeterRegistry meterRegistry,
                        ObjectProvider<DeviceRegistry> deviceRegistryProvider,
                        ObjectProvider<FormationService> formationServiceProvider) {
        this.meterRegistry = meterRegistry;
        this.deviceRegistry = deviceRegistryProvider.getIfAvailable();
        this.formationService = formationServiceProvider.getIfAvailable();

        // --- 无标签 Gauge ---
        registerSupplierGauge("aerofleet.drones.online", "当前在线无人机数",
                this::computeOnlineDrones, onlineDrones);
        registerSupplierGauge("aerofleet.drones.offline", "离线无人机数",
                this::computeOfflineDrones, offlineDrones);
        registerSupplierGauge("aerofleet.formation.active", "活跃编队数",
                this::computeActiveFormations, activeFormations);

        // --- Counter ---
        this.messageDecodedCounter = Counter.builder("aerofleet.messages.decoded")
                .description("成功解码的 MAVLink 消息数")
                .register(meterRegistry);
        this.messageErrorCounter = Counter.builder("aerofleet.messages.errors")
                .description("MAVLink 消息解码错误数（CRC / 未知消息校验失败，由 mavlink-core 跨模块桥接）")
                .register(meterRegistry);
        this.udpDatagramsCounter = Counter.builder("aerofleet.udp.ingest.datagrams")
                .description("收到的 UDP 数据报数；注入端送出数 − 本值 = 内核层丢弃数（间接对账，非溢出直接计数）")
                .register(meterRegistry);
        this.udpNoiseBytesCounter = Counter.builder("aerofleet.udp.ingest.noise.bytes")
                .description("MAVLink 流解析时跳过的非 STX 噪声字节数（单位=字节，不是帧数）")
                .register(meterRegistry);

        // --- 签名（rejected 的 reason 标签在首次记录时按需创建，不预注册空值）---
        this.signingVerifiedCounter = Counter.builder("aerofleet.signing.verified")
                .description("MAVLink v2 签名验签通过的帧数")
                .register(meterRegistry);
        this.signingUnsignedCounter = Counter.builder("aerofleet.signing.unsigned")
                .description("未签名但被放行的帧数（仅 reject-unsigned=false 时非零；"
                        + "收紧为 true 后该值应恒为 0）")
                .register(meterRegistry);

        log.info("DroneMetrics initialized; deviceRegistry={}, formationService={}",
                deviceRegistry != null ? "present" : "absent",
                formationService != null ? "present" : "absent");
    }

    // =====================================================================
    // Gauge 注册辅助
    // =====================================================================

    /**
     * 注册一个无标签 Gauge：优先用 supplier 实时计算，supplier 返回 null 时回退到 fallback。
     */
    private void registerSupplierGauge(String name, String description,
                                       Supplier<Number> supplier, AtomicInteger fallback) {
        Gauge.builder(name, () -> {
            Number n = supplier.get();
            return n != null ? n.doubleValue() : fallback.get();
        }).description(description).register(meterRegistry);
    }

    /**
     * 注册一个带标签 Gauge（仅首次注册，后续调用跳过）。
     */
    private void registerTaggedGauge(String name, String description,
                                     String tagKey, String tagValue, AtomicInteger value) {
        String key = name + "." + tagKey + "=" + tagValue;
        if (registeredGauges.add(key)) {
            Gauge.builder(name, value, AtomicInteger::doubleValue)
                    .description(description)
                    .tag(tagKey, tagValue)
                    .register(meterRegistry);
        }
    }

    // =====================================================================
    // 实时计算（从注入组件读取；组件不可用时返回 null 触发回退）
    // =====================================================================

    @Nullable
    private Number computeOnlineDrones() {
        if (deviceRegistry != null) {
            int count = 0;
            for (var s : deviceRegistry.all()) {
                if (s.online) {
                    count++;
                }
            }
            return count;
        }
        return null;
    }

    @Nullable
    private Number computeOfflineDrones() {
        if (deviceRegistry != null) {
            int count = 0;
            for (var s : deviceRegistry.all()) {
                if (!s.online) {
                    count++;
                }
            }
            return count;
        }
        return null;
    }

    @Nullable
    private Number computeActiveFormations() {
        if (formationService != null) {
            return formationService.activeFormations().size();
        }
        return null;
    }

    // =====================================================================
    // 公共 API — 供其他组件调用更新指标
    // =====================================================================

    /** 记录一条接收到的 MAVLink 消息（按 msgId 标签计数）。 */
    public void recordMessageReceived(int msgId) {
        Counter c = messageReceivedCounters.computeIfAbsent(msgId, id ->
                Counter.builder("aerofleet.messages.received")
                        .description("接收的 MAVLink 消息总数")
                        .tag("msgId", String.valueOf(id))
                        .register(meterRegistry));
        c.increment();
    }

    /** 记录一条成功解码的消息。 */
    public void recordMessageDecoded() {
        messageDecodedCounter.increment();
    }

    /** 记录一条解码错误。 */
    public void recordMessageError() {
        messageErrorCounter.increment();
    }

    /**
     * 批量记录解码错误：一个 UDP 数据报内可能含多个 CRC 失败帧，
     * 逐帧调用 {@link #recordMessageError()} 会把收包热路径放大数倍。
     */
    public void recordMessageErrors(long count) {
        if (count > 0) {
            messageErrorCounter.increment(count);
        }
    }

    /** 记录收到一个 UDP 数据报（发生在解析之前，是内核丢包对账的分母）。 */
    public void recordUdpDatagram() {
        udpDatagramsCounter.increment();
    }

    /** 记录解析时跳过的噪声字节数（单位=字节）。 */
    public void recordUdpNoiseBytes(long bytes) {
        if (bytes > 0) {
            udpNoiseBytesCounter.increment(bytes);
        }
    }

    /** 记录一次摄入侧丢弃，按 reason 标签归类。 */
    public void recordIngestDrop(String reason) {
        recordIngestDrop(reason, 1L);
    }

    /** 批量记录摄入侧丢弃（同一 reason）。 */
    public void recordIngestDrop(String reason, long count) {
        if (count <= 0) {
            return;
        }
        String r = reason == null ? IngestDropReason.FRAME_LISTENER_ERROR : reason;
        Counter c = ingestDroppedByReason.computeIfAbsent(r,
                key -> Counter.builder("aerofleet.udp.ingest.dropped")
                        .description("UDP 摄入侧丢弃；reason 前缀 frame-/datagram- 标明计数单位")
                        .tag("reason", key)
                        .register(meterRegistry));
        c.increment(count);
    }

    /**
     * UDP 摄入侧丢弃原因（{@code aerofleet.udp.ingest.dropped} 的 reason 标签取值）。
     *
     * <p><b>单位不能混加</b>：{@code frame-} 前缀计的是帧数，{@code datagram-} 前缀计的
     * 是数据报数。它们被放在同一指标名下是为了一个查询就能看全摄入损耗归因，
     * 但写告警规则时必须按 reason 单独设阈值。取值集合固定，标签基数安全。
     */
    public static final class IngestDropReason {
        /** 帧：CRC / 未知消息校验失败（桥接自 {@code MavlinkParser.crcErrors} 的增量）。 */
        public static final String FRAME_CRC_ERROR = "frame-crc-error";
        /** 帧：单个帧监听器抛异常，该帧未进入业务链。 */
        public static final String FRAME_LISTENER_ERROR = "frame-listener-error";
        /** 帧：验签失败被丢弃。 */
        public static final String FRAME_SIGNATURE_REJECTED = "frame-signature-rejected";
        /** 帧：设备白名单拒绝（未注册 sysid）。 */
        public static final String FRAME_WHITELIST_REJECTED = "frame-whitelist-rejected";
        /** 帧：每 sysid 频率限制丢弃。 */
        public static final String FRAME_RATE_LIMITED = "frame-rate-limited";
        /** 数据报：长度等于接收缓冲上限，疑似被内核截断。 */
        public static final String DATAGRAM_TRUNCATED = "datagram-truncated";
        /** 数据报：{@code socket.receive} 抛 IOException，本轮无包。 */
        public static final String DATAGRAM_RECEIVE_ERROR = "datagram-receive-error";

        private IngestDropReason() {
        }
    }

    // ==================== 签名（MAVLink v2） ====================

    /** 验签拒绝的原因（{@code aerofleet.signing.rejected} 的 reason 标签取值）。 */
    public static final class SigningRejectReason {
        /** 未签名帧且 reject-unsigned=true：有客户端没升级签名。 */
        public static final String UNSIGNED = "unsigned";
        /** 帧带着有效签名，但来源 sysid 在密钥库里取不到口令——最值得警惕的一种。 */
        public static final String NO_KEY = "no-key";
        /** 签名值不匹配（被篡改，或两端密钥配错）。 */
        public static final String BAD_SIGNATURE = "bad-signature";
        /** timestamp 非单调，疑似重放。 */
        public static final String TIMESTAMP_REPLAY = "timestamp-replay";
        /** 验签过程抛异常（代码缺陷或帧结构异常）。 */
        public static final String ERROR = "error";

        private SigningRejectReason() {
        }
    }

    /** 记录一帧验签通过。 */
    public void recordSigningVerified() {
        signingVerifiedCounter.increment();
    }

    /**
     * 记录一帧未签名但被放行。
     * 仅在 {@code reject-unsigned=false}（观察期姿态）下非零；
     * 收紧为 fail-closed 后该计数器应恒为 0——这是"staging 已收紧"的观测证据。
     */
    public void recordSigningUnsigned() {
        signingUnsignedCounter.increment();
    }

    /** 记录一帧验签拒绝，并带上原因标签。 */
    public void recordSigningRejected(String reason) {
        Counter c = signingRejectedByReason.computeIfAbsent(
                reason == null ? SigningRejectReason.ERROR : reason,
                r -> Counter.builder("aerofleet.signing.rejected")
                        .description("MAVLink v2 签名验签拒绝的帧数")
                        .tag("reason", r)
                        .register(meterRegistry));
        c.increment();
    }

    /** 手动更新在线无人机数（当未注入 DeviceRegistry 时作为 Gauge 数据源）。 */
    public void updateOnlineDrones(int count) {
        onlineDrones.set(count);
    }

    /** 手动更新离线无人机数（当未注入 DeviceRegistry 时作为 Gauge 数据源）。 */
    public void updateOfflineDrones(int count) {
        offlineDrones.set(count);
    }

    /** 手动更新活跃编队数（当未注入 FormationService 时作为 Gauge 数据源）。 */
    public void updateActiveFormations(int count) {
        activeFormations.set(count);
    }

    /** 更新某节点的 mesh 路由表大小（按 sysId 标签）。 */
    public void updateMeshRoutes(int sysId, int routeCount) {
        AtomicInteger v = meshRoutesBySysId.computeIfAbsent(sysId, id -> new AtomicInteger(routeCount));
        v.set(routeCount);
        registerTaggedGauge("aerofleet.mesh.routes", "mesh 路由表大小",
                "sysId", String.valueOf(sysId), v);
    }

    /** 更新某节点的 mesh 邻居数（按 sysId 标签）。 */
    public void updateMeshNeighbors(int sysId, int neighborCount) {
        AtomicInteger v = meshNeighborsBySysId.computeIfAbsent(sysId, id -> new AtomicInteger(neighborCount));
        v.set(neighborCount);
        registerTaggedGauge("aerofleet.mesh.neighbors", "mesh 邻居数",
                "sysId", String.valueOf(sysId), v);
    }

    /** 更新编排引擎某节点的当前阶段（按 sysId 标签）。阶段：0=测绘/1=规划/2=部署/3=服务/4=自愈。 */
    public void updateOrchPhase(int sysId, int phase) {
        AtomicInteger v = orchPhaseBySysId.computeIfAbsent(sysId, id -> new AtomicInteger(phase));
        v.set(phase);
        registerTaggedGauge("aerofleet.orch.phase", "编排引擎当前阶段",
                "sysId", String.valueOf(sysId), v);
    }

    /** 更新某基站连接的地面终端数（按 towerId 标签）。 */
    public void updateCelltowerConnected(int towerId, int connectedCount) {
        AtomicInteger v = celltowerConnectedByTowerId.computeIfAbsent(towerId, id -> new AtomicInteger(connectedCount));
        v.set(connectedCount);
        registerTaggedGauge("aerofleet.celltower.connected", "连接的地面终端数",
                "towerId", String.valueOf(towerId), v);
    }
}