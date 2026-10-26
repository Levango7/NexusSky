package io.aerofleet.cloud.ntrip;

import io.aerofleet.cloud.gateway.DeviceRegistry;
import io.aerofleet.cloud.gateway.DroneSnapshot;
import io.aerofleet.cloud.gateway.UdpGateway;
import io.aerofleet.mavlink.MavlinkFrame;
import io.aerofleet.mavlink.messages.GpsRtcmData;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * NTRIP 差分转发服务（GAPS §十.10-3）的契约测试。
 *
 * <p>三层都测：分片是**纯函数**直接点名；下发目标选择用 Mockito 钉「只发在线机」；
 * 端到端沿用 #166 假 caster 的路数——真 TCP、真 NTRIP 字节序列、真 framer，
 * 不用 mock 骗自己（坑全在字节层）。
 */
class NtripRtcmForwarderTest {

    // ---------- 1. 分片纯函数 ----------

    @Test
    @DisplayName("≤180：单片不分片（bit0=0），seq 落在 bit3-7，数据逐字节保留")
    void smallFrameIsSingleMessage() {
        byte[] rtcm = bytesOf(120);
        List<GpsRtcmData> out = NtripRtcmForwarder.fragment(rtcm, 7);
        assertThat(out).hasSize(1);
        GpsRtcmData m = out.get(0);
        assertThat(m.fragmented()).isFalse();
        assertThat(m.sequenceId()).isEqualTo(7);
        assertThat(m.fragmentId()).isZero();
        assertThat(m.len).isEqualTo(120);
        assertThat(m.payload()).isEqualTo(rtcm);
    }

    @Test
    @DisplayName("181~360：两片，bit0=1、分片号 0/1、同 seq，拼接逐字节还原")
    void mediumFrameSplitsInTwo() {
        byte[] rtcm = bytesOf(200);
        List<GpsRtcmData> out = NtripRtcmForwarder.fragment(rtcm, 3);
        assertThat(out).hasSize(2);
        ByteArrayOutputStream joined = new ByteArrayOutputStream();
        for (int i = 0; i < 2; i++) {
            GpsRtcmData m = out.get(i);
            assertThat(m.fragmented()).as("frag %d", i).isTrue();
            assertThat(m.sequenceId()).isEqualTo(3);
            assertThat(m.fragmentId()).isEqualTo(i);
            joined.write(m.payload(), 0, m.payload().length);
        }
        assertThat(joined.toByteArray()).isEqualTo(rtcm);
        assertThat(out.get(0).len).isEqualTo(180);
        assertThat(out.get(1).len).isEqualTo(20);
    }

    @Test
    @DisplayName("721 字节：发不了（分片号只有 2 bit）⇒ 空列表，由调用方计数丢弃")
    void oversizedFrameIsRefused() {
        assertThat(NtripRtcmForwarder.fragment(bytesOf(721), 1)).isEmpty();
        assertThat(NtripRtcmForwarder.fragment(bytesOf(720), 1)).hasSize(4);
    }

    @Test
    @DisplayName("seq 只取 5 bit：33 与 1 同模（官方序号域 0~31）")
    void sequenceWrapsAtFiveBits() {
        List<GpsRtcmData> out = NtripRtcmForwarder.fragment(bytesOf(10), 33);
        assertThat(out.get(0).sequenceId()).isEqualTo(1);
    }

    // ---------- 2. 下发目标选择（只发在线机） ----------

    private NtripRtcmForwarder forwarderWith(UdpGateway gateway, DeviceRegistry registry) {
        return new NtripRtcmForwarder(gateway, registry,
                "127.0.0.1", 2101, "MOUNT", "", "", 1000);
    }

    private DeviceRegistry registryWith(int... sysids) {
        DeviceRegistry registry = mock(DeviceRegistry.class);
        List<DroneSnapshot> drones = new ArrayList<>();
        for (int sysid : sysids) {
            DroneSnapshot s = new DroneSnapshot(sysid);
            s.online = sysid != 63;   // 63 号固定离线，作对照
            drones.add(s);
        }
        when(registry.all()).thenReturn(drones);
        return registry;
    }

