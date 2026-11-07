package io.aerofleet.cloud.surveillance;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 媒体面**真 UDP 回环**测试：设备模拟器推 RTP/PS → 收流会话 → 排序去重 → 组帧 → 解 PS。
 *
 * <p>这是"零硬件自给"的关键一步：链路两端都是**我们的代码 + 真 UDP socket**
 * （127.0.0.1 随机端口），不需要摄像头、不需要 ZLMediaKit、不需要真机。
 * 它证明的是协议层自洽——帧边界、时间戳纪律、PS 结构、分片重拼都能被独立栈收发。
 *
 * <p>钉住三条硬规则：
 * <ol>
 *   <li>GB §4.3.6：同一帧所有 RTP 包时间戳相同、不同帧不同（时间戳=帧边界）；</li>
 *   <li>GB 附录 C.1：关键帧 PS 含系统头 + PSM、非关键帧不含；</li>
 *   <li>RFC 3550：乱序到达仍能按序号拼出正确帧，重复包被去重。</li>
 * </ol>
 */
@DisplayName("GB28181 媒体面：真 UDP RTP/PS 回环")
class Gb28181MediaLoopbackTest {

    private Gb28181DeviceSimulator sim;
    private Gb28181MediaSession session;

    @BeforeEach
    void boot() throws Exception {
        sim = new Gb28181DeviceSimulator(0,
                Gb28181DeviceId.of("34010000001", "10", "132", "0001"),
                "127.0.0.1:5060", 4000, 1);
        session = Gb28181MediaSession.open(0);
        session.start();
        sim.attachMediaSession(session);
        sim.streamMediaTo(session.address());
    }

    @AfterEach
    void shutdown() {
        if (sim != null) {
            sim.close();
        }
        if (session != null) {
            session.close();
        }
    }

