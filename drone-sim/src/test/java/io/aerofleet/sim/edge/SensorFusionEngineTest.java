package io.aerofleet.sim.edge;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * SensorFusionEngine EKF 传感器融合单测（M12 增强）。
 * <p>
 * 覆盖：纯 GPS / GPS+IMU / GPS+Vision / GPS+LiDAR / 全传感器 / EKF 收敛性 /
 * 传感器丢失降级 / 原有 fuse 向后兼容。
 */
@DisplayName("SensorFusionEngine EKF 传感器融合 (M12)")
class SensorFusionEngineTest {

    private static final double MPL_LAT = 111000.0;

    // ===================== 原有 fuse 向后兼容测试 =====================

    @Test
    @DisplayName("fuse: 全传感器融合 sensorMask=15")
    void testFusionWithAllSensors() {
        SensorFusionEngine engine = new SensorFusionEngine();
        FusedState state = engine.fuse(30.0, 120.0, 100, 45, 10, 30.001, 120.001, 101,
                true, true, true, true);
        assertEquals(15, state.sensorMask, "all 4 bits");
        assertTrue(state.accuracy > 0, "accuracy > 0");
    }

    @Test
    @DisplayName("fuse: 纯 GPS 融合 sensorMask=1")
    void testFusionWithGpsOnly() {
        SensorFusionEngine engine = new SensorFusionEngine();
        FusedState state = engine.fuse(30.0, 120.0, 100, 0, 0, 0, 0, 0,
                true, false, false, false);
        assertEquals(1, state.sensorMask);
    }

    @Test
    @DisplayName("fuse: 多次调用保持向后兼容签名")
    void testFuseBackwardCompatibleSignature() {
        SensorFusionEngine engine = new SensorFusionEngine();
        // 第一次调用
        FusedState s1 = engine.fuse(30.0, 120.0, 100, 0, 5, 30.0, 120.0, 100,
                true, true, true, true);
        assertNotNull(s1);
        assertEquals(15, s1.sensorMask);
        // 第二次调用（仅 GPS）
        FusedState s2 = engine.fuse(30.001, 120.001, 101, 0, 0, 0, 0, 0,
                true, false, false, false);
        assertEquals(1, s2.sensorMask, "第二次 fuse 掩码反映本次传感器");
    }

    // ===================== 纯 GPS 融合 =====================

    @Test
    @DisplayName("纯 GPS 融合：位置接近 GPS 测量值")
    void pureGpsFusionPositionCloseToMeasurement() {
        SensorFusionEngine engine = new SensorFusionEngine();
        double gpsLat = 30.0, gpsLon = 120.0, gpsAlt = 100.0;
        // 多次 GPS 更新，位置应收敛到 GPS 测量值
        for (int i = 0; i < 10; i++) {
            engine.updateGps(gpsLat, gpsLon, gpsAlt, 3.0);
        }
        FusedState s = engine.getFusedState();
        assertEquals(gpsLat, s.lat, 1e-4, "纬度接近 GPS 测量值");
        assertEquals(gpsLon, s.lon, 1e-4, "经度接近 GPS 测量值");
        assertEquals(gpsAlt, s.alt, 1e-2, "高度接近 GPS 测量值");
        assertEquals(1, s.sensorMask, "仅 GPS 掩码");
    }

    // ===================== GPS + IMU 融合 =====================

