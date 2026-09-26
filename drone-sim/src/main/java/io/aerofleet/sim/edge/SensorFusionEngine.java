package io.aerofleet.sim.edge;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * M12 传感器融合引擎：基于扩展卡尔曼滤波（EKF）的 GPS + IMU + 视觉 + LiDAR 异步融合。
 *
 * <h2>状态空间（9 维 CA 模型）</h2>
 * <pre>
 *   x = [lat, lon, alt, vLat, vLon, vAlt, aLat, aLon, aAlt]ᵀ
 *   位置单位：度 / 米(alt)；速度单位：度/秒 / 米/秒(vAlt)；加速度单位：度/秒² / 米/秒²(aAlt)
 * </pre>
 *
 * <h2>预测步（IMU 驱动，高频 ~100Hz）</h2>
 * <pre>
 *   x_k = F·x_{k-1}，其中 F 为 CA 状态转移矩阵：
 *     位置 += 速度×dt + 0.5×加速度×dt²
 *     速度 += 加速度×dt
 *     加速度由 IMU 直接注入（控制输入）
 *   P_k = F·P_{k-1}·Fᵀ + Q
 * </pre>
 *
 * <h2>更新步（多传感器异步序贯更新）</h2>
 * <ul>
 *   <li>GPS（~10Hz）：观测 [lat, lon, alt]，R_gps ≈ 3-5m</li>
 *   <li>视觉（~30Hz）：观测 [lat, lon]，R_vision ≈ 0.5-2m</li>
 *   <li>LiDAR（~20Hz）：观测 [alt]，R_lidar ≈ 0.1m</li>
 * </ul>
 *
 * <p>本实现状态转移与观测均为线性，EKF 退化为线性卡尔曼滤波，但框架支持非线性扩展
 * （如后续加入协调转弯模型或非线性能量观测）。矩阵运算纯 Java 实现，不依赖外部库。
 *
 * <p>矩阵统一用 {@code double[][]} 表示，向量用 {@code double[]} 表示。
 */
public class SensorFusionEngine {

    private static final Logger log = LoggerFactory.getLogger(SensorFusionEngine.class);

    // ===================== 状态维度与索引 =====================
    private static final int N = 9;
    private static final int LAT = 0, LON = 1, ALT = 2;
    private static final int VLAT = 3, VLON = 4, VALT = 5;
    private static final int ALAT = 6, ALON = 7, AALT = 8;

    // ===================== 地球常数 =====================
    private static final double METERS_PER_DEG_LAT = 111000.0;

    // ===================== 默认噪声参数（米制） =====================
    /** 位置过程噪声（米） */
    private static final double DEF_Q_POS = 0.5;
    /** 速度过程噪声（米/秒） */
    private static final double DEF_Q_VEL = 0.5;
    /** 加速度过程噪声（米/秒²），反映 IMU 偏置与漂移 */
    private static final double DEF_Q_ACC = 0.2;
    /** 初始位置不确定度（米） */
    private static final double INIT_POS_UNCERT = 10.0;
    /** 初始速度不确定度（米/秒） */
    private static final double INIT_VEL_UNCERT = 5.0;
    /** 初始加速度不确定度（米/秒²） */
    private static final double INIT_ACC_UNCERT = 2.0;

    // ===================== EKF 状态 =====================
    private double[] x;          // 状态估计
    private double[][] P;        // 状态协方差
    private boolean initialized = false;
    /** 传感器掩码：bit0=GPS bit1=IMU bit2=Vision bit3=LiDAR */
    private int sensorMask = 0;

    public SensorFusionEngine() {
        x = new double[N];
        P = new double[N][N];
    }

    // ===================== 公共 EKF API =====================

