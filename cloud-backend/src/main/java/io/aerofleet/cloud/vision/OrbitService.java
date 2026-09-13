package io.aerofleet.cloud.vision;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.aerofleet.cloud.mission.DroneCommandService;
import io.aerofleet.cloud.mission.MissionItemRequest;
import io.aerofleet.cloud.mission.MissionUploadResult;
import io.aerofleet.mavlink.enums.MavEnums;
import io.aerofleet.mavlink.messages.MissionItemInt;
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
 * Auto-redirect orbit (P3 follow-up, problem 1): fly a circle around a
 * point, take a photo at every arc waypoint, geolocate targets, and feed
 * the tracker so the operator sees a track history per target.
 *
 * Mission shape: [takeoff, (waypoint, capture) x 8 around the circle, rtl].
 * Progress is observed on the drone-sim truth channel (/camera/shots) -
 * simpler and more robust than parsing MISSION_CURRENT, and the truth HTTP
 * sidecar is already a hard dependency of the capture pipeline.
 */
@Service
public class OrbitService {

    private static final Logger log = LoggerFactory.getLogger(OrbitService.class);

    /** Camera intrinsics - must mirror drone-sim's CameraModel defaults. */
    private static final int IMAGE_W = 1920;
    private static final int IMAGE_H = 1080;
    private static final double HFOV_DEG = 90;
    private static final double HOME_LAT = 22.5907;
    private static final double HOME_LON = 113.9345;
    private static final double M_PER_DEG_LAT = 111_320.0;
    private static final double M_PER_DEG_LON = M_PER_DEG_LAT * Math.cos(Math.toRadians(HOME_LAT));

