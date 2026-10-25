package io.aerofleet.cloud.vision;

import io.aerofleet.cloud.gateway.DeviceRegistry;
import io.aerofleet.cloud.gateway.DroneSnapshot;
import io.aerofleet.cloud.gateway.MavlinkMessageEvent;
import io.aerofleet.mavlink.messages.ThermalHotspotMsg;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * THERMAL_HOTSPOT(30064) 逐点坐标 ingest + 反投影契约（P2 #19 条件③）。
 *
 * <p>钉住四类行为：
 * <ul>
 *   <li><b>正下视中心像素</b>：解算结果必须是飞机自身经纬度（射线垂直向下，
 *       地面交点就在正下方）——这是能写死数值的锚点；</li>
 *   <li><b>水平姿态</b>：yaw=0 时右侧像素（u &gt; 中心）必须落在东侧（lon 增大）、
 *       纬度几乎不变——证明旋转矩阵方向没搞反；</li>
 *   <li><b>缺输入不猜</b>：无 GPS / 无高度 / 无姿态 / 无求解器，lat/lon 一律 null
 *       （面板据此明说"无法解算"，而不是填 0 冒充坐标）；</li>
 *   <li><b>帧语义</b>：温度 0.1℃ 量化、truncated 透传、容量上限。</li>
 * </ul>
 */
@DisplayName("ThermalIngestListener：30064 逐点像素 → lat/lon（P2 #19 条件③）")
class ThermalHotspotGeolocationTest {

    private final DeviceRegistry registry = new DeviceRegistry();
    private final ThermalTaskService tasks = new ThermalTaskService();
    private final GeolocationSolver solver = new GeolocationSolver();
    private final ThermalIngestListener listener =
            new ThermalIngestListener(registry, tasks, solver);

    private static final int SYSID = 7;

    /** 位姿齐备的快照：32°N, 118°E，离地 60m，水平机姿，正下视。 */
    private DroneSnapshot snapshotWithPose() {
        DroneSnapshot s = registry.registerIfAbsent(SYSID);
        s.online = true;
        s.lat = 32.0;
        s.lon = 118.0;
        s.relativeAlt = 60.0;
        s.roll = 0.0;
        s.pitch = 0.0;
        s.yaw = 0.0;
        return s;
    }

    private void publish(int sysid, ThermalHotspotMsg msg) {
        listener.onThermalHotspot(new MavlinkMessageEvent(this, sysid, ThermalHotspotMsg.ID, msg, 7L));
    }

    @Test
    @DisplayName("正下视 + 中心像素 → 解算结果 = 飞机自身经纬度（地面交点就在正下方）")
    void nadirCenterPixelMapsToDronePosition() {
        snapshotWithPose();
        int cx = ThermalIngestListener.THERMAL_IMAGE_W / 2;
        int cy = ThermalIngestListener.THERMAL_IMAGE_H / 2;
        ThermalHotspotMsg msg = new ThermalHotspotMsg(SYSID, 1000L, false,
                new int[][]{{cx, cy, 612}});

        publish(SYSID, msg);

        DroneSnapshot s = registry.all().stream().filter(d -> d.sysid == SYSID).findFirst().orElseThrow();
        assertThat(s.thermalHotspots).hasSize(1);
        DroneSnapshot.ThermalHotspot h = s.thermalHotspots.get(0);
        assertThat(h.lat()).as("中心像素射线垂直向下：交点就是机下点").isEqualTo(32.0);
        assertThat(h.lon()).isEqualTo(118.0);
        assertThat(h.tempC()).as("0.1℃ 量化：612 → 61.2℃").isEqualTo(61.2);
        assertThat(s.thermalHotspotsTruncated).isFalse();
    }

    @Test
    @DisplayName("水平机姿 + 右侧像素 → lon 增大（东侧）、lat 不变（旋转方向正确）")
    void levelFlightRightPixelFallsEast() {
        snapshotWithPose();
        ThermalHotspotMsg msg = new ThermalHotspotMsg(SYSID, 1000L, false,
                new int[][]{{ThermalIngestListener.THERMAL_IMAGE_W - 1, 12, 505}});

        publish(SYSID, msg);

        DroneSnapshot.ThermalHotspot h = registry.all().stream()
                .filter(d -> d.sysid == SYSID).findFirst().orElseThrow()
                .thermalHotspots.get(0);
        assertThat(h.lat()).isNotNull();
        assertThat(h.lon()).as("图像右侧 = 机体右侧 = 东（yaw=0）").isGreaterThan(118.0);
        assertThat(h.lat()).as("正侧方向不改变纬度").isBetween(31.9999, 32.0001);
    }