    /**
     * EKF 预测步（IMU 驱动）。
     *
     * @param dt        时间步长（秒）
     * @param imuAccelX 东向加速度（米/秒²，机体系 → 东北天系后）
     * @param imuAccelY 北向加速度（米/秒²）
     * @param imuAccelZ 垂直加速度（米/秒²，向上为正）
     */
    public synchronized void predict(double dt, double imuAccelX, double imuAccelY, double imuAccelZ) {
        if (!initialized || dt <= 0) {
            return;
        }
        double mplLat = METERS_PER_DEG_LAT;
        double mplLon = metersPerDegLon(x[LAT]);

        // 1. 将 IMU 加速度注入状态（控制输入）
        x[ALAT] = imuAccelY / mplLat;   // 北向 → 纬度加速度
        x[ALON] = imuAccelX / mplLon;   // 东向 → 经度加速度
        x[AALT] = imuAccelZ;            // 垂直

        // 2. 构建状态转移矩阵 F（CA 模型）
        double[][] F = identity(N);
        double halfDt2 = 0.5 * dt * dt;
        F[LAT][VLAT] = dt;  F[LAT][ALAT] = halfDt2;
        F[LON][VLON] = dt;  F[LON][ALON] = halfDt2;
        F[ALT][VALT] = dt;  F[ALT][AALT] = halfDt2;
        F[VLAT][ALAT] = dt; F[VLON][ALON] = dt; F[VALT][AALT] = dt;

        // 3. 状态预测：x = F·x
        x = matVec(F, x);

        // 4. 过程噪声 Q（对角阵，反映 IMU 噪声特性）
        double[][] Q = new double[N][N];
        Q[LAT][LAT] = sq(DEF_Q_POS / mplLat);
        Q[LON][LON] = sq(DEF_Q_POS / mplLon);
        Q[ALT][ALT] = sq(DEF_Q_POS);
        Q[VLAT][VLAT] = sq(DEF_Q_VEL / mplLat);
        Q[VLON][VLON] = sq(DEF_Q_VEL / mplLon);
        Q[VALT][VALT] = sq(DEF_Q_VEL);
        Q[ALAT][ALAT] = sq(DEF_Q_ACC / mplLat);
        Q[ALON][ALON] = sq(DEF_Q_ACC / mplLon);
        Q[AALT][AALT] = sq(DEF_Q_ACC);

        // 5. 协方差预测：P = F·P·Fᵀ + Q
        double[][] Ft = transpose(F);
        P = matAdd(matMul(matMul(F, P), Ft), Q);

        sensorMask |= 2; // IMU
    }

    /**
     * GPS 更新（观测位置 lat/lon/alt）。
     *
     * @param lat      纬度（度）
     * @param lon      经度（度）
     * @param alt      高度（米）
     * @param accuracy GPS 精度（米，1σ，典型 3-5m）
     */
    public synchronized void updateGps(double lat, double lon, double alt, double accuracy) {
        if (!initialized) {
            init(lat, lon, alt);
        }
        double mplLat = METERS_PER_DEG_LAT;
        double mplLon = metersPerDegLon(lat);
        double[][] H = new double[3][N];
        H[0][LAT] = 1; H[1][LON] = 1; H[2][ALT] = 1;
        double[][] R = new double[3][3];
        R[0][0] = sq(accuracy / mplLat);
        R[1][1] = sq(accuracy / mplLon);
        R[2][2] = sq(accuracy);
        update(new double[]{lat, lon, alt}, H, R);
        sensorMask |= 1;
    }

    /**
     * 视觉更新（观测位置 lat/lon，更精确但可能有延迟）。
     *
     * @param lat      纬度（度）
     * @param lon      经度（度）
     * @param accuracy 视觉精度（米，1σ，典型 0.5-2m）
     */
    public synchronized void updateVision(double lat, double lon, double accuracy) {
        if (!initialized) {
            init(lat, lon, 0.0);
        }
        double mplLat = METERS_PER_DEG_LAT;
        double mplLon = metersPerDegLon(lat);
        double[][] H = new double[2][N];
        H[0][LAT] = 1; H[1][LON] = 1;
        double[][] R = new double[2][2];
        R[0][0] = sq(accuracy / mplLat);
        R[1][1] = sq(accuracy / mplLon);
        update(new double[]{lat, lon}, H, R);
        sensorMask |= 4;
    }

    /**
     * LiDAR 更新（观测高度 alt）。
     *
     * @param alt      高度（米）
     * @param accuracy LiDAR 精度（米，1σ，典型 0.1m）
     */
    public synchronized void updateLidar(double alt, double accuracy) {
        if (!initialized) {
            init(0.0, 0.0, alt);
        }
        double[][] H = new double[1][N];
        H[0][ALT] = 1;
        double[][] R = new double[1][1];
        R[0][0] = sq(accuracy);
        update(new double[]{alt}, H, R);
        sensorMask |= 8;
    }

