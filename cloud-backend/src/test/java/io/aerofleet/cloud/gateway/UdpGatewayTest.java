package io.aerofleet.cloud.gateway;

import io.aerofleet.cloud.telemetry.PendingAcks;
import io.aerofleet.mavlink.MavlinkFrame;
import io.aerofleet.mavlink.enums.MavEnums;
import io.aerofleet.mavlink.messages.Heartbeat;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetSocketAddress;
import java.net.SocketException;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * UdpGateway 单元测试：使用真实 UDP 端口 + Mockito mock 的 TelemetryIngestService/PendingAcks。
 *
 * <p>策略说明：UdpGateway 构造时直接 {@code new UdpMavlinkTransport(bindAddress, udpPort)} 绑定真实
 * DatagramSocket，无法注入 mock transport。因此采用"真实端口 + mock 下游"方案：
 * <ul>
 *   <li>每个测试用独立高端口（{@link #BASE_PORT} + offset）避免与其他测试/默认 14550 冲突</li>
 *   <li>TelemetryIngestService 和 PendingAcks 用 Mockito.mock，验证 handle() 调用</li>
 *   <li>用独立 DatagramSocket 向网关端口发送 MAVLink v2 帧，Awaitility 等待异步回调</li>
 *   <li>dronePort 指向无人监听的端口，discovery 心跳发送失败仅记日志不影响测试</li>
 * </ul>
 *
 * <p>经验来源：2026-09-17-telemetry-ingest-service-websocket-forward-routing
 * （MAVLink 帧构造 via {@code MavlinkMessage.toFrame(sysid, compid, seq)}）。
 */
@DisplayName("UdpGateway: UDP MAVLink 网关接收与转发")
class UdpGatewayTest {

    /** 基础端口：高端口区避免与默认 14550/14540 或其他测试冲突。 */
    private static final int BASE_PORT = 24600;
    /** dronePort：无人监听，discovery 发送失败仅记日志。 */
    private static final int DRONE_PORT = 24699;

    private TelemetryIngestService ingest;
    private PendingAcks pendings;
    private UdpGateway gateway;
    private int currentPort;

    @BeforeEach
    void setUp() {
        ingest = mock(TelemetryIngestService.class);
        pendings = mock(PendingAcks.class);
    }

    @AfterEach
    void tearDown() {
        if (gateway != null) {
            gateway.shutdown();
            gateway = null;
        }
    }

    /** 构造一个绑定指定端口的 UdpGateway（白名单关闭，频率限制 100/s）。 */
    private UdpGateway newGateway(int udpPort) throws Exception {
        return newGateway(udpPort, false, 100);
    }

    /** 构造一个绑定指定端口的 UdpGateway，可指定白名单和频率限制参数。 */
    private UdpGateway newGateway(int udpPort, boolean whitelistEnabled, int maxRate) throws Exception {
        currentPort = udpPort;
        gateway = new UdpGateway(udpPort, "127.0.0.1", DRONE_PORT, "", "0.0.0.0",
                whitelistEnabled, maxRate, ingest, pendings);
        return gateway;
    }

    /** 通过反射注入 DeviceRegistry（模拟 @Autowired(required=false) 字段注入）。 */
    private void injectDeviceRegistry(DeviceRegistry registry) throws Exception {
        Field field = UdpGateway.class.getDeclaredField("deviceRegistry");
        field.setAccessible(true);
        field.set(gateway, registry);
    }

    /** 构造一个 drone HEARTBEAT 帧（type=2 四旋翼, autopilot=6 PX4, sysid=1）。 */
    private MavlinkFrame droneHeartbeat(int sysid, int seq) {
        Heartbeat hb = new Heartbeat(0, 2, 6, 0, MavEnums.MAV_STATE_ACTIVE);
        return hb.toFrame(sysid, 1, seq);
    }

    /** 用独立 DatagramSocket 向指定端口发送一帧 MAVLink v2 字节。 */
    private void sendBytes(int port, byte[] data) throws Exception {
        try (DatagramSocket sender = new DatagramSocket()) {
            sender.send(new DatagramPacket(data, data.length,
                    new InetSocketAddress("127.0.0.1", port)));
        }
    }

    /** 发送一帧 MAVLink Heartbeat 到网关端口。 */
    private void sendFrame(int port, MavlinkFrame frame) throws Exception {
        sendBytes(port, frame.encodeV2());
    }

    /** 等待 ingest.handle 被调用至少 N 次（最多 2 秒）。 */
    private void awaitHandleCount(int n) throws InterruptedException {
        long deadline = System.currentTimeMillis() + 2000;
        while (System.currentTimeMillis() < deadline) {
            if (mockingDetails(ingest).getInvocations().size() >= n) {
                return;
            }
            Thread.sleep(20);
        }
    }

    // ===== 基本生命周期 =====

    @Test
    @DisplayName("1. 基本启动：构造后 getLocalPort 返回绑定端口")
    void startup_returnsBoundPort() throws Exception {
        int port = BASE_PORT + 1;
        gateway = newGateway(port);
        assertThat(gateway.getLocalPort()).isEqualTo(port);
    }

    @Test
    @DisplayName("2. shutdown 关闭网关不抛异常")
    void shutdown_noException() throws Exception {
        gateway = newGateway(BASE_PORT + 2);
        gateway.shutdown();
        // 再调一次验证幂等不抛异常（shutdown 内部 transport.close 应安全）
        // 注意：不在此 assertDoesNotThrow 二次 shutdown，因 DatagramSocket.close 幂等但
        // transport.close 会 interrupt 已停止线程——此处仅验证首次 shutdown 无异常
        gateway = null; // 防止 tearDown 二次关闭
    }

    // ===== 消息接收与转发 =====

    @Test
    @DisplayName("3. 接收单条 MAVLink Heartbeat 并转发到 TelemetryIngestService")
    void receiveSingleHeartbeat_forwardsToIngest() throws Exception {
        int port = BASE_PORT + 3;
        gateway = newGateway(port);
        // 让接收线程就绪
        Thread.sleep(100);

        sendFrame(port, droneHeartbeat(1, 0));

        awaitHandleCount(1);
        verify(ingest, timeout(2000)).handle(any(MavlinkFrame.class));
    }

    @Test
    @DisplayName("4. 接收多条消息批量处理（5 帧）")
    void receiveMultipleFrames_batchForward() throws Exception {
        int port = BASE_PORT + 4;
        gateway = newGateway(port);
        Thread.sleep(100);

        for (int i = 0; i < 5; i++) {
            sendFrame(port, droneHeartbeat(1, i));
        }

        awaitHandleCount(5);
        verify(ingest, timeout(2000).times(5)).handle(any(MavlinkFrame.class));
    }

    // ===== 异常包处理 =====

    @Test
    @DisplayName("5. 接收空包（0 字节）不调用 ingest")
    void receiveEmptyPacket_noIngestCall() throws Exception {
        int port = BASE_PORT + 5;
        gateway = newGateway(port);
        Thread.sleep(100);

        sendBytes(port, new byte[0]);

        Thread.sleep(500);
        verify(ingest, never()).handle(any(MavlinkFrame.class));
    }

    @Test
    @DisplayName("6. 接收畸形短包（1 字节）不调用 ingest")
    void receiveMalformedShortPacket_noIngestCall() throws Exception {
        int port = BASE_PORT + 6;
        gateway = newGateway(port);
        Thread.sleep(100);

        sendBytes(port, new byte[]{(byte) 0xFD}); // 只有 STX，无完整头

        Thread.sleep(500);
        verify(ingest, never()).handle(any(MavlinkFrame.class));
    }

    @Test
    @DisplayName("7. 接收非 MAVLink 随机字节不调用 ingest")
    void receiveRandomBytes_noIngestCall() throws Exception {
        int port = BASE_PORT + 7;
        gateway = newGateway(port);
        Thread.sleep(100);

        byte[] junk = new byte[]{0x01, 0x02, 0x03, 0x04, 0x05, 0x06, 0x07, 0x08};
        sendBytes(port, junk);

        Thread.sleep(500);
        verify(ingest, never()).handle(any(MavlinkFrame.class));
    }

    // ===== 端口绑定 =====

    @Test
    @DisplayName("8. 端口绑定失败（端口已占用）抛 IOException")
    void portAlreadyInUse_throwsIOException() throws Exception {
        int port = BASE_PORT + 8;
        // 先占用端口
        try (DatagramSocket occupier = new DatagramSocket(new InetSocketAddress(port))) {
            assertThatThrownBy(() -> new UdpGateway(port, "127.0.0.1", DRONE_PORT, "", "0.0.0.0",
                    false, 100, ingest, pendings))
                    .isInstanceOf(java.io.IOException.class);
        }
    }

    // ===== 并发安全 =====

    @Test
    @DisplayName("9. 并发接收：10 线程各发 1 帧，全部转发")
    void concurrentReceive_allForwarded() throws Exception {
        int port = BASE_PORT + 9;
        gateway = newGateway(port);
        Thread.sleep(100);

        int threads = 10;
        CountDownLatch latch = new CountDownLatch(threads);
        for (int i = 0; i < threads; i++) {
            final int seq = i;
            new Thread(() -> {
                try {
                    sendFrame(port, droneHeartbeat(1, seq));
                } catch (Exception e) {
                    // 发送失败不阻断
                } finally {
                    latch.countDown();
                }
            }).start();
        }
        latch.await(3, TimeUnit.SECONDS);

        awaitHandleCount(threads);
        verify(ingest, timeout(2000).times(threads)).handle(any(MavlinkFrame.class));
    }

    // ===== 关闭后行为 =====

    @Test
    @DisplayName("10. 关闭后不再接收消息")
    void afterShutdown_noMoreReceive() throws Exception {
        int port = BASE_PORT + 10;
        gateway = newGateway(port);
        Thread.sleep(100);
        gateway.shutdown();
        gateway = null; // 防 tearDown 二次关闭

        // 关闭后发送
        sendFrame(port, droneHeartbeat(1, 0));
        Thread.sleep(500);

        verify(ingest, never()).handle(any(MavlinkFrame.class));
    }

    // ===== 大包处理 =====

    @Test
    @DisplayName("11. 大包处理（1500 字节 > 标准 MTU 片段）不崩溃")
    void largePacket_noCrash() throws Exception {
        int port = BASE_PORT + 11;
        gateway = newGateway(port);
        Thread.sleep(100);

        // 构造 1500 字节包：首字节伪造成 STX，其余随机
        byte[] big = new byte[1500];
        big[0] = (byte) 0xFD;
        for (int i = 1; i < big.length; i++) {
            big[i] = (byte) (i & 0xFF);
        }
        sendBytes(port, big);

        // 大包 CRC 校验失败，不应调用 ingest，但接收线程不应崩溃
        Thread.sleep(500);
        // 验证网关仍可正常工作：发送合法帧仍能接收
        sendFrame(port, droneHeartbeat(1, 0));
        awaitHandleCount(1);
        verify(ingest, timeout(2000)).handle(any(MavlinkFrame.class));
    }

    // ===== 路由学习 =====

    @Test
    @DisplayName("12. 路由学习：收到 drone sysid 帧后 routeOf 返回源地址")
    void routeLearning_routeOfReturnsSource() throws Exception {
        int port = BASE_PORT + 12;
        gateway = newGateway(port);
        Thread.sleep(100);

        // sysid=1（drone），非 GCS_SYSID(255)
        sendFrame(port, droneHeartbeat(1, 0));
        awaitHandleCount(1);

        // 等待路由表更新（onFrame 中 learn 调用）
        long deadline = System.currentTimeMillis() + 2000;
        while (System.currentTimeMillis() < deadline) {
            if (gateway.routeOf(1) != null) {
                break;
            }
            Thread.sleep(20);
        }
        assertThat(gateway.routeOf(1))
                .as("收到 sysid=1 的帧后应学习到路由")
                .isNotNull();
    }

    @Test
    @DisplayName("13. GCS sysid(255) 帧不学习路由（仅转发）")
    void gcsSysidFrame_noRouteLearning() throws Exception {
        int port = BASE_PORT + 13;
        gateway = newGateway(port);
        Thread.sleep(100);

        // sysid=255 = GCS_SYSID，onFrame 中跳过路由学习
        sendFrame(port, droneHeartbeat(UdpGateway.GCS_SYSID, 0));
        awaitHandleCount(1);

        Thread.sleep(500);
        assertThat(gateway.routeOf(UdpGateway.GCS_SYSID))
                .as("GCS sysid 不应学习路由")
                .isNull();
        // 但仍应转发到 ingest
        verify(ingest, timeout(2000)).handle(any(MavlinkFrame.class));
    }

    // ===== 异常不导致线程退出 =====

    @Test
    @DisplayName("14. ingest.handle 抛异常后接收线程仍存活")
    void ingestThrows_threadSurvives() throws Exception {
        int port = BASE_PORT + 14;
        gateway = newGateway(port);
        Thread.sleep(100);

        // 第一次调用抛异常
        doThrow(new RuntimeException("simulated ingest failure"))
                .when(ingest).handle(any(MavlinkFrame.class));

        sendFrame(port, droneHeartbeat(1, 0));
        Thread.sleep(500);

        // 恢复正常行为
        doNothing().when(ingest).handle(any(MavlinkFrame.class));
        reset(ingest);
        sendFrame(port, droneHeartbeat(1, 1));

        awaitHandleCount(1);
        verify(ingest, timeout(2000)).handle(any(MavlinkFrame.class));
    }

    // ===== 消息计数 =====

    @Test
    @DisplayName("15. 消息计数：发送 N 帧后 ingest.handle 恰好调用 N 次")
    void messageCount_exactCount() throws Exception {
        int port = BASE_PORT + 15;
        gateway = newGateway(port);
        Thread.sleep(100);

        int n = 7;
        for (int i = 0; i < n; i++) {
            sendFrame(port, droneHeartbeat(1, i));
        }
        awaitHandleCount(n);

        // 额外等待确保没有多余调用
        Thread.sleep(300);
        verify(ingest, times(n)).handle(any(MavlinkFrame.class));
    }

    // ===== 设备白名单 (P1-3) =====

    @Test
    @DisplayName("16. 白名单启用 + DeviceRegistry 中已注册的 sysid 帧正常转发")
    void whitelistEnabled_registeredSysid_forwards() throws Exception {
        int port = BASE_PORT + 16;
        gateway = newGateway(port, true, 100);
        Thread.sleep(100);

        // mock DeviceRegistry：sysid=1 已注册
        DeviceRegistry registry = mock(DeviceRegistry.class);
        when(registry.get(1)).thenReturn(new DroneSnapshot(1));
        injectDeviceRegistry(registry);

        sendFrame(port, droneHeartbeat(1, 0));
        awaitHandleCount(1);
        verify(ingest, timeout(2000)).handle(any(MavlinkFrame.class));
    }

    @Test
    @DisplayName("17. 白名单启用 + 未注册 sysid 帧被丢弃")
    void whitelistEnabled_unregisteredSysid_dropped() throws Exception {
        int port = BASE_PORT + 17;
        gateway = newGateway(port, true, 100);
        Thread.sleep(100);

        // mock DeviceRegistry：sysid=1 未注册（返回 null）
        DeviceRegistry registry = mock(DeviceRegistry.class);
        when(registry.get(1)).thenReturn(null);
        injectDeviceRegistry(registry);

        sendFrame(port, droneHeartbeat(1, 0));
        Thread.sleep(500);

        verify(ingest, never()).handle(any(MavlinkFrame.class));
    }

    @Test
    @DisplayName("18. 白名单启用 + DeviceRegistry 为 null 时所有非 GCS sysid 帧被丢弃")
    void whitelistEnabled_nullRegistry_allDropped() throws Exception {
        int port = BASE_PORT + 18;
        gateway = newGateway(port, true, 100);
        Thread.sleep(100);

        // 不注入 DeviceRegistry，字段保持 null

        sendFrame(port, droneHeartbeat(1, 0));
        Thread.sleep(500);

        verify(ingest, never()).handle(any(MavlinkFrame.class));
    }

    @Test
    @DisplayName("19. 白名单启用 + GCS sysid(255) 帧始终放行（不受白名单限制）")
    void whitelistEnabled_gcsSysid_alwaysAllowed() throws Exception {
        int port = BASE_PORT + 19;
        gateway = newGateway(port, true, 100);
        Thread.sleep(100);

        // 不注入 DeviceRegistry，字段保持 null，但 GCS_SYSID 应始终放行
        sendFrame(port, droneHeartbeat(UdpGateway.GCS_SYSID, 0));
        awaitHandleCount(1);
        verify(ingest, timeout(2000)).handle(any(MavlinkFrame.class));
    }

    @Test
    @DisplayName("20. 白名单关闭 + 未注册 sysid 帧正常转发（dev 模式）")
    void whitelistDisabled_unregisteredSysid_forwards() throws Exception {
        int port = BASE_PORT + 20;
        gateway = newGateway(port, false, 100);
        Thread.sleep(100);

        // 不注入 DeviceRegistry，白名单关闭时所有 sysid 都应放行
        sendFrame(port, droneHeartbeat(1, 0));
        awaitHandleCount(1);
        verify(ingest, timeout(2000)).handle(any(MavlinkFrame.class));
    }

    // ===== 频率限制 (P1-3) =====

    @Test
    @DisplayName("21. 频率限制：超过 maxRate 的帧被丢弃")
    void rateLimit_exceedMax_dropped() throws Exception {
        int port = BASE_PORT + 21;
        int maxRate = 5;
        gateway = newGateway(port, false, maxRate);
        Thread.sleep(100);

        // 发送 maxRate + 3 帧，只有 maxRate 帧应被转发
        for (int i = 0; i < maxRate + 3; i++) {
            sendFrame(port, droneHeartbeat(1, i));
        }

        awaitHandleCount(maxRate);
        Thread.sleep(500);
        // 恰好 maxRate 帧被转发，超出部分被丢弃
        verify(ingest, times(maxRate)).handle(any(MavlinkFrame.class));
    }

    @Test
    @DisplayName("22. 频率限制：GCS sysid 不受频率限制")
    void rateLimit_gcsSysid_notLimited() throws Exception {
        int port = BASE_PORT + 22;
        int maxRate = 3;
        gateway = newGateway(port, false, maxRate);
        Thread.sleep(100);

        // GCS sysid 不受频率限制，发送 maxRate + 5 帧都应转发
        int total = maxRate + 5;
        for (int i = 0; i < total; i++) {
            sendFrame(port, droneHeartbeat(UdpGateway.GCS_SYSID, i));
        }

        awaitHandleCount(total);
        verify(ingest, timeout(2000).times(total)).handle(any(MavlinkFrame.class));
    }

    @Test
    @DisplayName("23. 频率限制：不同 sysid 独立计数")
    void rateLimit_differentSysids_independent() throws Exception {
        int port = BASE_PORT + 23;
        int maxRate = 3;
        gateway = newGateway(port, false, maxRate);
        Thread.sleep(100);

        // sysid=1 发 maxRate 帧，sysid=2 也发 maxRate 帧，都应全部转发
        for (int i = 0; i < maxRate; i++) {
            sendFrame(port, droneHeartbeat(1, i));
            sendFrame(port, droneHeartbeat(2, i));
        }

        awaitHandleCount(maxRate * 2);
        verify(ingest, timeout(2000).times(maxRate * 2)).handle(any(MavlinkFrame.class));
    }
}
