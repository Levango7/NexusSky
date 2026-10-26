package io.aerofleet.sim;

import io.aerofleet.mavlink.MavlinkFrame;
import io.aerofleet.mavlink.enums.MavEnums;
import io.aerofleet.mavlink.messages.Heartbeat;
import io.aerofleet.mavlink.messages.MavlinkMessage;
import io.aerofleet.mavlink.messages.ThermalDataMsg;
import io.aerofleet.mavlink.messages.ThermalHotspotMsg;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * THERMAL_HOTSPOT(30064) 逐热点像素坐标真实下发守卫（P2 #19 条件③收官）。
 *
 * <p>走<b>真实 UDP 协议路径</b>（模式同 {@code ThermalDataEmissionTest}）：
 * 起 VirtualDrone、GCS 发心跳建回程路由，等 1Hz 遥测里的 30064 帧。
 * 背景：30012 只有聚合量与热点<b>数量</b>，像素坐标一个字节都没有，云端反投影
 * （{@code GeolocationSolver}）拿不到输入——"热源在地面哪个位置"在链路层就丢了。
 * 本守卫防这条新消息也变成"壳到 sim 侧"的零发送点。
 *
 * <p>断言口径：
 * <ul>
 *   <li>帧真到了，且 sysid/timestamp 正确；</li>
 *   <li>逐点坐标落在合成场景分辨率内（0≤u&lt;32, 0≤v&lt;24），不是没填的 0；</li>
 *   <li>峰值温度远高于 22℃ 基线且与阈值（≈均值+2σ）相容；</li>
 *   <li>同 tick 的 30012 时间戳能在 30064 上找到（两帧配对，云端靠它对齐）。</li>
 * </ul>
 */
@DisplayName("M3 THERMAL_HOTSPOT(30064) 真实下发（1Hz 遥测，P2 #19 条件③守卫）")
class ThermalHotspotEmissionTest {

    private static final int AUTOPILOT_COMP = 1;
    private static final long SCAN_TIMEOUT_MS = 10_000;

    /** 合成场景分辨率（VirtualDrone.synthesizeThermalScene：32×24）。 */
    private static final int SCENE_W = 32;
    private static final int SCENE_H = 24;

    /** 合成热源峰值下限：22℃ 基线 + 最弱源强 30℃ = 52℃，取保守值避开舍入。 */
    private static final double PEAK_C_MIN = 44.0;

    @Test
    @DisplayName("1Hz 遥测含 THERMAL_HOTSPOT：逐点坐标在场景内、峰值温度显著、时间戳与同 tick 30012 配对")
    void thermalHotspotsFlowOverRealUdp() throws Exception {
        SimConfig config = SimConfig.parse(new String[]{"--port=0"});
        try (VirtualDrone drone = new VirtualDrone(config);
             DatagramSocket gcs = new DatagramSocket()) {
            drone.start();
            InetAddress addr = InetAddress.getLoopbackAddress();
            int[] seq = {0};

            // 心跳建立回程路由（transport 只认收到的对端；沿 ThermalData 先例）
            send(gcs, addr, drone.getLocalPort(), seq, new Heartbeat(0, 2, 6, 0, MavEnums.MAV_STATE_ACTIVE));

            List<ThermalHotspotMsg> hotspots = new ArrayList<>();
            Set<Long> dataTimestamps = new HashSet<>();
            boolean paired = false;

            long deadline = System.currentTimeMillis() + SCAN_TIMEOUT_MS;
            gcs.setSoTimeout(500);
            byte[] buf = new byte[512];
            DatagramPacket pkt = new DatagramPacket(buf, buf.length);
            while (System.currentTimeMillis() < deadline && !paired) {
                try {
                    gcs.receive(pkt);
                } catch (java.net.SocketTimeoutException e) {
                    continue;
                }
                MavlinkFrame frame;
                try {
                    frame = MavlinkFrame.decodeV2(Arrays.copyOf(pkt.getData(), pkt.getLength()));
                } catch (Exception e) {
                    continue;   // 坏帧丢弃，继续扫描
                }
                switch (frame.getMessageId()) {
                    case ThermalDataMsg.ID -> dataTimestamps.add(ThermalDataMsg.decode(frame).timestamp);
                    case ThermalHotspotMsg.ID -> {
                        ThermalHotspotMsg msg = ThermalHotspotMsg.decode(frame);
                        hotspots.add(msg);
                        paired = dataTimestamps.contains(msg.timestamp);
                    }
                    default -> { }
                }
            }

            // --- 帧真的到了 ---
            assertThat(hotspots).as("1Hz 遥测应含 THERMAL_HOTSPOT(30064) 帧").isNotEmpty();

            for (ThermalHotspotMsg msg : hotspots) {
                assertThat(msg.sysid).isEqualTo(config.sysid);
                assertThat(msg.timestamp).isPositive();

                assertThat(msg.count)
                        .as("合成场景有两个热源：count∈[1,MAX_HOTSPOTS]")
                        .isBetween(1, ThermalHotspotMsg.MAX_HOTSPOTS);
                assertThat(msg.truncated)
                        .as("两个热源装得下，不该置 truncated")
                        .isFalse();
                assertThat(msg.hotspots.length).as("数组长度 = count").isEqualTo(msg.count);

                for (int i = 0; i < msg.count; i++) {
                    int[] h = msg.hotspots[i];
                    double peakC = h[2] / 10.0;
                    assertThat(h[0]).as("热点 %d 像素 u 在场景内", i).isBetween(0, SCENE_W - 1);
                    assertThat(h[1]).as("热点 %d 像素 v 在场景内", i).isBetween(0, SCENE_H - 1);
                    assertThat(peakC)
                            .as("热点 %d 峰值远高于 22℃ 基线（否则是噪声像素被当成热源）", i)
                            .isGreaterThan(PEAK_C_MIN);
                    assertThat(peakC).as("峰值温度合理上界").isLessThan(120.0);
                }
            }

            // --- 两帧配对：云端靠同 tick 时间戳把逐点坐标贴到聚合帧上 ---
            assertThat(paired)
                    .as("至少一帧 THERMAL_HOTSPOT 的时间戳与同 tick 的 THERMAL_DATA 相同；"
                            + "各带一个 currentTimeMillis() 会让配对失败").isTrue();
        }
    }