    /**
     * 返回当前 EKF 估计的融合状态。
     *
     * @return 融合状态（位置、航向、速度、精度、传感器掩码）
     */
    public synchronized FusedState getFusedState() {
        double heading = computeHeading();
        double velocity = computeSpeedMps();
        // 位置不确定度（米，1σ）：水平 + 垂直
        double mplLat = METERS_PER_DEG_LAT;
        double mplLon = metersPerDegLon(x[LAT]);
        double posRmse = Math.sqrt(P[LAT][LAT] * mplLat * mplLat
                + P[LON][LON] * mplLon * mplLon
                + P[ALT][ALT]);
        return new FusedState(x[LAT], x[LON], x[ALT], heading, velocity, posRmse, sensorMask);
    }

    /**
     * 返回当前状态协方差矩阵（9×9，深拷贝）。
     */
    public synchronized double[][] getCovariance() {
        return cloneMat(P);
    }

    /** 是否已初始化（已收到首个位置观测） */
    public synchronized boolean isInitialized() {
        return initialized;
    }

    // ===================== 向后兼容 API =====================

    /**
     * 融合多传感器数据（向后兼容，内部委托 EKF）。
     * <p>
     * 保留 M12 原有方法签名，内部改用 EKF 序贯更新：
     * <ol>
     *   <li>若未初始化，用首个可用位置传感器（GPS &gt; Vision &gt; LiDAR）初始化</li>
     *   <li>若有 IMU，用 IMU 航向/速度做速度观测更新</li>
     *   <li>若有 GPS，updateGps（精度 3m）</li>
     *   <li>若有 Vision，updateVision（精度 1m）</li>
     *   <li>若有 LiDAR，updateLidar（精度 0.1m）</li>
     * </ol>
     *
     * @return 融合状态
     */
    public synchronized FusedState fuse(double gpsLat, double gpsLon, double gpsAlt,
                           double imuHeading, double imuVelocity,
                           double visionLat, double visionLon,
                           double lidarAlt,
                           boolean hasGps, boolean hasImu, boolean hasVision, boolean hasLidar) {
        // 每次 fuse 调用重置掩码，反映本次传感器组合（EKF 状态保持累积）
        sensorMask = 0;

        // 初始化：优先用 GPS，其次 Vision，再次 LiDAR
        if (!initialized) {
            if (hasGps) {
                init(gpsLat, gpsLon, gpsAlt);
            } else if (hasVision) {
                init(visionLat, visionLon, hasLidar ? lidarAlt : 0.0);
            } else if (hasLidar) {
                init(0.0, 0.0, lidarAlt);
            }
        }

        // IMU 速度观测更新
        if (hasImu) {
            updateImuVelocity(imuHeading, imuVelocity, 0.5);
        }

        // 各传感器异步序贯更新
        if (hasGps) {
            updateGps(gpsLat, gpsLon, gpsAlt, 3.0);
        }
        if (hasVision) {
            updateVision(visionLat, visionLon, 1.0);
        }
        if (hasLidar) {
            updateLidar(lidarAlt, 0.1);
        }

        return getFusedState();
    }

    // ===================== 内部方法 =====================

    /** 初始化 EKF 状态 */
    private void init(double lat, double lon, double alt) {
        x = new double[N];
        x[LAT] = lat; x[LON] = lon; x[ALT] = alt;
        double mplLat = METERS_PER_DEG_LAT;
        double mplLon = metersPerDegLon(lat);
        P = new double[N][N];
        P[LAT][LAT] = sq(INIT_POS_UNCERT / mplLat);
        P[LON][LON] = sq(INIT_POS_UNCERT / mplLon);
        P[ALT][ALT] = sq(INIT_POS_UNCERT);
        P[VLAT][VLAT] = sq(INIT_VEL_UNCERT / mplLat);
        P[VLON][VLON] = sq(INIT_VEL_UNCERT / mplLon);
        P[VALT][VALT] = sq(INIT_VEL_UNCERT);
        P[ALAT][ALAT] = sq(INIT_ACC_UNCERT / mplLat);
        P[ALON][ALON] = sq(INIT_ACC_UNCERT / mplLon);
        P[AALT][AALT] = sq(INIT_ACC_UNCERT);
        initialized = true;
    }