    @Test
    @DisplayName("GPS+IMU 融合：速度估计收敛到真实值")
    void gpsImuFusionVelocityConverges() {
        SensorFusionEngine engine = new SensorFusionEngine();
        double lat0 = 30.0, lon0 = 120.0, alt0 = 100.0;
        double trueSpeedMps = 10.0; // 正北 10 m/s
        double dt = 0.1; // 100Hz IMU
        double gpsInterval = 1.0; // 10Hz GPS
        int totalSteps = 200; // 20 秒
        double mplLat = MPL_LAT;

        // 初始化
        engine.updateGps(lat0, lon0, alt0, 3.0);

        double t = 0;
        double lastGpsTime = 0;
        for (int i = 1; i <= totalSteps; i++) {
            t = i * dt;
            // IMU 预测（匀速，加速度=0）
            engine.predict(dt, 0, 0, 0);
            // GPS 更新（10Hz）
            if (t - lastGpsTime >= gpsInterval) {
                double trueLat = lat0 + (trueSpeedMps * t) / mplLat;
                engine.updateGps(trueLat, lon0, alt0, 3.0);
                lastGpsTime = t;
            }
        }

        FusedState s = engine.getFusedState();
        // 速度应收敛到 10 m/s（容差 2 m/s，受过程噪声与 GPS 离散更新影响）
        assertEquals(trueSpeedMps, s.velocity, 2.0,
                "速度估计收敛到真实值 10 m/s");
        assertTrue(s.sensorMask == 3 || s.sensorMask == 1,
                "掩码包含 GPS+IMU: " + s.sensorMask);
    }

    // ===================== GPS + Vision 融合 =====================

    @Test
    @DisplayName("GPS+Vision 融合：精度比纯 GPS 更高（协方差更小）")
    void gpsVisionFusionMorePreciseThanGpsOnly() {
        double lat = 30.0, lon = 120.0, alt = 100.0;

        // 纯 GPS
        SensorFusionEngine gpsOnly = new SensorFusionEngine();
        for (int i = 0; i < 20; i++) {
            gpsOnly.updateGps(lat, lon, alt, 3.0);
        }
        double gpsOnlyPosVar = positionVarianceMeters(gpsOnly);

        // GPS + Vision
        SensorFusionEngine gpsVision = new SensorFusionEngine();
        for (int i = 0; i < 20; i++) {
            gpsVision.updateGps(lat, lon, alt, 3.0);
            gpsVision.updateVision(lat, lon, 1.0);
        }
        double gpsVisionPosVar = positionVarianceMeters(gpsVision);

        assertTrue(gpsVisionPosVar < gpsOnlyPosVar,
                "GPS+Vision 协方差(" + gpsVisionPosVar + ") 应 < 纯 GPS(" + gpsOnlyPosVar + ")");
    }

    // ===================== GPS + LiDAR 融合 =====================

    @Test
    @DisplayName("GPS+LiDAR 融合：高度精度提升")
    void gpsLidarFusionAltitudeMorePrecise() {
        double lat = 30.0, lon = 120.0, alt = 100.0;

        // 纯 GPS
        SensorFusionEngine gpsOnly = new SensorFusionEngine();
        for (int i = 0; i < 20; i++) {
            gpsOnly.updateGps(lat, lon, alt, 3.0);
        }
        double gpsOnlyAltVar = gpsOnly.getCovariance()[2][2]; // alt 方差（米²）

        // GPS + LiDAR
        SensorFusionEngine gpsLidar = new SensorFusionEngine();
        for (int i = 0; i < 20; i++) {
            gpsLidar.updateGps(lat, lon, alt, 3.0);
            gpsLidar.updateLidar(alt, 0.1);
        }
        double gpsLidarAltVar = gpsLidar.getCovariance()[2][2];

        assertTrue(gpsLidarAltVar < gpsOnlyAltVar,
                "GPS+LiDAR 高度方差(" + gpsLidarAltVar + ") 应 < 纯 GPS(" + gpsOnlyAltVar + ")");
    }

    // ===================== 全传感器融合 =====================

