package io.aerofleet.sim.twin;

import java.util.ArrayList;
import java.util.List;

/**
 * M13 轨迹预测器：卡尔曼滤波 + 运动学模型增强（CV/CA/CT）+ 不确定性量化。
 * <p>
 * 纯 Java 实现，不依赖外部矩阵库。矩阵统一用二维 {@code double[][]} 表示，
 * 向量用 {@code double[]} 表示。
 * <p>
 * 状态空间约定（角度单位：度，距离单位：米，时间单位：秒）：
 * <ul>
 *   <li>CV（匀速）：[lat, lon, alt, vLat, vLon, vAlt]（6 维）</li>
 *   <li>CA（匀加速）：[lat, lon, alt, vLat, vLon, vAlt, aLat, aLon, aAlt]（9 维）</li>
 *   <li>CT（协调转弯）：[lat, lon, vLat, vLon, omega, alt, vAlt]（7 维，非线性）</li>
 * </ul>
 */
public class TrajectoryPredictor {

    /** 运动学模型类型 */
    public enum MotionModel { CV, CA, CT }

    /** 地球近似常数：1 度纬度对应米数 */
    private static final double METERS_PER_DEG_LAT = 111000.0;

    /** 卡方分布 df=2 的 95% 分位数 ≈ 5.991，置信椭圆半轴缩放因子为其平方根 */
    private static final double CHI2_95_DF2 = 5.9914645;

    /** 默认过程/测量噪声（米制） */
    private static final double DEF_Q_POS = 0.5;      // m
    private static final double DEF_Q_VEL = 0.5;      // m/s
    private static final double DEF_Q_ACC = 0.2;      // m/s²
    private static final double DEF_Q_TURN = 0.01;    // rad/s
    private static final double DEF_R_MEAS = 3.0;     // m（GPS 精度 2-5m 取中）

    // ===================== 矩阵运算工具 =====================

    /** 矩阵乘法 A(m×k) × B(k×n) → C(m×n) */
    static double[][] matMul(double[][] A, double[][] B) {
        int m = A.length, k = A[0].length, n = B[0].length;
        double[][] C = new double[m][n];
        for (int i = 0; i < m; i++)
            for (int j = 0; j < n; j++) {
                double s = 0;
                for (int t = 0; t < k; t++) s += A[i][t] * B[t][j];
                C[i][j] = s;
            }
        return C;
    }

    /** 矩阵 × 向量 */
    static double[] matVec(double[][] A, double[] v) {
        int m = A.length, k = v.length;
        double[] r = new double[m];
        for (int i = 0; i < m; i++) {
            double s = 0;
            for (int t = 0; t < k; t++) s += A[i][t] * v[t];
            r[i] = s;
        }
        return r;
    }

    /** 转置 */
    static double[][] transpose(double[][] A) {
        int m = A.length, n = A[0].length;
        double[][] T = new double[n][m];
        for (int i = 0; i < m; i++)
            for (int j = 0; j < n; j++) T[j][i] = A[i][j];
        return T;
    }

    /** 矩阵加法 */
    static double[][] matAdd(double[][] A, double[][] B) {
        int m = A.length, n = A[0].length;
        double[][] C = new double[m][n];
        for (int i = 0; i < m; i++)
            for (int j = 0; j < n; j++) C[i][j] = A[i][j] + B[i][j];
        return C;
    }

    /** 矩阵减法 */
    static double[][] matSub(double[][] A, double[][] B) {
        int m = A.length, n = A[0].length;
        double[][] C = new double[m][n];
        for (int i = 0; i < m; i++)
            for (int j = 0; j < n; j++) C[i][j] = A[i][j] - B[i][j];
        return C;
    }

    /** 单位矩阵 */
    static double[][] identity(int n) {
        double[][] I = new double[n][n];
        for (int i = 0; i < n; i++) I[i][i] = 1;
        return I;
    }

