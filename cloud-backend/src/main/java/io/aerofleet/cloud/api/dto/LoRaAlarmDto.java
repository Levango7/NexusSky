package io.aerofleet.cloud.api.dto;

import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Positive;

/**
 * LoRa 回传报警 DTO（布控球 → 无人机 mesh → 指挥中心）。
 * <p>
 * 从无人机 mesh 网络路由过来的 LoRa 回传报警事件的数据传输对象。
 * 包含布控球设备信息、报警内容、中继无人机信息与信号强度。
 */
public final class LoRaAlarmDto {

    /** 布控球设备 ID。 */
    @Positive
    private int deviceId;
    /** 报警类型（FIRE/INTRUSION/MOTION/UNKNOWN）。 */
    @NotBlank
    private String alarmType;
    /** 报警位置纬度（WGS84，度）。 */
    @DecimalMin("-90.0")
    @DecimalMax("90.0")
    private double lat;
    /** 报警位置经度（WGS84，度）。 */
    @DecimalMin("-180.0")
    @DecimalMax("180.0")
    private double lon;
    /** 报警时间戳（毫秒）。 */
    private long timestamp;
    /** 严重程度（1-5，5 最严重）。 */
    @Min(1)
    @Max(5)
    private int severity;
    /** 中继无人机 sysid。 */
    private int relayDroneSysid;
    /** 信号强度（dBm）。 */
    private double signalStrengthDbm;

    /** 默认构造器（用于 JSON 反序列化）。 */
    public LoRaAlarmDto() {
    }

    /**
     * 创建 LoRa 回传报警 DTO。
     *
     * @param deviceId          布控球设备 ID
     * @param alarmType         报警类型
     * @param lat               纬度
     * @param lon               经度
     * @param timestamp         时间戳
     * @param severity          严重程度
     * @param relayDroneSysid   中继无人机 sysid
     * @param signalStrengthDbm 信号强度（dBm）
     */
    public LoRaAlarmDto(int deviceId, String alarmType, double lat, double lon,
                        long timestamp, int severity, int relayDroneSysid,
                        double signalStrengthDbm) {
        this.deviceId = deviceId;
        this.alarmType = alarmType;
        this.lat = lat;
        this.lon = lon;
        this.timestamp = timestamp;
        this.severity = severity;
        this.relayDroneSysid = relayDroneSysid;
        this.signalStrengthDbm = signalStrengthDbm;
    }

    public int getDeviceId() {
        return deviceId;
    }

    public void setDeviceId(int deviceId) {
        this.deviceId = deviceId;
    }

    public String getAlarmType() {
        return alarmType;
    }

    public void setAlarmType(String alarmType) {
        this.alarmType = alarmType;
    }

    public double getLat() {
        return lat;
    }

    public void setLat(double lat) {
        this.lat = lat;
    }

    public double getLon() {
        return lon;
    }

    public void setLon(double lon) {
        this.lon = lon;
    }

    public long getTimestamp() {
        return timestamp;
    }

    public void setTimestamp(long timestamp) {
        this.timestamp = timestamp;
    }

    public int getSeverity() {
        return severity;
    }

    public void setSeverity(int severity) {
        this.severity = severity;
    }

    public int getRelayDroneSysid() {
        return relayDroneSysid;
    }

    public void setRelayDroneSysid(int relayDroneSysid) {
        this.relayDroneSysid = relayDroneSysid;
    }

    public double getSignalStrengthDbm() {
        return signalStrengthDbm;
    }

    public void setSignalStrengthDbm(double signalStrengthDbm) {
        this.signalStrengthDbm = signalStrengthDbm;
    }

    @Override
    public String toString() {
        return "LoRaAlarmDto{deviceId=" + deviceId
                + ", type=" + alarmType
                + ", lat=" + lat
                + ", lon=" + lon
                + ", ts=" + timestamp
                + ", severity=" + severity
                + ", relay=" + relayDroneSysid
                + ", rssi=" + signalStrengthDbm + "dBm}";
    }
}