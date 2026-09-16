package io.aerofleet.sim.satrelay;

/**
 * 简化圆轨道模型（M7 星-空-地多层级中继，FR-5.1）。
 * <p>
 * 基于圆轨道近似（非 SGP4），用于仿真卫星轨道位置与可见性计算。
 * 轨道位置随仿真时钟推进而更新，平近点角按时间线性增加（FR-5.1.1.3）。
 * <p>
 * 性能：单星单时刻轨道位置计算 <1ms（DFX 4.1.1），6 次三角函数 + 6 次乘法。
 * 确定性：相同轨道参数与相同时钟输入，结果逐位一致（DFX 4.2.4，纯浮点无随机）。
 */
public final class OrbitModel {

    /** 地球半径（km）。 */
    public static final double EARTH_R_KM = 6371.0;
    /** 地球引力参数 μ = G·M（km³/s²）。 */
    public static final double MU_KM3_S2 = 398600.4418;

    private OrbitModel() {
    }

    /**
     * 计算卫星在指定时刻的 ECI 坐标（km）。
     * <p>
     * 圆轨道近似：真近点角 = 平近点角，轨道为正圆。
     * <ol>
     *   <li>半长轴 a = R_earth + h</li>
     *   <li>角速度 ω = sqrt(μ / a³)</li>
     *   <li>演化平近点角 M(t) = M0 + ω·Δt</li>
     *   <li>轨道面内位置 → 旋转到 ECI（应用倾角 i 和 RAAN Ω）</li>
     * </ol>
     *
     * @param orbitAltitudeKm 轨道高度（km）
     * @param inclinationDeg  轨道倾角（度）
     * @param raanDeg         升交点赤经（度）
     * @param meanAnomalyDeg  初始平近点角（度）
     * @param timeMs          仿真时钟（ms）
     * @return ECI 坐标 [x, y, z] in km
     */
    public static double[] positionAt(double orbitAltitudeKm, double inclinationDeg,
                                      double raanDeg, double meanAnomalyDeg, long timeMs) {
        double a = EARTH_R_KM + orbitAltitudeKm;           // 半长轴 (km)
        double omega = Math.sqrt(MU_KM3_S2 / (a * a * a)); // 角速度 (rad/s)
        double dtSec = timeMs / 1000.0;
        double mRad = Math.toRadians(meanAnomalyDeg) + omega * dtSec; // 演化平近点角

        // 轨道面内位置（圆轨道，真近点角 = 平近点角）
        double xOrb = a * Math.cos(mRad);
        double yOrb = a * Math.sin(mRad);

        // 旋转到 ECI（应用倾角 i 和 RAAN Ω）
        double incRad = Math.toRadians(inclinationDeg);
        double raanRad = Math.toRadians(raanDeg);
        double cosOmega = Math.cos(raanRad);
        double sinOmega = Math.sin(raanRad);
        double cosInc = Math.cos(incRad);
        double sinInc = Math.sin(incRad);

        double xEci = cosOmega * xOrb - sinOmega * cosInc * yOrb;
        double yEci = sinOmega * xOrb + cosOmega * cosInc * yOrb;
        double zEci = sinInc * yOrb;

        return new double[]{xEci, yEci, zEci};
    }

    /**
     * 计算卫星相对地面点的仰角与方位角（FR-5.1.1.4）。
     * <p>
     * 将卫星 ECI 坐标转为地面点当地 ENU（东-北-天）坐标，再算仰角/方位角。
     * 奇异处理：卫星与地面点重合时仰角 = 90°（FR-5.1.3.3）。
     *
     * @param satPosKm    卫星 ECI 坐标 [x, y, z] in km
     * @param groundLatDeg 地面点纬度（度）
     * @param groundLonDeg 地面点经度（度）
     * @return [elevationDeg, azimuthDeg]，仰角 [-90, 90]，方位角 [0, 360)
     */
    public static double[] elevationAzimuth(double[] satPosKm, double groundLatDeg, double groundLonDeg) {
        double latRad = Math.toRadians(groundLatDeg);
        double lonRad = Math.toRadians(groundLonDeg);

        // 地面点 ECI 坐标
        double xG = EARTH_R_KM * Math.cos(latRad) * Math.cos(lonRad);
        double yG = EARTH_R_KM * Math.cos(latRad) * Math.sin(lonRad);
        double zG = EARTH_R_KM * Math.sin(latRad);

        // 卫星相对地面点的 ECI 向量
        double dx = satPosKm[0] - xG;
        double dy = satPosKm[1] - yG;
        double dz = satPosKm[2] - zG;

        // 转为 ENU（东-北-天）
        double sinLat = Math.sin(latRad);
        double cosLat = Math.cos(latRad);
        double sinLon = Math.sin(lonRad);
        double cosLon = Math.cos(lonRad);

        double e = -sinLon * dx + cosLon * dy;
        double n = -sinLat * cosLon * dx - sinLat * sinLon * dy + cosLat * dz;
        double u = cosLat * cosLon * dx + cosLat * sinLon * dy + sinLat * dz;

        // 仰角与方位角
        double horizontal = Math.sqrt(e * e + n * n);
        double elevationDeg;
        double azimuthDeg;

        if (horizontal < 1e-9) {
            // 奇异处理：卫星在地面点正上方或正下方
            elevationDeg = u >= 0 ? 90.0 : -90.0;
            azimuthDeg = 0.0;
        } else {
            elevationDeg = Math.toDegrees(Math.atan2(u, horizontal));
            azimuthDeg = Math.toDegrees(Math.atan2(e, n));
            if (azimuthDeg < 0) {
                azimuthDeg += 360.0;
            }
        }

        return new double[]{elevationDeg, azimuthDeg};
    }

    /**
     * 计算演化后的平近点角（度，归一化到 [0, 360)）。
     *
     * @param meanAnomalyDeg 初始平近点角（度）
     * @param orbitAltitudeKm 轨道高度（km）
     * @param timeMs 仿真时钟（ms）
     * @return 演化后的平近点角（度，[0, 360)）
     */
    public static double evolvedMeanAnomaly(double meanAnomalyDeg, double orbitAltitudeKm, long timeMs) {
        double a = EARTH_R_KM + orbitAltitudeKm;
        double omega = Math.sqrt(MU_KM3_S2 / (a * a * a));
        double dtSec = timeMs / 1000.0;
        double mDeg = meanAnomalyDeg + Math.toDegrees(omega * dtSec);
        mDeg = mDeg % 360.0;
        if (mDeg < 0) {
            mDeg += 360.0;
        }
        return mDeg;
    }
}