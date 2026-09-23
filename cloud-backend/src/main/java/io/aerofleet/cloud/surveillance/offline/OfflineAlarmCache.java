package io.aerofleet.cloud.surveillance.offline;

import io.aerofleet.cloud.alarm.AlarmEvent;
import io.aerofleet.cloud.alarm.AlarmEventStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentLinkedQueue;

/**
 * 本地报警缓存组件。
 * <p>
 * 灾害断网场景下，安防设备与云端失联时在本地缓存报警事件；
 * 网络恢复后通过 {@link #flushToStore(AlarmEventStore)} 批量上传到
 * {@link AlarmEventStore}，确保报警事件不丢失。
 * <p>
 * 线程安全：使用 {@link ConcurrentLinkedQueue} 存储离线报警事件，
 * 适用于多线程并发写入场景（如多个传感器同时触发报警）。
 * <p>
 * 容量限制：默认上限 {@value #DEFAULT_MAX_CACHE_SIZE} 条，
 * 超限时自动驱逐最旧事件（FIFO 策略），防止内存溢出。
 */
@Component
public class OfflineAlarmCache {

    private static final Logger log = LoggerFactory.getLogger(OfflineAlarmCache.class);

    /** 默认最大缓存容量。 */
    public static final int DEFAULT_MAX_CACHE_SIZE = 500;

    private final ConcurrentLinkedQueue<OfflineAlarmEvent> queue = new ConcurrentLinkedQueue<>();
    private final int maxCacheSize;

    /** 报警事件存储（可为 null，降级模式）。 */
    @Autowired(required = false)
    private AlarmEventStore alarmEventStore;

    public OfflineAlarmCache() {
        this(DEFAULT_MAX_CACHE_SIZE);
    }

    public OfflineAlarmCache(int maxCacheSize) {
        if (maxCacheSize <= 0) {
            throw new IllegalArgumentException("maxCacheSize must be positive: " + maxCacheSize);
        }
        this.maxCacheSize = maxCacheSize;
    }

    /**
     * 缓存一条离线报警事件。
     * <p>
     * 当缓存数量超过 {@link #maxCacheSize} 时，驱逐最旧的事件（队首元素）。
     *
     * @param event 离线报警事件
     */
    public void cacheAlarm(OfflineAlarmEvent event) {
        if (event == null) {
            return;
        }
        queue.offer(event);
        // 超容量时驱逐最旧事件
        while (queue.size() > maxCacheSize) {
            OfflineAlarmEvent evicted = queue.poll();
            if (evicted != null) {
                log.warn("离线报警缓存超容量，驱逐最旧事件: deviceId={} timestampMs={}",
                        evicted.deviceId, evicted.timestampMs);
            }
        }
        log.debug("离线报警已缓存: deviceId={} eventType={} severity={} pendingCount={}",
                event.deviceId, event.eventType, event.severity, queue.size());
    }

    /**
     * 获取所有待上传的离线报警事件列表。
     * <p>
     * 返回队列的快照副本，不修改原队列。
     *
     * @return 待上传报警事件列表
     */
    public List<OfflineAlarmEvent> getPendingAlarms() {
        return new ArrayList<>(queue);
    }

    /**
     * 网络恢复后批量上传缓存事件到 AlarmEventStore。
     * <p>
     * 将每条离线报警事件转换为标准 {@link AlarmEvent} 格式并存储。
     * 上传成功后清空缓存。
     * <p>
     * 降级模式：若传入的 store 为 null，则尝试使用注入的 alarmEventStore；
     * 若两者均为 null，则返回 0。
     *
     * @param store 报警事件存储（可为 null，则使用注入的依赖）
     * @return 上传成功的事件数量
     */
    public int flushToStore(AlarmEventStore store) {
        AlarmEventStore target = store != null ? store : alarmEventStore;
        if (target == null) {
            log.warn("AlarmEventStore 不可用，无法上传离线报警缓存");
            return 0;
        }
        if (queue.isEmpty()) {
            return 0;
        }

        int successCount = 0;
        OfflineAlarmEvent event;
        while ((event = queue.poll()) != null) {
            try {
                AlarmEvent alarmEvent = convertToAlarmEvent(event);
                target.store(alarmEvent);
                successCount++;
            } catch (Exception e) {
                log.error("离线报警上传失败: deviceId={} eventType={}",
                        event.deviceId, event.eventType, e);
                // 上传失败的事件重新放回队列，等待下次重试
                queue.offer(event);
            }
        }
        log.info("离线报警批量上传完成: 成功 {} 条", successCount);
        return successCount;
    }

