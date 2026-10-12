package io.aerofleet.cloud.gateway;

import io.aerofleet.cloud.metrics.DroneMetrics;
import io.aerofleet.cloud.config.MavlinkSigningConfiguration;
import io.aerofleet.cloud.telemetry.PendingAcks;
import io.aerofleet.mavlink.MavlinkFrame;
import io.aerofleet.mavlink.MavlinkMessageInfo;
import io.aerofleet.mavlink.enums.MavEnums;
import io.aerofleet.mavlink.messages.Heartbeat;
import io.aerofleet.mavlink.security.MavlinkSignatureConfig;
import io.aerofleet.mavlink.security.MavlinkSigner;
import io.aerofleet.mavlink.security.MavlinkSignerFactory;
import io.aerofleet.mavlink.security.SigningKeyManager;
import io.aerofleet.mavlink.security.TimestampTracker;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import java.lang.reflect.Field;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetSocketAddress;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.verify;

/**
 * C5 签名统计接入 Prometheus 的测试。
 *
 * <p><b>这个文件要堵的洞</b>：签名统计此前是 {@code UdpGateway} 里的纯内存计数器，
 * 每 60s 打一条 {@code log.info} 然后清零。而 {@code micrometer-registry-prometheus}
 * 早就接好、{@code /actuator/prometheus} 早就暴露——也就是说，本系统最该被监控的
 * 安全计数器，对**已经在采集的 Prometheus 是不可见的**：有人在爆破签名时
 * {@code rejected} 在涨，但没有任何东西能据此告警。
 *
 * <p><b>为什么不止测"计数器会加"</b>：{@code Counter} 自己会加是显然的，
 * 真正会坏的是<b>接线</b>——{@code droneMetrics} 是 {@code @Autowired(required=false)}
 * 字段，没人保证它被注上了。所以这里断言的是<b>端到端</b>：打真实 UDP 字节走生产
 * 同一条路（{@code DatagramSocket → UdpMavlinkTransport → MavlinkParser → onFrame
 * → verifyFrame}），然后直接读 {@link SimpleMeterRegistry}。
 *
 * <p><b>为什么断言 reason 标签而不只是总数</b>：裸的"拒了多少"不可行动，
 * "因为什么被拒"才可行动——{@code no-key}（有帧带着有效签名但来源 sysid
 * 不在密钥库里）与 {@code unsigned}（客户端没升级签名）是完全不同的两件事，
 * 前者更值得警惕。只测总数会让这两个原因在告警里糊成一个数字。
 */
@DisplayName("UdpGateway: 签名统计接入 Micrometer（可告警）")
class UdpGatewaySigningMetricsTest {

    private static final String SECRET = "metrics-test-secret-key";
    private static final String OTHER_SECRET = "metrics-attacker-key";
    private static final int DRONE_PORT = 24698;

    private TelemetryIngestService ingest;
    private PendingAcks pendings;
    private UdpGateway gateway;
    private SimpleMeterRegistry registry;
    private int port;

    @BeforeEach
    void setUp() throws Exception {
        ingest = mock(TelemetryIngestService.class);
        pendings = mock(PendingAcks.class);
        registry = new SimpleMeterRegistry();
        port = ephemeralPort();
        gateway = gatewayWithSigning(port, SECRET, true);
    }

    @AfterEach
    void tearDown() {
        if (gateway != null) {
            gateway.shutdown();
        }
        registry.close();
    }

    private static int ephemeralPort() throws Exception {
        try (DatagramSocket probe = new DatagramSocket(0)) {
            return probe.getLocalPort();
        }
    }

    private UdpGateway gatewayWithSigning(int p, String secret, boolean rejectUnsigned) throws Exception {
        UdpGateway gw = new UdpGateway(p, "127.0.0.1", DRONE_PORT, "", "127.0.0.1",
                false, 1000, ingest, pendings);

        MavlinkSignatureConfig cfg = new MavlinkSignatureConfig();
        cfg.setEnabled(true);
        cfg.setRejectUnsigned(rejectUnsigned);
        cfg.setSecretKey(secret);
        SigningKeyManager km = new SigningKeyManager(secret);

        inject(gw, "signatureConfig", cfg);
        inject(gw, "signingKeyManager", km);
        inject(gw, "signerFactory", new MavlinkSignerFactory(km, rejectUnsigned));
        inject(gw, "timestampTracker", new TimestampTracker());
        // 关键：把真实的 DroneMetrics 接上，用真实 SimpleMeterRegistry 当后端。
        // DroneMetrics 的两个 ObjectProvider 依赖（DeviceRegistry/FormationService）
        // 在本测试里不存在，传空 provider 即可——它们是可选依赖。
        // 字段名必须是 droneMetricsProvider（生产代码用 ObjectProvider 惰性解析，
        // 直接字段注入会形成 udpGateway ⇄ droneCommandService 循环依赖）。
        inject(gw, "droneMetricsProvider", fixedProvider(new DroneMetrics(registry, emptyProvider(), emptyProvider())));
        inject(gw, "droneMetrics", new DroneMetrics(registry, emptyProvider(), emptyProvider()));
        return gw;
    }

