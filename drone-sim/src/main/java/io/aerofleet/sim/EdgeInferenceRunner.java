package io.aerofleet.sim;

import io.aerofleet.mavlink.messages.MavlinkMessage;
import io.aerofleet.mavlink.enums.EdgeTaskType;
import io.aerofleet.mavlink.messages.EdgeTaskStatusMsg;
import io.aerofleet.mavlink.messages.SensorFusionDataMsg;
import io.aerofleet.mavlink.messages.VisionDetectionMsg;
import io.aerofleet.sim.edge.EdgeNode;
import io.aerofleet.sim.edge.EdgeTask;
import io.aerofleet.sim.edge.FusedState;
import io.aerofleet.sim.edge.SensorFusionEngine;
import io.aerofleet.sim.edge.VideoStreamAnalyzer;

import java.io.IOException;

/**
 * M12 边缘推理接线层（2026-10-04）：把 {@code io.aerofleet.sim.edge} 的两个算法库
 * 真正接入飞行运行路径，由 {@code VirtualDrone} 的 20Hz tick 驱动。
 *
 * <h2>两条链路</h2>
 * <ul>
 *   <li><b>传感器融合</b>：{@link SensorFusionEngine} 9 维 EKF——每 tick 用
 *       「伪 IMU」（Host 速度差分加速度）做预测，2Hz 用带噪 GPS 观测 +
 *       （注入式 LiDAR 源存在时的）LiDAR 高程做序贯更新，1Hz 经
 *       SENSOR_FUSION_DATA(30054) 下发融合位姿，同时发一条
 *       EDGE_TASK_STATUS(30053, taskType=SENSOR_FUSION) 报告本次融合任务完成；</li>
 *   <li><b>视频分析</b>：2Hz 用 {@code CameraModel.capture} 取当前合成快照，
 *       经 {@code ShotImageWriter.renderGray} 渲染成 160×90 灰度帧喂
 *       {@link VideoStreamAnalyzer}（帧差 + 连通域 + 质心跟踪），
 *       每周期发一条 EDGE_TASK_STATUS(30053, taskType=VIDEO_ANALYSIS)，
 *       resultSize = 检测标签序列化字节数；同时每个已分类目标（vehicle/person）
 *       发一条 VISION_DETECTION(30014)，承载 u/v 像素坐标 + kind + 置信度 +
 *       质心跟踪 ID（unknown 亮区不占协议 kind 枚举，跳过不上报）。</li>
 * </ul>
 *
 * <h2>为什么默认常开（与 M11 执行级的 {@code --autonomy-exec} 不同）</h2>
 * M11 的执行级会<b>改变飞行行为</b>，所以默认关闭；本类是<b>被动观测</b>——
 * 融合位姿与视频检测结果只下行上报，不回写导航、不触碰飞行状态，与雷达扫描
 * （FR-06）、IMU/LiDAR 遥测（FR-21/22）同级，因此无需开关。
 *
 * <h2>诚实性边界（不得含糊）</h2>
 * <ul>
 *   <li>「边缘 AI」仍是<b>经典 CV / 线性 KF</b>，不是机器学习模型（全仓 pom 无 ML
 *       依赖，{@code AiAutonomyWiringTest.noMlRuntimeDependency} 钉死）；</li>
 *   <li>融合结果<b>不回写</b> {@code DronePhysics}——飞控仍用真值积分，
 *       30054 是「观测增强遥测」而非导航输入；</li>
 *   <li>LiDAR 高程观测取真实 AGL 而非点云 nearestDistance（后者依赖波束几何，
 *       可能打到障碍物），sensorMask 的 LiDAR 位代表「注入式 LiDAR 硬件在环」；</li>
 *   <li>本 tick 内任何阶段抛出的异常都被就地吞掉并记日志——边缘分析永远
 *       不允许打断飞行循环（与 {@code sendLidarData} 等遥测方法同一纪律）。</li>
 * </ul>
 */
final class EdgeInferenceRunner {

    /** 灰度帧分辨率（16:9，均匀缩放自 1920×1080 针孔模型）。 */
    static final int FRAME_W = 160;
    static final int FRAME_H = 90;
    /** GPS 观测精度（1σ，米），与 {@link SensorFusionEngine} 文档的 R_gps 量级一致。 */
    static final double GPS_ACCURACY_M = 3.0;

    private static final long VISION_PERIOD_MS = 500;        // 视频分析 2Hz
    private static final long FUSION_UPDATE_PERIOD_MS = 500; // GPS/LiDAR 更新 2Hz
    private static final long EMIT_PERIOD_MS = 1000;         // 融合下发 1Hz
    private static final double LIDAR_ACCURACY_M = 0.1;
    private static final double IMU_VEL_ACCURACY_MPS = 0.5;
    private static final int STATUS_COMPLETED = 2;
    private static final int STATUS_FAILED = 3;

