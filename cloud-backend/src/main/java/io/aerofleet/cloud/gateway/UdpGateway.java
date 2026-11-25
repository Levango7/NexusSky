package io.aerofleet.cloud.gateway;

import io.aerofleet.cloud.metrics.DroneMetrics;
import io.aerofleet.cloud.rid.RidIngestService;
import io.aerofleet.cloud.telemetry.PendingAcks;
import io.aerofleet.mavlink.MavlinkFrame;
import io.aerofleet.mavlink.MavlinkMessageInfo;
import io.aerofleet.mavlink.enums.MavEnums;
import io.aerofleet.mavlink.messages.CommandAck;
import io.aerofleet.mavlink.messages.Heartbeat;
import io.aerofleet.mavlink.messages.MissionAckMsg;
import io.aerofleet.mavlink.messages.MissionCountMsg;
import io.aerofleet.mavlink.messages.MissionRequest;
import io.aerofleet.mavlink.messages.MissionRequestInt;
import io.aerofleet.mavlink.messages.MissionRequestList;
import io.aerofleet.mavlink.messages.Statustext;
import io.aerofleet.mavlink.security.MavlinkSignatureConfig;
import io.aerofleet.mavlink.security.MavlinkSigner;
import io.aerofleet.mavlink.security.MavlinkSignerFactory;
import io.aerofleet.mavlink.security.SigningKeyManager;
import io.aerofleet.mavlink.security.TimestampTracker;
import io.aerofleet.mavlink.transport.UdpIngestObserver;
import io.aerofleet.mavlink.transport.UdpMavlinkTransport;
import org.springframework.beans.factory.ObjectProvider;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.net.SocketAddress;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import java.util.function.BiConsumer;

/**
 * Binds the MAVLink UDP transport (default port 14550) and pumps every valid
 * frame into {@link TelemetryIngestService}.
 *
 * <p>Routing: every drone's sysid is mapped to the socket address its frames
 * arrive from, so COMMAND/MISSION traffic is directed per-drone even when
 * several drones (or several link-sim proxies, one per "network segment")
 * share this gateway. lastPeer remains only as a discovery bootstrap.
 *
 * <p>Security (P1-3):
 * <ul>
 *   <li><b>Device whitelist</b>: when {@code aerofleet.udp.device-whitelist-enabled=true}
 *       (prod default), only frames from sysids registered in {@link DeviceRegistry}
 *       are accepted. Dev mode ({@code false}) accepts all sysids.</li>
 *   <li><b>Rate limiting</b>: per-sysid sliding-window rate limit
 *       ({@code aerofleet.udp.max-frame-rate-per-sysid}, default 100 fps).
 *       Excess frames are dropped with a WARN log.</li>
 *   <li><b>Bind address</b>: {@code aerofleet.udp.bind-address} (default 0.0.0.0;
 *       prod should bind to an internal NIC).</li>
 * </ul>
 */
@Component
public class UdpGateway {

    private static final Logger log = LoggerFactory.getLogger(UdpGateway.class);

    /** MAVLink system id of the ground station (cloud) itself. */
    public static final int GCS_SYSID = 255;
    public static final int GCS_COMPID = 190;   // MAV_COMP_ID_MISSIONPLANNER

    private final UdpMavlinkTransport transport;
    private final TelemetryIngestService ingest;
    private final PendingAcks pendings;

    /** sysid routes with aging (D3): see {@link RouteTable}. */
    private final RouteTable droneRoutes = new RouteTable();

    /** 设备白名单是否启用（prod=true, dev=false）。 */
    private final boolean deviceWhitelistEnabled;

    /** 每 sysid 滑动窗口频率限制器（独立横切组件，见 {@link FrameRateLimiter}）。 */
    private final FrameRateLimiter rateLimiter;

    /** 设备注册表，用于白名单校验；可选注入（dev 模式下可能为 null）。 */
    @Autowired(required = false)
    private DeviceRegistry deviceRegistry;

    // ==================== MAVLink v2 签名组件（可选注入） ====================

    /** 签名器工厂：按 sysid 取对应口令的签名器（单机全局口令与多机密钥库走同一路径）。 */
    @Autowired(required = false)
    private MavlinkSignerFactory signerFactory;

    /** 密钥管理器：提供 sysid→密钥与 linkId 映射。 */
    @Autowired(required = false)
    private SigningKeyManager signingKeyManager;

    /** 时间戳跟踪器：重放攻击防护。 */
    @Autowired(required = false)
    private TimestampTracker timestampTracker;

