package io.aerofleet.cloud.flightlog;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.aerofleet.cloud.gateway.AlertEntry;
import io.aerofleet.cloud.gateway.DroneSnapshot;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * FlightLogService unit tests (batch B3): write/read-back round trip,
 * telemetry throttle, type/sysid filters, track reconstruction, malformed
 * line tolerance. Pure file IO under @TempDir - no Spring context.
 */
class FlightLogServiceTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @TempDir
    Path tmp;

    private static DroneSnapshot snap(int sysid, double lat, double lon, double alt) {
        DroneSnapshot s = new DroneSnapshot(sysid);
        s.lat = lat;
        s.lon = lon;
        s.relativeAlt = alt;
        s.battery = 80;
        s.voltage = 15000;
        s.mode = "MISSION";
        s.armed = true;
        s.online = true;
        return s;
    }

    @Test
    void telemetryRoundTripsThroughFile() throws Exception {
        FlightLogService svc = new FlightLogService(tmp.toString(), 0, MAPPER);
        svc.telemetry(snap(1, 22.5907, 113.9345, 42.0));
        Path f = tmp.resolve("flight-" + LocalDate.now() + ".jsonl");
        assertTrue(Files.exists(f), "daily file created");
        List<Map<String, Object>> rows = svc.query(LocalDate.now(), "telemetry", 1, 0);
        assertEquals(1, rows.size());
        assertEquals(22.5907, (double) rows.get(0).get("lat"), 1e-9);
        assertEquals("MISSION", rows.get(0).get("mode"));
        assertEquals(true, rows.get(0).get("armed"));
    }

    @Test
    void telemetryThrottleDropsRapidDuplicates() {
        FlightLogService svc = new FlightLogService(tmp.toString(), 60_000, MAPPER);
        svc.telemetry(snap(1, 22.5, 113.9, 10));
        svc.telemetry(snap(1, 22.6, 113.9, 20));   // within the window: dropped
        svc.telemetry(snap(2, 22.7, 113.9, 30));   // other drone: written
        assertEquals(1, svc.query(LocalDate.now(), "telemetry", 1, 0).size(),
                "sysid 1 throttled to one line");
        assertEquals(1, svc.query(LocalDate.now(), "telemetry", 2, 0).size(),
                "sysid 2 independent throttle namespace");
    }

    @Test
    void alertAndMissionAlwaysWritten() {
        FlightLogService svc = new FlightLogService(tmp.toString(), 60_000, MAPPER);
        svc.alert(3, new AlertEntry(6, "low battery", System.currentTimeMillis()));
        svc.mission(3, "uploaded 4 items");
        assertEquals(1, svc.query(LocalDate.now(), "alert", 3, 0).size());
        assertEquals(1, svc.query(LocalDate.now(), "mission", 3, 0).size());
        assertEquals("low battery",
                svc.query(LocalDate.now(), "alert", 3, 0).get(0).get("text"));
    }

    @Test
    void limitReturnsNewestTail() {
        FlightLogService svc = new FlightLogService(tmp.toString(), 0, MAPPER);
        for (int i = 0; i < 10; i++) {
            svc.mission(1, "event " + i);
        }
        List<Map<String, Object>> tail = svc.query(LocalDate.now(), "mission", 1, 3);
        assertEquals(3, tail.size(), "limited to the newest 3");
        assertEquals("event 9", tail.get(2).get("text"), "tail keeps order");
    }

    @Test
    void trackForRebuildsFromTelemetryLines() {
        FlightLogService svc = new FlightLogService(tmp.toString(), 0, MAPPER);
        svc.telemetry(snap(1, 22.5907, 113.9345, 10));
        svc.telemetry(snap(1, 22.5917, 113.9355, 20));
        svc.alert(1, new AlertEntry(6, "noise", System.currentTimeMillis()));   // not a track point
        assertEquals(2, svc.trackFor(LocalDate.now(), 1).size(),
                "two telemetry points reconstructed");
        assertEquals(22.5917, svc.trackFor(LocalDate.now(), 1).get(1).lat, 1e-9);
    }

    @Test
    void nanFieldsSerializeAsNullAndSkippedInTrack() {
        FlightLogService svc = new FlightLogService(tmp.toString(), 0, MAPPER);
        DroneSnapshot s = new DroneSnapshot(4);   // lat/lon stay NaN
        svc.telemetry(s);
        List<Map<String, Object>> rows = svc.query(LocalDate.now(), "telemetry", 4, 0);
        assertEquals(1, rows.size());
        assertNull(rows.get(0).get("lat"), "NaN serialized as null");
        assertTrue(svc.trackFor(LocalDate.now(), 4).isEmpty(),
                "NaN rows are not track points");
    }

    @Test
    void missingDayYieldsEmpty() {
        FlightLogService svc = new FlightLogService(tmp.toString(), 0, MAPPER);
        assertTrue(svc.query(LocalDate.of(2020, 1, 1), null, null, 0).isEmpty());
        assertTrue(svc.trackFor(LocalDate.of(2020, 1, 1), 1).isEmpty());
    }
}
