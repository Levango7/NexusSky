package io.aerofleet.sim.orch;

import java.util.Collections;
import java.util.HashSet;
import java.util.Set;

/**
 * 无人机信息类（M9 应急任务编排，T3 覆盖优化算法）。
 * <p>
 * 描述一架可用无人机的静态属性与当前状态，作为 {@link CoverageOptimizer} 的输入。
 * 所有字段 final 不可变；{@code supportedCellTypes} 做防御性拷贝保证内部集合不可变。
 * <p>
 * 字段说明：
 * <pre>
 * droneId            无人机唯一标识
 * batteryPercent     当前电量百分比（0-100）
 * currentLat         当前纬度（度）
 * currentLon         当前经度（度）
 * supportedCellTypes 支持的基站类型集合（1=LTE, 2=WiFi, 3=LoRa）
 * </pre>
 */
public final class DroneInfo {

    /** 无人机唯一标识。 */
    public final int droneId;
    /** 当前电量百分比（0-100）。 */
    public final int batteryPercent;
    /** 当前纬度（度）。 */
    public final double currentLat;
    /** 当前经度（度）。 */
    public final double currentLon;
    /** 支持的基站类型集合（1=LTE, 2=WiFi, 3=LoRa）。 */
    public final Set<Integer> supportedCellTypes;

    /**
     * 构造无人机信息。
     *
     * @param droneId            无人机唯一标识
     * @param batteryPercent     电量百分比（0-100）
     * @param currentLat         当前纬度（度）
     * @param currentLon         当前经度（度）
     * @param supportedCellTypes 支持的基站类型集合（1=LTE, 2=WiFi, 3=LoRa），null 视为空集
     */
    public DroneInfo(int droneId, int batteryPercent, double currentLat, double currentLon,
                     Set<Integer> supportedCellTypes) {
        if (batteryPercent < 0 || batteryPercent > 100) {
            throw new IllegalArgumentException(
                    "batteryPercent must be in [0, 100], got " + batteryPercent);
        }
        this.droneId = droneId;
        this.batteryPercent = batteryPercent;
        this.currentLat = currentLat;
        this.currentLon = currentLon;
        this.supportedCellTypes = supportedCellTypes == null
                ? Collections.emptySet()
                : Collections.unmodifiableSet(new HashSet<>(supportedCellTypes));
    }

    /** @return 无人机唯一标识 */
    public int getDroneId() {
        return droneId;
    }

    /** @return 电量百分比（0-100） */
    public int getBatteryPercent() {
        return batteryPercent;
    }

    /** @return 当前纬度（度） */
    public double getCurrentLat() {
        return currentLat;
    }

    /** @return 当前经度（度） */
    public double getCurrentLon() {
        return currentLon;
    }

    /** @return 支持的基站类型集合（不可变） */
    public Set<Integer> getSupportedCellTypes() {
        return supportedCellTypes;
    }

    @Override
    public String toString() {
        return "DroneInfo{droneId=" + droneId + ", battery=" + batteryPercent + "%"
                + ", lat=" + currentLat + ", lon=" + currentLon
                + ", cellTypes=" + supportedCellTypes + "}";
    }
}