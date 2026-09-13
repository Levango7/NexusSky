package io.aerofleet.cloud.vision;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.aerofleet.cloud.gateway.DeviceRegistry;
import io.aerofleet.cloud.mission.DroneCommandService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Intelligent-imaging pipeline (P3-4 scaffold):
 *   1. trigger a shot      (MAV_CMD_IMAGE_START_CAPTURE via the command path)
 *   2. pull shot metadata  (drone-sim ground-truth HTTP /camera/shots)
 *   3. geolocate targets   (GeolocationSolver inverts the pinhole chain)
 *   4. score against truth (sim /targets gives the ground truth positions)
 *
 * The "detector" is the projection itself in this scaffold: whatever the
 * camera saw (projected into the frame) is detected; the solver then answers
 * WHERE it is on the ground. Swapping in a real CV detector later means
 * replacing step 2's target list with model output - steps 3/4 are unchanged.
 */
@Service
public class CaptureService {

    private static final Logger log = LoggerFactory.getLogger(CaptureService.class);

    /** Camera intrinsics - must mirror drone-sim's CameraModel defaults. */
    private static final int IMAGE_W = 1920;
    private static final int IMAGE_H = 1080;
    private static final double HFOV_DEG = 90;
    /** Sim home - must match the sim's --lat/--lon (defaults agree). */
    private static final double HOME_LAT = 22.5907;
    private static final double HOME_LON = 113.9345;
    private static final double M_PER_DEG_LAT = 111_320.0;

    private final DroneCommandService commands;
    private final DeviceRegistry registry;
    private final GeolocationSolver solver;
    private final ObjectMapper mapper = new ObjectMapper();
    private final HttpClient http = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(2)).build();

    /** Ground-truth HTTP base of the drone-sim instance (properties-configurable). */
    private final String simTruthBase;

    public CaptureService(DroneCommandService commands,
                          DeviceRegistry registry,
                          GeolocationSolver solver,
                          @Value("${aerofleet.sim-truth-base:http://127.0.0.1:18080}")
                          String simTruthBase) {
        this.commands = commands;
        this.registry = registry;
        this.solver = solver;
        this.simTruthBase = simTruthBase;
    }

    /**
     * Full capture->locate->score pipeline for one drone.
     * Returns the shot metadata with every detected target geolocated and
     * compared against ground truth (error in meters).
     */
    public Map<String, Object> captureAndLocate(int sysid) throws Exception {
        long before = latestFrameSeq();

        // 1. Trigger the camera (MAV_CMD 2000). The ACK path proves the
        //    camera actually fired (a refused shot surfaces as an exception).
        int result = commands.command(sysid,
                io.aerofleet.mavlink.enums.MavEnums.MAV_CMD_IMAGE_START_CAPTURE,
                0, 1, 1, 0, 0, 0, 0);   // p2: 1 photo, p3: every 1s (single)
        if (result != io.aerofleet.mavlink.enums.MavEnums.MAV_RESULT_ACCEPTED) {
            throw new DroneCommandService.CommandException(
                    "camera refused capture: MAV_RESULT=" + result, result);
        }

        // 2. Wait for the new frame to appear on the truth channel.
        JsonNode shot = awaitNewShot(before, 5_000);
        if (shot == null) {
            throw new DroneCommandService.CommandException("no shot arrived from sim", -1);
        }

        // 3. Geolocate every detected target.
        double[] camNe = latLonToNe(shot.path("lat").asDouble(), shot.path("lon").asDouble());
        List<Map<String, Object>> detections = new ArrayList<>();
        for (JsonNode t : shot.path("targets")) {
            GeolocationSolver.Result r = solver.solve(
                    t.path("u").asDouble(), t.path("v").asDouble(),
                    IMAGE_W, IMAGE_H, HFOV_DEG,
                    HOME_LAT, HOME_LON,
                    camNe[0], camNe[1],
                    shot.path("altM").asDouble(),
                    shot.path("droneRollDeg").asDouble(),
                    shot.path("dronePitchDeg").asDouble(),
                    shot.path("droneYawDeg").asDouble(),
                    shot.path("gimbalPitchDeg").asDouble(),
                    shot.path("gimbalYawDeg").asDouble());
            if (r == null) {
                continue;
            }
            Map<String, Object> d = new HashMap<>();
            d.put("kind", t.path("kind").asText());
            d.put("pixel", Map.of("u", t.path("u").asDouble(), "v", t.path("v").asDouble()));
            d.put("lat", r.lat);
            d.put("lon", r.lon);
            d.put("groundRangeM", Math.round(r.groundRangeM * 10) / 10.0);

            // 4. Score against truth: find the same target id in /targets.
            double errM = truthErrorM(t.path("id").asInt(), r.lat, r.lon);
            d.put("id", t.path("id").asInt());
            d.put("truthErrorM", Math.round(errM * 10) / 10.0);
            detections.add(d);
        }

        Map<String, Object> out = new HashMap<>();
        out.put("sysid", sysid);
        out.put("frameSeq", shot.path("frameSeq").asLong());
        out.put("shotLat", shot.path("lat").asDouble());
        out.put("shotLon", shot.path("lon").asDouble());
        out.put("altM", shot.path("altM").asDouble());
        out.put("detections", detections);
        log.info("captureAndLocate sysid={} frame={} -> {} detection(s)",
                sysid, shot.path("frameSeq").asLong(), detections.size());
        return out;
    }

    // ---- helpers ----

    private double[] latLonToNe(double lat, double lon) {
        return new double[]{
                (lat - HOME_LAT) * M_PER_DEG_LAT,
                (lon - HOME_LON) * M_PER_DEG_LAT * Math.cos(Math.toRadians(HOME_LAT))};
    }

    private long latestFrameSeq() throws Exception {
        JsonNode shots = getJson(simTruthBase + "/camera/shots");
        if (!shots.isArray() || shots.isEmpty()) {
            return 0;
        }
        return shots.get(shots.size() - 1).path("frameSeq").asLong();
    }

    /** Poll the truth channel until a frame newer than {@code before} lands. */
    private JsonNode awaitNewShot(long before, long timeoutMs) throws Exception {
        long deadline = System.currentTimeMillis() + timeoutMs;
        while (System.currentTimeMillis() < deadline) {
            JsonNode shots = getJson(simTruthBase + "/camera/shots");
            for (JsonNode s : shots) {
                if (s.path("frameSeq").asLong() > before) {
                    return s;
                }
            }
            Thread.sleep(100);
        }
        return null;
    }

    /** Ground-truth position error of a detected target, in meters. */
    private double truthErrorM(int targetId, double lat, double lon) {
        try {
            for (JsonNode t : getJson(simTruthBase + "/targets")) {
                if (t.path("id").asInt() == targetId) {
                    double dn = (lat - t.path("lat").asDouble()) * M_PER_DEG_LAT;
                    double de = (lon - t.path("lon").asDouble()) * M_PER_DEG_LAT
                            * Math.cos(Math.toRadians(HOME_LAT));
                    return Math.hypot(dn, de);
                }
            }
        } catch (Exception e) {
            log.debug("truth fetch failed: {}", e.getMessage());
        }
        return -1; // truth unavailable
    }

    private JsonNode getJson(String url) throws Exception {
        HttpResponse<String> resp = http.send(
                HttpRequest.newBuilder(URI.create(url)).GET().build(),
                HttpResponse.BodyHandlers.ofString());
        if (resp.statusCode() != 200) {
            throw new IllegalStateException("sim truth HTTP " + resp.statusCode());
        }
        return mapper.readTree(resp.body());
    }
}
