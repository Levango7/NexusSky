package io.aerofleet.cloud.regulator;

import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * 监管对接配置类。
 * <p>
 * 通过 {@code aerofleet.regulator.*} 配置项控制监管平台对接行为，包括：
 * <ul>
 *   <li>{@code sinkType} — 上报通道类型：{@code uom}（真实 UOM 平台）/ {@code sim}（模拟平台）/ {@code none}（空实现）</li>
 *   <li>{@code uomBaseUrl} — UOM 平台基础 URL</li>
 *   <li>{@code simBaseUrl} — 模拟监管平台基础 URL</li>
 *   <li>{@code telemetryReportInterval} — 遥测上报周期（秒），有效范围 10-300</li>
 *   <li>{@code httpTimeoutMs} — HTTP 请求超时（毫秒）</li>
 *   <li>{@code maxRetry} — 失败最大重试次数</li>
 *   <li>{@code apiKey} — UOM 平台 API Key（可选，仅 {@code sinkType=uom} 时使用）</li>
 *   <li>{@code signingKey} — 请求签名密钥（可选，仅 {@code sinkType=uom} 时使用）</li>
 *   <li>{@code reportQueueCapacity} — 上报队列容量</li>
 * </ul>
 * <p>
 * 当 {@code telemetryReportInterval} 超出 10-300 范围时，启动阶段输出 WARN 日志并回退至默认值 60 秒。
 */
@Component
@ConfigurationProperties(prefix = "aerofleet.regulator")
public class RegulatorConfig {

    private static final Logger log = LoggerFactory.getLogger(RegulatorConfig.class);

    /** 遥测上报周期默认值（秒）。 */
    private static final int DEFAULT_TELEMETRY_REPORT_INTERVAL = 60;

    /** 遥测上报周期最小值（秒）。 */
    private static final int MIN_TELEMETRY_REPORT_INTERVAL = 10;

    /** 遥测上报周期最大值（秒）。 */
    private static final int MAX_TELEMETRY_REPORT_INTERVAL = 300;

    /** 上报通道类型：uom / sim / none。 */
    private String sinkType = "sim";

    /** UOM 平台基础 URL。 */
    private String uomBaseUrl = "https://uom.caac.gov.cn";

    /** 模拟监管平台基础 URL。 */
    private String simBaseUrl = "http://localhost:18080";

    /** 遥测上报周期（秒），有效范围 10-300。 */
    private int telemetryReportInterval = DEFAULT_TELEMETRY_REPORT_INTERVAL;

    /** HTTP 请求超时（毫秒）。 */
    private int httpTimeoutMs = 5000;

    /** 失败最大重试次数。 */
    private int maxRetry = 3;

    /** UOM 平台 API Key（可选，仅 sinkType=uom 时使用）。 */
    private String apiKey;

    /** 请求签名密钥（可选，仅 sinkType=uom 时使用）。 */
    private String signingKey;

    /** 上报队列容量。 */
    private int reportQueueCapacity = 1000;

    /**
     * 启动后校验 {@code telemetryReportInterval} 范围。
     * <p>
     * 若配置值超出 10-300 范围，输出 WARN 日志并回退至默认值 60 秒。
     */
    @PostConstruct
    public void validateTelemetryReportInterval() {
        if (telemetryReportInterval < MIN_TELEMETRY_REPORT_INTERVAL
                || telemetryReportInterval > MAX_TELEMETRY_REPORT_INTERVAL) {
            log.warn("telemetryReportInterval={} 超出有效范围 [{}-{}]，回退至默认值 {}",
                    telemetryReportInterval,
                    MIN_TELEMETRY_REPORT_INTERVAL,
                    MAX_TELEMETRY_REPORT_INTERVAL,
                    DEFAULT_TELEMETRY_REPORT_INTERVAL);
            telemetryReportInterval = DEFAULT_TELEMETRY_REPORT_INTERVAL;
        }
    }

    public String getSinkType() {
        return sinkType;
    }

    public void setSinkType(String sinkType) {
        this.sinkType = sinkType;
    }

    public String getUomBaseUrl() {
        return uomBaseUrl;
    }

    public void setUomBaseUrl(String uomBaseUrl) {
        this.uomBaseUrl = uomBaseUrl;
    }

    public String getSimBaseUrl() {
        return simBaseUrl;
    }

    public void setSimBaseUrl(String simBaseUrl) {
        this.simBaseUrl = simBaseUrl;
    }

    public int getTelemetryReportInterval() {
        return telemetryReportInterval;
    }

    public void setTelemetryReportInterval(int telemetryReportInterval) {
        this.telemetryReportInterval = telemetryReportInterval;
    }

    public int getHttpTimeoutMs() {
        return httpTimeoutMs;
    }

    public void setHttpTimeoutMs(int httpTimeoutMs) {
        this.httpTimeoutMs = httpTimeoutMs;
    }

    public int getMaxRetry() {
        return maxRetry;
    }

    public void setMaxRetry(int maxRetry) {
        this.maxRetry = maxRetry;
    }

    public String getApiKey() {
        return apiKey;
    }

    public void setApiKey(String apiKey) {
        this.apiKey = apiKey;
    }

    public String getSigningKey() {
        return signingKey;
    }

    public void setSigningKey(String signingKey) {
        this.signingKey = signingKey;
    }

    public int getReportQueueCapacity() {
        return reportQueueCapacity;
    }

    public void setReportQueueCapacity(int reportQueueCapacity) {
        this.reportQueueCapacity = reportQueueCapacity;
    }
}