    /** 签名配置：控制签名开关与策略。 */
    @Autowired(required = false)
    private MavlinkSignatureConfig signatureConfig;

    /** RID 接入服务：可选注入，用于处理 OPEN_DRONE_ID_* 消息。 */
    @Autowired(required = false)
    private RidIngestService ridIngestService;

    /**
     * 可观测性指标：<b>必须用 ObjectProvider，不能直接注入</b>。
     *
     * <p>签名统计此前只进日志、对已接好的 Prometheus 端点不可见，这里把它同步给
     * {@link DroneMetrics}。
     *
     * <p><b>为什么是 ObjectProvider 而不是 {@code @Autowired DroneMetrics}</b>：
     * 直接字段注入会在 UdpGateway 创建期间就急切解析 DroneMetrics，而 DroneMetrics
     * 的构造参数里有 {@code ObjectProvider<FormationService>}——它会被解析成
     * FormationService bean，而 FormationService → droneCommandService → udpGateway，
     * 于是形成 <b>udpGateway ⇄ droneCommandService 循环</b>，Spring 抛
     * {@code BeanCurrentlyInCreationException}，整个上下文起不来（实测 13 个
     * {@code @SpringBootTest} 上下文全挂）。本仓到处用 ObjectProvider 正是为了破环。
     * 用 provider 则在真正验签时（上下文已就绪）才解析，顺带天然满足
     * 「指标是可选依赖、不是启动依赖」。
     */
    @Autowired(required = false)
    private ObjectProvider<DroneMetrics> droneMetricsProvider;

    /** DroneMetrics 的惰性缓存：每帧都走 getIfAvailable() 没必要。 */
    private volatile DroneMetrics droneMetrics;

    /** 签名统计计数器（60s 周期报告）。 */
    private final SigningStats signingStats = new SigningStats();

    /** 惰性解析 DroneMetrics（首次需要时才找 bean，避免创建期循环依赖）。 */
    private DroneMetrics metrics() {
        DroneMetrics m = droneMetrics;
        if (m == null && droneMetricsProvider != null) {
            m = droneMetricsProvider.getIfAvailable();
            if (m != null) {
                droneMetrics = m;
            }
        }
        return m;
    }

    /** 记一次验签拒绝：内存计数（周期日志）+ Prometheus 计数（可告警）两处同步。 */
    private void rejectSigning(String reason) {
        signingStats.rejected++;
        DroneMetrics m = metrics();
        if (m != null) {
            m.recordSigningRejected(reason);
        }
    }

    /**
     * 记一次摄入侧丢弃（统一丢弃视图）。与 {@code signing.rejected} 不冲突：
     * 后者是安全维度的原因细分，本方法是容量/摄入维度的总量归因。
     * DroneMetrics 是可选依赖（ObjectProvider 破环），未注入时静默跳过。
     */
    private void recordDrop(String reason) {
        DroneMetrics m = metrics();
        if (m != null) {
            m.recordIngestDrop(reason);
        }
    }

    /**
     * 路由换源计数与抖动告警（威胁建模 ④-F2）。
     * <p>
     * 首次学习不算换源；同 sysid 在 {@link #ROUTE_FLAP_WINDOW_MS} 窗口内换源达到
     * {@link #ROUTE_FLAP_WARN_THRESHOLD} 次即 WARN（窗口滑动、跨过阈值时各触发一次）——
     * 抢路由攻击会持续高频换源，合法重绑定（换 IP/换代理）频率远低于此。
     */
    private void recordRouteChange(int sysid, boolean wasKnown) {
        if (!wasKnown) {
            return;
        }
        DroneMetrics m = metrics();
        if (m != null) {
            m.recordRouteChanged();
        }
        long now = System.currentTimeMillis();
        Deque<Long> window = routeChangeWindows.computeIfAbsent(sysid, k -> new ArrayDeque<>());
        synchronized (window) {
            while (!window.isEmpty() && now - window.peekFirst() > ROUTE_FLAP_WINDOW_MS) {
                window.pollFirst();
            }
            window.addLast(now);
            if (window.size() == ROUTE_FLAP_WARN_THRESHOLD) {
                log.warn("Route flap suspected: sysid={} changed source {} times in {}s"
                                + " (route hijack or link flapping?)",
                        sysid, window.size(), ROUTE_FLAP_WINDOW_MS / 1000);
            }
        }
    }

