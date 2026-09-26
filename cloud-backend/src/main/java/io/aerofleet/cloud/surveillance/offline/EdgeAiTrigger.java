package io.aerofleet.cloud.surveillance.offline;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.Collections;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 边缘 AI 触发模拟组件。
 * <p>
 * 模拟布控球内置 AI 检测能力（人形/车辆/火灾/动物），当检测到异常时
 * 自动生成离线报警事件并缓存到 {@link OfflineAlarmCache}。
 * <p>
 * 灾害断网场景下，布控球的边缘 AI 仍可独立运行，无需云端算力支持，
 * 检测结果暂存本地，网络恢复后批量上传。
 * <p>
 * 每台设备可独立配置启用的检测类型，默认启用 PERSON 和 FIRE 检测。
 */
@Component
public class EdgeAiTrigger {

    private static final Logger log = LoggerFactory.getLogger(EdgeAiTrigger.class);

    /** AI 检测类型枚举。 */
    public enum AiDetectType {
        /** 人形检测。 */
        PERSON,
        /** 车辆检测。 */
        VEHICLE,
        /** 火灾检测。 */
        FIRE,
        /** 动物检测。 */
        ANIMAL
    }

    /** 默认启用的检测类型集合。 */
    private static final Set<AiDetectType> DEFAULT_ENABLED_TYPES =
            Collections.unmodifiableSet(EnumSet.of(AiDetectType.PERSON, AiDetectType.FIRE));

    /** 各设备启用的检测类型配置。 */
    private final Map<String, Set<AiDetectType>> deviceDetectionConfig = new ConcurrentHashMap<>();

    /** 各检测类型的触发次数统计。 */
    private final Map<AiDetectType, AtomicLong> detectionCounters = new ConcurrentHashMap<>();

    /** 各检测类型的最近检测时间戳（毫秒）。 */
    private final Map<AiDetectType, Long> lastDetectionTimestamps = new ConcurrentHashMap<>();

    /** 离线报警缓存（可为 null，降级模式）。 */
    @Autowired(required = false)
    private OfflineAlarmCache offlineAlarmCache;

    public EdgeAiTrigger() {
        // 初始化各检测类型的计数器
        for (AiDetectType type : AiDetectType.values()) {
            detectionCounters.put(type, new AtomicLong(0));
        }
    }

    /**
     * 模拟一次 AI 检测触发。
     * <p>
     * 检查目标设备的检测类型是否启用，若启用则生成离线报警事件并缓存。
     *
     * @param deviceId    设备 ID
     * @param deviceName  设备名称
     * @param detectType  检测类型
     * @param lat         纬度
     * @param lon         经度
     * @param description 事件描述
     * @return true 若检测已触发并缓存；false 若该检测类型未启用或缓存不可用
     */
    public boolean triggerDetection(String deviceId, String deviceName,
                                    AiDetectType detectType,
                                    double lat, double lon, String description) {
        if (!isDetectionEnabled(deviceId, detectType)) {
            log.debug("设备 {} 的 {} 检测未启用，跳过触发", deviceId, detectType);
            return false;
        }

        if (offlineAlarmCache == null) {
            log.warn("OfflineAlarmCache 不可用，无法缓存边缘 AI 报警: deviceId={} detectType={}",
                    deviceId, detectType);
            return false;
        }

        long now = System.currentTimeMillis();

        // 构建离线报警事件
        String eventType = mapDetectTypeToEventType(detectType);
        String severity = mapDetectTypeToSeverity(detectType);
        String desc = description != null ? description : buildDefaultDescription(detectType, deviceId);

        OfflineAlarmCache.OfflineAlarmEvent event = new OfflineAlarmCache.OfflineAlarmEvent(
                deviceId,
                deviceName,
                eventType,
                severity,
                desc,
                lat,
                lon,
                0.0,
                now,
                now);

        offlineAlarmCache.cacheAlarm(event);

        // 更新统计
        detectionCounters.get(detectType).incrementAndGet();
        lastDetectionTimestamps.put(detectType, now);

        log.info("边缘 AI 检测触发: deviceId={} detectType={} eventType={} severity={}",
                deviceId, detectType, eventType, severity);
        return true;
    }

