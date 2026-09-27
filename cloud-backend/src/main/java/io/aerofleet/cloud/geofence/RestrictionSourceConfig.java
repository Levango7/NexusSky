package io.aerofleet.cloud.geofence;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * 限飞区数据源配置类。
 * <p>
 * 通过 {@code aerofleet.geofence.restriction.*} 配置项控制限飞区缓存行为，包括：
 * <ul>
 *   <li>{@code sourceType} — 数据源类型：{@code MOCK}（模拟数据）/ {@code LOCAL_FILE}（本地文件）/ {@code HTTP}（远程 API）</li>
 *   <li>{@code sourcePath} — 数据源路径（LOCAL_FILE 时为文件路径，HTTP 时为 URL）</li>
 *   <li>{@code refreshIntervalMs} — 定期刷新间隔（毫秒），默认 86400000（24 小时）</li>
 *   <li>{@code fetchTimeoutMs} — 拉取超时（毫秒），默认 30000</li>
 *   <li>{@code enabled} — 是否启用限飞区缓存，默认 true</li>
 *   <li>{@code cacheTtlMs} — 缓存 TTL（毫秒），超过后标记为 stale，默认 86400000</li>
 * </ul>
 */
@Component
@ConfigurationProperties(prefix = "aerofleet.geofence.restriction")
public class RestrictionSourceConfig {

    /** 数据源类型枚举。 */
    public enum SourceType { MOCK, LOCAL_FILE, HTTP }

    /** 数据源类型，默认 MOCK。 */
    private SourceType sourceType = SourceType.MOCK;

    /** 数据源路径（LOCAL_FILE 时为文件路径，HTTP 时为 URL）。 */
    private String sourcePath = "";

    /** 定期刷新间隔（毫秒），默认 24 小时。 */
    private long refreshIntervalMs = 86400000;

    /** 拉取超时（毫秒），默认 30 秒。 */
    private long fetchTimeoutMs = 30000;

    /** 是否启用限飞区缓存，默认 true。 */
    private boolean enabled = true;

    /** 缓存 TTL（毫秒），超过后标记为 stale，默认 24 小时。 */
    private long cacheTtlMs = 86400000;

    public SourceType getSourceType() {
        return sourceType;
    }

    public void setSourceType(SourceType sourceType) {
        this.sourceType = sourceType;
    }

    public String getSourcePath() {
        return sourcePath;
    }

    public void setSourcePath(String sourcePath) {
        this.sourcePath = sourcePath;
    }

    public long getRefreshIntervalMs() {
        return refreshIntervalMs;
    }

    public void setRefreshIntervalMs(long refreshIntervalMs) {
        this.refreshIntervalMs = refreshIntervalMs;
    }

    public long getFetchTimeoutMs() {
        return fetchTimeoutMs;
    }

    public void setFetchTimeoutMs(long fetchTimeoutMs) {
        this.fetchTimeoutMs = fetchTimeoutMs;
    }

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public long getCacheTtlMs() {
        return cacheTtlMs;
    }

    public void setCacheTtlMs(long cacheTtlMs) {
        this.cacheTtlMs = cacheTtlMs;
    }
}