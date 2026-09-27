package io.aerofleet.cloud.rid;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * Remote ID 配置类。
 * <p>
 * 通过 {@code aerofleet.rid.*} 配置项控制 Remote ID 广播行为，包括：
 * <ul>
 *   <li>{@code enabled} — 是否启用 Remote ID 广播</li>
 *   <li>{@code broadcastInterval} — 广播间隔（秒）</li>
 *   <li>{@code timeoutPeriods} — 超时周期数（连续超时该次数后判定为广播错误）</li>
 *   <li>{@code defaultUaType} — 默认无人机类型（1=Helicopter/Multirotor）</li>
 *   <li>{@code defaultSelfId} — 默认 Self ID 描述</li>
 *   <li>{@code operatorId} — 操作者 ID（运行时可更新）</li>
 *   <li>{@code operatorLat} — 操作者纬度（运行时可更新）</li>
 *   <li>{@code operatorLon} — 操作者经度（运行时可更新）</li>
 * </ul>
 */
@Component
@ConfigurationProperties(prefix = "aerofleet.rid")
public class RidConfig {

    /** 是否启用 Remote ID 广播。 */
    private boolean enabled = false;

    /** 广播间隔（秒）。 */
    private double broadcastInterval = 1.0;

    /** 超时周期数。 */
    private int timeoutPeriods = 3;

    /** 默认无人机类型。 */
    private int defaultUaType = 1;

    /** 默认 Self ID 描述。 */
    private String defaultSelfId = "NexusSky drone";

    /** 操作者 ID（运行时可更新）。 */
    private String operatorId;

    /** 操作者纬度（运行时可更新）。 */
    private double operatorLat;

    /** 操作者经度（运行时可更新）。 */
    private double operatorLon;

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public double getBroadcastInterval() {
        return broadcastInterval;
    }

    public void setBroadcastInterval(double broadcastInterval) {
        this.broadcastInterval = broadcastInterval;
    }

    public int getTimeoutPeriods() {
        return timeoutPeriods;
    }

    public void setTimeoutPeriods(int timeoutPeriods) {
        this.timeoutPeriods = timeoutPeriods;
    }

    public int getDefaultUaType() {
        return defaultUaType;
    }

    public void setDefaultUaType(int defaultUaType) {
        this.defaultUaType = defaultUaType;
    }

    public String getDefaultSelfId() {
        return defaultSelfId;
    }

    public void setDefaultSelfId(String defaultSelfId) {
        this.defaultSelfId = defaultSelfId;
    }

    public String getOperatorId() {
        return operatorId;
    }

    public void setOperatorId(String operatorId) {
        this.operatorId = operatorId;
    }

    public double getOperatorLat() {
        return operatorLat;
    }

    public void setOperatorLat(double operatorLat) {
        this.operatorLat = operatorLat;
    }

    public double getOperatorLon() {
        return operatorLon;
    }

    public void setOperatorLon(double operatorLon) {
        this.operatorLon = operatorLon;
    }
}