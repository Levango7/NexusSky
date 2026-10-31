package io.aerofleet.cloud.gateway;

import io.aerofleet.cloud.telemetry.PendingAcks;
import io.aerofleet.mavlink.MavlinkFrame;
import io.aerofleet.mavlink.messages.CommandAck;
import io.aerofleet.mavlink.messages.GlobalPositionInt;
import io.aerofleet.mavlink.messages.Heartbeat;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentMatcher;

import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetSocketAddress;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

/**
 * 关键帧独立预算回归（P2-F5，PX4 SITL 首验实测的失败模式）。
 *
 * <p>修复前：单一滑动窗口下，高码率流（SITL 4MB/s ≈ 8.6k fps）在每秒开头把 100/s
 * 预算吃光，**MISSION_ACK/COMMAND_ACK/心跳成批被丢**（单轮 340k+ 条丢帧日志，
 * 任务上传必现超时、armed 显示滞后）。修复后：关键帧走独立小预算（默认 50/s），
 * 洪泛时仍能抵达；非关键帧的主窗口语义不变；预算可关（退回旧行为）。
 *
 * <p>测试策略与 {@link UdpGatewayTest} 相同：真实 UDP 端口 + mock 下游；
 * 用小配额（5/3/2）保证窗口判定确定性（突发远小于 1 秒滑动窗）。
 */
@DisplayName("UdpGateway: 关键帧独立预算（P2-F5）")
class UdpGatewayCriticalFrameRateTest {

    private static final int DRONE_PORT = 24698;

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

    /** 构造网关：主窗口 maxRate、关键帧预算 criticalRate。 */
    private UdpGateway newGateway(int maxRate, int criticalRate) throws Exception {
        gateway = new UdpGateway(0, "127.0.0.1", DRONE_PORT, "", "0.0.0.0",
                false, maxRate, ingest, pendings);
        gateway.setMaxCriticalFrameRatePerSysid(criticalRate);
        currentPort = gateway.getLocalPort();
        return gateway;
    }

    private MavlinkFrame positionFrame(int sysid, int seq) {
        GlobalPositionInt p = new GlobalPositionInt(0, 225900000 + seq, 1139345000,
                584000, 2000, 0, 0, 0, 0);
        return p.toFrame(sysid, 1, seq);
    }

    private MavlinkFrame ackFrame(int sysid, int seq) {
        CommandAck ack = new CommandAck(400, 0, 0, 0, 255, 190);
        return ack.toFrame(sysid, 1, seq);
    }

    private MavlinkFrame heartbeatFrame(int sysid, int seq) {
        Heartbeat hb = new Heartbeat(0, 2, 6, 0, 4);
        return hb.toFrame(sysid, 1, seq);
    }

    private void sendFrame(MavlinkFrame frame) throws Exception {
        try (DatagramSocket sender = new DatagramSocket()) {
            sender.send(new DatagramPacket(frame.encodeV2(), frame.encodeV2().length,
                    new InetSocketAddress("127.0.0.1", currentPort)));
        }
    }

    private ArgumentMatcher<MavlinkFrame> msgId(int id) {
        return f -> f.getMessageId() == id;
    }

    @Test
    @DisplayName("洪泛下关键帧存活：主窗口被非关键帧打满后，ACK 仍全部抵达且主窗口语义不变")
    void ackSurvivesFloodOnMainWindow() throws Exception {
        gateway = newGateway(5, 50);
        Thread.sleep(100);

        // 非关键帧洪泛：20 帧打满主窗口（5 过，15 丢）
        for (int i = 0; i < 20; i++) {
            sendFrame(positionFrame(1, i));
        }
        // 关键帧走独立预算：3 个 ACK 不占主窗口，应全部抵达
        for (int i = 0; i < 3; i++) {
            sendFrame(ackFrame(1, 100 + i));
        }

        verify(ingest, timeout(3000).times(3)).handle(argThat(msgId(CommandAck.ID)));
        verify(ingest, timeout(3000).times(5)).handle(argThat(msgId(GlobalPositionInt.ID)));
    }

    @Test
    @DisplayName("关键预算自身耗尽也会丢（防滥用语义保留）：critical=2 时第 3 个 ACK 被丢")
    void criticalBudgetExhaustionStillDrops() throws Exception {
        gateway = newGateway(100, 2);
        Thread.sleep(100);

        for (int i = 0; i < 4; i++) {
            sendFrame(ackFrame(1, i));
        }

        verify(ingest, timeout(3000).times(2)).handle(argThat(msgId(CommandAck.ID)));
        Thread.sleep(300);
        verify(ingest, times(2)).handle(argThat(msgId(CommandAck.ID)));
    }

    @Test
    @DisplayName("critical<=0（豁免关闭）：ACK 退回主窗口，洪泛下照旧被丢（旧行为可回退）")
    void criticalDisabled_fallsBackToMainWindow() throws Exception {
        gateway = newGateway(3, 0);
        Thread.sleep(100);

        // 主窗口被非关键帧占满（3 过）
        for (int i = 0; i < 10; i++) {
            sendFrame(positionFrame(1, i));
        }
        // 豁免关闭：ACK 走主窗口 → 被丢
        for (int i = 0; i < 2; i++) {
            sendFrame(ackFrame(1, 50 + i));
        }

        verify(ingest, timeout(3000).times(3)).handle(argThat(msgId(GlobalPositionInt.ID)));
        Thread.sleep(300);
        verify(ingest, never()).handle(argThat(msgId(CommandAck.ID)));
    }

    @Test
    @DisplayName("心跳同享关键预算：洪泛下不丢")
    void heartbeatSharesCriticalBudget() throws Exception {
        gateway = newGateway(5, 50);
        Thread.sleep(100);

        for (int i = 0; i < 20; i++) {
            sendFrame(positionFrame(1, i));
        }
        for (int i = 0; i < 3; i++) {
            sendFrame(heartbeatFrame(1, 200 + i));
        }

        verify(ingest, timeout(3000).times(3)).handle(argThat(msgId(Heartbeat.ID)));
    }
}