    /**
     * 每 sysid 换源窗口（60s 滑动）：路由抢夺/链路抖动的告警依据（威胁建模 ④-F2）。
     * <p>
     * 为什么只告警不改语义：多 link-sim 代理（drone-extra-ports）下，同一架飞机的帧
     * **合法地**从不同代理地址交替到达——换源节流/滞回会误伤；而 UDP 源地址本就
     * 无认证，任何「确认 N 帧再切换」的滞回对手方同样伪造得出。根治=MAVLink v2
     * 签名收紧（reject-unsigned，见 SigningPostureService 的 C5 分阶段流程）。
     */
    private final ConcurrentHashMap<Integer, Deque<Long>> routeChangeWindows = new ConcurrentHashMap<>();

    /** 换源告警阈值：60s 窗口内同 sysid 换源达到该次数即 WARN（跨过阈值触发一次）。 */
    private static final int ROUTE_FLAP_WARN_THRESHOLD = 3;

    /** 换源告警窗口长度（毫秒）。 */
    private static final long ROUTE_FLAP_WINDOW_MS = 60_000L;

    /**
     * 关键帧独立预算（每 sysid 每秒）；<=0 关闭豁免（关键帧退回主窗口，旧行为）。
     * 实际限速逻辑在 {@link FrameRateLimiter}，本字段经 {@link #syncCriticalBudget()} 转发。
     */
    @Value("${aerofleet.udp.max-critical-frame-rate-per-sysid:50}")
    private int maxCriticalFrameRatePerSysid = 50;

    /** 构造后把 {@code @Value} 注入的关键帧预算转发给 {@link #rateLimiter}。 */
    @jakarta.annotation.PostConstruct
    void syncCriticalBudget() {
        rateLimiter.setMaxCriticalFrameRatePerSysid(maxCriticalFrameRatePerSysid);
    }

    /**
     * 内核 UDP 接收缓冲区字节数；{@code 0} 表示沿用 OS 默认值（不改变既有行为）。
     * <p>
     * 摄入侧是单线程串行收包，突发流量下内核缓冲溢出会**静默丢包**（无计数器）。
     * 容量实测显示摄入量约 4,100 帧/s 即进入临界区，加大缓冲是最低成本缓解手段。
     * 用字段注入而非构造参数，是为了不改构造签名——测试与既有调用点零影响。
     */
    @Value("${aerofleet.udp.receive-buffer-bytes:0}")
    private int receiveBufferBytes;

    public UdpGateway(@Value("${aerofleet.udp-port:14550}") int udpPort,
                      @Value("${aerofleet.drone-host:127.0.0.1}") String droneHost,
                      @Value("${aerofleet.drone-port:14540}") int dronePort,
                      @Value("${aerofleet.drone-extra-ports:}") String extraPorts,
                      @Value("${aerofleet.udp.bind-address:0.0.0.0}") String bindAddress,
                      @Value("${aerofleet.udp.device-whitelist-enabled:false}") boolean deviceWhitelistEnabled,
                      @Value("${aerofleet.udp.max-frame-rate-per-sysid:100}") int maxFrameRatePerSysid,
                      TelemetryIngestService ingest,
                      PendingAcks pendings) throws IOException {
        this.ingest = ingest;
        this.pendings = pendings;
        this.deviceWhitelistEnabled = deviceWhitelistEnabled;
        this.rateLimiter = new FrameRateLimiter(maxFrameRatePerSysid);
        this.transport = new UdpMavlinkTransport(bindAddress, udpPort);

        // 摄入侧信号出口：mavlink-core 只认识 UdpIngestObserver 接口，Micrometer 接线留在本层。
        // 依赖倒置是硬要求——mavlink-core 不能反向依赖 cloud-backend 或监控后端。
        // 这里不能立刻调 metrics()：droneMetricsProvider 是字段注入，发生在构造之后；
        // 回调是惰性触发的，届时容器已就绪（与 rejectSigning() 同构）。
        // 所有回调都在收包线程内同步执行，故实现只做 Counter.increment，不阻塞、不抛异常。
        transport.setIngestObserver(new UdpIngestObserver() {
            @Override
            public void onDatagramReceived(int bytes) {
                DroneMetrics m = metrics();
                if (m != null) {
                    m.recordUdpDatagram();
                }
            }

            @Override
            public void onParseErrors(int crcErrors, long noiseBytes) {
                DroneMetrics m = metrics();
                if (m == null) {
                    return;
                }
                // crcErrors 是帧数：同时喂给「解码错误总数」和统一丢弃视图，
                // 这是同一批帧的两个视角，不是两次丢弃。噪声按字节单独记，单位不同。
                if (crcErrors > 0) {
                    m.recordMessageErrors(crcErrors);
                    m.recordIngestDrop(DroneMetrics.IngestDropReason.FRAME_CRC_ERROR, crcErrors);
                }
                if (noiseBytes > 0) {
                    m.recordUdpNoiseBytes(noiseBytes);
                }
            }

            @Override
            public void onTruncatedDatagram(int bytes) {
                recordDrop(DroneMetrics.IngestDropReason.DATAGRAM_TRUNCATED);
            }

            @Override
            public void onListenerError() {
                recordDrop(DroneMetrics.IngestDropReason.FRAME_LISTENER_ERROR);
            }

            @Override
            public void onReceiveError() {
                recordDrop(DroneMetrics.IngestDropReason.DATAGRAM_RECEIVE_ERROR);
            }
        });

        // 带源地址的监听器：路由表的学习与分发都靠它
        transport.addFrameListener((BiConsumer<MavlinkFrame, SocketAddress>) this::onFrame);

        // QGroundControl-style active handshake toward each configured drone port.
        java.net.InetSocketAddress droneAddr = new java.net.InetSocketAddress(droneHost, dronePort);
        transport.enablePeerDiscovery(droneAddr, () -> signedGcsHeartbeat(0));
        if (extraPorts != null && !extraPorts.isBlank()) {
            for (String p : extraPorts.split(",")) {
                try {
                    int port = Integer.parseInt(p.trim());
                    transport.enablePeerDiscovery(
                            new java.net.InetSocketAddress(droneHost, port),
                            () -> signedGcsHeartbeat(0));
                } catch (NumberFormatException e) {
                    log.warn("Ignoring invalid extra drone port: {}", p);
                }
            }
        }
        log.info("MAVLink UDP gateway listening on {}:{}, discovery -> {} (+{}), whitelist={}, maxRate={}/s",
                bindAddress, transport.getLocalPort(), droneAddr, extraPorts,
                deviceWhitelistEnabled, maxFrameRatePerSysid);
    }

