package io.aerofleet.cloud.vision;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.aerofleet.cloud.gateway.DeviceRegistry;
import io.aerofleet.cloud.mission.common.DroneCommandService;
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
 * The "detector" has two sources (batch E2):
 *   - "truth": the projection itself (what the camera saw in the frame),
 *     the original scaffold;
 *   - "pixels": a REAL JPEG rendered by the sim, fed through BlobDetector
 *     (pure-JDK blob finder) so the chain runs on pixels, not metadata.
 * Both feed the same solver + truth-scoring steps.
 */
@Service
public class CaptureService {

    private static final Logger log = LoggerFactory.getLogger(CaptureService.class);

    /** Camera intrinsics - must mirror drone-sim's CameraModel defaults. */
    private static final int IMAGE_W = 1920;
    private static final int IMAGE_H = 1080;
    private static final double HFOV_DEG = 90;
    /** Rendered JPEG resolution (matches ShotImageWriter's uniform 1/3 scale). */
    private static final int JPEG_W = 640;
    private static final int JPEG_H = 360;
    /** Sim home - must match the sim's --lat/--lon (defaults agree). */
    private static final double HOME_LAT = 22.5907;
    private static final double HOME_LON = 113.9345;
    private static final double M_PER_DEG_LAT = 111_320.0;

