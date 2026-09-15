package io.aerofleet.sim;

import io.aerofleet.mavlink.messages.EnvironmentAlert;

/**
 * 环境告警事件载体（record，不可变）。
 * 包私有——由同包 {@link EnvAlertEngine} 产出，{@link EnvironmentModel} 消费。
 *
 * @param type     告警类型
 * @param severity MAV_SEVERITY 级别（critical=2/warning=4/info=6，FR-21）
 * @param value    触发时实测值
 * @param threshold 触发阈值
 * @param text     人类可读描述
 */
record EnvAlert(EnvAlertType type, int severity, double value,
                double threshold, String text) {
    /**
     * 转换为 MAVLink ENVIRONMENT_ALERT 消息（FR-25）。
     * value/threshold 转为 int16 单位（×10）。
     */
    EnvironmentAlert toMessage() {
        return new EnvironmentAlert(type.code, severity,
                (int) Math.round(value * 10), (int) Math.round(threshold * 10), text);
    }
}