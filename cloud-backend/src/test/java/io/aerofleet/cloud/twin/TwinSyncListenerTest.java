package io.aerofleet.cloud.twin;

import io.aerofleet.cloud.gateway.MavlinkMessageEvent;
import io.aerofleet.mavlink.messages.GlobalPositionInt;
import io.aerofleet.mavlink.messages.MavlinkMessage;
import io.aerofleet.mavlink.messages.SensorFusionDataMsg;
import io.aerofleet.mavlink.messages.SysStatus;
import io.aerofleet.mavlink.messages.TwinStateSyncMsg;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.context.ApplicationEventPublisher;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.atLeast;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

/**
 * TwinSyncListener 孪生实时同步接线单测（M13）。
 * <p>
 * 直接实例化（无 Spring 上下文），@EventListener 的 SpEL 条件不在此验证——
 * 条件串抄自 TerrainMapService 等既有监听器，bean 装配由
 * CloudBackendApplicationTests 兜底。
 */
@DisplayName("TwinSyncListener 孪生实时同步接线 (M13)")
class TwinSyncListenerTest {

    private static final int SYSID = 7;

    private DigitalTwinService twinService;
    private ApplicationEventPublisher publisher;
    private TwinSyncListener listener;

    @BeforeEach
    void setUp() {
        twinService = new DigitalTwinService();
        publisher = mock(ApplicationEventPublisher.class);
        listener = new TwinSyncListener(twinService, publisher);
    }

    private static MavlinkMessageEvent event(int sysid, MavlinkMessage msg, long ts) {
        return new MavlinkMessageEvent(new Object(), sysid, msg.messageId(), msg, ts);
    }

    private static SensorFusionDataMsg fusion(int latE7, int lonE7, int altMm,
                                              float velocity, int headingCdeg) {
        return new SensorFusionDataMsg(latE7, lonE7, altMm, velocity, 3.0f, headingCdeg, SYSID, 3);
    }

    private static GlobalPositionInt gpi(int latE7, int lonE7, int altMm,
                                         int vx, int vy, int vz, int hdgCdeg) {
        return new GlobalPositionInt(0, latE7, lonE7, altMm, altMm, vx, vy, vz, hdgCdeg);
    }

    private List<MavlinkMessageEvent> publishedEvents() {
        ArgumentCaptor<MavlinkMessageEvent> captor = ArgumentCaptor.forClass(MavlinkMessageEvent.class);
        verify(publisher, atLeast(0)).publishEvent(captor.capture());
        return captor.getAllValues();
    }

    private List<TwinStateSyncMsg> publishedTwinSyncs() {
        return publishedEvents().stream()
                .filter(e -> e.getMsgId() == TwinStateSyncMsg.ID)
                .map(e -> (TwinStateSyncMsg) e.getMessage())
                .toList();
    }

    @Test
    @DisplayName("30054 融合态换算后喂 syncTwin：1E7/mm/cdeg 还原正确，电量未知为 -1")
    void sensorFusionFeedsSyncTwin() {
        listener.onSensorFusionData(event(SYSID,
                fusion(300_000_000, 1_200_000_000, 50_000, 5.0f, 9000), 1000));

        TwinState s = twinService.getTwin(SYSID);
        assertThat(s.lat).isEqualTo(30.0);
        assertThat(s.lon).isEqualTo(120.0);
        assertThat(s.alt).isEqualTo(50.0);
        assertThat(s.heading).isEqualTo(90.0);
        assertThat(s.velocity).isEqualTo(5.0);
        assertThat(s.battery).isEqualTo(-1.0);
        assertThat(s.driftMeters).isEqualTo(0.0);
    }

    @Test
    @DisplayName("SYS_STATUS 电量进入下一次孪生同步")
    void sysStatusFeedsBatteryIntoNextSync() {
        listener.onSysStatus(event(SYSID, new SysStatus(0, 0, 0, 350, 11_100, 180, 80), 900));
        listener.onSensorFusionData(event(SYSID,
                fusion(300_000_000, 1_200_000_000, 50_000, 5.0f, 9000), 1000));

        assertThat(twinService.getTwin(SYSID).battery).isEqualTo(80.0);
    }