    /** Frame + source address entry: whitelist check, rate limit, ingest, and route learning. */
    private void onFrame(MavlinkFrame frame, SocketAddress source) {
        int sysid = frame.getSystemId();

        // 接入侧计数：必须放在所有策略丢弃分支之前。否则「收到多少帧」会被验签/白名单/
        // 限速的丢弃量污染，无法与容量基线里的注入量对账（这正是此前无法区分
        // 「注入器少发」与「UDP 摄入丢包」的原因）。
        // 语义：能构造出 MavlinkFrame 就说明已完成解析，故 received 与 decoded 同步递增；
        // 解析失败发生在 mavlink-core 的 MavlinkParser（crcErrors），根本不进本方法——
        // 那部分现在由 UdpIngestObserver 桥接到 messages.errors / ingest.dropped{frame-crc-error}。
        // DroneMetrics 是可选依赖（ObjectProvider 破环），未注入时整段跳过；
        // metrics() 命中 volatile 缓存后每帧只是一次读，不额外查 Spring 容器。
        DroneMetrics m = metrics();
        if (m != null) {
            m.recordMessageReceived(frame.getMessageId());
            m.recordMessageDecoded();
        }

        // MAVLink sysid 有效域 1..255（GCS=255）：sysid=0 是畸形/伪造帧。白名单与
        // 限速两个守卫都以 sysid>0 为前提，放它过去等于双双旁路（威胁建模 ④-F1：
        // 无限帧可直达 ingest 扇出全量监听器）。
        if (sysid <= 0) {
            log.warn("Rejected frame with invalid sysid={} (msgId={})",
                    sysid, frame.getMessageId());
            recordDrop(DroneMetrics.IngestDropReason.FRAME_INVALID_SYSID);
            return;
        }

        // 签名验证（签名启用时）
        if (isSigningEnabled() && !verifyFrame(frame)) {
            recordDrop(DroneMetrics.IngestDropReason.FRAME_SIGNATURE_REJECTED);
            return; // 验证失败，丢弃帧
        }

        // 白名单校验：prod 模式下只接受已注册 sysid（GCS_SYSID 始终放行）
        if (deviceWhitelistEnabled && sysid > 0 && sysid != GCS_SYSID) {
            if (deviceRegistry == null || deviceRegistry.get(sysid) == null) {
                log.warn("Rejected frame from unregistered sysid={} (device whitelist enabled)", sysid);
                recordDrop(DroneMetrics.IngestDropReason.FRAME_WHITELIST_REJECTED);
                return;
            }
        }

        // 频率限制：滑动窗口算法（GCS_SYSID 不受限）。
        // P2-F5：关键帧走独立小预算（见 FrameRateLimiter）——单一窗口下高码率流
        // 会在每秒开头把预算吃满，ACK/心跳成批被丢（SITL 实测 340k+ 条丢帧）。
        // 非关键帧的窗口与上限不变；关键预算耗尽才丢关键帧（防滥用语义保留）；
        // 预算配置 <=0 时豁免关闭，关键帧退回主窗口（旧行为）。
        if (sysid > 0 && sysid != GCS_SYSID) {
            if (!rateLimiter.allowIngest(sysid, frame.getMessageId())) {
                boolean critical = rateLimiter.isCritical(frame.getMessageId());
                log.warn("Rate limit exceeded for sysid={}, dropping {}frame (max {}/s)",
                        sysid, critical ? "critical " : "",
                        critical ? rateLimiter.maxCriticalFrameRate() : rateLimiter.maxFrameRate());
                recordDrop(DroneMetrics.IngestDropReason.FRAME_RATE_LIMITED);
                return;
            }
        }

        if (sysid > 0 && sysid != GCS_SYSID) {
            SocketAddress prev = droneRoutes.get(sysid);
            droneRoutes.learn(sysid, source);
            if (prev == null || !prev.equals(source)) {
                log.debug("Route sysid={} -> {}", sysid, source);
                recordRouteChange(sysid, prev != null);
            }
        }
        ingest.handle(frame);

        // RID 消息接入：当 ridIngestService 可用时，将帧传递给 RID 处理链
        if (ridIngestService != null) {
            ridIngestService.onFrame(frame);
        }
    }