    /**
     * IMU 速度观测更新（用于 fuse 兼容路径）。
     * <p>
     * 观测 [vLat, vLon]，测量噪声由 IMU 速度精度决定。
     */
    private synchronized void updateImuVelocity(double headingDeg, double velocityMps, double accuracyMps) {
        if (!initialized) {
            return;
        }
        double mplLat = METERS_PER_DEG_LAT;
        double mplLon = metersPerDegLon(x[LAT]);
        // 航向 → 速度分量（度/秒）
        double vLat = velocityMps * Math.cos(Math.toRadians(headingDeg)) / mplLat;
        double vLon = velocityMps * Math.sin(Math.toRadians(headingDeg)) / mplLon;
        double[][] H = new double[2][N];
        H[0][VLAT] = 1; H[1][VLON] = 1;
        double[][] R = new double[2][2];
        R[0][0] = sq(accuracyMps / mplLat);
        R[1][1] = sq(accuracyMps / mplLon);
        update(new double[]{vLat, vLon}, H, R);
        sensorMask |= 2;
    }

    /**
     * 通用 EKF 更新步（序贯更新）。
     * <pre>
     *   y = z - H·x
     *   S = H·P·Hᵀ + R
     *   K = P·Hᵀ·S⁻¹
     *   x = x + K·y
     *   P = (I - K·H)·P·(I - K·H)ᵀ + K·R·Kᵀ   （Joseph 形式，数值稳定）
     * </pre>
     * <p>协方差更新采用 Joseph 形式以保证正定性与对称性，
     * 避免简化形式 P=(I-KH)P 在有限精度下数值漂移导致失去正定。
     * <p>矩阵 S 求逆时若奇异（观测信息冗余/数值退化），
     * 捕获 {@link ArithmeticException} 跳过本次更新并记录日志，不抛出。
     */
    private synchronized void update(double[] z, double[][] H, double[][] R) {
        int m = z.length;
        double[] Hx = matVec(H, x);
        double[] y = new double[m];
        for (int i = 0; i < m; i++) {
            y[i] = z[i] - Hx[i];
        }
        double[][] Ht = transpose(H);
        double[][] S = matAdd(matMul(matMul(H, P), Ht), R);   // m×m
        double[][] Sinv;
        try {
            Sinv = inverse(S);
        } catch (ArithmeticException e) {
            // 奇异矩阵：观测信息冗余或数值退化，跳过本次更新避免状态污染
            log.warn("[SensorFusionEngine] update skipped (singular S): {}", e.getMessage());
            return;
        }
        double[][] K = matMul(matMul(P, Ht), Sinv);      // n×m
        double[] Ky = matVec(K, y);
        for (int i = 0; i < N; i++) {
            x[i] += Ky[i];
        }
        // Joseph 形式协方差更新：P = (I-KH)·P·(I-KH)ᵀ + K·R·Kᵀ
        // 相比简化形式 P=(I-KH)P，Joseph 形式在有限精度下能保证 P 的正定性与对称性
        double[][] KH = matMul(K, H);
        double[][] IKH = matSub(identity(N), KH);
        double[][] IKHt = transpose(IKH);
        double[][] KR = matMul(K, R);
        double[][] KRKt = matMul(KR, transpose(K));
        P = matAdd(matMul(matMul(IKH, P), IKHt), KRKt);
    }

    /** 由速度状态计算航向（度，正北 0，顺时针为正） */
    private double computeHeading() {
        double vLatMps = x[VLAT] * METERS_PER_DEG_LAT;
        double vLonMps = x[VLON] * metersPerDegLon(x[LAT]);
        if (Math.abs(vLatMps) < 1e-12 && Math.abs(vLonMps) < 1e-12) {
            return 0.0;
        }
        return Math.toDegrees(Math.atan2(vLonMps, vLatMps));
    }

    /** 由速度状态计算水平速度（米/秒） */
    private double computeSpeedMps() {
        double vLatMps = x[VLAT] * METERS_PER_DEG_LAT;
        double vLonMps = x[VLON] * metersPerDegLon(x[LAT]);
        return Math.hypot(vLatMps, vLonMps);
    }

