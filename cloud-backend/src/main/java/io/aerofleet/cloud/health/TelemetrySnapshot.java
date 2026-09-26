package io.aerofleet.cloud.health;

import java.util.Collections;
import java.util.List;

/**
 * 健康监控输入遥测快照（P1-2 健康管理）。
 * <p>
 * 从 {@code DroneSnapshot} 提取与部件健康相关的指标，作为
 * {@link HealthMonitorService#calculateScore(int, TelemetrySnapshot)} 的输入。
 * 解耦 DroneSnapshot 与评分逻辑，便于单元测试构造数据。
 * <p>
 * 字段语义：
 * <ul>
 *   <li>{@code batteryPct} — 电池电量百分比 [0,100]，-1 表示未知</li>
 *   <li>{@code motorRpms} — 各电机转速（RPM）列表，用于计算转速稳定性</li>
 *   <li>{@code vibrationG} — 整机振动幅值（g），>=0</li>
 *   <li>{@code temperatureC} — 核心部件温度（°C）</li>
 *   <li>{@code rssiDbm} — 链路 RSSI（dBm，负值）</li>
 *   <li>{@code imuDrift} — 陀螺仪漂移（°/s），>=0</li>
 *   <li>{@code gpsSatellites} — GPS 卫星数</li>
 *   <li>{@code gpsHdop} — GPS HDOP 精度衰减因子，>=0</li>
 *   <li>{@code batteryCycles} — 电池循环次数（预测性维护用）</li>
 * </ul>
 */
public class TelemetrySnapshot {

    private final long timestamp;
    private double batteryPct = -1;
    private List<Double> motorRpms = Collections.emptyList();
    private double vibrationG = 0;
    private double temperatureC = 25;
    private double rssiDbm = -70;
    private double imuDrift = 0;
    private int gpsSatellites = 0;
    private double gpsHdop = 99;
    private int batteryCycles = 0;

    public TelemetrySnapshot(long timestamp) {
        this.timestamp = timestamp;
    }

    public long getTimestamp() {
        return timestamp;
    }

    public double getBatteryPct() {
        return batteryPct;
    }

    public void setBatteryPct(double batteryPct) {
        this.batteryPct = batteryPct;
    }

    public List<Double> getMotorRpms() {
        return motorRpms;
    }

    public void setMotorRpms(List<Double> motorRpms) {
        this.motorRpms = motorRpms == null ? Collections.emptyList() : motorRpms;
    }

    public double getVibrationG() {
        return vibrationG;
    }

    public void setVibrationG(double vibrationG) {
        this.vibrationG = vibrationG;
    }

    public double getTemperatureC() {
        return temperatureC;
    }

    public void setTemperatureC(double temperatureC) {
        this.temperatureC = temperatureC;
    }

    public double getRssiDbm() {
        return rssiDbm;
    }

    public void setRssiDbm(double rssiDbm) {
        this.rssiDbm = rssiDbm;
    }

    public double getImuDrift() {
        return imuDrift;
    }

    public void setImuDrift(double imuDrift) {
        this.imuDrift = imuDrift;
    }

    public int getGpsSatellites() {
        return gpsSatellites;
    }

    public void setGpsSatellites(int gpsSatellites) {
        this.gpsSatellites = gpsSatellites;
    }

    public double getGpsHdop() {
        return gpsHdop;
    }

    public void setGpsHdop(double gpsHdop) {
        this.gpsHdop = gpsHdop;
    }

    public int getBatteryCycles() {
        return batteryCycles;
    }

    public void setBatteryCycles(int batteryCycles) {
        this.batteryCycles = batteryCycles;
    }
}