    /**
     * Route aging (D3): drop routes silent for 5 min - a replaced/rebound
     * drone otherwise leaves a stale address that silently eats commands.
     * Runs every 60 s.
     */
    @org.springframework.scheduling.annotation.Scheduled(fixedDelay = 60_000, initialDelay = 60_000)
    public void pruneStaleRoutes() {
        List<Integer> pruned = droneRoutes.prune();
        for (Integer sysid : pruned) {
            log.info("pruning stale route sysid={} (silent >5min)", sysid);
        }
        // 路由走了，出站广播窗口随之作废（key 与路由同生命周期）
        rateLimiter.pruneBroadcastWindows(pruned);
    }

    /**
     * 频率限制窗口清理：移除长时间（2 分钟）无活动的 sysid 窗口，防止内存泄漏。
     * 主窗口、关键帧预算窗口与出站广播窗口同一批清理。每 5 分钟运行一次。
     */
    @org.springframework.scheduling.annotation.Scheduled(fixedDelay = 300_000, initialDelay = 300_000)
    public void pruneStaleRateLimitWindows() {
        long cutoff = System.currentTimeMillis() - 120_000;
        int removedRate = rateLimiter.pruneStaleWindows(cutoff);
        int beforeRoute = routeChangeWindows.size();
        routeChangeWindows.entrySet().removeIf(entry -> {
            Deque<Long> window = entry.getValue();
            synchronized (window) {
                return window.isEmpty() || window.peekLast() < cutoff;
            }
        });
        int removed = removedRate + (beforeRoute - routeChangeWindows.size());
        if (removed > 0) {
            log.debug("Pruned {} stale rate-limit windows", removed);
        }
    }

    /** 设置关键帧预算（主要用于测试注入；<=0 关闭豁免，退回主窗口旧行为）。 */
    void setMaxCriticalFrameRatePerSysid(int maxCriticalFrameRatePerSysid) {
        this.maxCriticalFrameRatePerSysid = maxCriticalFrameRatePerSysid;
        this.rateLimiter.setMaxCriticalFrameRatePerSysid(maxCriticalFrameRatePerSysid);
    }

    public int getLocalPort() {
        return transport.getLocalPort();
    }

    /** Send one frame to the drone with the given sysid (route learned per-drone). */
    public void send(int sysid, MavlinkFrame frame) throws IOException {
        SocketAddress addr = sysid > 0 ? droneRoutes.get(sysid) : null;
        MavlinkFrame frameToSend = frame;

        // 签名启用时，对帧附加签名
        if (isSigningEnabled()) {
            MavlinkFrame signed = signFrame(sysid, frame);
            if (signed != null) {
                frameToSend = signed;
            }
            // signed==null 表示降级为未签名帧（signFrame 已记录 WARN 日志）
        }

        if (addr != null) {
            transport.send(frameToSend, addr);
        } else if (sysid > 0) {
            sendUnrouted(sysid, frameToSend);
        } else {
            transport.sendToLastPeer(frameToSend); // sysid<=0（广播级/未知）：沿用旧兜底
        }
    }