    /** 矩阵求逆（高斯-约旦消元，n×n）。若奇异抛出 {@link ArithmeticException}。 */
    static double[][] inverse(double[][] A) {
        int n = A.length;
        double[][] M = new double[n][2 * n];
        for (int i = 0; i < n; i++) {
            System.arraycopy(A[i], 0, M[i], 0, n);
            M[i][n + i] = 1;
        }
        for (int col = 0; col < n; col++) {
            int piv = col;
            for (int r = col + 1; r < n; r++)
                if (Math.abs(M[r][col]) > Math.abs(M[piv][col])) piv = r;
            if (Math.abs(M[piv][col]) < 1e-12)
                throw new ArithmeticException("奇异矩阵，无法求逆");
            if (piv != col) { double[] tmp = M[piv]; M[piv] = M[col]; M[col] = tmp; }
            double d = M[col][col];
            for (int j = 0; j < 2 * n; j++) M[col][j] /= d;
            for (int r = 0; r < n; r++) {
                if (r == col) continue;
                double f = M[r][col];
                if (f != 0)
                    for (int j = 0; j < 2 * n; j++) M[r][j] -= f * M[col][j];
            }
        }
        double[][] inv = new double[n][n];
        for (int i = 0; i < n; i++) System.arraycopy(M[i], n, inv[i], 0, n);
        return inv;
    }

    /** 深拷贝矩阵 */
    static double[][] cloneMat(double[][] A) {
        double[][] C = new double[A.length][];
        for (int i = 0; i < A.length; i++) C[i] = A[i].clone();
        return C;
    }

    private static double sq(double v) { return v * v; }

    /** 1 度经度对应米数（随纬度变化） */
    private static double metersPerDegLon(double lat) {
        return METERS_PER_DEG_LAT * Math.cos(Math.toRadians(lat));
    }

    // ===================== 卡尔曼滤波器 =====================

    /**
     * 通用线性卡尔曼滤波器，支持任意状态维 {@code n} 与观测维 {@code m}。
     * <p>
     * 预测步：{@code x = F·x; P = F·P·Fᵀ + Q}<br>
     * 更新步：{@code y = z - H·x; S = H·P·Hᵀ + R; K = P·Hᵀ·S⁻¹; x = x + K·y; P = (I - K·H)·P}
     */
    public static class KalmanFilter {
        private final int n; // 状态维
        private final int m; // 观测维
        private double[] x;          // 状态估计
        private double[][] P;        // 状态协方差
        private double[][] F;        // 状态转移
        private double[][] H;        // 观测
        private double[][] Q;        // 过程噪声协方差
        private double[][] R;        // 测量噪声协方差

        public KalmanFilter(int stateDim, int measDim) {
            this.n = stateDim;
            this.m = measDim;
            this.x = new double[n];
            this.P = new double[n][n];
            this.F = identity(n);
            this.H = new double[m][n];
            this.Q = new double[n][n];
            this.R = new double[m][m];
        }

        public void setState(double[] x) { this.x = x.clone(); }
        public void setCovariance(double[][] P) { this.P = cloneMat(P); }
        public void setF(double[][] F) { this.F = cloneMat(F); }
        public void setH(double[][] H) { this.H = cloneMat(H); }
        public void setQ(double[][] Q) { this.Q = cloneMat(Q); }
        public void setR(double[][] R) { this.R = cloneMat(R); }

        public int stateDim() { return n; }
        public int measDim() { return m; }
        public double[] getState() { return x.clone(); }
        public double[][] getCovariance() { return cloneMat(P); }

        /** 预测步：{@code x = F·x；P = F·P·Fᵀ + Q} */
        public void predict() {
            x = matVec(F, x);
            double[][] Ft = transpose(F);
            P = matAdd(matMul(matMul(F, P), Ft), Q);
        }

        /** 更新步：输入观测 z（m 维，位置 [lat, lon, alt]） */
        public void update(double[] z) {
            double[] Hx = matVec(H, x);
            double[] y = new double[m]; // 残新
            for (int i = 0; i < m; i++) y[i] = z[i] - Hx[i];
            double[][] S = matAdd(matMul(matMul(H, P), transpose(H)), R); // m×m
            double[][] K = matMul(matMul(P, transpose(H)), inverse(S));    // n×m
            double[] Ky = matVec(K, y);
            for (int i = 0; i < n; i++) x[i] += Ky[i];
            double[][] KH = matMul(K, H);
            double[][] IKH = matSub(identity(n), KH);
            P = matMul(IKH, P);
        }
    }

