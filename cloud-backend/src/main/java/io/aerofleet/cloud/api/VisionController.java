package io.aerofleet.cloud.api;

import io.aerofleet.cloud.vision.CaptureService;
import io.aerofleet.cloud.vision.OrbitService;
import io.aerofleet.cloud.vision.TargetTracker;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * Intelligent imaging endpoints (P3 + batch A): capture -> geolocate ->
 * truth-score; orbit -> per-photo tracking; live track query.
 */
@RestController
@RequestMapping("/api/v1/vision")
public class VisionController {

    private static final Logger log = LoggerFactory.getLogger(VisionController.class);

    private final CaptureService capture;
    private final OrbitService orbit;
    private final TargetTracker tracker;

    public VisionController(CaptureService capture, OrbitService orbit, TargetTracker tracker) {
        this.capture = capture;
        this.orbit = orbit;
        this.tracker = tracker;
    }

    /**
     * Trigger a photo on the drone, then geolocate every detected target
     * (pixel -> ground lat/lon) and score the result against the simulated
     * ground truth. Requires the drone-sim truth HTTP (--http-port).
     */
    @PostMapping("/drones/{sysid}/capture")
    public Map<String, Object> captureAndLocate(@PathVariable("sysid") int sysid) {
        try {
            return capture.captureAndLocate(sysid);
        } catch (Exception e) {
            log.warn("vision pipeline failed for sysid={}: {}", sysid, e.getMessage());
            return Map.of("status", "error", "result", e.getMessage());
        }
    }

    /**
     * Fly an auto-redirect orbit: upload a circular mission around
     * (lat,lon), arm + start, take a photo at every arc waypoint, geolocate
     * each shot, and feed the tracker. Returns shots + track snapshot.
     * Body: {"lat":22.5912,"lon":113.935,"radiusM":50,"altM":60,"photos":4}
     */
    @PostMapping("/drones/{sysid}/orbit")
    public Map<String, Object> orbit(@PathVariable("sysid") int sysid,
                                     @RequestBody Map<String, Object> body) {
        double lat = num(body, "lat");
        double lon = num(body, "lon");
        double radiusM = num(body, "radiusM", 50);
        double altM = num(body, "altM", 60);
        int photos = (int) num(body, "photos", 4);
        try {
            return orbit.orbitAndTrack(sysid, lat, lon, radiusM, altM, photos);
        } catch (IllegalArgumentException e) {
            return Map.of("status", "error", "result", e.getMessage());
        } catch (Exception e) {
            log.warn("orbit failed for sysid={}: {}", sysid, e.getMessage());
            return Map.of("status", "error", "result", e.getMessage());
        }
    }

    /** Current tracks of one drone (id, state, hits, last seen, prediction). */
    @GetMapping("/drones/{sysid}/tracks")
    public Map<String, Object> tracks(@PathVariable("sysid") int sysid) {
        return Map.of("sysid", sysid, "tracks", tracker.tracksOf(sysid).stream()
                .map(t -> Map.of(
                        "trackId", t.trackId,
                        "state", t.state.name(),
                        "hits", t.hits,
                        "kind", t.last.kind,
                        "lastSeen", Map.of("lat", t.last.lat, "lon", t.last.lon),
                        "predicted", Map.of(
                                "lat", t.predictLatLon(System.currentTimeMillis())[0],
                                "lon", t.predictLatLon(System.currentTimeMillis())[1])))
                .toList());
    }

    private static double num(Map<String, Object> body, String key) {
        Object v = body.get(key);
        if (v instanceof Number n) {
            return n.doubleValue();
        }
        throw new IllegalArgumentException("missing numeric field '" + key + "'");
    }

    private static double num(Map<String, Object> body, String key, double dflt) {
        Object v = body.get(key);
        return v instanceof Number n ? n.doubleValue() : dflt;
    }
}