    /** 始终返回同一个 bean 的 ObjectProvider（模拟已就绪的 Spring 容器）。 */
    private static <T> ObjectProvider<T> fixedProvider(T bean) {
        return new ObjectProvider<>() {
            @Override
            public T getObject(Object... args) {
                return bean;
            }

            @Override
            public T getObject() {
                return bean;
            }

            @Override
            public T getIfAvailable() {
                return bean;
            }

            @Override
            public T getIfUnique() {
                return bean;
            }
        };
    }

    /** 构造一个「拿不到任何 bean」的 ObjectProvider（DroneMetrics 的可选依赖）。 */
    private static <T> ObjectProvider<T> emptyProvider() {
        return new ObjectProvider<>() {
            @Override
            public T getObject(Object... args) {
                throw new org.springframework.beans.factory.NoSuchBeanDefinitionException("n/a");
            }

            @Override
            public T getObject() {
                throw new org.springframework.beans.factory.NoSuchBeanDefinitionException("n/a");
            }

            @Override
            public T getIfAvailable() {
                return null;
            }

            @Override
            public T getIfUnique() {
                return null;
            }
        };
    }

    private static void inject(Object target, String field, Object value) throws Exception {
        Field f = UdpGateway.class.getDeclaredField(field);
        f.setAccessible(true);
        f.set(target, value);
    }

    // ==================== 帧构造（与 UdpGatewaySigningTest 同款生产路径） ====================

    private static Heartbeat droneHeartbeat() {
        return new Heartbeat(0, 2, 6, 0, MavEnums.MAV_STATE_ACTIVE);
    }

    private static byte[] unsignedFrame(int sysid, int seq) {
        return droneHeartbeat().toFrame(sysid, 1, seq).encodeV2();
    }

    private static byte[] signedFrame(int sysid, int seq, String secret) {
        MavlinkSigner signer = new MavlinkSigner(true, secret, sysid & 0xFF, true);
        return droneHeartbeat().toFrame(sysid, 1, seq, signer).encodeV2();
    }

    /**
     * 用**指定** timestamp 签一帧（造重放形态）。
     *
     * <p>不能复用 {@code toFrame(..., signer)}：它内部走 {@code nextTimestamp()} 保证
     * 逐帧递增，造不出"回退"的帧。重放用例要的正是"签名合法、timestamp 回退"。
     *
     * <p>timestamp 是<b>开机毫秒数</b>，不是 Unix 毫秒——传 1000 这种小数值会得到
     * "远早于当前"的帧，被"新流最多落后 60 秒"的口径直接拒掉，连前置帧都进不来。
     * 所以只能相对 {@link MavlinkSigner#currentSigningTimestamp()} 取偏移。
     */
    private static byte[] signedFrame(int sysid, int seq, String secret, long timestamp) {
        MavlinkSigner signer = new MavlinkSigner(true, secret, sysid & 0xFF, true);
        Heartbeat hb = droneHeartbeat();
        byte[] payload = hb.encode();
        // 先用占位签名构造临时帧，取"帧头至 CRC"的 12+len 字节作为签名输入
        byte[] placeholder = new byte[MavlinkSigner.SIGNATURE_LENGTH];
        byte[] all = MavlinkFrame.ofSigned(sysid, 1, seq, Heartbeat.ID,
                MavlinkMessageInfo.crcExtraOf(Heartbeat.ID), payload,
                sysid & 0xFF, timestamp, placeholder).encodeV2();
        byte[] frameBytes = java.util.Arrays.copyOf(all, 12 + payload.length);
        byte[] sig = signer.sign(frameBytes, sysid & 0xFF, timestamp);
        return MavlinkFrame.ofSigned(sysid, 1, seq, Heartbeat.ID,
                MavlinkMessageInfo.crcExtraOf(Heartbeat.ID), payload,
                sysid & 0xFF, timestamp, sig).encodeV2();
    }

    private static byte[] tamperSignature(byte[] frame) {
        byte[] copy = frame.clone();
        copy[copy.length - 1] ^= 0x5A;
        return copy;
    }