    @Test
    @DisplayName("同步后发布 TWIN_STATE_SYNC(30055)：字段与孪生态一一对应")
    void syncPublishesTwinStateSyncMsg() {
        listener.onSysStatus(event(SYSID, new SysStatus(0, 0, 0, 350, 11_100, 180, 80), 900));
        listener.onSensorFusionData(event(SYSID,
                fusion(300_000_000, 1_200_000_000, 50_000, 5.0f, 9000), 1000));

        List<TwinStateSyncMsg> msgs = publishedTwinSyncs();
        assertThat(msgs).hasSize(1);
        TwinStateSyncMsg m = msgs.get(0);
        assertThat(m.twinLat).isEqualTo(300_000_000);
        assertThat(m.twinLon).isEqualTo(1_200_000_000);
        assertThat(m.twinAlt).isEqualTo(50_000);
        assertThat(m.twinHeading).isEqualTo(9000);
        assertThat(m.twinVelocity).isEqualTo(5.0f);
        assertThat(m.twinBattery).isEqualTo(80);
        assertThat(m.driftMeters).isEqualTo(0.0f);
        assertThat(m.sysId).isEqualTo(SYSID);
        TwinState s = twinService.getTwin(SYSID);
        assertThat(publishedEvents().get(0).getMsgTimestamp()).isEqualTo(s.syncTimestamp);
    }

    @Test
    @DisplayName("电量未知时 twinBattery 发 255（MAVLink 未知惯例），REST 侧保持 -1")
    void unknownBatteryMapsTo255Sentinel() {
        listener.onSensorFusionData(event(SYSID,
                fusion(300_000_000, 1_200_000_000, 50_000, 5.0f, 9000), 1000));

        assertThat(publishedTwinSyncs().get(0).twinBattery).isEqualTo(255);
        assertThat(twinService.getTwin(SYSID).battery).isEqualTo(-1.0);
    }

    @Test
    @DisplayName("1Hz 节流：每 sysid 独立计时，节流窗口内只同步不发布")
    void publishThrottledTo1HzPerSysid() {
        listener.onSensorFusionData(event(SYSID, fusion(300_000_000, 1_200_000_000, 50_000, 5.0f, 9000), 1000));
        listener.onSensorFusionData(event(8, fusion(310_000_000, 1_210_000_000, 60_000, 6.0f, 18000), 1200));
        listener.onSensorFusionData(event(SYSID, fusion(300_100_000, 1_200_000_000, 50_000, 5.0f, 9000), 1500));
        listener.onSensorFusionData(event(SYSID, fusion(300_200_000, 1_200_000_000, 50_000, 5.0f, 9000), 2100));

        assertThat(publishedTwinSyncs()).hasSize(3);
        assertThat(twinService.getTwin(SYSID).lat).isEqualTo(30.02);
        assertThat(twinService.getTwin(8).lat).isEqualTo(31.0);
    }

    @Test
    @DisplayName("syncTwin 的位移漂移进入第二次发布的 driftMeters")
    void driftFlowsIntoSecondPublish() {
        listener.onSensorFusionData(event(SYSID, fusion(300_000_000, 1_200_000_000, 50_000, 5.0f, 9000), 1000));
        listener.onSensorFusionData(event(SYSID, fusion(300_001_000, 1_200_000_000, 50_000, 5.0f, 9000), 2100));

        List<TwinStateSyncMsg> msgs = publishedTwinSyncs();
        assertThat(msgs).hasSize(2);
        assertThat(msgs.get(1).driftMeters).isBetween(11.0f, 11.2f);
    }

    @Test
    @DisplayName("发布链路抛异常不外溢：孪生态照常更新，监听器不把异常带给事件组播")
    void publishFailureIsIsolated() {
        doThrow(new RuntimeException("ws down")).when(publisher).publishEvent(any(MavlinkMessageEvent.class));

        assertThatCode(() -> listener.onSensorFusionData(event(SYSID,
                fusion(300_000_000, 1_200_000_000, 50_000, 5.0f, 9000), 1000)))
                .doesNotThrowAnyException();

        assertThat(twinService.getTwin(SYSID)).isNotNull();
        assertThat(twinService.getTwin(SYSID).lat).isEqualTo(30.0);
    }

    @Test
    @DisplayName("30054 消息体损坏（cast 失败）只影响本条：不抛异常、不写入孪生")
    void malformedFusionMessageIsIsolated() {
        MavlinkMessageEvent bogus =
                new MavlinkMessageEvent(new Object(), SYSID, SensorFusionDataMsg.ID,
                        new SysStatus(0, 0, 0, 350, 11_100, 180, 80), 1000);

        assertThatCode(() -> listener.onSensorFusionData(bogus)).doesNotThrowAnyException();

        assertThat(twinService.getTwin(SYSID)).isNull();
    }