    /** 宿主抽象：机载观测/真值的取数口，测试用假实现替换。 */
    interface Host {
        int sysid();

        /** 带噪 GPS 纬度观测（度）。 */
        double gpsLat();

        /** 带噪 GPS 经度观测（度）。 */
        double gpsLon();

        /** GPS 高度观测（米）。 */
        double gpsAltM();

        double velNorthMps();

        double velEastMps();

        double velUpMps();

        /** LiDAR 高程观测（米）；无注入式 LiDAR 源时返回 null。 */
        Double lidarAltM();

        /** 当前合成相机快照；相机不可用时 null。 */
        CameraModel.Shot captureShot();

        void send(MavlinkMessage msg) throws IOException;
    }

    private final Host host;
    private final VideoStreamAnalyzer video = new VideoStreamAnalyzer(FRAME_W, FRAME_H);
    private final SensorFusionEngine fusion = new SensorFusionEngine();
    private EdgeNode edgeNode;

    private long lastVisionMs = -1;
    private long lastUpdateMs = -1;
    private long lastEmitMs = -1;
    private double prevVn;
    private double prevVe;
    private double prevVu;
    private boolean hasPrevVel = false;
    private long videoTaskSeq = 0;
    private long fusionTaskSeq = 0;
    private long lastFusionProcMs = 0;

    EdgeInferenceRunner(Host host) {
        this.host = host;
    }

    private EdgeNode edgeNode() {
        if (edgeNode == null) {
            edgeNode = new EdgeNode(host.sysid());
        }
        return edgeNode;
    }

    /**
     * 20Hz 入口。三个阶段互相隔离：任何阶段抛异常只影响本阶段本轮，
     * 不影响其余阶段与后续 tick。
     */
    void tick(long nowMs, double dt) {
        try {
            fusionPredict(dt);
        } catch (Exception e) {
            SimLog.warn("edge fusion predict failed: " + e.getMessage());
        }
        try {
            fusionUpdateAndEmit(nowMs);
        } catch (Exception e) {
            SimLog.warn("edge fusion update/emit failed: " + e.getMessage());
        }
        try {
            visionCycle(nowMs);
        } catch (Exception e) {
            SimLog.warn("edge vision cycle failed: " + e.getMessage());
        }
    }

    // ------------------------------------------------------------------
    // 传感器融合链路
    // ------------------------------------------------------------------

    /** 每 tick 预测：伪 IMU = 宿主速度差分加速度（首 tick 只记录速度）。 */
    private void fusionPredict(double dt) {
        if (dt <= 0) {
            return;
        }
        double vn = host.velNorthMps();
        double ve = host.velEastMps();
        double vu = host.velUpMps();
        if (!hasPrevVel) {
            hasPrevVel = true;
            prevVn = vn;
            prevVe = ve;
            prevVu = vu;
            return;
        }
        double aE = (ve - prevVe) / dt;
        double aN = (vn - prevVn) / dt;
        double aU = (vu - prevVu) / dt;
        fusion.predict(dt, aE, aN, aU);
        prevVn = vn;
        prevVe = ve;
        prevVu = vu;
    }

    /** 2Hz 序贯更新 + 1Hz 下发 30054 与 30053(SENSOR_FUSION)。 */
    private void fusionUpdateAndEmit(long nowMs) throws IOException {
        if (lastUpdateMs < 0 || nowMs - lastUpdateMs >= FUSION_UPDATE_PERIOD_MS) {
            long t0 = System.nanoTime();
            fusion.updateGps(host.gpsLat(), host.gpsLon(), host.gpsAltM(), GPS_ACCURACY_M);
            // IMU 速度观测：纯加速度注入无法收敛初始速度（恒速时加速度恒 0，
            // 速度状态停在 0，航向输出失真），必须周期性喂速度观测。
            double vN = host.velNorthMps();
            double vE = host.velEastMps();
            double headingDeg = Math.toDegrees(Math.atan2(vE, vN));
            fusion.updateVelocity(headingDeg, Math.hypot(vN, vE), IMU_VEL_ACCURACY_MPS);
            Double lidarAlt = host.lidarAltM();
            if (lidarAlt != null) {
                fusion.updateLidar(lidarAlt, LIDAR_ACCURACY_M);
            }
            lastFusionProcMs = (System.nanoTime() - t0) / 1_000_000;
            lastUpdateMs = nowMs;
        }
        if (lastEmitMs < 0) {
            lastEmitMs = nowMs;   // 首个周期只建立时间基准，等 EKF 至少完成一次更新
            return;
        }
        if (nowMs - lastEmitMs < EMIT_PERIOD_MS || !fusion.isInitialized()) {
            return;
        }
        lastEmitMs = nowMs;
        FusedState s = fusion.getFusedState();
        int lat1e7 = (int) Math.round(s.lat * 1e7);
        int lon1e7 = (int) Math.round(s.lon * 1e7);
        int altMm = (int) Math.round(s.alt * 1000.0);
        double hdgDeg = ((s.heading % 360.0) + 360.0) % 360.0;
        int hdgCdeg = (int) Math.round(hdgDeg * 100.0);
        host.send(new SensorFusionDataMsg(lat1e7, lon1e7, altMm,
                (float) s.velocity, (float) s.accuracy, hdgCdeg,
                host.sysid(), s.sensorMask));
        String taskId = "fusion-" + fusionTaskSeq;
        edgeNode().submitTask(new EdgeTask(taskId, EdgeTaskType.SENSOR_FUSION.name()));
        host.send(new EdgeTaskStatusMsg(fusionTaskSeq, lastFusionProcMs,
                SensorFusionDataMsg.LEN, host.sysid(),
                EdgeTaskType.SENSOR_FUSION.ordinal(), STATUS_COMPLETED));
        fusionTaskSeq++;
    }

