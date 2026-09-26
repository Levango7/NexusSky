package io.aerofleet.cloud.flightlog;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.aerofleet.cloud.gateway.AlertEntry;
import io.aerofleet.cloud.gateway.DroneSnapshot;
import io.aerofleet.cloud.gateway.TrackPoint;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Flight-log persistence: JSON Lines files under a configurable directory,
 * one file per UTC day, one line per event.
 *
 *   { "t": "...", "type": "telemetry", "sysid": 1, "alt": 42.3, ... }
 *   { "t": "...", "type": "alert",     "sysid": 1, "severity": 6, "text": "..." }
 *   { "t": "...", "type": "mission",  "sysid": 1, "text": "uploaded 4 items" }
 *
 * The write path is fire-and-forget on the calling thread (single JSON line,
 * small); a failed append logs and drops rather than disturbing the caller.
 * Query path: REST reads today's (or a given day's) file back, newest-first
 * optional. This is the scaffold-honest persistence: no DB dependency, files
 * are grep-able, swapping to SQLite/Postgres later is a package change.
 */
@Component
public class FlightLogService {

    private static final Logger log = LoggerFactory.getLogger(FlightLogService.class);
    private static final DateTimeFormatter TS = DateTimeFormatter.ISO_LOCAL_DATE_TIME;

    private final ObjectMapper mapper;
    private final Path dir;
    /** Track points are written at most this often per drone (ms). */
    private final long trackMinIntervalMs;

    /** sysid -> last track-point write, for the telemetry throttle. */
    private final Map<Integer, Long> lastTrackWrite = new HashMap<>();

    public FlightLogService(
            @Value("${aerofleet.flightlog.dir:./flight-logs}") String dir,
            @Value("${aerofleet.flightlog.track-interval-ms:1000}") long trackMinIntervalMs,
            ObjectMapper objectMapper) {
        this.dir = Path.of(dir);
        this.trackMinIntervalMs = trackMinIntervalMs;
        this.mapper = objectMapper;
        try {
            Files.createDirectories(this.dir);
            log.info("flight log directory: {}", this.dir.toAbsolutePath());
        } catch (IOException e) {
            log.error("flight log directory unusable ({}): {}", dir, e.getMessage());
            throw new UncheckedIOException(e);
        }
    }

    /** File for a given UTC date. */
    private Path fileFor(LocalDate day) {
        return dir.resolve("flight-" + day + ".jsonl");
    }

    private void append(Map<String, Object> event) {
        try {
            String line = mapper.writeValueAsString(event) + "\n";
            Files.writeString(fileFor(LocalDate.now()), line, StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        } catch (IOException e) {
            log.debug("flight log append failed: {}", e.getMessage());
        }
    }

    private static String now() {
        return LocalDateTime.now().format(TS);
    }

    // ---- writers ----

    /** Throttled telemetry snapshot (1 per drone per interval). */
    public void telemetry(DroneSnapshot s) {
        Long last = lastTrackWrite.get(s.sysid);
        long now = System.currentTimeMillis();
        if (last != null && now - last < trackMinIntervalMs) {
            return;
        }
        lastTrackWrite.put(s.sysid, now);
        Map<String, Object> e = base("telemetry", s.sysid);
        e.put("lat", nanToNull(s.lat));
        e.put("lon", nanToNull(s.lon));
        e.put("relativeAlt", nanToNull(s.relativeAlt));
        e.put("groundspeed", nanToNull(s.groundspeed));
        e.put("battery", s.battery);
        e.put("voltage", s.voltage);
        e.put("mode", s.mode);
        e.put("armed", s.armed);
        e.put("online", s.online);
        append(e);
    }

    /** Alert event: always written immediately. */
    public void alert(int sysid, AlertEntry entry) {
        Map<String, Object> e = base("alert", sysid);
        e.put("severity", entry.severity);
        e.put("text", entry.text);
        append(e);
    }

    /** Mission lifecycle event: upload/ack/commands worth remembering. */
    public void mission(int sysid, String text) {
        Map<String, Object> e = base("mission", sysid);
        e.put("text", text);
        append(e);
    }

    /** Online/offline transition. */
    public void connectivity(int sysid, boolean online) {
        Map<String, Object> e = base("connectivity", sysid);
        e.put("online", online);
        append(e);
    }

    private static Map<String, Object> base(String type, int sysid) {
        Map<String, Object> e = new HashMap<>();
        e.put("t", now());
        e.put("type", type);
        e.put("sysid", sysid);
        return e;
    }

    private static Object nanToNull(double v) {
        return Double.isNaN(v) ? null : v;
    }

    // ---- readers (REST) ----

    /**
     * Read events of one type (or all) for a day, optionally filtered by
     * sysid, limited to the most recent {@code limit} entries.
     */
    public List<Map<String, Object>> query(LocalDate day, String type, Integer sysid, int limit) {
        Path f = fileFor(day);
        if (!Files.isReadable(f)) {
            return List.of();
        }
        List<Map<String, Object>> out = new ArrayList<>();
        try {
            for (String line : Files.readAllLines(f, StandardCharsets.UTF_8)) {
                if (line.isBlank()) {
                    continue;
                }
                try {
                    @SuppressWarnings("unchecked")
                    Map<String, Object> m = mapper.readValue(line, Map.class);
                    if (type != null && !type.equals(m.get("type"))) {
                        continue;
                    }
                    if (sysid != null && sysid.intValue() != ((Number) m.get("sysid")).intValue()) {
                        continue;
                    }
                    out.add(m);
                } catch (IOException badLine) {
                    // skip malformed line
                }
            }
        } catch (IOException e) {
            log.warn("flight log read failed: {}", e.getMessage());
            return List.of();
        }
        if (limit > 0 && out.size() > limit) {
            return out.subList(out.size() - limit, out.size());
        }
        return out;
    }

    /** Track points for a drone on a day, reconstructed from telemetry lines. */
    public List<TrackPoint> trackFor(LocalDate day, int sysid) {
        List<TrackPoint> pts = new ArrayList<>();
        for (Map<String, Object> m : query(day, "telemetry", sysid, 0)) {
            Object lat = m.get("lat");
            Object lon = m.get("lon");
            if (lat == null || lon == null) {
                continue;
            }
            double alt = m.get("relativeAlt") instanceof Number n ? n.doubleValue() : 0;
            pts.add(new TrackPoint(((Number) lat).doubleValue(), ((Number) lon).doubleValue(),
                    alt, 0));
        }
        return pts;
    }
}
