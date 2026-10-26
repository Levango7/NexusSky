package io.aerofleet.sim;

import io.aerofleet.mavlink.messages.GpsRtcmData;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * F6 RTK 上界语义（GAPS §十.10-1 裁决 B 案，2026-10-26）：
 * {@code --rtk} 只声明能力**上界**，5/6 只有在新鲜度窗口内真的收到过
 * {@code GPS_RTCM_DATA} 改正数才上报；断链降级、未配置不抬升。
 * <p>
 * 全部走**真实 UDP 路径**（与 AdaptivePathExecutionTest 同一模式）：
 * 帧经 MAVLink 编码发给模拟机监听端口，由接收线程解码并更新
 * {@code lastRtcmAtMs}——不碰生产代码缝，防「测试绿但真实链路没走通」。
 * 窗口用 {@code aerofleet.sim.rtcm-fresh-window-ms=80} 压短，
 * 断链降级断言无需真等 5 秒。
 */
class VirtualDroneRtkCeilingTest {

    private static final int AUTOPILOT_COMP = 1;
    /** 压短后的新鲜度窗口（毫秒）。 */
    private static final long WINDOW_MS = 80;
    /** 断链降级断言前的等待：两倍窗口，确保 freshness 判定已翻转。 */
    private static final long STALE_WAIT_MS = 2 * WINDOW_MS + 60;

    @BeforeEach
    void shrinkWindow() {
        System.setProperty("aerofleet.sim.rtcm-fresh-window-ms", String.valueOf(WINDOW_MS));
    }

    @AfterEach
    void restoreWindow() {
        System.clearProperty("aerofleet.sim.rtcm-fresh-window-ms");
    }

    @Test
    @DisplayName("上界语义：--rtk fixed 但从未收到改正数 ⇒ 3（配置不等于事实）")
    void fixedWithoutCorrectionsStays3dFix() throws Exception {
        SimConfig config = SimConfig.parse(new String[]{"--port=0", "--rtk", "fixed"});
        try (VirtualDrone drone = new VirtualDrone(config)) {
            drone.start();
            awaitSteady(drone);
            assertThat(drone.rtkFixType()).isEqualTo(3);
        }
    }

    @Test
    @DisplayName("收到改正数 ⇒ 按上界上报 6（fixed）；断链后降回 3")
    void fixedUpgradesWithCorrectionsThenDegrades() throws Exception {
        SimConfig config = SimConfig.parse(new String[]{"--port=0", "--rtk", "fixed"});
        try (VirtualDrone drone = new VirtualDrone(config);
             DatagramSocket gcs = new DatagramSocket()) {
            drone.start();
            awaitSteady(drone);
            assertThat(drone.rtkFixType()).isEqualTo(3);

            // 注入一帧真实 RTCM（32 字节假改正数；帧经 encodeV2 + 真实 UDP 解码路径）
            sendRtcm(gcs, drone);

            // 等接收线程处理（生产语义：volatile 写后立即可见，轮询留余量）
            long deadline = System.currentTimeMillis() + 2_000;
            while (drone.rtkFixType() != 6 && System.currentTimeMillis() < deadline) {
                Thread.sleep(20);
            }
            assertThat(drone.rtkFixType()).isEqualTo(6);

            // 停止注入 ⇒ 超出新鲜度窗口后降回 3（NTRIP 断链必须掉精度）
            Thread.sleep(STALE_WAIT_MS);
            assertThat(drone.rtkFixType()).isEqualTo(3);
        }
    }

    @Test
    @DisplayName("--rtk float + 改正数 ⇒ 5（上界按 float）")
    void floatCeilingReports5() throws Exception {
        SimConfig config = SimConfig.parse(new String[]{"--port=0", "--rtk", "float"});
        try (VirtualDrone drone = new VirtualDrone(config);
             DatagramSocket gcs = new DatagramSocket()) {
            drone.start();
            awaitSteady(drone);
            sendRtcm(gcs, drone);
            long deadline = System.currentTimeMillis() + 2_000;
            while (drone.rtkFixType() != 5 && System.currentTimeMillis() < deadline) {
                Thread.sleep(20);
            }
            assertThat(drone.rtkFixType()).isEqualTo(5);
        }
    }

    @Test
    @DisplayName("未配置 --rtk 的机器收到改正数也不抬升 ⇒ 3（上界即 3）")
    void correctionsDoNotLiftUnconfiguredDrone() throws Exception {
        SimConfig config = SimConfig.parse(new String[]{"--port=0"});
        try (VirtualDrone drone = new VirtualDrone(config);
             DatagramSocket gcs = new DatagramSocket()) {
            drone.start();
            awaitSteady(drone);
            sendRtcm(gcs, drone);
            Thread.sleep(400);   // 给接收线程足够时间处理，再断言「没有因此抬升」
            assertThat(drone.rtkFixType()).isEqualTo(3);
        }
    }

    /** 发一帧 32 字节的假 RTCM 改正数（broadcast、不分片）到模拟机监听端口。 */
    private static void sendRtcm(DatagramSocket gcs, VirtualDrone drone) throws Exception {
        byte[] rtcm = new byte[32];
        for (int i = 0; i < rtcm.length; i++) {
            rtcm[i] = (byte) i;
        }
        byte[] frame = GpsRtcmData.broadcast(rtcm, 0)
                .toFrame(VirtualDrone.GCS_SYSID, AUTOPILOT_COMP, 0).encodeV2();
        InetAddress addr = InetAddress.getLoopbackAddress();
        gcs.send(new DatagramPacket(frame, frame.length, addr, drone.getLocalPort()));
    }

    /** 等模拟机接收线程进入稳态（start 返回后给接收线程留一点余量，避免与启动竞态）。 */
    private static void awaitSteady(VirtualDrone drone) throws Exception {
        Thread.sleep(150);
    }
}
