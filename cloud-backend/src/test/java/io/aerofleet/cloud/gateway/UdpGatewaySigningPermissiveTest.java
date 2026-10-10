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

import java.lang.reflect.Field;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetSocketAddress;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

/**
 * C5 第一步的精确语义：**开着签名但不拒绝未签名**（staging 姿态）。
 *
 * <p>这一步是"最终在 prod 打开签名"之前的关键演练，它的价值完全取决于
 * 三类帧各自的命运被正确实现：
 *
 * <table>
 *   <tr><th>帧</th><th>reject-unsigned=true</th><th>reject-unsigned=false（本类）</th></tr>
 *   <tr><td>未签名</td><td>拒绝</td><td><b>放行</b>（这是本姿态的全部意义）</td></tr>
 *   <tr><td>签名+密钥正确</td><td>放行</td><td>放行</td></tr>
 *   <tr><td>签名+密钥错误</td><td>拒绝</td><td><b>仍拒绝</b></td></tr>
 * </table>
 *
 * <p><b>最容易误解的一点</b>：{@code reject-unsigned=false} <b>只</b>保护未签名客户端，
 * <b>不</b>保护"签错密钥"的客户端。后者照样被丢——一个签名校验失败的帧要么是被篡改，
 * 要么是密钥配置错了，两种情况都不该放行。这个不对称是刻意设计，不是疏漏。
 *
 * <p>本类与 {@link UdpGatewaySigningTest} 的分工：那个覆盖默认（fail-closed）姿态，
 * 这个覆盖"开了但不拒"的演练姿态。
 */
@DisplayName("C5 第一步：开着签名但不拒未签名（reject-unsigned=false）")
class UdpGatewaySigningPermissiveTest {

    private static final String SECRET = "c5-permissive-secret";

    private static final int DRONE_PORT = 24699;

    private TelemetryIngestService ingest;

    private PendingAcks pendings;

    private UdpGateway gateway;

    private int port;

