package io.aerofleet.cloud.gateway;

import io.aerofleet.cloud.telemetry.PendingAcks;
import io.aerofleet.mavlink.MavlinkFrame;
import io.aerofleet.mavlink.enums.MavEnums;
import io.aerofleet.mavlink.messages.Heartbeat;
import io.aerofleet.mavlink.security.MavlinkSignatureConfig;
import io.aerofleet.mavlink.security.MavlinkSigner;
import io.aerofleet.mavlink.security.MavlinkSignerFactory;
import io.aerofleet.mavlink.security.SigningKeyManager;
import io.aerofleet.mavlink.security.TimestampTracker;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.lang.reflect.Field;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetSocketAddress;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

/**
 * C5 MAVLink v2 签名<b>接收验证路径</b>测试。
 *
 * <p><b>为什么单独写这个文件</b>：签名能力此前已有两侧覆盖——{@code mavlink-core} 的
 * {@code MavlinkSignerTest}/{@code MavlinkSigningVectorTest}/{@code SigningKeyManagerTest}
 * 管密码学本身，{@code MavlinkSigningConfigurationTest} 管 bean 装配，
 * {@code scripts/e2e-signing.ps1} 管全链路（884 行 / 6 场景）。
 *
 * <p>但 {@link UdpGateway} 那段 <b>fail-closed 验签决策</b>——拒绝未签名、按来源 sysid
 * 取密钥、取不到密钥就拒、timestamp 防重放——此前<b>零测试覆盖</b>；而
 * {@code e2e-signing.ps1} 从未被任何 workflow 调用（CI 只跑 9 个 e2e，签名不在其中）。
 * 结果是：一段安全语义全靠人眼保证，CI 全绿也证明不了它还在工作。
 *
 * <p><b>策略</b>：不反射调 private {@code verifyFrame}（那样测不到
 * Parser → Transport → verifyFrame 的真实链路），而是打真实 UDP 字节走生产同一条路：
 * {@code DatagramSocket → UdpMavlinkTransport → MavlinkParser → onFrame → verifyFrame}。
 * 顺带覆盖「签名块是否正确从线上字节切出来」——全链路最容易错的一环。
 *
 * <p><b>端口</b>：不用固定 offset（那会和并发构建相撞），改用 OS 分配的瞬时端口，
 * 网关自己绑定；dronePort 指向无人监听的端口，discovery 发送失败仅记日志。
 *
 * <p>经验来源：C5 签名能力核查（2026-10-09）。ROADMAP 原先声称
 * "MavlinkParser 层仍只切帧不验签"，逐条核对代码后确认该描述<b>不准确</b>：
 * {@code MavlinkParser} 完整解析 13 字节签名块，验签在 {@code UdpGateway} 内完成且 fail-closed。
 * 真正的问题是<b>无人验证它在工作</b>——本文件补上这一环。
 */
@DisplayName("UdpGateway: MAVLink v2 签名接收验证（fail-closed 路径）")
class UdpGatewaySigningTest {

    /** 单机模式口令：模拟"一把全局密钥"部署。 */
    private static final String SECRET = "c5-test-secret-key";

    /** 另一把口令：模拟攻击者或密钥不匹配。 */
    private static final String OTHER_SECRET = "c5-attacker-key";

    /** 无人监听的端口，仅供 discovery 指向。 */
    private static final int DRONE_PORT = 24699;

    private TelemetryIngestService ingest;
    private PendingAcks pendings;
    private UdpGateway gateway;
    private int port;

    @TempDir
    Path tempDir;

    @BeforeEach
    void setUp() throws Exception {
        ingest = mock(TelemetryIngestService.class);
        pendings = mock(PendingAcks.class);
        port = ephemeralPort();
        // 默认：单机模式签名 + rejectUnsigned=true（fail-closed 出厂口径）
        gateway = gatewayWithSigning(port, SECRET, null, true);
    }

    @AfterEach
    void tearDown() {
        if (gateway != null) {
            gateway.shutdown();
        }
    }

    private static int ephemeralPort() throws Exception {
        try (DatagramSocket probe = new DatagramSocket(0)) {
            return probe.getLocalPort();
        }
    }

    /** 构造网关并注入签名组件（{@code @Autowired(required=false)} 字段无 setter，按仓内既有做法反射）。 */
    private UdpGateway gatewayWithSigning(int p, String secret, Path keyStore,
                                          boolean rejectUnsigned) throws Exception {
        UdpGateway gw = new UdpGateway(p, "127.0.0.1", DRONE_PORT, "", "127.0.0.1",
                false, 1000, ingest, pendings);

        MavlinkSignatureConfig cfg = new MavlinkSignatureConfig();
        cfg.setEnabled(true);
        cfg.setRejectUnsigned(rejectUnsigned);
        SigningKeyManager km;
        if (keyStore != null) {
            cfg.setKeyStorePath(keyStore.toString());
            // 不给 defaultKey：未知 sysid 应取不到口令
            km = new SigningKeyManager(keyStore, null);
        } else {
            cfg.setSecretKey(secret);
            km = new SigningKeyManager(secret);
        }
        MavlinkSignerFactory factory = new MavlinkSignerFactory(km, rejectUnsigned);

        inject(gw, "signatureConfig", cfg);
        inject(gw, "signingKeyManager", km);
        inject(gw, "signerFactory", factory);
        inject(gw, "timestampTracker", new TimestampTracker());
        return gw;
    }

