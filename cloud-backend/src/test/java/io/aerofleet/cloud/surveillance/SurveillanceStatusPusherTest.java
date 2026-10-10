package io.aerofleet.cloud.surveillance;

import io.aerofleet.cloud.api.ws.TelemetryWebSocketHandler;
import io.aerofleet.cloud.gateway.MavlinkMessageEvent;
import io.aerofleet.mavlink.messages.SurveillanceStatusMsg;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.context.ApplicationEventPublisher;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 安防设备状态推送器单测（WS_TYPE_MAP 收口 2026-10-05 边界清零）。
 * <p>
 * 覆盖 SurveillanceStatusPusher 的 30059 帧发布：厂商/状态映射、
 * 单通道摄像头计数、进程内 uptime、显式租户路由，以及无客户端跳过。
 */
@DisplayName("SurveillanceStatusPusher 设备状态推送 (30059)")
class SurveillanceStatusPusherTest {

    private SurveillanceDeviceRegistry registry;
    private ApplicationEventPublisher publisher;
    private TelemetryWebSocketHandler wsHandler;
    private SurveillanceStatusPusher pusher;

    @BeforeEach
    void setUp() {
        registry = new SurveillanceDeviceRegistry();
        publisher = mock(ApplicationEventPublisher.class);
        wsHandler = mock(TelemetryWebSocketHandler.class);
        when(wsHandler.connectionCount()).thenReturn(1);
        pusher = new SurveillanceStatusPusher(registry, publisher, wsHandler);
    }

    /** 构造并注册设备；租户在 register 之后设置（register 会以当前上下文覆写 tenantId）。 */
    private SurveillanceDevice registerDevice(String id, SurveillanceDevice.Vendor vendor,
                                              Integer tenantId) {
        SurveillanceDevice d = new SurveillanceDevice(id, "cam-" + id, vendor,
                "192.168.1.10", 80, "admin", "pass");
        registry.register(d);
        d.tenantId = tenantId;
        return d;
    }

    @Test
    @DisplayName("逐设备发布 30059：厂商/状态/单通道计数/显式租户正确")
    void publishesPerDeviceStatusFrames() {
        registerDevice("cam-hik", SurveillanceDevice.Vendor.HIKVISION, 42);
        registerDevice("cam-onvif", SurveillanceDevice.Vendor.ONVIF, null);

        pusher.pushOnce();

        ArgumentCaptor<MavlinkMessageEvent> captor =
                ArgumentCaptor.forClass(MavlinkMessageEvent.class);
        verify(publisher, times(2)).publishEvent(captor.capture());
        List<MavlinkMessageEvent> events = captor.getAllValues();

        MavlinkMessageEvent hik = events.get(0);
        assertThat(hik.getMsgId()).isEqualTo(SurveillanceStatusMsg.ID);
        assertThat(hik.isTenantExplicit()).isTrue();
        assertThat(hik.getOwnerTenantId()).isEqualTo(42);
        SurveillanceStatusMsg hikMsg = (SurveillanceStatusMsg) hik.getMessage();
        assertThat(hikMsg.deviceId).isEqualTo(SurveillanceStatusPusher.deviceIdToU16("cam-hik"));
        assertThat(hikMsg.deviceType).isEqualTo(0);
        assertThat(hikMsg.status).isEqualTo(0);
        assertThat(hikMsg.onlineCameras).isEqualTo(1);
        assertThat(hikMsg.totalCameras).isEqualTo(1);
        assertThat(hikMsg.uptimeSec).isGreaterThanOrEqualTo(0);
        assertThat(hikMsg.lastEventMs).isGreaterThan(0);

        MavlinkMessageEvent onvif = events.get(1);
        assertThat(onvif.getOwnerTenantId()).isNull();
        assertThat(((SurveillanceStatusMsg) onvif.getMessage()).deviceType).isEqualTo(3);
    }