    @BeforeEach
    void setUp() throws Exception {
        ingest = mock(TelemetryIngestService.class);
        pendings = mock(PendingAcks.class);
        port = ephemeralPort();
        // 关键：enabled=true 但 rejectUnsigned=false
        gateway = gatewayWithSigning(port, SECRET, false);
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

    private UdpGateway gatewayWithSigning(int p, String secret, boolean rejectUnsigned)
            throws Exception {
        UdpGateway gw = new UdpGateway(p, "127.0.0.1", DRONE_PORT, "", "127.0.0.1",
                false, 1000, ingest, pendings);
        MavlinkSignatureConfig cfg = new MavlinkSignatureConfig();
        cfg.setEnabled(true);
        cfg.setSecretKey(secret);
        cfg.setRejectUnsigned(rejectUnsigned);
        SigningKeyManager km = new SigningKeyManager(secret);
        inject(gw, "signatureConfig", cfg);
        inject(gw, "signingKeyManager", km);
        inject(gw, "signerFactory", new MavlinkSignerFactory(km, rejectUnsigned));
        inject(gw, "timestampTracker", new TimestampTracker());
        return gw;
    }

    private static void inject(UdpGateway gw, String field, Object value) throws Exception {
        Field f = UdpGateway.class.getDeclaredField(field);
        f.setAccessible(true);
        f.set(gw, value);
    }

    private Heartbeat droneHeartbeat() {
        return new Heartbeat(0, 2, 6, 0, MavEnums.MAV_STATE_ACTIVE);
    }

    private byte[] unsignedFrame(int sysid, int seq) {
        return droneHeartbeat().toFrame(sysid, 1, seq).encodeV2();
    }

    private byte[] signedFrame(int sysid, int seq, String secret) {
        MavlinkSigner signer = new MavlinkSigner(true, secret, sysid & 0xFF, false);
        return droneHeartbeat().toFrame(sysid, 1, seq, signer).encodeV2();
    }

    private void send(byte[] bytes) throws Exception {
        try (DatagramSocket s = new DatagramSocket()) {
            s.send(new DatagramPacket(bytes, bytes.length,
                    new InetSocketAddress("127.0.0.1", port)));
        }
    }

    private void assertNothingForwarded() throws Exception {
        Thread.sleep(1200);
        verify(ingest, never()).handle(any(MavlinkFrame.class));
    }

    // ==================== 三条断言，缺一不可 ====================

    @Test
    @DisplayName("1. 未签名帧被放行 —— 这一步的全部意义（旧固件/第三方设备不断线）")
    void unsignedFrame_isAllowed() throws Exception {
        send(unsignedFrame(42, 0));

        verify(ingest, timeout(2000)).handle(any(MavlinkFrame.class));
    }

    @Test
    @DisplayName("2. 签名+密钥正确：放行，且与未签名帧共存")
    void correctlySignedFrame_isAllowedAlongsideUnsigned() throws Exception {
        send(unsignedFrame(42, 0));
        send(signedFrame(42, 1, SECRET));

        verify(ingest, timeout(2500).times(2)).handle(any(MavlinkFrame.class));
    }

    @Test
    @DisplayName("3. 签名+密钥错误：仍被拒（reject-unsigned=false 不保护签错密钥的）")
    void wronglySignedFrame_isStillRejected() throws Exception {
        send(signedFrame(42, 0, "some-other-key"));

        assertNothingForwarded();
    }

    @Test
    @DisplayName("4. 篡改：仍被拒（与姿态无关的安全底线）")
    void tamperedFrame_isStillRejected() throws Exception {
        byte[] good = signedFrame(42, 0, SECRET);
        byte[] bad = good.clone();
        bad[bad.length - 1] ^= 0x5A;
        send(bad);

        assertNothingForwarded();
    }

    @Test
    @DisplayName("5. 未知 sysid 签的帧：仍被拒（不拿本机密钥放行未知来源）")
    void unknownSysid_isStillRejected() throws Exception {
        // 单密钥模式下任意 sysid 都能取到密钥，所以这里改用多机密钥库；
        // 覆盖"取不到密钥就拒"在 permissive 姿态下依然成立。
        Path store = Files.createTempFile("c5-permissive", ".json");
        Files.writeString(store, """
                {
                  "version": 1,
                  "sysids": {
                    "7": {"key": "key-for-7", "linkId": 7}
                  }
                }
                """);
        gateway.shutdown();
        port = ephemeralPort();
        UdpGateway gw = new UdpGateway(port, "127.0.0.1", DRONE_PORT, "", "127.0.0.1",
                false, 1000, ingest, pendings);
        MavlinkSignatureConfig cfg = new MavlinkSignatureConfig();
        cfg.setEnabled(true);
        cfg.setKeyStorePath(store.toString());
        cfg.setRejectUnsigned(false);
        SigningKeyManager km = new SigningKeyManager(store, null);
        inject(gw, "signatureConfig", cfg);
        inject(gw, "signingKeyManager", km);
        inject(gw, "signerFactory", new MavlinkSignerFactory(km, false));
        inject(gw, "timestampTracker", new TimestampTracker());
        gateway = gw;

        // sysid=99 不在库里、且无 defaultKey ⇒ 取不到口令 ⇒ 拒
        send(signedFrame(99, 0, "key-for-7"));

        assertNothingForwarded();
    }

    @Test
    @DisplayName("6. 重放：仍被拒（与姿态无关）")
    void replay_isStillRejected() throws Exception {
        byte[] frame = signedFrame(42, 0, SECRET);
        send(frame);
        verify(ingest, timeout(2000)).handle(any(MavlinkFrame.class));

        send(frame); // 原样重发
        Thread.sleep(1200);
        verify(ingest, times(1)).handle(any(MavlinkFrame.class));
    }
}