    @Test
    @DisplayName("全传感器融合：精度最高")
    void allSensorsFusionMostPrecise() {
        double lat = 30.0, lon = 120.0, alt = 100.0;

        // 纯 GPS
        SensorFusionEngine gpsOnly = new SensorFusionEngine();
        for (int i = 0; i < 20; i++) {
            gpsOnly.updateGps(lat, lon, alt, 3.0);
        }
        double gpsOnlyVar = positionVarianceMeters(gpsOnly);

        // 全传感器
        SensorFusionEngine all = new SensorFusionEngine();
        for (int i = 0; i < 20; i++) {
            all.updateGps(lat, lon, alt, 3.0);
            all.updateVision(lat, lon, 1.0);
            all.updateLidar(alt, 0.1);
        }
        double allVar = positionVarianceMeters(all);
        FusedState s = all.getFusedState();

        assertTrue(allVar < gpsOnlyVar,
                "全传感器协方差(" + allVar + ") 应 < 纯 GPS(" + gpsOnlyVar + ")");
        assertEquals(13, s.sensorMask, "GPS+Vision+LiDAR 掩码 = 1|4|8 = 13");
    }

    // ===================== EKF 收敛性 =====================

    @Test
    @DisplayName("EKF 收敛性：滤波后 RMSE < 测量噪声")
    void ekfConvergenceRmseLessThanMeasurementNoise() {
        // 真实轨迹：从 (30,120,100) 以 5 m/s 正北匀速运动
        double lat0 = 30.0, lon0 = 120.0, alt0 = 100.0;
        double trueSpeed = 5.0; // m/s
        double dt = 0.1; // 100Hz
        double gpsInterval = 0.5; // 2Hz GPS
        double gpsNoise = 3.0; // 米
        int totalSteps = 300; // 30 秒
        double mplLat = MPL_LAT;

        // 用固定种子生成可重复噪声
        java.util.Random rng = new java.util.Random(42L);

        SensorFusionEngine engine = new SensorFusionEngine();
        double lastGpsTime = -gpsInterval; // 让首步就更新 GPS
        double sumSqErr = 0;
        int errCount = 0;

        for (int i = 1; i <= totalSteps; i++) {
            double t = i * dt;
            // IMU 预测（匀速，加速度=0 + 小噪声）
            double imuAccelNoise = (rng.nextDouble() - 0.5) * 0.1;
            engine.predict(dt, 0, imuAccelNoise, 0);

            // GPS 更新（带噪声）
            if (t - lastGpsTime >= gpsInterval) {
                double trueLat = lat0 + (trueSpeed * t) / mplLat;
                double measLat = trueLat + (rng.nextGaussian() * gpsNoise / mplLat);
                double measLon = lon0 + (rng.nextGaussian() * gpsNoise / (MPL_LAT * Math.cos(Math.toRadians(lat0))));
                double measAlt = alt0 + (rng.nextGaussian() * gpsNoise);
                engine.updateGps(measLat, measLon, measAlt, gpsNoise);
                lastGpsTime = t;
            }

            // 统计滤波后位置误差（仅在 GPS 更新后统计，避免纯预测误差累积）
            if (t - lastGpsTime < dt) {
                double trueLat = lat0 + (trueSpeed * t) / mplLat;
                FusedState s = engine.getFusedState();
                double errMeters = Math.hypot(
                        (s.lat - trueLat) * mplLat,
                        (s.lon - lon0) * (MPL_LAT * Math.cos(Math.toRadians(lat0))));
                sumSqErr += errMeters * errMeters;
                errCount++;
            }
        }

        double rmse = Math.sqrt(sumSqErr / errCount);
        assertTrue(rmse < gpsNoise,
                "滤波后 RMSE(" + rmse + "m) 应 < 测量噪声(" + gpsNoise + "m)");
    }

    // ===================== 传感器丢失降级 =====================