    // ===================== 预测结果 =====================

    /** 单步预测结果：位置 + 速度 + 不确定性（协方差与 95% 置信椭圆）。 */
    public static class PredictedPoint {
        public final double lat, lon, alt;
        public final double vLat, vLon, vAlt;
        /** 位置协方差（3×3，[lat, lon, alt]，原始状态空间单位） */
        public final double[][] positionCovariance;
        /** 95% 置信椭圆半长轴（米） */
        public final double confSemiMajorMeters;
        /** 95% 置信椭圆半短轴（米） */
        public final double confSemiMinorMeters;
        /** 95% 置信椭圆方向角（度，正北为 0，顺时针为正） */
        public final double confAngleDeg;
        /** 本步使用的运动学模型 */
        public final MotionModel model;

        public PredictedPoint(double lat, double lon, double alt,
                              double vLat, double vLon, double vAlt,
                              double[][] positionCovariance,
                              double confSemiMajorMeters, double confSemiMinorMeters,
                              double confAngleDeg, MotionModel model) {
            this.lat = lat; this.lon = lon; this.alt = alt;
            this.vLat = vLat; this.vLon = vLon; this.vAlt = vAlt;
            this.positionCovariance = positionCovariance;
            this.confSemiMajorMeters = confSemiMajorMeters;
            this.confSemiMinorMeters = confSemiMinorMeters;
            this.confAngleDeg = confAngleDeg;
            this.model = model;
        }
    }

    // ===================== 模型构建 =====================

    /**
     * 构建 CV（匀速）模型卡尔曼滤波器，6 维状态 [lat, lon, alt, vLat, vLon, vLat]。
     * 状态转移矩阵 F = I + dt·A（匀速运动模型）。
     *
     * @param qPosMeters 位置过程噪声（米）
     * @param qVelMps    速度过程噪声（米/秒）
     * @param rMeasMeters 位置测量噪声（米，GPS 精度）
     */
    public static KalmanFilter buildCV(double lat, double lon, double alt,
                                       double vLat, double vLon, double vAlt,
                                       double dt,
                                       double qPosMeters, double qVelMps, double rMeasMeters) {
        double mplLat = METERS_PER_DEG_LAT;
        double mplLon = metersPerDegLon(lat);
        KalmanFilter kf = new KalmanFilter(6, 3);
        kf.setState(new double[]{lat, lon, alt, vLat, vLon, vAlt});

        // F = I + dt·A，A = [[0,I3],[0,0]]
        double[][] F = identity(6);
        F[0][3] = dt; F[1][4] = dt; F[2][5] = dt;
        kf.setF(F);

        // H = [I3, 0]（只观测位置）
        double[][] H = new double[3][6];
        H[0][0] = 1; H[1][1] = 1; H[2][2] = 1;
        kf.setH(H);

        // Q：对角阵，位置噪声小、速度噪声大
        double[][] Q = new double[6][6];
        Q[0][0] = sq(qPosMeters / mplLat);
        Q[1][1] = sq(qPosMeters / mplLon);
        Q[2][2] = sq(qPosMeters);
        Q[3][3] = sq(qVelMps / mplLat);
        Q[4][4] = sq(qVelMps / mplLon);
        Q[5][5] = sq(qVelMps);
        kf.setQ(Q);

        // R：对角阵，GPS 精度
        double[][] R = new double[3][3];
        R[0][0] = sq(rMeasMeters / mplLat);
        R[1][1] = sq(rMeasMeters / mplLon);
        R[2][2] = sq(rMeasMeters);
        kf.setR(R);

        // 初始协方差：位置用 R，速度用 Q 速度分量
        double[][] P = new double[6][6];
        P[0][0] = R[0][0]; P[1][1] = R[1][1]; P[2][2] = R[2][2];
        P[3][3] = Q[3][3]; P[4][4] = Q[4][4]; P[5][5] = Q[5][5];
        kf.setCovariance(P);
        return kf;
    }