    /**
     * 清空缓存。
     */
    public void clear() {
        queue.clear();
        log.info("离线报警缓存已清空");
    }

    /**
     * 待上传报警事件数量。
     *
     * @return 当前缓存中的事件数量
     */
    public int pendingCount() {
        return queue.size();
    }

    /**
     * 获取缓存统计信息。
     *
     * @return 统计 map（含 totalCount/oldestTimestampMs/newestTimestampMs）
     */
    public Map<String, Object> getCacheStats() {
        Map<String, Object> stats = new LinkedHashMap<>();
        stats.put("totalCount", queue.size());
        stats.put("maxCacheSize", maxCacheSize);

        long oldest = Long.MAX_VALUE;
        long newest = Long.MIN_VALUE;
        for (OfflineAlarmEvent e : queue) {
            if (e.timestampMs < oldest) {
                oldest = e.timestampMs;
            }
            if (e.timestampMs > newest) {
                newest = e.timestampMs;
            }
        }
        stats.put("oldestTimestampMs", queue.isEmpty() ? 0L : oldest);
        stats.put("newestTimestampMs", queue.isEmpty() ? 0L : newest);
        return stats;
    }

    /**
     * 将离线报警事件转换为标准 AlarmEvent。
     */
    private static AlarmEvent convertToAlarmEvent(OfflineAlarmEvent event) {
        String eventId = UUID.randomUUID().toString();
        AlarmEvent.EventType eventType = AlarmEvent.parseEventType(event.eventType);
        AlarmEvent.Severity severity = AlarmEvent.Severity.fromString(event.severity);

        return new AlarmEvent(
                eventId,
                event.deviceId,
                event.deviceName,
                eventType,
                severity,
                event.description,
                event.lat,
                event.lon,
                event.alt,
                event.timestampMs,
                false);
    }

    /**
     * 离线报警事件数据类。
     * <p>
     * 包含安防设备在断网期间产生的报警信息，网络恢复后批量上传。
     */
    public static class OfflineAlarmEvent {
        /** 设备 ID。 */
        private final String deviceId;
        /** 设备名称。 */
        private final String deviceName;
        /** 事件类型（MOTION/INTRUSION/FIRE/DOOR/CUSTOM）。 */
        private final String eventType;
        /** 严重程度（INFO/WARN/CRITICAL）。 */
        private final String severity;
        /** 事件描述。 */
        private final String description;
        /** 纬度（WGS84，度）。 */
        private final double lat;
        /** 经度（WGS84，度）。 */
        private final double lon;
        /** 海拔（米）。 */
        private final double alt;
        /** 触发时间戳（毫秒）。 */
        private final long timestampMs;
        /** 缓存时间戳（毫秒）。 */
        private final long cachedAtMs;

        public OfflineAlarmEvent(String deviceId, String deviceName, String eventType,
                                 String severity, String description,
                                 double lat, double lon, double alt,
                                 long timestampMs, long cachedAtMs) {
            this.deviceId = deviceId;
            this.deviceName = deviceName;
            this.eventType = eventType;
            this.severity = severity;
            this.description = description;
            this.lat = lat;
            this.lon = lon;
            this.alt = alt;
            this.timestampMs = timestampMs;
            this.cachedAtMs = cachedAtMs;
        }

        public String getDeviceId() {
            return deviceId;
        }

        public String getDeviceName() {
            return deviceName;
        }

        public String getEventType() {
            return eventType;
        }

        public String getSeverity() {
            return severity;
        }

        public String getDescription() {
            return description;
        }

        public double getLat() {
            return lat;
        }

        public double getLon() {
            return lon;
        }

        public double getAlt() {
            return alt;
        }

        public long getTimestampMs() {
            return timestampMs;
        }

        public long getCachedAtMs() {
            return cachedAtMs;
        }

        @Override
        public String toString() {
            return "OfflineAlarmEvent{deviceId=" + deviceId
                    + ", eventType=" + eventType
                    + ", severity=" + severity
                    + ", timestampMs=" + timestampMs + '}';
        }
    }
}