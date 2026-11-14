package io.aerofleet.cloud.api.dto;

import io.aerofleet.cloud.gateway.DroneSnapshot;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * P1-11 RTK/定位精度字段外露：eph(HDOP*100)/epv(VDOP*100)/satellites 在
 * summary/telemetry/detail/status 视图中一致可见（-1 表示尚未收到）。
 */
class DroneViewsPrecisionFieldsTest {

    @Test
    void summaryCarriesPrecisionFields() {
        DroneSnapshot s = new DroneSnapshot(1);
        s.satellites = 18;
        s.eph = 95;   // HDOP*100
        s.epv = 120;  // VDOP*100

        Map<String, Object> m = DroneViews.summary(s);

        assertEquals(18, m.get("satellites"));
        assertEquals(95, m.get("eph"));
        assertEquals(120, m.get("epv"));
    }

    @Test
    void telemetryAndStatusCarryPrecisionFields() {
        DroneSnapshot s = new DroneSnapshot(2);
        s.satellites = 12;
        s.eph = 150;
        s.epv = 210;

        Map<String, Object> t = DroneViews.telemetry(s);
        assertEquals(12, t.get("satellites"));
        assertEquals(150, t.get("eph"));
        assertEquals(210, t.get("epv"));

        Map<String, Object> st = DroneViews.status(s);
        assertEquals(12, st.get("satellites"));
        assertEquals(150, st.get("eph"));
        assertEquals(210, st.get("epv"));
    }

    @Test
    void unreportedPrecisionFieldsUseSentinel() {
        DroneSnapshot s = new DroneSnapshot(3);

        Map<String, Object> t = DroneViews.telemetry(s);
        assertEquals(-1, t.get("satellites"));
        assertEquals(-1, t.get("eph"));
        assertEquals(-1, t.get("epv"));
    }
}