    @Test
    @DisplayName("只发给在线机：61/62 在线、63 离线 ⇒ 63 零下发；单片 30B⇒每机 1 条")
    void forwardsOnlyToOnlineDrones() throws Exception {
        UdpGateway gateway = mock(UdpGateway.class);
        NtripRtcmForwarder f = forwarderWith(gateway, registryWith(61, 62, 63));
        f.forward(bytesOf(30));

        ArgumentCaptor<MavlinkFrame> frames = ArgumentCaptor.forClass(MavlinkFrame.class);
        verify(gateway, org.mockito.Mockito.times(1)).send(org.mockito.ArgumentMatchers.eq(61), frames.capture());
        verify(gateway, org.mockito.Mockito.times(1)).send(org.mockito.ArgumentMatchers.eq(62), frames.capture());
        org.mockito.Mockito.verify(gateway, org.mockito.Mockito.never())
                .send(org.mockito.ArgumentMatchers.eq(63), org.mockito.ArgumentMatchers.any());
        assertThat(f.messagesForwarded()).isEqualTo(1);
        assertThat(GpsRtcmData.decode(frames.getValue()).payload()).isEqualTo(bytesOf(30));
    }

    @Test
    @DisplayName("200 字节帧 ⇒ 每台在线机 2 条分片消息")
    void fragmentedFrameSendsBothFragments() throws Exception {
        UdpGateway gateway = mock(UdpGateway.class);
        NtripRtcmForwarder f = forwarderWith(gateway, registryWith(61));
        f.forward(bytesOf(200));
        ArgumentCaptor<MavlinkFrame> frames = ArgumentCaptor.forClass(MavlinkFrame.class);
        verify(gateway, org.mockito.Mockito.times(2)).send(org.mockito.ArgumentMatchers.eq(61), frames.capture());
        List<GpsRtcmData> decoded = new ArrayList<>();
        for (MavlinkFrame frame : frames.getAllValues()) {
            decoded.add(GpsRtcmData.decode(frame));
        }
        assertThat(decoded.get(0).fragmentId()).isZero();
        assertThat(decoded.get(1).fragmentId()).isEqualTo(1);
        assertThat(decoded.get(0).sequenceId()).isEqualTo(decoded.get(1).sequenceId());
        ByteArrayOutputStream joined = new ByteArrayOutputStream();
        for (GpsRtcmData m : decoded) {
            joined.write(m.payload(), 0, m.payload().length);
        }
        assertThat(joined.toByteArray()).isEqualTo(bytesOf(200));
    }

    @Test
    @DisplayName("没有在线机：跳过并计数，不做无谓分片")
    void skipsWhenNoOnlineTargets() {
        UdpGateway gateway = mock(UdpGateway.class);
        NtripRtcmForwarder f = forwarderWith(gateway, registryWith(63));
        f.forward(bytesOf(30));
        org.mockito.Mockito.verifyNoInteractions(gateway);
        assertThat(f.forwardsSkippedNoTarget()).isEqualTo(1);
    }

    @Test
    @DisplayName("超 720 字节：丢弃计数，零下发（绝不静默截断）")
    void dropsOversizedFrame() {
        UdpGateway gateway = mock(UdpGateway.class);
        NtripRtcmForwarder f = forwarderWith(gateway, registryWith(61));
        f.forward(bytesOf(800));
        org.mockito.Mockito.verifyNoInteractions(gateway);
        assertThat(f.framesDroppedTooLarge()).isEqualTo(1);
    }

    // ---------- 3. 端到端：真 caster + 真 framer + 真转发循环 ----------