    @Test
    @DisplayName("云台/姿态输入缺失 → lat/lon 为 null（不猜、不填 0）")
    void missingInputsYieldNullNotZero() {
        // 无 GPS、无高度：连 poseReady 都进不去
        DroneSnapshot bare = registry.registerIfAbsent(SYSID);
        bare.online = true;
        publish(SYSID, new ThermalHotspotMsg(SYSID, 1L, false, new int[][]{{16, 12, 500}}));
        assertThat(bare.thermalHotspots.get(0).lat()).as("无 GPS：明示无法解算").isNull();
        assertThat(bare.thermalHotspots.get(0).lon()).isNull();

        // 有 GPS 但离地高度为 0（停机坪）：解不出来
        bare.lat = 32.0;
        bare.lon = 118.0;
        bare.relativeAlt = 0.0;
        publish(SYSID, new ThermalHotspotMsg(SYSID, 2L, false, new int[][]{{16, 12, 500}}));
        assertThat(bare.thermalHotspots.get(0).lat()).as("离地 0：射线打不到地").isNull();

        // 无姿态（ATTITUDE 还没来）
        bare.relativeAlt = 60.0;
        publish(SYSID, new ThermalHotspotMsg(SYSID, 3L, false, new int[][]{{16, 12, 500}}));
        assertThat(bare.thermalHotspots.get(0).lat()).as("无姿态：不解算").isNull();
    }

    @Test
    @DisplayName("未注入求解器（老装配）：坐标位置如实上报，lat/lon = null")
    void withoutSolverNullCoordinatesButPixelsKept() {
        ThermalIngestListener noSolver = new ThermalIngestListener(registry, tasks);
        snapshotWithPose();
        noSolver.onThermalHotspot(new MavlinkMessageEvent(this, SYSID, ThermalHotspotMsg.ID,
                new ThermalHotspotMsg(SYSID, 5L, false, new int[][]{{3, 4, 523}}), 5L));

        DroneSnapshot.ThermalHotspot h = registry.all().stream()
                .filter(d -> d.sysid == SYSID).findFirst().orElseThrow()
                .thermalHotspots.get(0);
        assertThat(h.u()).isEqualTo(3);
        assertThat(h.v()).isEqualTo(4);
        assertThat(h.tempC()).isEqualTo(52.3);
        assertThat(h.lat()).as("没有求解器就不解算——留着像素信息总比丢帧好").isNull();
    }

    @Test
    @DisplayName("帧语义：truncated 透传、多条热点按序解算、0 热点合法")
    void frameSemantics() {
        snapshotWithPose();
        publish(SYSID, new ThermalHotspotMsg(SYSID, 9L, true, new int[][]{
                {10, 8, 700}, {20, 15, 560}, {25, 20, 480}}));

        DroneSnapshot s = registry.all().stream().filter(d -> d.sysid == SYSID).findFirst().orElseThrow();
        assertThat(s.thermalHotspots).hasSize(3);
        assertThat(s.thermalHotspotsTruncated).isTrue();
        assertThat(s.thermalHotspots.get(0).tempC()).isEqualTo(70.0);
        assertThat(s.thermalHotspots.get(1).tempC()).isEqualTo(56.0);
        assertThat(s.thermalHotspots.get(2).tempC()).isEqualTo(48.0);
        assertThat(s.thermalHotspots).allSatisfy(h -> assertThat(h.lat()).isNotNull());

        publish(SYSID, new ThermalHotspotMsg(SYSID, 10L, false, new int[0][]));
        assertThat(s.thermalHotspots).isEmpty();
        assertThat(s.thermalHotspotsTruncated).isFalse();
    }

    @Test
    @DisplayName("hasPose：位姿最小输入集判定（NaN/贴地/缺姿态都不算齐）")
    void hasPoseGate() {
        DroneSnapshot s = new DroneSnapshot(1);
        assertThat(ThermalIngestListener.hasPose(s)).isFalse();
        s.lat = 32.0;
        s.lon = 118.0;
        assertThat(ThermalIngestListener.hasPose(s)).as("缺高度").isFalse();
        s.relativeAlt = 60.0;
        assertThat(ThermalIngestListener.hasPose(s)).as("缺姿态").isFalse();
        s.roll = 0.0;
        s.pitch = 0.0;
        s.yaw = 0.0;
        assertThat(ThermalIngestListener.hasPose(s)).isTrue();

        s.relativeAlt = 0.2;
        assertThat(ThermalIngestListener.hasPose(s)).as("贴地 0.2m：fov 微扰都可能朝天，不给解").isFalse();
    }

    @Test
    @DisplayName("同一帧多热点互不串味：各点像素与温度一一对应")
    void hotspotsKeepTheirOwnPixelsAndTemps() {
        snapshotWithPose();
        publish(SYSID, new ThermalHotspotMsg(SYSID, 11L, false, new int[][]{
                {5, 5, 601}, {26, 19, 455}}));

        List<DroneSnapshot.ThermalHotspot> hs = registry.all().stream()
                .filter(d -> d.sysid == SYSID).findFirst().orElseThrow().thermalHotspots;
        assertThat(hs.get(0).u()).isEqualTo(5);
        assertThat(hs.get(0).v()).isEqualTo(5);
        assertThat(hs.get(0).tempC()).isEqualTo(60.1);
        assertThat(hs.get(1).u()).isEqualTo(26);
        assertThat(hs.get(1).v()).isEqualTo(19);
        assertThat(hs.get(1).tempC()).isEqualTo(45.5);
        // 不同的像素 → 不同的解算结果（不是同一个坐标复制两遍）
        assertThat(hs.get(0).lon()).isNotEqualTo(hs.get(1).lon());
    }
}