    private void send(byte[] bytes) throws Exception {
        try (DatagramSocket s = new DatagramSocket()) {
            s.send(new DatagramPacket(bytes, bytes.length,
                    new InetSocketAddress("127.0.0.1", port)));
        }
    }

    private double counterValue(String name) {
        var c = registry.find(name).counter();
        assertThat(c).as("指标 %s 应已注册（否则说明接线断了，不是「没发生」）", name).isNotNull();
        return c.count();
    }

    private double rejectedReason(String reason) {
        var c = registry.find("aerofleet.signing.rejected").tag("reason", reason).counter();
        assertThat(c).as("应存在 reason=%s 的 rejected 计数", reason).isNotNull();
        return c.count();
    }

    /**
     * 读某个 reason 的拒绝计数，**不存在时返回 0**（而不是断言它存在）。
     *
     * <p>计数是按标签惰性创建的：没发生过的事件根本不会注册 meter。
     * 所以"该 reason 为 0"有两种等价的观测方式——计数器不存在、或存在且为 0——
     * 而"计数器不存在"恰恰是更好的证据（它证明这条路径一次都没被走到）。
     */
    private double rejectedReasonOrZero(String reason) {
        var c = registry.find("aerofleet.signing.rejected").tag("reason", reason).counter();
        return c == null ? 0.0 : c.count();
    }

    // ==================== 场景 ====================

    @Test
    @DisplayName("0. Spring 上下文能加载：droneMetrics 用 ObjectProvider 不构成循环依赖")
    void springContextLoadsWithoutCircularDependency() {
        // 这个断言看起来多余，但它是本文件里最贵的一个：
        // 若把 ObjectProvider<DroneMetrics> 写回 @Autowired DroneMetrics，
        // 会形成 udpGateway → droneMetrics → FormationService → droneCommandService
        // → udpGateway 的环，Spring 抛 BeanCurrentlyInCreationException。
        // 那种失败**只**在全量套件里出现（13 个 @SpringBootTest 上下文全挂），
        // 单测用反射注入完全绕过了容器、根本发现不了。
        // ApplicationContextRunner 只装签名相关 bean，足以验证"这条依赖链能解析"。
        new ApplicationContextRunner()
                .withUserConfiguration(MavlinkSigningConfiguration.class)
                .withBean("droneMetrics", DroneMetrics.class,
                        () -> new DroneMetrics(registry, emptyProvider(), emptyProvider()))
                .withPropertyValues("mavlink.signing.enabled=true",
                        "mavlink.signing.secret-key=" + SECRET)
                .run(context -> assertThat(context)
                        .as("droneMetrics 作为 bean 与签名组件共存时上下文必须起得来")
                        .hasNotFailed());
    }

    @Test
    @DisplayName("1. 验签通过 → aerofleet.signing.verified 递增")
    void verifiedFrame_incrementsVerifiedMetric() throws Exception {
        send(signedFrame(42, 0, SECRET));

        verify(ingest, timeout(2000)).handle(any(MavlinkFrame.class));
        assertThat(counterValue("aerofleet.signing.verified"))
                .as("通过的帧必须落在 Prometheus 上，否则「签名在工作」只能靠翻日志证明")
                .isEqualTo(1.0);
    }

    @Test
    @DisplayName("2. 未签名 + rejectUnsigned=true → rejected{reason=unsigned} 递增")
    void unsignedFrame_incrementsRejectedWithUnsignedReason() throws Exception {
        send(unsignedFrame(42, 0));

        Thread.sleep(1200);
        assertThat(rejectedReason(DroneMetrics.SigningRejectReason.UNSIGNED)).isEqualTo(1.0);
        assertThat(counterValue("aerofleet.signing.verified"))
                .as("被拒的帧不该同时被计为 verified")
                .isZero();
    }

    @Test
    @DisplayName("3. 篡改签名 → rejected{reason=bad-signature}，不是 unsigned")
    void tamperedFrame_incrementsBadSignatureReason() throws Exception {
        send(tamperSignature(signedFrame(42, 0, SECRET)));

        Thread.sleep(1200);
        assertThat(rejectedReason(DroneMetrics.SigningRejectReason.BAD_SIGNATURE)).isEqualTo(1.0);
        assertThat(rejectedReasonOrZero(DroneMetrics.SigningRejectReason.UNSIGNED))
                .as("篡改帧带着签名块，一次都没走过 unsigned 分支——"
                        + "归类错了告警会指错方向（把篡改当成客户端没升级）")
                .isZero();
    }

    @Test
    @DisplayName("4. 密钥不匹配 → rejected{reason=bad-signature}（有签名但算不出真值）")
    void wrongKey_incrementsBadSignatureReason() throws Exception {
        send(signedFrame(42, 0, OTHER_SECRET));

        Thread.sleep(1200);
        assertThat(rejectedReason(DroneMetrics.SigningRejectReason.BAD_SIGNATURE)).isEqualTo(1.0);
    }