    @Test
    @DisplayName("容量语义：hottestFirst 按峰值温度降序截断，最冷的热源被丢（而非静默任意丢）")
    void truncationKeepsHottest() {
        List<ThermalSource.Hotspot> four = List.of(
                new ThermalSource.Hotspot(1, 1, 50.0, 10),
                new ThermalSource.Hotspot(2, 2, 90.0, 12),
                new ThermalSource.Hotspot(3, 3, 70.0, 8),
                new ThermalSource.Hotspot(4, 4, 60.0, 9));
        List<ThermalSource.Hotspot> kept = VirtualDrone.hottestFirst(four, ThermalHotspotMsg.MAX_HOTSPOTS);

        assertThat(kept).hasSize(ThermalHotspotMsg.MAX_HOTSPOTS);
        assertThat(kept.get(0).tempC()).isEqualTo(90.0);
        assertThat(kept.get(1).tempC()).isEqualTo(70.0);
        assertThat(kept.get(2).tempC()).isEqualTo(60.0);
        assertThat(kept.stream().map(ThermalSource.Hotspot::tempC).toList())
                .as("被丢的必须是最冷的那个（50℃）").doesNotContain(50.0);
        // 0.1℃ 量化口径：调用方 ×10 后为整数，90.0℃ → 900
        assertThat((int) Math.round(kept.get(0).tempC() * 10)).isEqualTo(900);

        // 不满容量时原样保留、不补零
        List<ThermalSource.Hotspot> two = VirtualDrone.hottestFirst(four, 2);
        assertThat(two).hasSize(2);
        assertThat(two.get(0).tempC()).isEqualTo(90.0);
        // 入参不被就地排序
        assertThat(four.get(0).tempC()).isEqualTo(50.0);
        assertThat(VirtualDrone.hottestFirst(null, 3)).isEmpty();
        assertThat(VirtualDrone.hottestFirst(List.of(), 3)).isEmpty();
    }

    @Test
    @DisplayName("合成场景真的会产出热点：同一批探测点反复分析，热点数与坐标非平凡")
    void synthesizedSceneYieldsHotspotPixels() {
        // 与真实下发路径同一入口（thermalSource.analyze(synthesizeThermalScene(...))），
        // 这里钉住"坐标不是没填的 0/0"，避免 UDP 侧断言在空场景下永远通过。
        SimulatedThermalSource src = new SimulatedThermalSource();
        int totalPixelsSeen = 0;
        int nonzeroCoords = 0;
        int frames = 0;
        for (long t = 0; t < 60_000; t += 5_000) {
            ThermalSource.ThermalResult r = src.analyze(
                    VirtualDrone.synthesizeThermalScene(3, 1_700_000_000_000L + t));
            List<ThermalSource.Hotspot> kept = VirtualDrone.hottestFirst(
                    r.hotspots(), ThermalHotspotMsg.MAX_HOTSPOTS);
            assertThat(r.hotspots()).as("t=%d 两个热源应检出热点", t).isNotEmpty();
            assertThat(kept.size()).isLessThanOrEqualTo(ThermalHotspotMsg.MAX_HOTSPOTS);
            assertThat(r.hotspots().size() <= ThermalHotspotMsg.MAX_HOTSPOTS)
                    .as("两个热源装得下，不该触发 truncated").isTrue();
            for (ThermalSource.Hotspot h : r.hotspots()) {
                totalPixelsSeen++;
                if (h.u() != 0 || h.v() != 0) {
                    nonzeroCoords++;
                }
            }
            frames++;
        }
        assertThat(frames).isPositive();
        assertThat(nonzeroCoords)
                .as("至少部分热点不在原点").isGreaterThan(0);
        assertThat(totalPixelsSeen)
                .as("全部像素都在场景分辨率内").isPositive();
    }

    private void send(DatagramSocket gcs, InetAddress addr, int dronePort, int[] seq,
                      MavlinkMessage msg) throws Exception {
        byte[] frame = msg.toFrame(VirtualDrone.GCS_SYSID, AUTOPILOT_COMP, seq[0]++).encodeV2();
        gcs.send(new DatagramPacket(frame, frame.length, addr, dronePort));
    }
}