    @Test
    @DisplayName("端到端：假 caster 发 30B+200B 两条帧，转发成 1+2 条消息到在线机")
    void endToEndForwarding() throws Exception {
        byte[] small = Rtcm3Framer.buildFrame(bytesOf(30));
        byte[] medium = Rtcm3Framer.buildFrame(bytesOf(200));
        List<MavlinkFrame> sent = new ArrayList<>();
        CountDownLatch done = new CountDownLatch(1);
        AtomicReference<Throwable> casterError = new AtomicReference<>();

        try (ServerSocket caster = new ServerSocket(0)) {
            Thread casterThread = new Thread(() -> {
                try (Socket s = caster.accept()) {
                    readUntilHeaderEnd(s.getInputStream());
                    OutputStream out = s.getOutputStream();
                    out.write("ICY 200 OK\r\n\r\n".getBytes(StandardCharsets.UTF_8));
                    out.flush();
                    // 一条一条发，中间留间隔，逼出跨 read 边界的重组
                    out.write(small);
                    out.flush();
                    Thread.sleep(150);
                    out.write(medium);
                    out.flush();
                    Thread.sleep(500);   // 给转发循环留出消费窗口
                } catch (Throwable e) {
                    casterError.set(e);
                }
            });
            casterThread.setDaemon(true);
            casterThread.start();

            UdpGateway gateway = mock(UdpGateway.class);
            org.mockito.Mockito.doAnswer(inv -> {
                synchronized (sent) {
                    sent.add(inv.getArgument(1));
                    if (sent.size() >= 3) {
                        done.countDown();
                    }
                }
                return null;
            }).when(gateway).send(org.mockito.ArgumentMatchers.eq(61), org.mockito.ArgumentMatchers.any());

            NtripRtcmForwarder f = new NtripRtcmForwarder(gateway, registryWith(61, 63),
                    "127.0.0.1", caster.getLocalPort(), "MOUNT", "", "", 200);
            f.start();
            try {
                assertThat(done.await(10, TimeUnit.SECONDS))
                        .as("10s 内应收满 3 条（1 单片 + 2 分片）；实际 %d", sent.size())
                        .isTrue();
            } finally {
                f.close();
                casterThread.join(2000);
            }
            synchronized (sent) {
                // 转发的单位是**完整 RTCM 帧**（含 6 字节头尾）：接收方飞控要自己验
                // RTCM CRC-24Q，半条/去头都会被它拒——所以期望长度=两条帧的字节和。
                ByteArrayOutputStream payload = new ByteArrayOutputStream();
                for (MavlinkFrame frame : sent) {
                    GpsRtcmData m = GpsRtcmData.decode(frame);
                    assertThat(m.messageId()).isEqualTo(GpsRtcmData.ID);
                    payload.write(m.payload(), 0, m.payload().length);
                }
                assertThat(payload.toByteArray()).isEqualTo(join(small, medium));
            }
            assertThat(casterError.get()).isNull();
        }
    }

    /** 读到 \r\n\r\n 为止（NTRIP 请求头结束）。请求无 body，此后本测试不再读这条连接。 */
    private static void readUntilHeaderEnd(InputStream in) throws Exception {
        ByteArrayOutputStream head = new ByteArrayOutputStream();
        int prev = -1, cur;
        while ((cur = in.read()) >= 0) {
            head.write(cur);
            if (prev == '\r' && cur == '\n') {
                byte[] h = head.toByteArray();
                if (h.length >= 4 && h[h.length - 2] == '\r' && h[h.length - 3] == '\n') {
                    return;
                }
            }
            prev = cur;
        }
    }

    private static byte[] bytesOf(int n) {
        byte[] out = new byte[n];
        for (int i = 0; i < n; i++) {
            out[i] = (byte) (i * 7 + 1);
        }
        return out;
    }

    private static byte[] join(byte[] a, byte[] b) {
        byte[] out = new byte[a.length + b.length];
        System.arraycopy(a, 0, out, 0, a.length);
        System.arraycopy(b, 0, out, a.length, b.length);
        return out;
    }
}