    /**
     * 构建 CA（匀加速）模型卡尔曼滤波器，9 维状态
     * [lat, lon, alt, vLat, vLon, vAlt, aLat, aLon, aAlt]。
     * 状态转移含 0.5·dt²·加速度 项。
     *
     * @param qAccMps2 加速度过程噪声（米/秒²）
     */
    public static KalmanFilter buildCA(double lat, double lon, double alt,
                                       double vLat, double vLon, double vAlt,
                                       double aLat, double aLon, double aAlt,
                                       double dt,
                                       double qPosMeters, double qVelMps, double qAccMps2,
                                       double rMeasMeters) {
        double mplLat = METERS_PER_DEG_LAT;
        double mplLon = metersPerDegLon(lat);
        KalmanFilter kf = new KalmanFilter(9, 3);
        kf.setState(new double[]{lat, lon, alt, vLat, vLon, vAlt, aLat, aLon, aAlt});

        double[][] F = identity(9);
        double halfDt2 = 0.5 * dt * dt;
        // 位置 += dt·速度 + 0.5·dt²·加速度
        F[0][3] = dt; F[0][6] = halfDt2;
        F[1][4] = dt; F[1][7] = halfDt2;
        F[2][5] = dt; F[2][8] = halfDt2;
        // 速度 += dt·加速度
        F[3][6] = dt; F[4][7] = dt; F[5][8] = dt;
        kf.setF(F);

        double[][] H = new double[3][9];
        H[0][0] = 1; H[1][1] = 1; H[2][2] = 1;
        kf.setH(H);

        double[][] Q = new double[9][9];
        Q[0][0] = sq(qPosMeters / mplLat); Q[1][1] = sq(qPosMeters / mplLon); Q[2][2] = sq(qPosMeters);
        Q[3][3] = sq(qVelMps / mplLat);   Q[4][4] = sq(qVelMps / mplLon);   Q[5][5] = sq(qVelMps);
        Q[6][6] = sq(qAccMps2 / mplLat);  Q[7][7] = sq(qAccMps2 / mplLon);  Q[8][8] = sq(qAccMps2);
        kf.setQ(Q);

        double[][] R = new double[3][3];
        R[0][0] = sq(rMeasMeters / mplLat); R[1][1] = sq(rMeasMeters / mplLon); R[2][2] = sq(rMeasMeters);
        kf.setR(R);

        double[][] P = new double[9][9];
        P[0][0] = R[0][0]; P[1][1] = R[1][1]; P[2][2] = R[2][2];
        P[3][3] = Q[3][3]; P[4][4] = Q[4][4]; P[5][5] = Q[5][5];
        P[6][6] = Q[6][6]; P[7][7] = Q[7][7]; P[8][8] = Q[8][8];
        kf.setCovariance(P);
        return kf;
    }

    // ===================== CT（协调转弯）非线性模型 =====================

    /**
     * CT 模型非线性状态转移。状态 [lat, lon, vLat, vLon, omega, alt, vAlt]（7 维）。
     * 水平面用协调转弯（航向变化率 omega），高度用匀速。omega→0 时退化为 CV。
     */
    private static double[] ctTransition(double[] s, double dt) {
        double lat = s[0], lon = s[1], vLat = s[2], vLon = s[3], w = s[4], alt = s[5], vAlt = s[6];
        double nLat, nLon, nvLat, nvLon;
        if (Math.abs(w) < 1e-6) {
            // 退化匀速
            nLat = lat + vLat * dt;
            nLon = lon + vLon * dt;
            nvLat = vLat;
            nvLon = vLon;
        } else {
            double sw = Math.sin(w * dt) / w;
            double cw = (1 - Math.cos(w * dt)) / w;
            nLat = lat + sw * vLat - cw * vLon;
            nLon = lon + cw * vLat + sw * vLon;
            nvLat = Math.cos(w * dt) * vLat - Math.sin(w * dt) * vLon;
            nvLon = Math.sin(w * dt) * vLat + Math.cos(w * dt) * vLon;
        }
        return new double[]{nLat, nLon, nvLat, nvLon, w, alt + vAlt * dt, vAlt};
    }