    /**
     * 未学到路由的出站（P2-F6 ②，2026-11-07）。
     *
     * <p>**旧行为**：一律 {@code transport.sendToLastPeer}——即「最后说话者拿走命令」。
     * §12.4 表②记的失败形态：一个集群里有多实例时，命令只会落在**最近一次发言**的那一个
     * 上，其他实例既拿不到命令、也不会报错，静默失败。
     *
     * <p>**新行为**：对 {@link RouteTable#addresses()} 里**所有已知实例定向广播**
     * （不是 LAN 广播——逐个 {@code InetSocketAddress} 定向发，语义可枚举、可断言）。
     * 广播路径受 {@link FrameRateLimiter#allowOutboundBroadcast(int)} 限制：预算按 sysid 计，
     * 实例越多每实例实得越少（**总帧数不放大**，即按实例摊分）。
     *
     * <p>一个实例也没学到时（启动初期、或全部路由已老化）仍需兜底——退回
     * {@code sendToLastPeer}（可能为 null ⇒ 静默丢弃，与旧行为一致）。此时**不**广播：
     * 没有已知地址就没有广播对象，且退化成 lastPeer 可保住「drone-host 发现存活」的单机路径。
     */
    private void sendUnrouted(int sysid, MavlinkFrame frame) throws IOException {
        List<SocketAddress> known = new java.util.ArrayList<>();
        for (SocketAddress a : droneRoutes.addresses()) {
            known.add(a);
        }
        if (known.isEmpty() || !rateLimiter.allowOutboundBroadcast(sysid)) {
            transport.sendToLastPeer(frame); // 无已知实例 / 预算耗尽：旧兜底（best effort）
            return;
        }
        int delivered = 0;
        for (SocketAddress a : known) {
            transport.send(frame, a);
            delivered++;
        }
        log.debug("outbound broadcast sysid={} to {} known instance(s) (unrouted; P2-F6 ②)",
                sysid, delivered);
    }

    /** Send one frame to the drone with the given sysid, or learn-and-wait fallback. */
    public void send(MavlinkFrame frame) throws IOException {
        // 兼容旧签名：无 sysid 时用 lastPeer（discovery bootstrap 场景）
        transport.sendToLastPeer(frame);
    }

    /** Direct send used by the heartbeat watchdog re-discovery path. */
    public void sendToLastPeer(MavlinkFrame frame) throws IOException {
        transport.sendToLastPeer(frame);
    }

    public SocketAddress routeOf(int sysid) {
        return droneRoutes.get(sysid);
    }

    public SocketAddress getLastPeer() {
        return transport.getLastPeer();
    }

    /** GCS heartbeat frame (MAV_TYPE_GCS=6, AUTOPILOT_INVALID=8). */
    MavlinkFrame gcsHeartbeat(int seq) {
        Heartbeat hb = new Heartbeat(0, 6, 8, 0, MavEnums.MAV_STATE_ACTIVE);
        return hb.toFrame(GCS_SYSID, GCS_COMPID, seq);
    }

    /** Sequence counter for the periodic GCS heartbeat below. */
    private final java.util.concurrent.atomic.AtomicInteger hbSeq =
            new java.util.concurrent.atomic.AtomicInteger();

    /**
     * QGroundControl-style 1 Hz GCS heartbeat to every known drone route.
     * A real GCS always keeps chattering; without this the vehicle's datalink
     * failsafe (PX4 NAV_DLLC_ACT) correctly fires ~15s into a silent mission
     * flight and RTLs on its own. Discovery heartbeats stop once a drone
     * answers, so this loop is what keeps the link "alive" afterwards.
     */
    @org.springframework.scheduling.annotation.Scheduled(fixedDelay = 1000)
    public void heartbeatLoop() {
        if (droneRoutes.isEmpty()) {
            return;
        }
        MavlinkFrame hb = gcsHeartbeat(hbSeq.getAndIncrement() & 0xFF);

        // 签名启用时，对 GCS 心跳帧签名（使用 GCS_SYSID 密钥）
        if (isSigningEnabled()) {
            MavlinkFrame signed = signFrame(GCS_SYSID, hb);
            if (signed != null) {
                hb = signed;
            }
        }

        for (SocketAddress addr : droneRoutes.addresses()) {
            try {
                transport.send(hb, addr);
            } catch (IOException e) {
                // route went dark: the heartbeat watchdog will age it out
            }
        }
    }