    private final DroneCommandService commands;
    private final DeviceRegistry registry;
    private final GeolocationSolver solver;
    private final BlobDetector detector = new BlobDetector();
    private final ObjectMapper mapper;
    private final HttpClient http = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(2)).build();
    /** CV 评测指标层（F1）：拍摄主路径的纯旁路（spec N3）。 */
    private final CvEvalService eval;
    private final io.aerofleet.cloud.defect.DefectService defectService;

    /** Ground-truth HTTP base of the drone-sim instance (properties-configurable). */
    private final String simTruthBase;
    /** "truth" / "vision-source" / "pixels" / "external"（spec U1）。 */
    private final String source;
    /** M3 VisionSource 抽象层（FR-03）：truth/vision-source/external 时使用，null 表示走既有 pixels 路径。 */
    private final VisionSource visionSource;
    /** 外部推理服务源（F1，source=external 且 endpoint 配置齐全时才存在）。 */
    private final ExternalVisionSource externalSource;
    /** 投影简化降级实例（异常 5.1.2：VisionSource 异常时回退）。 */
    private final ProjectionVisionSource fallback = new ProjectionVisionSource();

    public CaptureService(DroneCommandService commands,
                          DeviceRegistry registry,
                          GeolocationSolver solver,
                          ObjectMapper objectMapper,
                          CvEvalService eval,
                          io.aerofleet.cloud.defect.DefectService defectService,
                          org.springframework.beans.factory.ObjectProvider<ExternalVisionSource> externalSourceProvider,
                          @Value("${aerofleet.sim-truth-base:http://127.0.0.1:18080}") String simTruthBase,
                          @Value("${aerofleet.vision.source:truth}") String source) {
        this.commands = commands;
        this.registry = registry;
        this.solver = solver;
        this.mapper = objectMapper;
        this.eval = eval;
        this.defectService = defectService;
        this.simTruthBase = simTruthBase;
        this.externalSource = externalSourceProvider.getIfAvailable();
        // source=external 但条件 Bean 缺失（endpoint 未配置）→ 启动时 WARN 回退 truth（spec N2）
        String effectiveSource = source;
        if ("external".equalsIgnoreCase(source) && externalSource == null) {
            log.warn("aerofleet.vision.source=external but ExternalVisionSource bean is absent "
                    + "(endpoint not configured?) — falling back to truth (check N2)");
            effectiveSource = "truth";
        }
        this.source = effectiveSource;
        // FR-03 投影简化切换：truth→ProjectionVisionSource / vision-source→SimulatedVisionSource
        // external→ExternalVisionSource / pixels→null
        this.visionSource = switch (effectiveSource) {
            case "truth" -> new ProjectionVisionSource();        // 投影简化（默认，既有行为）
            case "vision-source" -> new SimulatedVisionSource(); // 模拟检测（合成置信度）
            case "pixels" -> null;                               // 既有 BlobDetector 路径
            case "external" -> externalSource;                   // F1 外部推理服务（非 null 已保证）
            default -> new ProjectionVisionSource();             // 未知值默认投影简化
        };
    }

    /**
     * Full capture->locate->score pipeline for one drone.
     * Returns the shot metadata with every detected target geolocated and
     * compared against ground truth (error in meters).
     */
    public Map<String, Object> captureAndLocate(int sysid) throws Exception {
        return captureAndLocate(sysid, null);
    }

    /**
     * Full capture->locate->score pipeline for one drone.
     * Returns the shot metadata with every detected target geolocated and
     * compared against ground truth (error in meters). When
     * {@code sourceOverride} is "pixels", runs the E2 JPEG->blob pipeline.
     */
    public Map<String, Object> captureAndLocate(int sysid, String sourceOverride) throws Exception {
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
        double alt = shot.path("altM").asDouble();
        double roll = shot.path("droneRollDeg").asDouble();
        double pitch = shot.path("dronePitchDeg").asDouble();
        double yaw = shot.path("droneYawDeg").asDouble();
        double gpitch = shot.path("gimbalPitchDeg").asDouble();
        double gyaw = shot.path("gimbalYawDeg").asDouble();

        String effSource = sourceOverride != null ? sourceOverride : source;
        long detectStartNanos = System.nanoTime();
        DetectionBatch batch;
        if ("pixels".equalsIgnoreCase(effSource)) {
            batch = detectFromPixels(shot, camNe, alt, roll, pitch, yaw, gpitch, gyaw);
        } else if ("vision-source".equalsIgnoreCase(effSource)) {
            // FR-03 source=vision-source 分支：委托 VisionSource.detect() → 经 locateTarget 定位
            batch = detectFromVisionSource(shot, camNe, alt, roll, pitch, yaw, gpitch, gyaw);
        } else if ("external".equalsIgnoreCase(effSource)) {
            // F1 source=external 分支：JPEG → 外部推理服务 → 经 locateTarget 定位
            batch = detectFromExternal(shot, camNe, alt, roll, pitch, yaw, gpitch, gyaw);
        } else {
            List<Map<String, Object>> detections = new ArrayList<>();
            for (JsonNode t : shot.path("targets")) {
                Map<String, Object> d = locateTarget(
                        t.path("u").asDouble(), t.path("v").asDouble(),
                        t.path("kind").asText(), IMAGE_W, IMAGE_H,
                        camNe, alt, roll, pitch, yaw, gpitch, gyaw);
                // truth mode knows the target id directly
                double errM = truthErrorM(t.path("id").asInt(), (double) d.get("lat"), (double) d.get("lon"));
                d.put("confidence", 1.0);  // 投影真值置信度恒 1.0（与 ProjectionVisionSource.detect 同语义）
                d.put("id", t.path("id").asInt());
                d.put("truthErrorM", Math.round(errM * 10) / 10.0);
                detections.add(d);
            }
            batch = new DetectionBatch(detections, effSource);
        }
        double latencyMs = (System.nanoTime() - detectStartNanos) / 1_000_000.0;
        recordEval(shot, batch.source(), batch.items(), latencyMs);
        List<Map<String, Object>> detections = batch.items();

        Map<String, Object> out = new HashMap<>();
        out.put("sysid", sysid);
        out.put("frameSeq", shot.path("frameSeq").asLong());
        out.put("shotLat", shot.path("lat").asDouble());
        out.put("shotLon", shot.path("lon").asDouble());
        out.put("altM", shot.path("altM").asDouble());
        out.put("detections", detections);
        out.put("detectionSource", batch.source());
        out.put("latencyMs", Math.round(latencyMs * 10) / 10.0);
        log.info("captureAndLocate sysid={} frame={} source={} -> {} detection(s) in {} ms",
                sysid, shot.path("frameSeq").asLong(), batch.source(),
                detections.size(), Math.round(latencyMs));
        // F4 自动晋升（纯旁路，spec §2）：立案失败不影响拍照主路径——
        // onCapture 自吞异常；此前用 ObjectProvider 延迟解析，e2e 实测 getIfAvailable()
        // 静默返回 null 导致晋升从未执行，改为构造直注入（依赖无环，人工立案已证 Bean 可用）。
        try {
            defectService.onCapture(shot.path("frameSeq").asLong(), sysid, detections, null);
        } catch (Exception e) {
            log.warn("defect auto-promote bypass failed (ignored): {}", e.getMessage());
        }
        return out;
    }

    // ---- helpers ----

    /**
     * Pixels pipeline: pull the rendered JPEG, find blobs, geolocate each
     * centroid. Scores against the NEAREST truth target (a detector has no id).
     */
    private DetectionBatch detectFromPixels(JsonNode shot, double[] camNe,
                                            double alt, double roll, double pitch,
                                            double yaw, double gpitch, double gyaw) {
        List<Map<String, Object>> out = new ArrayList<>();
        long frameSeq = shot.path("frameSeq").asLong();
        try {
            byte[] jpeg = fetchJpeg(frameSeq);
            if (jpeg == null) {
                return new DetectionBatch(out, "pixels");
            }
            List<BlobDetector.Box> boxes = detector.detect(jpeg);
            for (BlobDetector.Box b : boxes) {
                Map<String, Object> d = locateTarget(b.u, b.v, "blob", JPEG_W, JPEG_H,
                        camNe, alt, roll, pitch, yaw, gpitch, gyaw);
                if (d == null) {
                    continue;
                }
                // Detector has no id: score against nearest truth target.
                double errM = nearestTruthErrorM((double) d.get("lat"), (double) d.get("lon"));
                d.put("id", -1);
                d.put("truthErrorM", Math.round(errM * 10) / 10.0);
                out.add(d);
            }
        } catch (Exception e) {
            log.warn("pixel pipeline failed for frame {}: {}", frameSeq, e.getMessage());
        }
        return new DetectionBatch(out, "pixels");
    }

    /** 拉取 sim 渲染 JPEG（pixels 与 external 路径共用），失败返回 null。 */
    private byte[] fetchJpeg(long frameSeq) {
        try {
            HttpResponse<byte[]> imgResp = http.send(
                    HttpRequest.newBuilder(URI.create(
                            simTruthBase + "/camera/shots/" + frameSeq + ".jpg")).GET().build(),
                    HttpResponse.BodyHandlers.ofByteArray());
            if (imgResp.statusCode() != 200) {
                log.warn("jpeg fetch {} -> {}", frameSeq, imgResp.statusCode());
                return null;
            }
            return imgResp.body();
        } catch (Exception e) {
            log.warn("jpeg fetch {} failed: {}", frameSeq, e.getMessage());
            return null;
        }
    }

    /**
     * F1 source=external 分支：拉 JPEG → ExternalVisionSource（HTTP 推理）→ 定位评分。
     * <p>
     * 外部源异常时回退投影简化（异常 5.1.2 同款降级），source 记 truth(fallback)（spec S1）。
     * 检出无真值 id，按最近真值评分。
     */
    private DetectionBatch detectFromExternal(JsonNode shot, double[] camNe,
                                              double alt, double roll, double pitch,
                                              double yaw, double gpitch, double gyaw) {
        List<Map<String, Object>> out = new ArrayList<>();
        long frameSeq = shot.path("frameSeq").asLong();
        byte[] jpeg = fetchJpeg(frameSeq);
        if (jpeg == null) {
            return new DetectionBatch(out, "external");
        }
        CameraShot camShot = toCameraShot(shot);
        CameraPose pose = new CameraPose(roll, pitch, yaw, gpitch, gyaw);
        List<VisionDetection> detections;
        try {
            detections = externalSource != null
                    ? externalSource.detect(camShot, pose, jpeg)
                    : List.of();
        } catch (Exception e) {
            log.warn("ExternalVisionSource.detect failed, falling back to ProjectionVisionSource: {}",
                    e.getMessage());
            detections = fallback.detect(camShot, pose);
            out.addAll(locateAndScore(detections, camNe, alt, roll, pitch, yaw, gpitch, gyaw,
                    IMAGE_W, IMAGE_H));
            return new DetectionBatch(out, "truth(fallback)");
        }
        out.addAll(locateAndScore(detections, camNe, alt, roll, pitch, yaw, gpitch, gyaw, JPEG_W, JPEG_H));
        return new DetectionBatch(out, "external");
    }

    /**
     * 通用"检出 → 定位 → 真值评分"循环（vision-source 与 external 共用）。
     *
     * @param imgW/imgH 检出坐标所属分辨率（元数据投影 1920×1080，JPEG 640×360）
     */
    private List<Map<String, Object>> locateAndScore(List<VisionDetection> detections,
                                                     double[] camNe, double alt, double roll,
                                                     double pitch, double yaw, double gpitch,
                                                     double gyaw, int imgW, int imgH) {
        List<Map<String, Object>> out = new ArrayList<>();
        for (VisionDetection d : detections) {
            Map<String, Object> loc = locateTarget(d.u(), d.v(), d.kind(),
                    imgW, imgH, camNe, alt, roll, pitch, yaw, gpitch, gyaw);
            if (loc == null) {
                continue;
            }
            loc.put("confidence", d.confidence());
            loc.put("trackId", d.trackId());
            // 评分：基于 trackId（若关联真值 id）或最近真值
            double errM = d.trackId() >= 0
                    ? truthErrorM(d.trackId(), (double) loc.get("lat"), (double) loc.get("lon"))
                    : nearestTruthErrorM((double) loc.get("lat"), (double) loc.get("lon"));
            loc.put("id", d.trackId());
            loc.put("truthErrorM", Math.round(errM * 10) / 10.0);
            out.add(loc);
        }
        return out;
    }

    /**
     * FR-03 source=vision-source 分支：委托 VisionSource.detect() → 经 locateTarget 定位。
     * <p>
     * VisionSource 异常时回退 ProjectionVisionSource + WARN 日志（异常 5.1.2），
     * source 记 truth(fallback)（spec S1）。
     * 定位与评分步骤复用既有 GeolocationSolver（FR-37 既有感知链路不变）。
     */
    private DetectionBatch detectFromVisionSource(JsonNode shot, double[] camNe,
                                                  double alt, double roll, double pitch,
                                                  double yaw, double gpitch, double gyaw) {
        // 构造 CameraShot + CameraPose
        CameraShot camShot = toCameraShot(shot);
        CameraPose pose = new CameraPose(roll, pitch, yaw, gpitch, gyaw);
        // 调 VisionSource.detect()，异常降级到 ProjectionVisionSource（异常 5.1.2）
        List<VisionDetection> detections;
        try {
            detections = visionSource != null
                    ? visionSource.detect(camShot, pose)
                    : fallback.detect(camShot, pose);
        } catch (Exception e) {
            log.warn("VisionSource.detect failed, falling back to ProjectionVisionSource: {}",
                    e.getMessage());
            detections = fallback.detect(camShot, pose);
            return new DetectionBatch(
                    locateAndScore(detections, camNe, alt, roll, pitch, yaw, gpitch, gyaw,
                            IMAGE_W, IMAGE_H),
                    "truth(fallback)");
        }
        return new DetectionBatch(
                locateAndScore(detections, camNe, alt, roll, pitch, yaw, gpitch, gyaw,
                        IMAGE_W, IMAGE_H),
                "vision-source");
    }

    /** 从 drone-sim 真值 HTTP 的 Shot JSON 构造 CameraShot（适配层）。 */
    private CameraShot toCameraShot(JsonNode shot) {
        List<CameraShot.ProjectedTarget> targets = new ArrayList<>();
        for (JsonNode t : shot.path("targets")) {
            targets.add(new CameraShot.ProjectedTarget(
                    t.path("id").asInt(),
                    t.path("kind").asText("unknown"),
                    t.path("u").asDouble(),
                    t.path("v").asDouble(),
                    t.path("lat").asDouble(),
                    t.path("lon").asDouble()));
        }
        return new CameraShot(
                shot.path("frameSeq").asLong(),
                System.currentTimeMillis(),
                shot.path("lat").asDouble(),
                shot.path("lon").asDouble(),
                shot.path("altM").asDouble(),
                shot.path("droneRollDeg").asDouble(),
                shot.path("dronePitchDeg").asDouble(),
                shot.path("droneYawDeg").asDouble(),
                shot.path("gimbalPitchDeg").asDouble(),
                shot.path("gimbalYawDeg").asDouble(),
                targets);
    }

    /** One pixel/truth detection -> geolocated view (null when unsolvable). */
    private Map<String, Object> locateTarget(double u, double v, String kind,
                                             int imgW, int imgH, double[] camNe,
                                             double alt, double roll, double pitch,
                                             double yaw, double gpitch, double gyaw) {
        GeolocationSolver.Result r = solver.solve(u, v, imgW, imgH, HFOV_DEG,
                HOME_LAT, HOME_LON, camNe[0], camNe[1], alt, roll, pitch, yaw, gpitch, gyaw);
        if (r == null) {
            return null;
        }
        Map<String, Object> d = new HashMap<>();
        d.put("kind", kind);
        d.put("pixel", Map.of("u", u, "v", v));
        d.put("lat", r.lat);
        d.put("lon", r.lon);
        d.put("groundRangeM", Math.round(r.groundRangeM * 10) / 10.0);
        return d;
    }

    /** 一次检测批次的产出：检出列表 + 实际生效的检测源（降级时可与配置值不同，spec S1）。 */
    private record DetectionBatch(List<Map<String, Object>> items, String source) {}

    /**
     * F1 评测记录（纯旁路，spec N3）：帧内真值数取自真值投影 targets 大小，
     * 每个检出的 truthErrorM 作为与最近真值的距离交给 CvEvalService 做贪心匹配。
     */
    private void recordEval(JsonNode shot, String source,
                            List<Map<String, Object>> detections, double latencyMs) {
        try {
            int truthCount = shot.path("targets").size();
            List<Double> dists = new ArrayList<>(detections.size());
            for (Map<String, Object> d : detections) {
                Object err = d.get("truthErrorM");
                dists.add(err instanceof Number n ? n.doubleValue() : -1.0);
            }
            eval.record(shot.path("frameSeq").asLong(), source, truthCount, dists, latencyMs);
        } catch (Exception e) {
            log.warn("cv-eval record failed (ignored): {}", e.getMessage());
        }
    }

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

    /** Distance to the NEAREST truth target (pixel detector has no target id). */
    private double nearestTruthErrorM(double lat, double lon) {
        double best = Double.MAX_VALUE;
        try {
            for (JsonNode t : getJson(simTruthBase + "/targets")) {
                double dn = (lat - t.path("lat").asDouble()) * M_PER_DEG_LAT;
                double de = (lon - t.path("lon").asDouble()) * M_PER_DEG_LAT
                        * Math.cos(Math.toRadians(HOME_LAT));
                best = Math.min(best, Math.hypot(dn, de));
            }
        } catch (Exception e) {
            log.debug("truth fetch failed: {}", e.getMessage());
        }
        return best == Double.MAX_VALUE ? -1 : best;
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
