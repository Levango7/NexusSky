package io.aerofleet.sim.satrelay;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * LEO 星座模型（M7 星-空-地多层级中继，FR-5.1）。
 * <p>
 * 管理一组 {@link SatelliteNode}，驱动轨道位置更新与可见卫星查询。
 * 星座规模 10-100 颗（FR-5.1.1.7），构造时校验。
 * <p>
 * 确定性：相同轨道参数与相同时钟输入，轨道位置逐位一致（DFX 4.2.4）。
 */
public final class LeoConstellation {

    /** 星座规模下限。 */
    public static final int MIN_SIZE = 10;
    /** 星座规模上限。 */
    public static final int MAX_SIZE = 100;

    private final List<SatelliteNode> satellites;

    /**
     * 构造器：校验星座规模 10-100（FR-5.1.1.7）。
     *
     * @param satellites 卫星列表
     * @throws IllegalArgumentException 规模 <10 或 >100
     */
    public LeoConstellation(List<SatelliteNode> satellites) {
        if (satellites == null || satellites.size() < MIN_SIZE || satellites.size() > MAX_SIZE) {
            throw new IllegalArgumentException(
                    "constellation size must be in [" + MIN_SIZE + ", " + MAX_SIZE
                            + "], got " + (satellites == null ? 0 : satellites.size()));
        }
        this.satellites = Collections.unmodifiableList(new ArrayList<>(satellites));
    }

    /** 卫星数量。 */
    public int size() {
        return satellites.size();
    }

    /** 获取所有卫星（不可变列表）。 */
    public List<SatelliteNode> satellites() {
        return satellites;
    }

    /** 按 satId 查找卫星，不存在返回 null。 */
    public SatelliteNode findSatellite(int satId) {
        for (SatelliteNode sat : satellites) {
            if (sat.satId() == satId) {
                return sat;
            }
        }
        return null;
    }

    /**
     * 推进所有卫星轨道位置（由仿真 tick 调用）。
     * <p>
     * 遍历所有卫星调 {@link SatelliteNode#updatePosition(long)}。
     * 性能：100 星 × <1ms = <100ms（DFX 4.1.1/4）。
     *
     * @param nowMs 仿真时钟（ms）
     */
    public void tick(long nowMs) {
        for (SatelliteNode sat : satellites) {
            sat.updatePosition(nowMs);
        }
    }

    /**
     * 查找对地面点可见的卫星列表（仰角 > 阈值）。
     *
     * @param latDeg       地面点纬度（度）
     * @param lonDeg       地面点经度（度）
     * @param thresholdDeg 仰角阈值（度）
     * @return 可见卫星列表
     */
    public List<SatelliteNode> findVisible(double latDeg, double lonDeg, double thresholdDeg) {
        List<SatelliteNode> visible = new ArrayList<>();
        for (SatelliteNode sat : satellites) {
            double[] elAz = OrbitModel.elevationAzimuth(sat.eciPositionKm(), latDeg, lonDeg);
            if (elAz[0] > thresholdDeg) {
                sat.updateVisibility(true, elAz[0], elAz[1]);
                visible.add(sat);
            } else {
                sat.updateVisibility(false, 0.0, elAz[1]);
            }
        }
        return visible;
    }

    /**
     * 工厂：生成均匀分布的 Walker 壳星座。
     * <p>
     * 所有卫星共享轨道高度与倾角，RAAN 与平近点角均匀分布。
     *
     * @param size            卫星数量（10-100）
     * @param orbitAltitudeKm 轨道高度（300-1200 km）
     * @param inclinationDeg  轨道倾角（度）
     * @return 星座实例
     */
    public static LeoConstellation walkerShell(int size, double orbitAltitudeKm, double inclinationDeg) {
        List<SatelliteNode> sats = new ArrayList<>(size);
        for (int i = 0; i < size; i++) {
            double raanDeg = 360.0 * i / size;
            double meanAnomalyDeg = 360.0 * i / size;
            sats.add(new SatelliteNode(i + 1, orbitAltitudeKm, inclinationDeg, raanDeg, meanAnomalyDeg));
        }
        return new LeoConstellation(sats);
    }

    @Override
    public String toString() {
        return "LeoConstellation{size=" + satellites.size() + "}";
    }
}