    /** CT 模型雅可比矩阵（中心差分数值近似，7×7），用于协方差传播。 */
    private static double[][] ctJacobian(double[] s, double dt) {
        int n = s.length;
        double eps = 1e-7;
        double[][] J = new double[n][n];
        for (int j = 0; j < n; j++) {
            double[] sp = s.clone(); sp[j] += eps;
            double[] sm = s.clone(); sm[j] -= eps;
            double[] fp = ctTransition(sp, dt);
            double[] fm = ctTransition(sm, dt);
            for (int i = 0; i < n; i++) J[i][j] = (fp[i] - fm[i]) / (2 * eps);
        }
        return J;
    }

    /** CT 模型过程噪声 Q（7×7，对角）。 */
    private static double[][] ctQ(double lat, double qPosMeters, double qVelMps, double qTurn) {
        double mplLat = METERS_PER_DEG_LAT, mplLon = metersPerDegLon(lat);
        double[][] Q = new double[7][7];
        Q[0][0] = sq(qPosMeters / mplLat);
        Q[1][1] = sq(qPosMeters / mplLon);
        Q[2][2] = sq(qVelMps / mplLat);
        Q[3][3] = sq(qVelMps / mplLon);
        Q[4][4] = sq(qTurn);
        Q[5][5] = sq(qPosMeters);
        Q[6][6] = sq(qVelMps);
        return Q;
    }

    // ===================== 状态提取工具 =====================

    private static int[] posIndex(MotionModel m) {
        return (m == MotionModel.CT) ? new int[]{0, 1, 5} : new int[]{0, 1, 2};
    }

    private static int[] velIndex(MotionModel m) {
        return (m == MotionModel.CT) ? new int[]{2, 3, 6} : new int[]{3, 4, 5};
    }

    private static double[] extractPos(double[] x, MotionModel m) {
        int[] idx = posIndex(m);
        return new double[]{x[idx[0]], x[idx[1]], x[idx[2]]};
    }

    private static double[] extractVel(double[] x, MotionModel m) {
        int[] idx = velIndex(m);
        return new double[]{x[idx[0]], x[idx[1]], x[idx[2]]};
    }

    private static double[][] extractPosCov(double[][] P, MotionModel m) {
        int[] idx = posIndex(m);
        double[][] c = new double[3][3];
        for (int i = 0; i < 3; i++)
            for (int j = 0; j < 3; j++) c[i][j] = P[idx[i]][idx[j]];
        return c;
    }

    // ===================== 不确定性量化 =====================

    /**
     * 计算 95% 置信椭圆参数（水平面，米单位）。
     *
     * @param posCov3 位置协方差 3×3（[lat, lon, alt]，原始状态空间单位）
     * @param lat     参考纬度，用于经度→米换算
     * @return [半长轴(米), 半短轴(米), 方向角(度)]
     */
    public static double[] confidenceEllipseMeters(double[][] posCov3, double lat) {
        double mplLat = METERS_PER_DEG_LAT, mplLon = metersPerDegLon(lat);
        // 转米空间 2×2 水平协方差
        double cxx = posCov3[0][0] * mplLat * mplLat;
        double cyy = posCov3[1][1] * mplLon * mplLon;
        double cxy = posCov3[0][1] * mplLat * mplLon;
        double tr = cxx + cyy;
        double det = cxx * cyy - cxy * cxy;
        double disc = Math.sqrt(Math.max(0, tr * tr / 4 - det));
        double l1 = tr / 2 + disc; // 大特征值
        double l2 = tr / 2 - disc; // 小特征值
        double k = Math.sqrt(CHI2_95_DF2);
        double semiMajor = Math.sqrt(Math.max(0, l1)) * k;
        double semiMinor = Math.sqrt(Math.max(0, l2)) * k;
        double angle = Math.toDegrees(0.5 * Math.atan2(2 * cxy, cxx - cyy));
        return new double[]{semiMajor, semiMinor, angle};
    }

