package io.aerofleet.cloud.api.controller;

import io.aerofleet.cloud.security.RequireRole;
import io.aerofleet.cloud.security.Role;
import io.aerofleet.cloud.vision.CaptureService;
import io.aerofleet.cloud.vision.OrbitJobManager;
import io.aerofleet.cloud.vision.OrbitService;
import io.aerofleet.cloud.vision.TargetTracker;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
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
    private final OrbitJobManager orbitJobs;
    private final TargetTracker tracker;

    public VisionController(CaptureService capture, OrbitService orbit,
                            OrbitJobManager orbitJobs, TargetTracker tracker) {
        this.capture = capture;
        this.orbit = orbit;
        this.orbitJobs = orbitJobs;
        this.tracker = tracker;
    }

    /**
     * Trigger a photo on the drone, then geolocate every detected target
     * (pixel -> ground lat/lon) and score the result against the simulated
     * ground truth. Requires the drone-sim truth HTTP (--http-port).
     * ?source=pixels runs the E2 pixel pipeline (real JPEG -> blob detect);
     * default = the configured aerofleet.vision.source.
     */
    @PostMapping("/drones/{sysid}/capture")
    @RequireRole(Role.OPERATOR)
    public Map<String, Object> captureAndLocate(@PathVariable("sysid") int sysid,
                                                @RequestParam(value = "source", required = false)
                                                String source) {
        try {
            return capture.captureAndLocate(sysid, source);
        } catch (Exception e) {
            log.warn("vision pipeline failed for sysid={}: {}", sysid, e.getMessage());
            return Map.of("status", "error", "result", e.getMessage());
        }
    }

    /**
     * Start an auto-redirect orbit ASYNC (D1): returns 202 + jobId instantly;
     * the 2-minute flight runs on the orbit pool. Poll
     * GET /vision/jobs/{jobId} for per-station progress and the final result.
     * Body: {"lat":22.5912,"lon":113.935,"radiusM":25,"altM":60,"photos":4}.
     * One in-flight job per drone: a repeat POST while running -> 409.
     */
    @PostMapping("/drones/{sysid}/orbit")
    @RequireRole(Role.OPERATOR)
    public ResponseEntity<Map<String, Object>> orbit(@PathVariable("sysid") int sysid,
                                                     @RequestBody Map<String, Object> body) {
        double lat = num(body, "lat");
        double lon = num(body, "lon");
        double radiusM = num(body, "radiusM", 25);
        double altM = num(body, "altM", 60);
        int photos = (int) num(body, "photos", 4);
        try {
            OrbitJobManager.OrbitJob job = orbitJobs.submit(sysid, lat, lon, radiusM, altM, photos);
            return ResponseEntity.accepted().body(Map.of(
                    "jobId", job.id,
                    "state", job.state.name(),
                    "photosRequested", job.photosRequested,
                    "sysid", sysid));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(
                    Map.of("status", "error", "result", e.getMessage()));
        } catch (IllegalStateException e) {
            return ResponseEntity.status(409).body(
                    Map.of("status", "error", "result", e.getMessage()));
        }
    }

    /** Poll an orbit job: state, per-station progress, final result. */
    @GetMapping("/jobs/{jobId}")
    public ResponseEntity<Map<String, Object>> job(@PathVariable("jobId") String jobId) {
        OrbitJobManager.OrbitJob j = orbitJobs.get(jobId);
        if (j == null) {
            return ResponseEntity.status(404).body(
                    Map.of("status", "error", "result", "unknown job " + jobId));
        }
        return ResponseEntity.ok(orbitJobs.viewOf(j));
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
