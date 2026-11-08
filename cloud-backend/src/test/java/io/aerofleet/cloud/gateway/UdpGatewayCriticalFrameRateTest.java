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
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
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
 * 用小配额（5/3/2）让窗口不变量在少量帧上就能被观测到。
 *
 * <p><b>断言口径（2026-11-08 收口）</b>：滑动窗口限流是「任意 1 秒窗口内 ≤ maxRate」，
 * 不是「总通过数恰好等于 maxRate」。机器负载高时发送循环可能跨过 1 秒边界，总通过数
 * 会大于 maxRate —— 这是限流语义的正确表现。原用例用 {@code times(5)} 钉总数，
 * 全量运行时偶发 5 vs 9（协调板 2026-11-07 记录）⇒ 改为记录 ingest 时刻并校验
 * 窗口不变量：既不会假红，也能在预算被绕过时真红。
 */
@DisplayName("UdpGateway: 关键帧独立预算（P2-F5）")
class UdpGatewayCriticalFrameRateTest {

    private static final int DRONE_PORT = 24698;
    private static final long ONE_SECOND_NANOS = 1_000_000_000L;

    private TelemetryIngestService ingest;
    private PendingAcks pendings;
    private UdpGateway gateway;
    private int currentPort;

    private final List<Long> positionIngestNanos = new CopyOnWriteArrayList<>();
    private final List<Long> ackIngestNanos = new CopyOnWriteArrayList<>();