    // ===================== 矩阵运算工具（自包含实现） =====================

    private static double sq(double v) { return v * v; }

    private static double metersPerDegLon(double lat) {
        return METERS_PER_DEG_LAT * Math.cos(Math.toRadians(lat));
    }

    /** 矩阵乘法 A(m×k) × B(k×n) → C(m×n) */
    private static double[][] matMul(double[][] A, double[][] B) {
        int m = A.length, k = A[0].length, n = B[0].length;
        double[][] C = new double[m][n];
        for (int i = 0; i < m; i++) {
            for (int j = 0; j < n; j++) {
                double s = 0;
                for (int t = 0; t < k; t++) {
                    s += A[i][t] * B[t][j];
                }
                C[i][j] = s;
            }
        }
        return C;
    }

    /** 矩阵 × 向量 */
    private static double[] matVec(double[][] A, double[] v) {
        int m = A.length, k = v.length;
        double[] r = new double[m];
        for (int i = 0; i < m; i++) {
            double s = 0;
            for (int t = 0; t < k; t++) {
                s += A[i][t] * v[t];
            }
            r[i] = s;
        }
        return r;
    }

    /** 转置 */
    private static double[][] transpose(double[][] A) {
        int m = A.length, n = A[0].length;
        double[][] T = new double[n][m];
        for (int i = 0; i < m; i++) {
            for (int j = 0; j < n; j++) {
                T[j][i] = A[i][j];
            }
        }
        return T;
    }

    /** 矩阵加法 */
    private static double[][] matAdd(double[][] A, double[][] B) {
        int m = A.length, n = A[0].length;
        double[][] C = new double[m][n];
        for (int i = 0; i < m; i++) {
            for (int j = 0; j < n; j++) {
                C[i][j] = A[i][j] + B[i][j];
            }
        }
        return C;
    }

    /** 矩阵减法 */
    private static double[][] matSub(double[][] A, double[][] B) {
        int m = A.length, n = A[0].length;
        double[][] C = new double[m][n];
        for (int i = 0; i < m; i++) {
            for (int j = 0; j < n; j++) {
                C[i][j] = A[i][j] - B[i][j];
            }
        }
        return C;
    }

    /** 单位矩阵 */
    private static double[][] identity(int n) {
        double[][] I = new double[n][n];
        for (int i = 0; i < n; i++) {
            I[i][i] = 1;
        }
        return I;
    }

    /** 矩阵求逆（高斯-约旦消元，n×n）。若奇异抛出 {@link ArithmeticException}。 */
    private static double[][] inverse(double[][] A) {
        int n = A.length;
        double[][] M = new double[n][2 * n];
        for (int i = 0; i < n; i++) {
            System.arraycopy(A[i], 0, M[i], 0, n);
            M[i][n + i] = 1;
        }
        for (int col = 0; col < n; col++) {
            int piv = col;
            for (int r = col + 1; r < n; r++) {
                if (Math.abs(M[r][col]) > Math.abs(M[piv][col])) {
                    piv = r;
                }
            }
            if (Math.abs(M[piv][col]) < 1e-12) {
                throw new ArithmeticException("奇异矩阵，无法求逆");
            }
            if (piv != col) {
                double[] tmp = M[piv]; M[piv] = M[col]; M[col] = tmp;
            }
            double d = M[col][col];
            for (int j = 0; j < 2 * n; j++) {
                M[col][j] /= d;
            }
            for (int r = 0; r < n; r++) {
                if (r == col) {
                    continue;
                }
                double f = M[r][col];
                if (f != 0) {
                    for (int j = 0; j < 2 * n; j++) {
                        M[r][j] -= f * M[col][j];
                    }
                }
            }
        }
        double[][] inv = new double[n][n];
        for (int i = 0; i < n; i++) {
            System.arraycopy(M[i], n, inv[i], 0, n);
        }
        return inv;
    }

    /** 深拷贝矩阵 */
    private static double[][] cloneMat(double[][] A) {
        double[][] C = new double[A.length][];
        for (int i = 0; i < A.length; i++) {
            C[i] = A[i].clone();
        }
        return C;
    }
}