    @Test
    @DisplayName("GPI 兜底：无融合态的设备（无边缘栈）用 GLOBAL_POSITION_INT 进孪生，电量沿用 SYS_STATUS")
    void gpiFallbackFeedsSyncTwinWhenNoFusion() {
        listener.onSysStatus(event(SYSID, new SysStatus(0, 0, 0, 350, 11_100, 180, 70), 900));
        listener.onGlobalPositionInt(event(SYSID,
                gpi(310_000_000, 1_210_000_000, 40_000, 300, 400, 0, 12_345), 1000));

        TwinState s = twinService.getTwin(SYSID);
        assertThat(s.lat).isEqualTo(31.0);
        assertThat(s.lon).isEqualTo(121.0);
        assertThat(s.alt).isEqualTo(40.0);
        assertThat(s.heading).isEqualTo(123.45);
        assertThat(s.velocity).isEqualTo(5.0);
        assertThat(s.battery).isEqualTo(70.0);
    }

    @Test
    @DisplayName("GPI 兜底让位：融合态新鲜（<3s）时 GPI 不竞争，孪生保持融合态位置")
    void gpiFallbackIgnoredWhileFusionFresh() {
        listener.onSensorFusionData(event(SYSID,
                fusion(300_000_000, 1_200_000_000, 50_000, 5.0f, 9000), 1000));
        listener.onGlobalPositionInt(event(SYSID,
                gpi(310_000_000, 1_210_000_000, 40_000, 300, 400, 0, 12_345), 2000));

        assertThat(twinService.getTwin(SYSID).lat).isEqualTo(30.0);
    }

    @Test
    @DisplayName("GPI 兜底接管：融合态过期（>3s，边缘栈停发）后 GPI 恢复孪生喂入")
    void gpiFallbackTakesOverAfterFusionStale() {
        listener.onSensorFusionData(event(SYSID,
                fusion(300_000_000, 1_200_000_000, 50_000, 5.0f, 9000), 1000));
        listener.onGlobalPositionInt(event(SYSID,
                gpi(310_000_000, 1_210_000_000, 40_000, 300, 400, 0, 12_345), 4500));

        assertThat(twinService.getTwin(SYSID).lat).isEqualTo(31.0);
    }

    @Test
    @DisplayName("GPI hdg=65535（未知）回退 heading=0（北向，MAVLink 惯例）")
    void gpiHdgUnknownMapsToZero() {
        listener.onGlobalPositionInt(event(SYSID,
                gpi(310_000_000, 1_210_000_000, 40_000, 0, 0, 0, 65535), 1000));

        assertThat(twinService.getTwin(SYSID).heading).isEqualTo(0.0);
    }

    @Test
    @DisplayName("GPI 兜底也发布 TWIN_STATE_SYNC(30055)，与融合态共享同一 1Hz 节流")
    void gpiFallbackPublishesTwinStateSync() {
        listener.onGlobalPositionInt(event(SYSID,
                gpi(310_000_000, 1_210_000_000, 40_000, 300, 400, 0, 12_345), 1000));
        listener.onGlobalPositionInt(event(SYSID,
                gpi(310_100_000, 1_210_000_000, 40_000, 300, 400, 0, 12_345), 1500));

        List<TwinStateSyncMsg> msgs = publishedTwinSyncs();
        assertThat(msgs).hasSize(1);
        assertThat(msgs.get(0).twinLat).isEqualTo(310_000_000);
        assertThat(msgs.get(0).twinHeading).isEqualTo(12_345);
        assertThat(msgs.get(0).twinVelocity).isEqualTo(5.0f);
        assertThat(msgs.get(0).twinBattery).isEqualTo(255);
    }

    @Test
    @DisplayName("GPI 兜底 per-sysid 独立：A 有新鲜融合态不受影响，B 纯 GPI 正常兜底")
    void gpiFallbackPerSysidIsIndependent() {
        listener.onSensorFusionData(event(SYSID,
                fusion(300_000_000, 1_200_000_000, 50_000, 5.0f, 9000), 1000));
        listener.onGlobalPositionInt(event(9,
                gpi(320_000_000, 1_220_000_000, 30_000, 0, 0, 0, 9000), 1000));
        listener.onGlobalPositionInt(event(SYSID,
                gpi(310_000_000, 1_210_000_000, 40_000, 300, 400, 0, 12_345), 1500));

        assertThat(twinService.getTwin(SYSID).lat).isEqualTo(30.0);
        assertThat(twinService.getTwin(9).lat).isEqualTo(32.0);
    }

    @Test
    @DisplayName("GPI 消息体损坏（cast 失败）只影响本条：不抛异常、不写入孪生")
    void malformedGpiIsIsolated() {
        MavlinkMessageEvent bogus =
                new MavlinkMessageEvent(new Object(), SYSID, GlobalPositionInt.ID,
                        new SysStatus(0, 0, 0, 350, 11_100, 180, 80), 1000);

        assertThatCode(() -> listener.onGlobalPositionInt(bogus)).doesNotThrowAnyException();

        assertThat(twinService.getTwin(SYSID)).isNull();
    }
}