    @Test
    @DisplayName("离线设备映射 status=1、onlineCameras=0")
    void offlineDeviceMapsCorrectly() {
        SurveillanceDevice d = registerDevice("cam-off", SurveillanceDevice.Vendor.DAHUA, 7);
        d.status = SurveillanceDevice.Status.OFFLINE;
        registry.register(d);

        pusher.pushOnce();

        ArgumentCaptor<MavlinkMessageEvent> captor =
                ArgumentCaptor.forClass(MavlinkMessageEvent.class);
        verify(publisher, times(1)).publishEvent(captor.capture());
        SurveillanceStatusMsg msg = (SurveillanceStatusMsg) captor.getValue().getMessage();
        assertThat(msg.deviceType).isEqualTo(1);
        assertThat(msg.status).isEqualTo(1);
        assertThat(msg.onlineCameras).isEqualTo(0);
        assertThat(msg.totalCameras).isEqualTo(1);
    }

    @Test
    @DisplayName("四态与多通道：FAULT→2 / MAINTENANCE→3；totalCameras 取设备模型（B2/B3）")
    void mapsFourStatesAndMultiChannel() {
        SurveillanceDevice fault = registerDevice("cam-fault", SurveillanceDevice.Vendor.HIKVISION, null);
        fault.status = SurveillanceDevice.Status.FAULT;
        fault.totalCameras = 4;
        SurveillanceDevice maint = registerDevice("cam-maint", SurveillanceDevice.Vendor.UNIVIEW, null);
        maint.status = SurveillanceDevice.Status.MAINTENANCE;

        pusher.pushOnce();

        ArgumentCaptor<MavlinkMessageEvent> captor =
                ArgumentCaptor.forClass(MavlinkMessageEvent.class);
        verify(publisher, times(2)).publishEvent(captor.capture());

        SurveillanceStatusMsg faultMsg = null;
        SurveillanceStatusMsg maintMsg = null;
        for (MavlinkMessageEvent e : captor.getAllValues()) {
            SurveillanceStatusMsg m = (SurveillanceStatusMsg) e.getMessage();
            if (m.deviceId == SurveillanceStatusPusher.deviceIdToU16("cam-fault")) {
                faultMsg = m;
            }
            if (m.deviceId == SurveillanceStatusPusher.deviceIdToU16("cam-maint")) {
                maintMsg = m;
            }
        }
        assertThat(faultMsg).isNotNull();
        assertThat(faultMsg.status).isEqualTo(2);         // 故障（此前协议值不可达）
        assertThat(faultMsg.totalCameras).isEqualTo(4);   // 多通道计数取模型
        assertThat(faultMsg.onlineCameras).isEqualTo(0);  // 故障 → 0 在线
        assertThat(maintMsg).isNotNull();
        assertThat(maintMsg.status).isEqualTo(3);         // 维护（此前协议值不可达）
    }

    @Test
    @DisplayName("无 WS 客户端时跳过推送")
    void skipsWithoutWsClients() {
        when(wsHandler.connectionCount()).thenReturn(0);
        registerDevice("cam-x", SurveillanceDevice.Vendor.UNIVIEW, 1);

        pusher.pushOnce();

        verify(publisher, never()).publishEvent(any());
    }

    @Test
    @DisplayName("设备 ID → u16 哈希稳定且在范围内")
    void deviceIdHashStable() {
        int a = SurveillanceStatusPusher.deviceIdToU16("cam-hik");
        assertThat(a).isEqualTo(SurveillanceStatusPusher.deviceIdToU16("cam-hik"));
        assertThat(a).isBetween(0, 0xFFFF);
        assertThat(SurveillanceStatusPusher.deviceIdToU16(null)).isZero();
    }

    @Test
    @DisplayName("firstSeenMs 在构造时初始化，uptime 随时间非递减")
    void firstSeenInitializedAtConstruction() throws InterruptedException {
        SurveillanceDevice d = new SurveillanceDevice("cam-t", "cam-t", SurveillanceDevice.Vendor.HIKVISION, "192.168.1.10", 80, "admin", "pass");
        long before = System.currentTimeMillis();
        Thread.sleep(5);
        long uptimeA = (System.currentTimeMillis() - d.firstSeenMs) / 1000;
        Thread.sleep(1100);
        long uptimeB = (System.currentTimeMillis() - d.firstSeenMs) / 1000;
        assertThat(d.firstSeenMs).isLessThanOrEqualTo(before);
        assertThat(uptimeB).isGreaterThanOrEqualTo(uptimeA);
    }
}
