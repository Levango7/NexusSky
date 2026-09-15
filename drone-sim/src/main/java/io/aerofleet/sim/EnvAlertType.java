package io.aerofleet.sim;

/**
 * 环境告警类型枚举（FR-18~20）：WIND/TEMP/WEATHER/HUMIDITY。
 * code 对齐 MAVLink ENVIRONMENT_ALERT.alertType 字段。
 */
public enum EnvAlertType {
    WIND(0),
    TEMP(1),
    WEATHER(2),
    HUMIDITY(3);

    /** MAVLink ENVIRONMENT_ALERT.alertType 字段值。 */
    public final int code;

    EnvAlertType(int code) {
        this.code = code;
    }
}