    // ------------------------------------------------------------------
    // 视频分析链路
    // ------------------------------------------------------------------

    /** 2Hz：渲染灰度帧 → 帧差/连通域/跟踪 → 30053(VIDEO_ANALYSIS) + 逐目标 30014。 */
    private void visionCycle(long nowMs) throws IOException {
        boolean due = lastVisionMs < 0 || nowMs - lastVisionMs >= VISION_PERIOD_MS;
        if (!due) {
            return;
        }
        lastVisionMs = nowMs;
        CameraModel.Shot shot = host.captureShot();
        if (shot == null) {
            return;
        }
        String taskId = "video-" + videoTaskSeq;
        edgeNode().submitTask(new EdgeTask(taskId, EdgeTaskType.VIDEO_ANALYSIS.name()));
        byte[] frame = ShotImageWriter.renderGray(shot, FRAME_W, FRAME_H);
        VideoStreamAnalyzer.DetailedResult result = video.analyzeFrameDetailed(frame);
        StringBuilder labels = new StringBuilder();
        for (VideoStreamAnalyzer.DetectionRecord r : result.records) {
            if (labels.length() > 0) {
                labels.append(',');
            }
            labels.append(r.label);
        }
        int resultSize = labels.toString()
                .getBytes(java.nio.charset.StandardCharsets.UTF_8).length;
        host.send(new EdgeTaskStatusMsg(videoTaskSeq, result.processingTimeMs,
                resultSize, host.sysid(),
                EdgeTaskType.VIDEO_ANALYSIS.ordinal(), STATUS_COMPLETED));
        // 逐目标 VISION_DETECTION(30014)：u/v 为 160×90 渲染帧像素坐标（0-based），
        // kind 映射 0=vehicle/1=person；unknown（无形状依据的帧差亮区）不占协议
        // kind 枚举，跳过不上报；跟踪 ID 超 u8 有效范围（>254）按协议 0xFF 占位。
        for (VideoStreamAnalyzer.DetectionRecord r : result.records) {
            int kind = visionKindOf(r.label);
            if (kind < 0) {
                continue;
            }
            int trackId = r.trackId <= 254 ? r.trackId : VisionDetectionMsg.TRACK_ID_NONE;
            host.send(new VisionDetectionMsg((float) r.u, (float) r.v,
                    (float) r.confidence, kind, trackId, host.sysid(), nowMs));
        }
        videoTaskSeq++;
    }

    /**
     * 视频检测 label → VISION_DETECTION(30014) kind 码：0=vehicle, 1=person。
     * 协议 kind 枚举（0=vehicle, 1=person, 2=animal, ...）没有 unknown 档——
     * 帧差亮区中无形状归类依据的 unknown 不映射任何 kind，返回 -1 由调用方跳过。
     */
    static int visionKindOf(String label) {
        if ("vehicle".equals(label)) {
            return 0;
        }
        if ("person".equals(label)) {
            return 1;
        }
        return -1;
    }

    // ------------------------------------------------------------------
    // 测试观察口
    // ------------------------------------------------------------------

    /** 边缘节点任务表大小（测试观察用）。 */
    int edgeTaskCount() {
        return edgeNode().getTaskCount();
    }

    /** 边缘节点任务状态（测试观察用）。 */
    String edgeTaskStatus(String taskId) {
        return edgeNode().getTaskStatus(taskId);
    }
}