    /**
     * 应用 UDP 接收缓冲区配置。
     * <p>
     * 必须放在 {@code @PostConstruct}：{@code @Value} 字段注入发生在构造之后，
     * 在构造函数里读 {@code receiveBufferBytes} 只会拿到默认值 0。
     * <p>
     * {@code receiveBufferBytes <= 0}（默认）时本方法不做任何事，行为与改动前完全一致。
     */
    @jakarta.annotation.PostConstruct
    void applyUdpReceiveBuffer() {
        if (receiveBufferBytes > 0) {
            transport.setReceiveBufferSize(receiveBufferBytes);
        }
    }

    @PreDestroy
    public void shutdown() {
        transport.close();
        log.info("MAVLink UDP gateway closed");
    }

    // ==================== MAVLink v2 签名集成 ====================

    /**
     * 判断签名是否启用。
     * 签名组件未注入或配置未启用时返回 false，所有路径与现有实现一致。
     */
    private boolean isSigningEnabled() {
        return signatureConfig != null && signatureConfig.isEnabled()
                && signerFactory != null && signingKeyManager != null;
    }

    /**
     * 发送路径签名：对帧附加 13 字节官方签名块。
     * <p>
     * 流程：从 keyManager 取该 sysid 的口令与 linkId → 计算 timestamp → 构造临时签名帧获取 frameBytes →
     * signer.sign() 计算签名 → 构造最终签名帧。
     * <p>
     * 降级条件（返回 null，调用方发送未签名帧）：
     * <ul>
     *   <li>密钥查找失败（keyFor 返回 null）</li>
     *   <li>签名计算返回 null（signer 未启用）</li>
     *   <li>签名计算异常</li>
     * </ul>
     *
     * @param sysid 目标系统 ID
     * @param frame 原始未签名帧
     * @return 签名帧，或 null（降级为未签名帧）
     */
    private MavlinkFrame signFrame(int sysid, MavlinkFrame frame) {
        try {
            SigningKeyManager.KeyEntry keyEntry = signingKeyManager.keyFor(sysid);
            if (keyEntry == null) {
                log.warn("签名降级：sysid={} 密钥查找失败，发送未签名帧", sysid);
                return null;
            }

            int linkId = keyEntry.linkId();
            // 官方签名时间戳：48 位、单位 10 微秒、纪元 2015-01-01
            long timestamp = MavlinkSigner.currentSigningTimestamp();

            // 获取 CRC_EXTRA（通过 msgId 查找消息定义）
            int crcExtra = MavlinkMessageInfo.crcExtraOf(frame.getMessageId());

            // 构造临时签名帧（signature=null）获取 frameBytes（帧头至 CRC，不含签名数据）
            MavlinkFrame tempFrame = MavlinkFrame.ofSigned(
                    frame.getSystemId(), frame.getComponentId(), frame.getSequence(),
                    frame.getMessageId(), crcExtra, frame.getPayload(),
                    linkId, timestamp, null);
            byte[] frameBytes = tempFrame.encodeV2();

            // 计算官方 sha256_48 签名：口令按目标 sysid 取（多机密钥模式下各机可不同）
            MavlinkSigner signer = signerFactory.signerFor(keyEntry);
            if (signer == null) {
                log.warn("签名降级：sysid={} 密钥条目里没有可用口令，发送未签名帧", sysid);
                return null;
            }
            byte[] signature = signer.sign(frameBytes, linkId, timestamp);
            if (signature == null) {
                log.warn("签名降级：sysid={} 签名计算返回 null，发送未签名帧", sysid);
                return null;
            }

            // 构造最终签名帧
            return MavlinkFrame.ofSigned(
                    frame.getSystemId(), frame.getComponentId(), frame.getSequence(),
                    frame.getMessageId(), crcExtra, frame.getPayload(),
                    linkId, timestamp, signature);
        } catch (Exception e) {
            log.warn("签名降级：sysid={} 签名计算异常：{}，发送未签名帧", sysid, e.getMessage());
            return null;
        }
    }

