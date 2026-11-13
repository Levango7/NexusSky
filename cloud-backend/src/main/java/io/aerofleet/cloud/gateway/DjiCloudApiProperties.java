package io.aerofleet.cloud.gateway;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * DJI Cloud API 栈配置（{@code aerofleet.dji-cloud.*}）。
 * <p>
 * 与 F2 机巢的 {@code aerofleet.dock.transport=mqtt} 同一口径：缺省关闭、
 * 显式启用、缺 broker 地址启动 fail-fast。
 */
@Component
@ConfigurationProperties(prefix = "aerofleet.dji-cloud")
public class DjiCloudApiProperties {

    /** 是否装配 DJI Cloud API 设备网关。缺省 false——命令面默认走无栈拒绝（WARN + false）。 */
    private boolean enabled = false;

    /** Broker 地址（tcp://host:1883 或 ssl://…）。enabled=true 时必填（空缺启动 fail-fast）。 */
    private String mqttBrokerUrl = "";

    /** mqtt 凭据（可空；EMQX 等 Broker 常要求）。 */
    private String mqttUsername = "";
    private String mqttPassword = "";

    /** 命令应答超时（ms）。超时返回 false（不假成功），记 WARN。 */
    private int commandTimeoutMs = 8000;

    public boolean isEnabled() { return enabled; }
    public void setEnabled(boolean enabled) { this.enabled = enabled; }
    public String getMqttBrokerUrl() { return mqttBrokerUrl; }
    public void setMqttBrokerUrl(String mqttBrokerUrl) { this.mqttBrokerUrl = mqttBrokerUrl; }
    public String getMqttUsername() { return mqttUsername; }
    public void setMqttUsername(String mqttUsername) { this.mqttUsername = mqttUsername; }
    public String getMqttPassword() { return mqttPassword; }
    public void setMqttPassword(String mqttPassword) { this.mqttPassword = mqttPassword; }
    public int getCommandTimeoutMs() { return commandTimeoutMs; }
    public void setCommandTimeoutMs(int commandTimeoutMs) { this.commandTimeoutMs = commandTimeoutMs; }
}