    /** 关闭签名（出厂形态）：四个组件全置 null。 */
    private UdpGateway gatewayWithoutSigning(int p) throws Exception {
        UdpGateway gw = new UdpGateway(p, "127.0.0.1", DRONE_PORT, "", "127.0.0.1",
                false, 1000, ingest, pendings);
        inject(gw, "signatureConfig", null);
        inject(gw, "signerFactory", null);
        inject(gw, "signingKeyManager", null);
        return gw;
    }

    private static void inject(UdpGateway gw, String field, Object value) throws Exception {
        Field f = UdpGateway.class.getDeclaredField(field);
        f.setAccessible(true);
        f.set(gw, value);
    }

    // ==================== 帧构造：全部走生产路径 toFrame(..., signer) ====================

    private Heartbeat droneHeartbeat() {
        return new Heartbeat(0, 2, 6, 0, MavEnums.MAV_STATE_ACTIVE);
    }

    /** 未签名帧（INC 位不置位）。 */
    private byte[] unsignedFrame(int sysid, int seq) {
        return droneHeartbeat().toFrame(sysid, 1, seq).encodeV2();
    }

    /** 签名帧：由 {@code secret} 签名，linkId = sysid & 0xFF。 */
    private byte[] signedFrame(int sysid, int seq, String secret) {
        MavlinkSigner signer = new MavlinkSigner(true, secret, sysid & 0xFF, true);
        return droneHeartbeat().toFrame(sysid, 1, seq, signer).encodeV2();
    }

    /** 篡改：动 13 字节签名块的最后一个字节（CRC 在签名块之前，仍然合法）。 */
    private byte[] tamperSignature(byte[] frame) {
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

    /** 断言"在观察窗内没有任何帧被放行"。 */
    private void assertNothingForwarded() throws Exception {
        Thread.sleep(1200);
        verify(ingest, never()).handle(any(MavlinkFrame.class));
    }

    // ==================== 场景 ====================

    @Test
    @DisplayName("1. 正常签名帧：验签通过并转发给 TelemetryIngestService")
    void signedFrame_isVerifiedAndForwarded() throws Exception {
        send(signedFrame(42, 0, SECRET));

        verify(ingest, timeout(2000)).handle(any(MavlinkFrame.class));
    }

    @Test
    @DisplayName("2. 篡改帧：签名末字节被改 → 验签失败即丢弃，不转发")
    void tamperedSignature_isRejected() throws Exception {
        send(tamperSignature(signedFrame(42, 0, SECRET)));

        assertNothingForwarded();
    }

    @Test
    @DisplayName("3. 未签名帧 + rejectUnsigned=true（fail-closed 默认）：拒绝")
    void unsignedFrame_rejectedWhenRejectUnsigned() throws Exception {
        send(unsignedFrame(42, 0));

        assertNothingForwarded();
    }

    @Test
    @DisplayName("4. 密钥不匹配：用另一把口令签的帧被拒（不拿本机密钥放行未知来源）")
    void wrongKey_isRejected() throws Exception {
        send(signedFrame(42, 0, OTHER_SECRET));

        assertNothingForwarded();
    }

    @Test
    @DisplayName("5. 多机密钥库：各 sysid 用自己的口令都能通过")
    void multiKeyPerSysid_allVerified() throws Exception {
        Path store = writeKeyStore();
        gateway.shutdown();
        port = ephemeralPort();
        gateway = gatewayWithSigning(port, null, store, true);

        send(signedFrame(7, 0, "key-for-7"));
        send(signedFrame(8, 0, "key-for-8"));

        verify(ingest, timeout(2500).times(2)).handle(any(MavlinkFrame.class));
    }

    @Test
    @DisplayName("6. 未知 sysid：密钥库无其口令且无 defaultKey → 拒绝（不顶替放行）")
    void unknownSysid_isRejected() throws Exception {
        Path store = writeKeyStore();
        gateway.shutdown();
        port = ephemeralPort();
        gateway = gatewayWithSigning(port, null, store, true);

        // sysid=99 不在库里；即便用库内口令签也无效——验签按**来源 sysid** 取密钥
        send(signedFrame(99, 0, "key-for-7"));

        assertNothingForwarded();
    }

    @Test
    @DisplayName("7. 重放：同一签名帧原样重发 → timestamp 非递增，第二次被拒")
    void replaySameFrame_isRejected() throws Exception {
        byte[] frame = signedFrame(42, 0, SECRET);
        send(frame);
        verify(ingest, timeout(2000)).handle(any(MavlinkFrame.class));

        // 原样重发：签名仍合法，但 timestamp 与上一帧相同 → 官方口径判重放
        send(frame);
        Thread.sleep(1200);
        verify(ingest, times(1)).handle(any(MavlinkFrame.class));
    }

    @Test
    @DisplayName("8. 签名未启用（出厂默认）：未签名帧照常通过，行为与改造前一致")
    void signingDisabled_unsignedPasses() throws Exception {
        gateway.shutdown();
        port = ephemeralPort();
        gateway = gatewayWithoutSigning(port);

        send(unsignedFrame(42, 0));

        verify(ingest, timeout(2000)).handle(any(MavlinkFrame.class));
    }

    // ==================== 辅助 ====================

    private Path writeKeyStore() throws Exception {
        Path store = tempDir.resolve("signing-keys.json");
        Files.writeString(store, """
                {
                  "version": 1,
                  "sysids": {
                    "7": {"key": "key-for-7", "linkId": 7},
                    "8": {"key": "key-for-8", "linkId": 8}
                  }
                }
                """);
        return store;
    }
}