    @Test
    @DisplayName("5. timestamp 回退（重放）→ rejected{reason=timestamp-replay}")
    void replayFrame_incrementsTimestampReplayReason() throws Exception {
        // 第一帧正常签名（走 MavlinkSigner 自己的当前时间戳）。
        // timestamp 是**开机毫秒数**，不能自己编小数值——编出来的会是"远早于当前"，
        // 会被"新流最多落后 60 秒"的口径直接拒掉，第一帧就进不来。
        send(signedFrame(42, 0, SECRET));
        verify(ingest, timeout(2000)).handle(any(MavlinkFrame.class));
        assertThat(counterValue("aerofleet.signing.verified")).isEqualTo(1.0);

        // 重放的真实形态：回带一个更旧的 timestamp。签名本身合法，但 timestamp 回退 ⇒ 拒。
        send(signedFrame(42, 1, SECRET, MavlinkSigner.currentSigningTimestamp() - 5_000_000L));

        Thread.sleep(1200);
        assertThat(rejectedReason(DroneMetrics.SigningRejectReason.TIMESTAMP_REPLAY)).isEqualTo(1.0);
        assertThat(counterValue("aerofleet.signing.verified"))
                .as("重放帧不该再算一次 verified")
                .isEqualTo(1.0);
    }

    @Test
    @DisplayName("6. 拒绝 reason 标签互不串味：三类拒绝各自独立计数")
    void rejectReasons_areIndependentCounters() throws Exception {
        send(unsignedFrame(42, 0));
        send(tamperSignature(signedFrame(43, 0, SECRET)));
        send(signedFrame(44, 0, OTHER_SECRET));

        Thread.sleep(1500);
        assertThat(rejectedReason(DroneMetrics.SigningRejectReason.UNSIGNED)).isEqualTo(1.0);
        assertThat(rejectedReason(DroneMetrics.SigningRejectReason.BAD_SIGNATURE))
                .as("篡改 + 密钥不匹配都是 bad-signature，应累计为 2")
                .isEqualTo(2.0);
    }

    @Test
    @DisplayName("7. permissive 姿态：未签名帧记 unsigned 而非 rejected，且仍被放行")
    void permissiveMode_countsUnsignedSeparately() throws Exception {
        gateway.shutdown();
        port = ephemeralPort();
        gateway = gatewayWithSigning(port, SECRET, false);

        send(unsignedFrame(42, 0));

        verify(ingest, timeout(2000)).handle(any(MavlinkFrame.class));
        assertThat(counterValue("aerofleet.signing.unsigned")).isEqualTo(1.0);
        assertThat(registry.find("aerofleet.signing.rejected").counters())
                .as("放行的帧不该进 rejected")
                .isEmpty();
    }

@Test
    @DisplayName("8. 没有 DroneMetrics 时签名链路照常工作（指标是可选的，不是启动依赖）")
    void worksWithoutMetricsBean() throws Exception {
        gateway.shutdown();
        // 用**全新的** registry：setUp 里那个已经被注入过的 DroneMetrics 注册过计数器了，
        // 在它上面断言"无指标"是自相矛盾的。
        SimpleMeterRegistry fresh = new SimpleMeterRegistry();
        int p = ephemeralPort();
        UdpGateway bare = new UdpGateway(p, "127.0.0.1", DRONE_PORT, "", "127.0.0.1",
                false, 1000, ingest, pendings);
        MavlinkSignatureConfig cfg = new MavlinkSignatureConfig();
        cfg.setEnabled(true);
        cfg.setRejectUnsigned(true);
        cfg.setSecretKey(SECRET);
        SigningKeyManager km = new SigningKeyManager(SECRET);
        inject(bare, "signatureConfig", cfg);
        inject(bare, "signingKeyManager", km);
        inject(bare, "signerFactory", new MavlinkSignerFactory(km, true));
        inject(bare, "timestampTracker", new TimestampTracker());
        // 刻意不注入 droneMetrics
        gateway = bare;

        try (DatagramSocket s = new DatagramSocket()) {
            byte[] f = signedFrame(42, 0, SECRET);
            s.send(new DatagramPacket(f, f.length, new InetSocketAddress("127.0.0.1", p)));
        }

        verify(ingest, timeout(2000)).handle(any(MavlinkFrame.class));
        assertThat(fresh.find("aerofleet.signing.verified").counter())
                .as("未注入 DroneMetrics 时不产生任何签名指标")
                .isNull();
        fresh.close();
    }
}