    // ===================== 多步预测 =====================

    /**
     * 基于卡尔曼滤波的多步轨迹预测（含不确定性）。每步输出位置 + 速度 + 协方差 + 95% 置信椭圆。
     * <p>
     * 预测时间越长，过程噪声累积使协方差越大、不确定性越大。
     *
     * @param model     运动学模型（CV/CA/CT）
     * @param horizonSec 预测时间窗（秒）
     * @param steps     输出点数
     * @return 每步预测结果列表（长度 = steps）
     */
    public List<PredictedPoint> predictTrajectory(
            double lat, double lon, double alt,
            double vLat, double vLon, double vAlt,
            double aLat, double aLon, double aAlt,
            double turnRate,
            MotionModel model,
            int horizonSec, int steps,
            double qPosMeters, double qVelMps, double qAccMps2, double qTurn, double rMeasMeters) {
        List<PredictedPoint> out = new ArrayList<>();
        if (steps <= 0 || horizonSec <= 0) return out;
        double dt = (double) horizonSec / steps;

        switch (model) {
            case CV: {
                KalmanFilter kf = buildCV(lat, lon, alt, vLat, vLon, vAlt, dt, qPosMeters, qVelMps, rMeasMeters);
                for (int i = 0; i < steps; i++) {
                    kf.predict();
                    out.add(toPredictedPoint(kf.getState(), kf.getCovariance(), MotionModel.CV));
                }
                break;
            }
            case CA: {
                KalmanFilter kf = buildCA(lat, lon, alt, vLat, vLon, vAlt, aLat, aLon, aAlt, dt,
                        qPosMeters, qVelMps, qAccMps2, rMeasMeters);
                for (int i = 0; i < steps; i++) {
                    kf.predict();
                    out.add(toPredictedPoint(kf.getState(), kf.getCovariance(), MotionModel.CA));
                }
                break;
            }
            case CT: {
                // 状态 [lat, lon, vLat, vLon, omega, alt, vAlt]
                double[] s = new double[]{lat, lon, vLat, vLon, turnRate, alt, vAlt};
                double[][] P = ctInitialCovariance(lat, qPosMeters, qVelMps, qTurn, rMeasMeters);
                double[][] Q = ctQ(lat, qPosMeters, qVelMps, qTurn);
                for (int i = 0; i < steps; i++) {
                    double[][] J = ctJacobian(s, dt);
                    s = ctTransition(s, dt);
                    P = matAdd(matMul(matMul(J, P), transpose(J)), Q);
                    out.add(toPredictedPoint(s, P, MotionModel.CT));
                }
                break;
            }
        }
        return out;
    }

    /** CT 初始协方差（7×7，对角）。 */
    private static double[][] ctInitialCovariance(double lat, double qPosMeters, double qVelMps,
                                                  double qTurn, double rMeasMeters) {
        double mplLat = METERS_PER_DEG_LAT, mplLon = metersPerDegLon(lat);
        double[][] P = new double[7][7];
        P[0][0] = sq(rMeasMeters / mplLat);
        P[1][1] = sq(rMeasMeters / mplLon);
        P[2][2] = sq(qVelMps / mplLat);
        P[3][3] = sq(qVelMps / mplLon);
        P[4][4] = sq(qTurn);
        P[5][5] = sq(rMeasMeters);
        P[6][6] = sq(qVelMps);
        return P;
    }

    /** 从状态与协方差构造 PredictedPoint（含置信椭圆）。 */
    private static PredictedPoint toPredictedPoint(double[] x, double[][] P, MotionModel model) {
        double[] pos = extractPos(x, model);
        double[] vel = extractVel(x, model);
        double[][] posCov = extractPosCov(P, model);
        double[] ell = confidenceEllipseMeters(posCov, pos[0]);
        return new PredictedPoint(pos[0], pos[1], pos[2], vel[0], vel[1], vel[2],
                posCov, ell[0], ell[1], ell[2], model);
    }