    /**
     * 接收路径签名验证：验证帧的签名与 timestamp。
     * <p>
     * 验证流程：
     * <ol>
     *   <li>检测 INC bit 0：未签名帧按 rejectUnsigned 配置处理</li>
     *   <li>签名帧：验证 signature + timestamp</li>
     *   <li>验证失败：丢弃帧 + WARN 日志</li>
     * </ol>
     *
     * @param frame 接收到的帧
     * @return true=通过验证（或未签名帧被允许），false=验证失败需丢弃
     */
    private boolean verifyFrame(MavlinkFrame frame) {
        boolean isSigned = frame.isSigned();

        if (!isSigned) {
            // 未签名帧：根据 rejectUnsigned 配置处理
            if (signatureConfig.isRejectUnsigned()) {
                rejectSigning(DroneMetrics.SigningRejectReason.UNSIGNED);
                log.warn("拒绝未签名帧：sysid={} msgId={} (rejectUnsigned=true)",
                        frame.getSystemId(), frame.getMessageId());
                return false;
            } else {
                signingStats.unsigned++;
                DroneMetrics m = metrics();
                if (m != null) {
                    m.recordSigningUnsigned();
                }
                return true; // 允许未签名帧通过
            }
        }

        // 签名帧：验证签名 + timestamp
        try {
            // 获取 frameBytes（帧头至 CRC 的全部字节，不含签名数据）
            byte[] fullBytes = frame.encodeV2();
            int frameBytesLen = 12 + frame.getPayloadLength();
            byte[] frameBytes = java.util.Arrays.copyOf(fullBytes, frameBytesLen);

            int linkId = frame.getLinkId();
            long timestamp = frame.getTimestamp();
            byte[] signature = frame.getSignature();

            // 验证签名：口令按来源 sysid 取；取不到口令时拒绝（不拿别的机的口令去验，
            // 否则等于把一个未知来源的帧当成本机队成员放行）
            MavlinkSigner signer = signerFactory.signerFor(frame.getSystemId());
            if (signer == null) {
                rejectSigning(DroneMetrics.SigningRejectReason.NO_KEY);
                log.warn("签名验证拒绝：sysid={} 无可用密钥（msgId={}），按未授权帧丢弃",
                        frame.getSystemId(), frame.getMessageId());
                return false;
            }
            if (!signer.verify(frameBytes, linkId, timestamp, signature)) {
                rejectSigning(DroneMetrics.SigningRejectReason.BAD_SIGNATURE);
                log.warn("签名验证失败：sysid={} linkId={} msgId={}",
                        frame.getSystemId(), linkId, frame.getMessageId());
                return false;
            }

            // 验证 timestamp（重放攻击防护，官方按 linkId+sysid+compid 分流且要求严格递增）
            if (timestampTracker != null && !timestampTracker.check(linkId, frame.getSystemId(),
                    frame.getComponentId(), timestamp)) {
                rejectSigning(DroneMetrics.SigningRejectReason.TIMESTAMP_REPLAY);
                log.warn("Timestamp 校验失败（疑似重放）：sysid={} linkId={} timestamp={}",
                        frame.getSystemId(), linkId, timestamp);
                return false;
            }

            signingStats.verified++;
            DroneMetrics m = metrics();
            if (m != null) {
                m.recordSigningVerified();
            }
            // C5 第三步的地面证据：这台机"确实有签名帧通过验签"。收紧 reject-unsigned 前的就绪判定
            // 只看它——计数器是全机队汇总的，回答不了"具体哪台没配密钥"。
            // 注册表内部按节流落库，此处不在每帧路径上写库。
            if (deviceRegistry != null) {
                deviceRegistry.markSigningVerified(frame.getSystemId());
            }
            return true;
        } catch (Exception e) {
            rejectSigning(DroneMetrics.SigningRejectReason.ERROR);
            log.warn("签名验证异常：sysid={} {}", frame.getSystemId(), e.getMessage());
            return false;
        }
    }

    /**
     * 构造签名后的 GCS 心跳帧（用于 discovery 和 heartbeatLoop）。
     * 签名未启用或降级时返回未签名心跳帧。
     */
    private MavlinkFrame signedGcsHeartbeat(int seq) {
        MavlinkFrame hb = gcsHeartbeat(seq);
        if (isSigningEnabled()) {
            MavlinkFrame signed = signFrame(GCS_SYSID, hb);
            if (signed != null) {
                return signed;
            }
        }
        return hb;
    }

    /**
     * 签名统计：每 60s 输出 verified/rejected/unsigned 计数。
     */
    @org.springframework.scheduling.annotation.Scheduled(fixedDelay = 60_000, initialDelay = 60_000)
    public void reportSigningStats() {
        if (!isSigningEnabled()) {
            return;
        }
        log.info("signing: verified={} rejected={} unsigned={}",
                signingStats.verified, signingStats.rejected, signingStats.unsigned);
        signingStats.reset();
    }

    /** 签名统计计数器（周期性重置）。 */
    private static class SigningStats {
        volatile long verified;
        volatile long rejected;
        volatile long unsigned;

        void reset() {
            verified = 0;
            rejected = 0;
            unsigned = 0;
        }
    }
}
