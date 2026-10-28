package io.aerofleet.cloud.api.dto;

import io.aerofleet.cloud.gateway.DroneSnapshot;
import io.aerofleet.mavlink.messages.ExtendedSysState;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * 飞行阶段 / 固件版本外露口径（2026-10-29 接线）。
 * <p>
 * 四字段此前只落在 DroneSnapshot 里、不出现在任何视图上，等于前端拿不到；
 * 这里钉住「telemetry 带四字段 + detail 继承 + 未上报时保持可被 UI 区分的哨兵」。
 */
class DroneViewsFlightInfoTest {

    private static final int SW = (1 << 24) | (14 << 16) | 7;

    @Test
    void telemetryCarriesFlightPhaseAndFirmware() {
        DroneSnapshot s = new DroneSnapshot(1);
        s.landedState = ExtendedSysState.LANDED_TAKEOFF;
        s.vtolState = ExtendedSysState.VTOL_UNDEFINED;
        s.flightSwVersion = SW;
        s.firmwareVersion = "1.14.7";

        Map<String, Object> t = DroneViews.telemetry(s);

        assertEquals(ExtendedSysState.LANDED_TAKEOFF, t.get("landedState"));
        assertEquals("起飞中", t.get("landedStateName"));
        assertEquals(SW, t.get("flightSwVersion"));
        assertEquals("1.14.7", t.get("firmwareVersion"));
    }

    @Test
    void unreportedValuesStayDistinguishableFromRealData() {
        Map<String, Object> t = DroneViews.telemetry(new DroneSnapshot(2));

        // -1 / 0 是「尚未收到」的哨兵；名称保持 null，由 UI 决定显示「未知」
        assertEquals(-1, t.get("landedState"));
        assertNull(t.get("landedStateName"));
        assertEquals(0, t.get("flightSwVersion"));
        assertNull(t.get("firmwareVersion"));
    }

    @Test
    void detailViewInheritsTheFourFields() {
        DroneSnapshot s = new DroneSnapshot(3);
        s.landedState = ExtendedSysState.LANDED_LANDING;
        s.flightSwVersion = SW;
        s.firmwareVersion = "1.14.7";

        Map<String, Object> d = DroneViews.detail(s);

        assertEquals("降落中", d.get("landedStateName"));
        assertEquals("1.14.7", d.get("firmwareVersion"));
    }
}