    /** 便捷重载：使用默认噪声参数。 */
    public List<PredictedPoint> predictTrajectory(
            double lat, double lon, double alt,
            double vLat, double vLon, double vAlt,
            MotionModel model, int horizonSec, int steps) {
        return predictTrajectory(lat, lon, alt, vLat, vLon, vAlt, 0, 0, 0, 0,
                model, horizonSec, steps, DEF_Q_POS, DEF_Q_VEL, DEF_Q_ACC, DEF_Q_TURN, DEF_R_MEAS);
    }

    // ===================== 模型自动选择 =====================

    /**
     * 根据观测序列自动选择运动学模型：直线段用 CV，加减速段用 CA，转弯段用 CT。
     *
     * @param obs 观测序列，每行 [lat, lon, alt]
     * @param dt  采样间隔（秒）
     */
    public MotionModel selectModel(List<double[]> obs, double dt) {
        if (obs == null || obs.size() < 3 || dt <= 0) return MotionModel.CV;
        int n = obs.size();
        // 速度（度/秒）
        double[] vLat = new double[n - 1], vLon = new double[n - 1];
        for (int i = 0; i < n - 1; i++) {
            vLat[i] = (obs.get(i + 1)[0] - obs.get(i)[0]) / dt;
            vLon[i] = (obs.get(i + 1)[1] - obs.get(i)[1]) / dt;
        }
        // 加速度幅度（米/秒²）
        double maxAcc = 0;
        for (int i = 0; i < n - 2; i++) {
            double aLatMps2 = (vLat[i + 1] - vLat[i]) / dt * METERS_PER_DEG_LAT;
            double aLonMps2 = (vLon[i + 1] - vLon[i]) / dt * metersPerDegLon(obs.get(i)[0]);
            maxAcc = Math.max(maxAcc, Math.hypot(aLatMps2, aLonMps2));
        }
        // 航向变化率（度/秒）
        double maxTurnRate = 0;
        for (int i = 0; i < n - 2; i++) {
            if (Math.hypot(vLat[i], vLon[i]) < 1e-9 || Math.hypot(vLat[i + 1], vLon[i + 1]) < 1e-9)
                continue;
            double h1 = Math.toDegrees(Math.atan2(vLon[i], vLat[i]));
            double h2 = Math.toDegrees(Math.atan2(vLon[i + 1], vLat[i + 1]));
            double dh = h2 - h1;
            while (dh > 180) dh -= 360;
            while (dh < -180) dh += 360;
            maxTurnRate = Math.max(maxTurnRate, Math.abs(dh) / dt);
        }
        if (maxTurnRate > 5.0) return MotionModel.CT;
        if (maxAcc > 1.0) return MotionModel.CA;
        return MotionModel.CV;
    }

    // ===================== 速度分量辅助 =====================

    /**
     * 由航向（度，正北 0，顺时针）与速度（米/秒）计算状态空间速度分量（度/秒）。
     *
     * @return [vLat(度/秒), vLon(度/秒)]
     */
    public static double[] velocityComponents(double headingDeg, double velocityMps, double lat) {
        double vLat = velocityMps * Math.cos(Math.toRadians(headingDeg)) / METERS_PER_DEG_LAT;
        double vLon = velocityMps * Math.sin(Math.toRadians(headingDeg)) / metersPerDegLon(lat);
        return new double[]{vLat, vLon};
    }

    // ===================== 原有预测 API（向后兼容） =====================

    /**
     * 简单匀速直线外推（M13 基线实现，保留以兼容既有调用方与测试）。
     */
    public List<double[]> predict(double lat, double lon, double alt, double heading, double velocity, int horizonSec, int numPoints) {
        List<double[]> points = new ArrayList<>();
        for (int i = 1; i <= numPoints; i++) {
            double dt = (double) horizonSec * i / numPoints;
            double dLat = (velocity * dt * Math.cos(Math.toRadians(heading))) / 111000;
            double dLon = (velocity * dt * Math.sin(Math.toRadians(heading))) / (111000 * Math.cos(Math.toRadians(lat)));
            points.add(new double[]{lat + dLat, lon + dLon, alt});
        }
        return points;
    }
}