    /**
     * 获取边缘 AI 检测统计信息。
     *
     * @return 统计 map（含各类型检测次数、最近检测时间）
     */
    public Map<String, Object> getDetectionStats() {
        Map<String, Object> stats = new LinkedHashMap<>();

        // 各检测类型触发次数
        Map<String, Long> counts = new LinkedHashMap<>();
        for (AiDetectType type : AiDetectType.values()) {
            counts.put(type.name(), detectionCounters.get(type).get());
        }
        stats.put("detectionCounts", counts);

        // 各检测类型最近触发时间
        Map<String, Long> lastTimes = new LinkedHashMap<>();
        for (AiDetectType type : AiDetectType.values()) {
            lastTimes.put(type.name(), lastDetectionTimestamps.getOrDefault(type, 0L));
        }
        stats.put("lastDetectionTimestamps", lastTimes);

        // 总触发次数
        long totalDetections = 0;
        for (AtomicLong counter : detectionCounters.values()) {
            totalDetections += counter.get();
        }
        stats.put("totalDetections", totalDetections);

        return stats;
    }

    /**
     * 配置某设备的启用检测类型。
     * <p>
     * 覆盖该设备之前的配置。传入空集合表示禁用所有检测类型。
     *
     * @param deviceId     设备 ID
     * @param enabledTypes 启用的检测类型集合
     */
    public void configureDetection(String deviceId, Set<AiDetectType> enabledTypes) {
        if (deviceId == null) {
            return;
        }
        Set<AiDetectType> types;
        if (enabledTypes == null || enabledTypes.isEmpty()) {
            types = Collections.emptySet();
        } else {
            types = Collections.unmodifiableSet(EnumSet.copyOf(enabledTypes));
        }
        deviceDetectionConfig.put(deviceId, types);
        log.info("设备 {} 的边缘 AI 检测配置已更新: {}", deviceId, types);
    }

    /**
     * 检查某设备的某类型检测是否启用。
     * <p>
     * 若设备未配置过，使用默认配置（PERSON + FIRE）。
     *
     * @param deviceId 设备 ID
     * @param type     检测类型
     * @return true 若该检测类型已启用
     */
    public boolean isDetectionEnabled(String deviceId, AiDetectType type) {
        if (deviceId == null || type == null) {
            return false;
        }
        Set<AiDetectType> config = deviceDetectionConfig.get(deviceId);
        if (config == null) {
            return DEFAULT_ENABLED_TYPES.contains(type);
        }
        return config.contains(type);
    }

    /**
     * 将检测类型映射为报警事件类型字符串。
     */
    private static String mapDetectTypeToEventType(AiDetectType detectType) {
        switch (detectType) {
            case PERSON:
                return "INTRUSION";
            case VEHICLE:
                return "MOTION";
            case FIRE:
                return "FIRE";
            case ANIMAL:
                return "CUSTOM";
            default:
                return "CUSTOM";
        }
    }

    /**
     * 将检测类型映射为严重程度字符串。
     */
    private static String mapDetectTypeToSeverity(AiDetectType detectType) {
        switch (detectType) {
            case FIRE:
                return "CRITICAL";
            case PERSON:
                return "WARN";
            case VEHICLE:
                return "INFO";
            case ANIMAL:
                return "INFO";
            default:
                return "INFO";
        }
    }

    /**
     * 构建默认事件描述。
     */
    private static String buildDefaultDescription(AiDetectType detectType, String deviceId) {
        switch (detectType) {
            case PERSON:
                return "边缘AI人形检测告警: 设备=" + deviceId;
            case VEHICLE:
                return "边缘AI车辆检测告警: 设备=" + deviceId;
            case FIRE:
                return "边缘AI火灾检测告警: 设备=" + deviceId;
            case ANIMAL:
                return "边缘AI动物检测告警: 设备=" + deviceId;
            default:
                return "边缘AI检测告警: 设备=" + deviceId;
        }
    }
}