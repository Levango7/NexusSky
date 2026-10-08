package io.aerofleet.cloud.dock;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * F2 机巢管控配置（aerofleet.dock.*）。
 * <p>
 * transport 默认 sim（HTTP 回环到机巢模拟器），与 C1 的 NoopReportSink 同型：
 * 缺省即工作，MQTT 属生产阶段显式开启。
 */
@Component
@ConfigurationProperties(prefix = "aerofleet.dock")
public class DockProperties {

    /** 传输实现：sim（默认）| mqtt（生产 seam，需 Broker，见 README 边界）。 */
    private String transport = "sim";

    /** sim 模式机巢模拟器基址（dock-sim 的 HTTP 端口）。 */
    private String simBaseUrl = "http://127.0.0.1:18081";

    /** 命令超时（ms）：超时记 WARN 并返回 504，不自动置 FAULT。 */
    private int commandTimeoutMs = 5000;

    /** 心跳周期与离线判定：超过 periods × heartbeatPeriodMs 无 OSD 判 OFFLINE。 */
    private long heartbeatPeriodMs = 5000;
    private int offlineAfterPeriods = 3;

    /** 温度阈值缺省（单巢可覆盖）：≥warn 记温度告警，≥crit 置 FAULT。 */
    private double tempWarnC = 55;
    private double tempCritC = 70;

    /** 无人值守调度轮询间隔（ms）。 */
    private long schedulePollMs = 30000;

    /** 单次无人值守任务的飞行超时（min），超时判 FAILED 并尝试关门。 */
    private int unattendedFlightTimeoutMin = 30;

    /** 日结度量重算回溯天数（幂等重算窗口）。 */
    private int metricsRollupDays = 7;

    public String getTransport() { return transport; }
    public void setTransport(String transport) { this.transport = transport; }
    public String getSimBaseUrl() { return simBaseUrl; }
    public void setSimBaseUrl(String simBaseUrl) { this.simBaseUrl = simBaseUrl; }
    public int getCommandTimeoutMs() { return commandTimeoutMs; }
    public void setCommandTimeoutMs(int commandTimeoutMs) { this.commandTimeoutMs = commandTimeoutMs; }
    public long getHeartbeatPeriodMs() { return heartbeatPeriodMs; }
    public void setHeartbeatPeriodMs(long heartbeatPeriodMs) { this.heartbeatPeriodMs = heartbeatPeriodMs; }
    public int getOfflineAfterPeriods() { return offlineAfterPeriods; }
    public void setOfflineAfterPeriods(int offlineAfterPeriods) { this.offlineAfterPeriods = offlineAfterPeriods; }
    public double getTempWarnC() { return tempWarnC; }
    public void setTempWarnC(double tempWarnC) { this.tempWarnC = tempWarnC; }
    public double getTempCritC() { return tempCritC; }
    public void setTempCritC(double tempCritC) { this.tempCritC = tempCritC; }
    public long getSchedulePollMs() { return schedulePollMs; }
    public void setSchedulePollMs(long schedulePollMs) { this.schedulePollMs = schedulePollMs; }
    public int getUnattendedFlightTimeoutMin() { return unattendedFlightTimeoutMin; }
    public void setUnattendedFlightTimeoutMin(int v) { this.unattendedFlightTimeoutMin = v; }
    public int getMetricsRollupDays() { return metricsRollupDays; }
    public void setMetricsRollupDays(int metricsRollupDays) { this.metricsRollupDays = metricsRollupDays; }
}