    private final DroneCommandService commands;
    private final GeolocationSolver solver;
    private final TargetTracker tracker;
    private final ObjectMapper mapper = new ObjectMapper();
    private final HttpClient http = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(2)).build();
    private final String simTruthBase;

    public OrbitService(DroneCommandService commands,
                        GeolocationSolver solver,
                        TargetTracker tracker,
                        @Value("${aerofleet.sim-truth-base:http://127.0.0.1:18080}")
                        String simTruthBase) {
        this.commands = commands;
        this.solver = solver;
        this.tracker = tracker;
        this.simTruthBase = simTruthBase;
    }

    /**
     * Fly a circle around (lat, lon) at {@code altM}, shooting
     * {@code photos} photos evenly around the arc. Called from an
     * {@link OrbitJobManager} worker thread; each completed station is pushed
     * into {@code progressOut} so polling clients see per-station increments.
     */
    public Map<String, Object> orbitAndTrack(int sysid, double lat, double lon,
                                             double radiusM, double altM, int photos,
                                             List<Map<String, Object>> progressOut) throws Exception {
        if (radiusM <= 0 || radiusM > 500) {
            throw new IllegalArgumentException("radiusM must be in (0, 500]");
        }
        if (altM < 20 || altM > 120) {
            throw new IllegalArgumentException("altM must be in [20, 120] (legal/safe band)");
        }
        if (photos < 2 || photos > 12) {
            throw new IllegalArgumentException("photos must be in [2, 12]");
        }

        long before = latestFrameSeq();
        List<MissionItemRequest> plan = buildCircle(lat, lon, radiusM, altM, photos);
        List<MissionItemInt> items = commands.toMissionItems(plan, sysid);
        MissionUploadResult upload = commands.uploadMission(sysid, items);
        if (!"ok".equals(upload.status())) {
            throw new IllegalStateException("mission upload rejected: " + upload.error());
        }
        log.info("orbit sysid={} mission uploaded ({} items)", sysid, items.size());

        // Arm + start on the ground; e2e runs this right after boot.
        try {
            commands.arm(sysid);
        } catch (Exception e) {
            log.info("orbit sysid={} arm skipped (already in flight?): {}", sysid, e.getMessage());
        }
        commands.startMission(sysid);

        // Wait for all photos: the truth channel is the source of truth (sic)
        // for capture progress - MISSION_CURRENT parsing would add protocol
        // surface without adding information here.
        List<Map<String, Object>> shotReports = new ArrayList<>();
        long deadline = System.currentTimeMillis() + 240_000; // 8 waypoints ~ 2 min + margin
        while (shotReports.size() < photos && System.currentTimeMillis() < deadline) {
            Thread.sleep(500);
            for (JsonNode s : newShots(before)) {
                if (shotReports.size() >= photos) {
                    break;
                }
                Map<String, Object> rep = locateAndFeed(sysid, s);
                shotReports.add(rep);
                if (progressOut != null) {
                    progressOut.add(rep);
                }
                before = Math.max(before, s.path("frameSeq").asLong());
            }
        }

        Map<String, Object> out = new HashMap<>();
        out.put("sysid", sysid);
        out.put("center", Map.of("lat", lat, "lon", lon, "radiusM", radiusM, "altM", altM));
        out.put("photosRequested", photos);
        out.put("photosTaken", shotReports.size());
        out.put("shots", shotReports);
        out.put("tracks", tracker.tracksOf(sysid).stream()
                .map(this::trackView).toList());
        log.info("orbit sysid={} done: {}/{} photos, {} track(s)",
                sysid, shotReports.size(), photos, tracker.tracksOf(sysid).size());
        return out;
    }

    /** REST view of one track. */
    private Map<String, Object> trackView(TargetTracker.Track t) {
        Map<String, Object> m = new HashMap<>();
        m.put("trackId", t.trackId);
        m.put("state", t.state.name());
        m.put("hits", t.hits);
        m.put("kind", t.last.kind);
        m.put("lastSeen", Map.of(
                "frameSeq", t.last.frameSeq,
                "lat", t.last.lat,
                "lon", t.last.lon));
        double[] pred = t.predictLatLon(System.currentTimeMillis());
        m.put("predicted", Map.of("lat", pred[0], "lon", pred[1]));
        return m;
    }

    /**
     * [takeoff, (waypoint + capture) x photos, rtl] around the circle.
     * First arc point is due north of the center, going clockwise.
     * Arc waypoints hold 2.5 s: the drone arrives braking (body pitch ~10deg
     * which shifts the nadir frame center ~11 m forward) - holding lets the
     * attitude settle to wobble-only so the camera looks straight down again.
     */
    List<MissionItemRequest> buildCircle(double lat, double lon,
                                         double radiusM, double altM, int photos) {
        List<MissionItemRequest> plan = new ArrayList<>(photos * 2 + 2);
        plan.add(new MissionItemRequest("takeoff", HOME_LAT, HOME_LON, altM, 0));
        for (int i = 0; i < photos; i++) {
            double ang = 2 * Math.PI * i / photos;
            double dn = radiusM * Math.cos(ang);
            double de = radiusM * Math.sin(ang);
            double pLat = lat + dn / M_PER_DEG_LAT;
            double pLon = lon + de / M_PER_DEG_LON;
            plan.add(new MissionItemRequest("waypoint", pLat, pLon, altM, 2.5));
            plan.add(new MissionItemRequest("capture", pLat, pLon, altM, 0));
        }
        plan.add(new MissionItemRequest("rtl", HOME_LAT, HOME_LON, 0, 0));
        return plan;
    }

    /** Solve + truth-compare one shot, then feed the tracker. */
    private Map<String, Object> locateAndFeed(int sysid, JsonNode shot) throws Exception {
        double[] camNe = latLonToNe(shot.path("lat").asDouble(), shot.path("lon").asDouble());
        List<TargetTracker.Observation> obs = new ArrayList<>();
        List<Map<String, Object>> detections = new ArrayList<>();
        long frameSeq = shot.path("frameSeq").asLong();
        long tMs = System.currentTimeMillis();
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
            obs.add(new TargetTracker.Observation(
                    frameSeq, tMs, r.lat, r.lon, t.path("kind").asText()));
            Map<String, Object> d = new HashMap<>();
            d.put("id", t.path("id").asInt());
            d.put("kind", t.path("kind").asText());
            d.put("pixel", Map.of("u", t.path("u").asDouble(), "v", t.path("v").asDouble()));
            d.put("lat", r.lat);
            d.put("lon", r.lon);
            d.put("groundRangeM", Math.round(r.groundRangeM * 10) / 10.0);
            d.put("truthErrorM", Math.round(truthErrorM(t.path("id").asInt(), r.lat, r.lon) * 10) / 10.0);
            detections.add(d);
        }
        if (!obs.isEmpty()) {
            tracker.ingest(sysid, obs);
        }
        Map<String, Object> rep = new HashMap<>();
        rep.put("frameSeq", frameSeq);
        rep.put("shotLat", shot.path("lat").asDouble());
        rep.put("shotLon", shot.path("lon").asDouble());
        rep.put("altM", shot.path("altM").asDouble());
        rep.put("droneRollDeg", shot.path("droneRollDeg").asDouble());
        rep.put("dronePitchDeg", shot.path("dronePitchDeg").asDouble());
        rep.put("droneYawDeg", shot.path("droneYawDeg").asDouble());
        rep.put("detections", detections);
        return rep;
    }

    // ---- truth-HTTP helpers (same pattern as CaptureService) ----

    private double[] latLonToNe(double lat, double lon) {
        return new double[]{
                (lat - HOME_LAT) * M_PER_DEG_LAT,
                (lon - HOME_LON) * M_PER_DEG_LON};
    }

    private long latestFrameSeq() throws Exception {
        JsonNode shots = getJson(simTruthBase + "/camera/shots");
        if (!shots.isArray() || shots.isEmpty()) {
            return 0;
        }
        return shots.get(shots.size() - 1).path("frameSeq").asLong();
    }

    /** Shots strictly newer than {@code before}. */
    private List<JsonNode> newShots(long before) throws Exception {
        List<JsonNode> out = new ArrayList<>();
        for (JsonNode s : getJson(simTruthBase + "/camera/shots")) {
            if (s.path("frameSeq").asLong() > before) {
                out.add(s);
            }
        }
        return out;
    }

    private double truthErrorM(int targetId, double lat, double lon) {
        try {
            for (JsonNode t : getJson(simTruthBase + "/targets")) {
                if (t.path("id").asInt() == targetId) {
                    double dn = (lat - t.path("lat").asDouble()) * M_PER_DEG_LAT;
                    double de = (lon - t.path("lon").asDouble()) * M_PER_DEG_LON;
                    return Math.hypot(dn, de);
                }
            }
        } catch (Exception e) {
            log.debug("truth fetch failed: {}", e.getMessage());
        }
        return -1;
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