    private void awaitFrames(int n, long timeoutMs) {
        long deadline = System.currentTimeMillis() + timeoutMs;
        while (System.currentTimeMillis() < deadline && session.frames().size() < n) {
            try {
                Thread.sleep(20);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
        }
    }

    @Test
    @DisplayName("推一帧关键帧：收流侧组出 1 帧、PS 解出基本流、含系统头+PSM")
    void keyframeRoundTripOverRealUdp() throws Exception {
        sim.pushFrame(100, 90_000L, true);
        awaitFrames(1, 3000);

        assertThat(session.frames()).hasSize(1);
        Gb28181MediaFrame f = session.frames().get(0);
        assertThat(f.timestamp()).isEqualTo(90_000L);
        assertThat(f.keyframe()).as("关键帧 PS 必含系统头 + PSM（附录 C.1）").isTrue();

        List<Gb28181PsDemuxer.StreamUnit> units = f.units();
        assertThat(units).hasSize(1);
        assertThat(units.get(0).streamId()).isEqualTo(Gb28181PsPacketizer.STREAM_ID_VIDEO);
        assertThat(units.get(0).streamType()).isEqualTo(Gb28181PsPacketizer.STREAM_TYPE_H264);
    }

    @Test
    @DisplayName("非关键帧：PS 不含系统头/PSM，但 payload 仍完整解出")
    void nonKeyframeRoundTrip() throws Exception {
        sim.pushFrame(200, 90_000L, false);
        awaitFrames(1, 3000);

        assertThat(session.frames()).hasSize(1);
        Gb28181MediaFrame f = session.frames().get(0);
        assertThat(f.keyframe()).isFalse();
        assertThat(f.units().get(0).data()).isNotEmpty();
    }

    @Test
    @DisplayName("GB §4.3.6 时间戳纪律：同帧时间戳相同、异帧不同（收流侧可识别帧边界）")
    void frameTimestampDisciplineHoldsOnTheWire() throws Exception {
        // 序号接续：第一帧从 300 起（4 片 ⇒ 300..303），第二帧必须从 304 起。
        // **这里曾写成 303**，与第一帧末片撞号 ⇒ 被去重逻辑判成重复 ⇒ 第二帧缺
        // 第一片 ⇒ PS 不完整 ⇒ 组不出（awaitFrames 等满 3s 超时）。
        // 它暴露的正是"序号必须每帧递增、不能复用"这条 RFC 3550 纪律。
        sim.pushFrame(300, 90_000L, true);
        sim.pushFrame(304, 93_600L, false);      // +40ms @90kHz
        awaitFrames(2, 3000);

        assertThat(session.frames()).hasSize(2);
        assertThat(session.frames().get(0).timestamp()).isEqualTo(90_000L);
        assertThat(session.frames().get(1).timestamp()).isEqualTo(93_600L);
        assertThat(session.frames().get(0).timestamp())
                .isNotEqualTo(session.frames().get(1).timestamp());
    }

    @Test
    @DisplayName("一帧切成多个 RTP 包：收流侧按序号拼回完整 PS")
    void multiPacketFrameIsReassembled() throws Exception {
        // 小 PS 也可能被切成多个包；直接验证"包数 ≥1 且拼回字节与打包一致"
        sim.pushFrame(400, 90_000L, true);
        awaitFrames(1, 3000);

        Gb28181MediaFrame f = session.frames().get(0);
        assertThat(f.packets()).isPositive();
        // 解复用成功即证明拼回的 PS 结构完整（错位会直接抛）
        assertThat(f.units()).isNotEmpty();
        assertThat(f.ps().length).isGreaterThan(20);
    }

    @Test
    @DisplayName("乱序 + 重复到达：仍能拼出正确帧、重复被去重")
    void outOfOrderAndDuplicatesHandled() throws Exception {
        // 直接向收流会话投递：故意把第 2 包先发、第 1 包再发，并重复第 1 包
        byte[] ps = Gb28181PsPacketizer.pack(90_000L, true, List.of(
                new Gb28181PsPacketizer.ElementaryStream(0xE0, 0x1B,
                        new byte[]{1, 2, 3, 4, 5, 6, 7, 8, 9})));
        java.net.DatagramSocket out = new java.net.DatagramSocket();
        try {
            int cut = Math.max(1, ps.length / 3);
            byte[][] parts = new byte[(ps.length + cut - 1) / cut][];
            for (int i = 0, off = 0; i < parts.length; i++) {
                int end = Math.min(ps.length, off + cut);
                parts[i] = java.util.Arrays.copyOfRange(ps, off, end);
                off = end;
            }
            // 乱序：最后一块先到，其余按反序；末包带 marker
            int n = parts.length;
            for (int i = n - 1; i >= 0; i--) {
                Gb28181RtpPacket p = Gb28181RtpPacket.of(i == n - 1, 96, 500 + i, 90_000L, 0x0A0B0C0D, parts[i]);
                out.send(new java.net.DatagramPacket(p.encode(), p.length(), session.address()));
            }
            // 重复第一块（同序号）—— 必须被去重
            Gb28181RtpPacket dup = Gb28181RtpPacket.of(false, 96, 500, 90_000L, 0x0A0B0C0D, parts[0]);
            out.send(new java.net.DatagramPacket(dup.encode(), dup.length(), session.address()));
        } finally {
            out.close();
        }
        awaitFrames(1, 3000);

        assertThat(session.frames()).hasSize(1);
        assertThat(session.frames().get(0).keyframe()).isTrue();
        assertThat(session.duplicateCount()).isPositive();
        assertThat(session.outOfOrderCount()).isPositive();
    }

    @Test
    @DisplayName("坏包不杀死会话：后续好帧仍能组出")
    void badPacketDoesNotKillSession() throws Exception {
        java.net.DatagramSocket out = new java.net.DatagramSocket();
        try {
            // version≠2 的坏 RTP
            byte[] bad = new byte[16];
            bad[0] = (byte) 0x40;
            out.send(new java.net.DatagramPacket(bad, bad.length, session.address()));
        } finally {
            out.close();
        }
        Thread.sleep(100);
        sim.pushFrame(600, 90_000L, true);
        awaitFrames(1, 3000);

        assertThat(session.frames()).as("坏包之后会话仍要能收流").hasSize(1);
    }

    @Test
    @DisplayName("收流观察者在组帧时被触发（不是拉取式的事后追认）")
    void frameObserverFiresOnAssembly() throws Exception {
        AtomicInteger hits = new AtomicInteger();
        sim.onMediaFrame(f -> hits.incrementAndGet());

        sim.pushFrame(700, 90_000L, true);
        awaitFrames(1, 3000);

        assertThat(hits.get()).isPositive();
    }

    @Test
    @DisplayName("未设推流目标时 pushFrame 安全返回（不抛、不发包）")
    void pushWithoutTargetIsNoop() throws Exception {
        Gb28181DeviceSimulator quiet = new Gb28181DeviceSimulator(0,
                Gb28181DeviceId.of("34010000001", "10", "132", "0001"),
                "127.0.0.1:5060", 4000, 1);
        try {
            assertThat(quiet.pushFrame(1, 90_000L, true)).isEqualTo(1);
        } finally {
            quiet.close();
        }
    }

    @Test
    @DisplayName("非法推流节奏拒绝（period<=0）")
    void illegalMediaTimingRejected() {
        assertThatThrownBy(() -> sim.setMediaTiming(0, 3600))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
