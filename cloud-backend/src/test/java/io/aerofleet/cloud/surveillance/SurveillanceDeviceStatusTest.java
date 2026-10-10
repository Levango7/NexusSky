package io.aerofleet.cloud.surveillance;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 设备四态语义测试（B2/B3）。
 * <p>
 * 语义契约：ONLINE/OFFLINE 是链路段态（心跳与超时扫描驱动）；
 * FAULT/MAINTENANCE 是人工/检测置位——**心跳与离线扫描都不覆盖**
 * （心跳只证明链路可达，不代表故障恢复或检修结束），恢复需显式置回 ONLINE。
 */
@DisplayName("SurveillanceDevice 四态语义 (B2)")
class SurveillanceDeviceStatusTest {

    private static SurveillanceDevice device() {
        return new SurveillanceDevice("cam-1", "cam", SurveillanceDevice.Vendor.HIKVISION,
                "192.168.1.10", 80, "admin", "pass");
    }

    @Test
    @DisplayName("新建设备默认 ONLINE、单通道")
    void defaultsOnlineSingleChannel() {
        SurveillanceDevice d = device();
        assertThat(d.status).isEqualTo(SurveillanceDevice.Status.ONLINE);
        assertThat(d.totalCameras).isEqualTo(1);
    }

    @Test
    @DisplayName("心跳：OFFLINE → ONLINE")
    void heartbeatRecoversOffline() {
        SurveillanceDevice d = device();
        d.status = SurveillanceDevice.Status.OFFLINE;
        d.heartbeat();
        assertThat(d.status).isEqualTo(SurveillanceDevice.Status.ONLINE);
    }

    @Test
    @DisplayName("心跳不覆盖 FAULT / MAINTENANCE（需显式恢复）")
    void heartbeatDoesNotClearFaultOrMaintenance() {
        SurveillanceDevice fault = device();
        fault.status = SurveillanceDevice.Status.FAULT;
        fault.heartbeat();
        assertThat(fault.status).isEqualTo(SurveillanceDevice.Status.FAULT);

        SurveillanceDevice maint = device();
        maint.status = SurveillanceDevice.Status.MAINTENANCE;
        maint.heartbeat();
        assertThat(maint.status).isEqualTo(SurveillanceDevice.Status.MAINTENANCE);
    }

    @Test
    @DisplayName("离线扫描只动 ONLINE 设备：FAULT 不被冲成 OFFLINE")
    void pruneDoesNotTouchFault() {
        SurveillanceDeviceRegistry registry = new SurveillanceDeviceRegistry();
        SurveillanceDevice fault = device();
        fault.status = SurveillanceDevice.Status.FAULT;
        fault.lastHeartbeatMs = 1; // 远超时
        registry.register(fault);

        List<String> pruned = registry.pruneStaleDevices(1000);
        assertThat(pruned).isEmpty();
        assertThat(fault.status).isEqualTo(SurveillanceDevice.Status.FAULT);
    }

    @Test
    @DisplayName("状态枚举值与协议值域一致（0-3，SurveillanceStatusMsg.status）")
    void statusOrdinalMatchesProtocol() {
        assertThat(SurveillanceDevice.Status.ONLINE.ordinal()).isEqualTo(0);
        assertThat(SurveillanceDevice.Status.OFFLINE.ordinal()).isEqualTo(1);
        assertThat(SurveillanceDevice.Status.FAULT.ordinal()).isEqualTo(2);
        assertThat(SurveillanceDevice.Status.MAINTENANCE.ordinal()).isEqualTo(3);
    }
}