    @BeforeEach
    void setUp() {
        ingest = mock(TelemetryIngestService.class);
        pendings = mock(PendingAcks.class);
        positionIngestNanos.clear();
        ackIngestNanos.clear();
        doAnswer(invocation -> {
            MavlinkFrame frame = invocation.getArgument(0);
            long now = System.nanoTime();
            if (frame.getMessageId() == GlobalPositionInt.ID) {
                positionIngestNanos.add(now);
            } else if (frame.getMessageId() == CommandAck.ID) {
                ackIngestNanos.add(now);
            }
            return null;
        }).when(ingest).handle(any());
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

    /** 同一 socket 连续发送：避免每条帧都新建 socket 拖慢发送循环（并非断言正确性的依赖）。 */
    private void sendFrames(MavlinkFrame... frames) throws Exception {
        try (DatagramSocket sender = new DatagramSocket()) {
            InetSocketAddress target = new InetSocketAddress("127.0.0.1", currentPort);
            for (MavlinkFrame frame : frames) {
                byte[] payload = frame.encodeV2();
                sender.send(new DatagramPacket(payload, payload.length, target));
            }
        }
    }

    private void sendFrame(MavlinkFrame frame) throws Exception {
        sendFrames(frame);
    }

    private ArgumentMatcher<MavlinkFrame> msgId(int id) {
        return f -> f.getMessageId() == id;
    }

    @Test
    @DisplayName("洪泛下关键帧存活：主窗口被非关键帧打满后，ACK 仍全部抵达且主窗口按 1 秒窗口限流")
    void ackSurvivesFloodOnMainWindow() throws Exception {
        gateway = newGateway(5, 50);
        Thread.sleep(100);

        // 非关键帧洪泛：20 帧打满主窗口（同一窗口内 5 过，15 丢）
        MavlinkFrame[] flood = new MavlinkFrame[20];
        for (int i = 0; i < flood.length; i++) {
            flood[i] = positionFrame(1, i);
        }
        sendFrames(flood);
        // 关键帧走独立预算：3 个 ACK 不占主窗口，应全部抵达
        for (int i = 0; i < 3; i++) {
            sendFrame(ackFrame(1, 100 + i));
        }

        verify(ingest, timeout(3000).times(3)).handle(argThat(msgId(CommandAck.ID)));

        // 3 个 ACK 在洪泛之后发送（同一条 UDP 回环 + 单接收线程）⇒ ACK 全部抵达时，
        // 20 个位置帧已逐帧判定完毕（要么 ingest、要么被主窗口丢弃）。
        List<Long> accepted = new ArrayList<>(positionIngestNanos);
        assertTrue(accepted.size() >= 5,
                "主窗口应至少放行 5 帧（maxRate=5/s），实际 " + accepted.size());
        assertAtMostPerSecond(accepted, 5);
    }

    @Test
    @DisplayName("关键预算自身耗尽也会丢（防滥用语义保留）：任意 1 秒窗口内至多 2 个 ACK")
    void criticalBudgetExhaustionStillDrops() throws Exception {
        gateway = newGateway(100, 2);
        Thread.sleep(100);

        for (int i = 0; i < 4; i++) {
            sendFrame(ackFrame(1, i));
        }

        verify(ingest, timeout(3000).times(2)).handle(argThat(msgId(CommandAck.ID)));
        awaitIngestQuiescence();

        List<Long> accepted = new ArrayList<>(ackIngestNanos);
        assertTrue(accepted.size() >= 2,
                "关键预算应至少放行 2 帧（critical=2/s），实际 " + accepted.size());
        assertAtMostPerSecond(accepted, 2);
    }

    @Test
    @DisplayName("critical<=0（豁免关闭）：ACK 退回主窗口，与位置帧共用同一 3/s 预算")
    void criticalDisabled_fallsBackToMainWindow() throws Exception {
        gateway = newGateway(3, 0);
        Thread.sleep(100);

        // 主窗口被非关键帧占满（3 过）
        MavlinkFrame[] flood = new MavlinkFrame[10];
        for (int i = 0; i < flood.length; i++) {
            flood[i] = positionFrame(1, i);
        }
        sendFrames(flood);
        // 豁免关闭：ACK 走主窗口 → 与位置帧共用同一个 3/s 预算
        for (int i = 0; i < 2; i++) {
            sendFrame(ackFrame(1, 50 + i));
        }

        awaitIngestQuiescence();

        List<Long> positions = new ArrayList<>(positionIngestNanos);
        List<Long> combined = new ArrayList<>(positions);
        combined.addAll(ackIngestNanos);
        Collections.sort(combined);

        assertTrue(positions.size() >= 3,
                "主窗口应至少放行 3 帧（maxRate=3/s），实际 " + positions.size());
        // 若 ACK 仍走独立预算，合并序列会在同一 1 秒窗口内突破 3 —— 这正是本用例要拦住的旧行为。
        assertAtMostPerSecond(combined, 3);
    }

    @Test
    @DisplayName("心跳同享关键预算：洪泛下不丢")
    void heartbeatSharesCriticalBudget() throws Exception {
        gateway = newGateway(5, 50);
        Thread.sleep(100);

        MavlinkFrame[] flood = new MavlinkFrame[20];
        for (int i = 0; i < flood.length; i++) {
            flood[i] = positionFrame(1, i);
        }
        sendFrames(flood);
        for (int i = 0; i < 3; i++) {
            sendFrame(heartbeatFrame(1, 200 + i));
        }

        verify(ingest, timeout(3000).times(3)).handle(argThat(msgId(Heartbeat.ID)));
    }

    /** 等接收线程把已发送的帧判定完：连续 200ms 无新 ingest 即视为静默（上限 3s）。 */
    private void awaitIngestQuiescence() throws InterruptedException {
        long deadline = System.nanoTime() + 3 * ONE_SECOND_NANOS;
        int lastCount = -1;
        long stableSince = System.nanoTime();
        while (System.nanoTime() < deadline) {
            int count = positionIngestNanos.size() + ackIngestNanos.size();
            if (count != lastCount) {
                lastCount = count;
                stableSince = System.nanoTime();
            } else if (System.nanoTime() - stableSince > 200_000_000L) {
                return;
            }
            Thread.sleep(10);
        }
    }

    /**
     * 滑动窗口不变量：任意 1 秒窗口内通过的帧数 ≤ limit。
     *
     * <p>实现按「超过 1000ms 才移出窗口」判定：若要放行第 limit+1 帧，窗口内必须只剩
     * limit-1 帧 ⇒ 任意闭区间 1 秒内不可能出现 limit+1 帧。出现即说明预算被绕过。
     */
    private static void assertAtMostPerSecond(List<Long> acceptedNanos, int limit) {
        for (long windowStart : acceptedNanos) {
            long inWindow = acceptedNanos.stream()
                    .filter(t -> t >= windowStart && t - windowStart <= ONE_SECOND_NANOS)
                    .count();
            assertTrue(inWindow <= limit,
                    "预算 " + limit + "/s 被突破：某 1 秒窗口内放行 " + inWindow + " 帧");
        }
    }
}