    @Test
    @DisplayName("传感器丢失降级：丢失 GPS 后靠 IMU 预测，不确定性增大")
    void sensorLossDegradationUncertaintyGrows() {
        double lat0 = 30.0, lon0 = 120.0, alt0 = 100.0;
        double trueSpeed = 5.0; // m/s 正北
        double dt = 0.1;
        double mplLat = MPL_LAT;

        SensorFusionEngine engine = new SensorFusionEngine();
        // 阶段 1：GPS + IMU 持续 10 秒，建立稳定估计
        double t = 0;
        for (int i = 1; i <= 100; i++) {
            t = i * dt;
            engine.predict(dt, 0, 0, 0);
            if (i % 10 == 0) { // 10Hz GPS
                double trueLat = lat0 + (trueSpeed * t) / mplLat;
                engine.updateGps(trueLat, lon0, alt0, 3.0);
            }
        }
        double varBeforeLoss = positionVarianceMeters(engine);

        // 阶段 2：丢失 GPS，仅靠 IMU 预测 5 秒
        for (int i = 1; i <= 50; i++) {
            engine.predict(dt, 0, 0, 0);
        }
        double varAfterLoss = positionVarianceMeters(engine);

        assertTrue(varAfterLoss > varBeforeLoss,
                "丢失 GPS 后协方差(" + varAfterLoss + ") 应 > 丢失前(" + varBeforeLoss + ")");
    }

    // ===================== 协方差矩阵形状与正定性 =====================

    @Test
    @DisplayName("协方差矩阵：9×9 形状且对角非负")
    void covarianceMatrixShapeAndNonNegativeDiagonal() {
        SensorFusionEngine engine = new SensorFusionEngine();
        engine.updateGps(30.0, 120.0, 100.0, 3.0);
        double[][] P = engine.getCovariance();
        assertEquals(9, P.length, "9 行");
        for (int i = 0; i < 9; i++) {
            assertEquals(9, P[i].length, "9 列");
            assertTrue(P[i][i] >= 0, "对角元素非负: P[" + i + "][" + i + "]=" + P[i][i]);
        }
    }

    // ===================== EKF API 序贯更新 =====================

    @Test
    @DisplayName("EKF API：predict + 异步 update 序贯调用")
    void ekfApiSequentialUpdate() {
        SensorFusionEngine engine = new SensorFusionEngine();
        // 初始化
        engine.updateGps(30.0, 120.0, 100.0, 3.0);
        // IMU 预测
        engine.predict(0.01, 0.1, 0.2, 0.0);
        // 视觉更新
        engine.updateVision(30.0001, 120.0001, 1.0);
        // LiDAR 更新
        engine.updateLidar(100.05, 0.1);
        // GPS 更新
        engine.updateGps(30.0002, 120.0002, 100.1, 3.0);

        FusedState s = engine.getFusedState();
        assertTrue(engine.isInitialized(), "已初始化");
        // 掩码应包含 GPS(1) + IMU(2) + Vision(4) + LiDAR(8) = 15
        assertEquals(15, s.sensorMask, "全传感器掩码");
        // 位置应在合理范围
        assertTrue(s.lat > 29.0 && s.lat < 31.0, "纬度合理");
        assertTrue(s.lon > 119.0 && s.lon < 121.0, "经度合理");
    }

    @Test
    @DisplayName("EKF API：未初始化时 predict 无副作用")
    void predictBeforeInitNoOp() {
        SensorFusionEngine engine = new SensorFusionEngine();
        engine.predict(0.1, 1.0, 1.0, 1.0);
        assertFalse(engine.isInitialized(), "未初始化");
        double[][] P = engine.getCovariance();
        // 全零矩阵
        for (int i = 0; i < 9; i++) {
            assertEquals(0.0, P[i][i], 1e-12, "未初始化协方差为 0");
        }
    }

    // ===================== 辅助方法 =====================

    /** 位置协方差迹（米²）：水平 + 垂直 */
    private static double positionVarianceMeters(SensorFusionEngine engine) {
        double[][] P = engine.getCovariance();
        double mplLat = MPL_LAT;
        double mplLon = MPL_LAT * Math.cos(Math.toRadians(engine.getFusedState().lat));
        return P[0][0] * mplLat * mplLat + P[1][1] * mplLon * mplLon + P[2][2];
    }
}
