package io.aerofleet.cloud.surveillance.offline;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link EdgeAiTrigger} 单元测试（该子包此前**零测试**）。
 *
 * <p>覆盖三件事：默认配置、触发后事件内容（类型/严重度/坐标/描述）、统计与非法入参。
 * 这些都是断网自治场景下"设备侧到底报了什么样的警"的对外承诺。
 */
@DisplayName("EdgeAiTrigger 边缘 AI 触发（离线自治）")
class EdgeAiTriggerTest {

    private EdgeAiTrigger trigger;
    private OfflineAlarmCache cache;

    @BeforeEach
    void setUp() {
        trigger = new EdgeAiTrigger();
        cache = new OfflineAlarmCache(16);
        ReflectionTestUtils.setField(trigger, "offlineAlarmCache", cache);
    }

    // ===== 默认配置 =====

    @Test
    @DisplayName("未配置的设备默认只开 PERSON + FIRE")
    void defaultConfigEnablesPersonAndFireOnly() {
        assertThat(trigger.isDetectionEnabled("cam-unknown", EdgeAiTrigger.AiDetectType.PERSON)).isTrue();
        assertThat(trigger.isDetectionEnabled("cam-unknown", EdgeAiTrigger.AiDetectType.FIRE)).isTrue();
        assertThat(trigger.isDetectionEnabled("cam-unknown", EdgeAiTrigger.AiDetectType.VEHICLE)).isFalse();
        assertThat(trigger.isDetectionEnabled("cam-unknown", EdgeAiTrigger.AiDetectType.ANIMAL)).isFalse();
    }

    @Test
    @DisplayName("deviceId 或 detectType 为 null 时一律视为未启用")
    void nullArgsAreNotEnabled() {
        assertThat(trigger.isDetectionEnabled(null, EdgeAiTrigger.AiDetectType.PERSON)).isFalse();
        assertThat(trigger.isDetectionEnabled("cam-1", null)).isFalse();
    }

    // ===== 触发 =====

    @Test
    @DisplayName("人形触发 ⇒ 缓存 INTRUSION/WARN，坐标与时间戳跟着事件走")
    void personTriggerCachesIntrusionWarnEvent() {
        long before = System.currentTimeMillis();
        boolean triggered = trigger.triggerDetection(
                "cam-1", "东门球机", EdgeAiTrigger.AiDetectType.PERSON, 30.123, 120.456, null);
        long after = System.currentTimeMillis();

        assertThat(triggered).isTrue();
        assertThat(cache.pendingCount()).isEqualTo(1);

        OfflineAlarmCache.OfflineAlarmEvent e = cache.getPendingAlarms().get(0);
        assertThat(e.getDeviceId()).isEqualTo("cam-1");
        assertThat(e.getDeviceName()).isEqualTo("东门球机");
        assertThat(e.getEventType()).isEqualTo("INTRUSION");
        assertThat(e.getSeverity()).isEqualTo("WARN");
        assertThat(e.getLat()).isEqualTo(30.123);
        assertThat(e.getLon()).isEqualTo(120.456);
        assertThat(e.getAlt()).isEqualTo(0.0);
        assertThat(e.getTimestampMs()).isBetween(before, after);
        assertThat(e.getDescription()).contains("人形").contains("cam-1");
    }

    @Test
    @DisplayName("火灾触发 ⇒ 事件类型 FIRE、严重度 CRITICAL（不能被降级成 WARN）")
    void fireTriggerIsCritical() {
        assertThat(trigger.triggerDetection(
                "cam-2", "仓库球机", EdgeAiTrigger.AiDetectType.FIRE, 0, 0, "仓库冒烟")).isTrue();

        OfflineAlarmCache.OfflineAlarmEvent e = cache.getPendingAlarms().get(0);
        assertThat(e.getEventType()).isEqualTo("FIRE");
        assertThat(e.getSeverity()).isEqualTo("CRITICAL");
        assertThat(e.getDescription()).isEqualTo("仓库冒烟");
    }

    @Test
    @DisplayName("未启用的检测类型不触发、不缓存")
    void disabledTypeIsSkipped() {
        assertThat(trigger.triggerDetection(
                "cam-1", "东门球机", EdgeAiTrigger.AiDetectType.VEHICLE, 0, 0, null)).isFalse();
        assertThat(cache.pendingCount()).isZero();
    }

    @Test
    @DisplayName("缓存依赖缺失时返回 false（明确降级，不能 NPE）")
    void missingCacheReturnsFalse() {
        EdgeAiTrigger bare = new EdgeAiTrigger();
        assertThat(bare.triggerDetection(
                "cam-1", "东门球机", EdgeAiTrigger.AiDetectType.PERSON, 0, 0, null)).isFalse();
        assertThat((Long) bare.getDetectionStats().get("totalDetections")).isZero();
    }

    // ===== 配置覆盖 =====

    @Test
    @DisplayName("configureDetection 覆盖默认配置：只开 VEHICLE 后 PERSON 不再触发")
    void configureOverridesDefaults() {
        trigger.configureDetection("cam-9", Set.of(EdgeAiTrigger.AiDetectType.VEHICLE));

        assertThat(trigger.isDetectionEnabled("cam-9", EdgeAiTrigger.AiDetectType.VEHICLE)).isTrue();
        assertThat(trigger.isDetectionEnabled("cam-9", EdgeAiTrigger.AiDetectType.PERSON)).isFalse();
        assertThat(trigger.triggerDetection(
                "cam-9", "路口球机", EdgeAiTrigger.AiDetectType.PERSON, 0, 0, null)).isFalse();
        assertThat(trigger.triggerDetection(
                "cam-9", "路口球机", EdgeAiTrigger.AiDetectType.VEHICLE, 0, 0, null)).isTrue();
    }

    @Test
    @DisplayName("传空集合 = 关闭该设备全部检测")
    void emptyConfigDisablesAll() {
        trigger.configureDetection("cam-9", Set.of());
        for (EdgeAiTrigger.AiDetectType type : EdgeAiTrigger.AiDetectType.values()) {
            assertThat(trigger.isDetectionEnabled("cam-9", type)).as(type.name()).isFalse();
        }
    }

    @Test
    @DisplayName("configureDetection(null, ...) 不抛异常也不影响已有配置")
    void nullDeviceConfigureIsIgnored() {
        trigger.configureDetection("cam-1", Set.of(EdgeAiTrigger.AiDetectType.ANIMAL));
        trigger.configureDetection(null, Set.of(EdgeAiTrigger.AiDetectType.PERSON));

        assertThat(trigger.isDetectionEnabled("cam-1", EdgeAiTrigger.AiDetectType.ANIMAL)).isTrue();
    }

    @Test
    @DisplayName("车辆/动物/火灾的映射与默认描述：每条分支都有人看，不能返回空串")
    void mappingsAndDefaultDescriptionsForEveryType() {
        // 车辆默认不开，先单独配置；车辆 + 动物两类映射此前无人覆盖
        trigger.configureDetection("cam-v", Set.of(
                EdgeAiTrigger.AiDetectType.VEHICLE, EdgeAiTrigger.AiDetectType.ANIMAL));

        assertThat(trigger.triggerDetection(
                "cam-v", "路口球机", EdgeAiTrigger.AiDetectType.VEHICLE, 0, 0, null)).isTrue();
        assertThat(trigger.triggerDetection(
                "cam-v", "路口球机", EdgeAiTrigger.AiDetectType.ANIMAL, 0, 0, null)).isTrue();
        // 火灾走默认配置即可，但描述必须显式断言（此前只测了自定义描述）
        assertThat(trigger.triggerDetection(
                "cam-f", "仓库球机", EdgeAiTrigger.AiDetectType.FIRE, 0, 0, null)).isTrue();

        List<OfflineAlarmCache.OfflineAlarmEvent> pending = cache.getPendingAlarms();
        assertThat(pending).hasSize(3);

        OfflineAlarmCache.OfflineAlarmEvent vehicle = pending.get(0);
        assertThat(vehicle.getEventType()).isEqualTo("MOTION");
        assertThat(vehicle.getSeverity()).isEqualTo("INFO");
        assertThat(vehicle.getDescription()).contains("车辆").contains("cam-v");

        OfflineAlarmCache.OfflineAlarmEvent animal = pending.get(1);
        assertThat(animal.getEventType()).isEqualTo("CUSTOM");
        assertThat(animal.getSeverity()).isEqualTo("INFO");
        assertThat(animal.getDescription()).contains("动物").contains("cam-v");

        OfflineAlarmCache.OfflineAlarmEvent fire = pending.get(2);
        assertThat(fire.getEventType()).isEqualTo("FIRE");
        assertThat(fire.getSeverity()).isEqualTo("CRITICAL");
        assertThat(fire.getDescription()).contains("火灾").contains("cam-f");
    }

    // ===== 统计 =====

    @Test
    @DisplayName("统计按类型计数，总数=各类型之和，未触发的类型时间戳为 0")
    void statsCountPerTypeAndTotal() {
        trigger.triggerDetection("cam-1", "a", EdgeAiTrigger.AiDetectType.PERSON, 0, 0, null);
        trigger.triggerDetection("cam-2", "b", EdgeAiTrigger.AiDetectType.PERSON, 0, 0, null);
        trigger.triggerDetection("cam-3", "c", EdgeAiTrigger.AiDetectType.FIRE, 0, 0, null);

        Map<String, Object> stats = trigger.getDetectionStats();

        assertThat((Long) stats.get("totalDetections")).isEqualTo(3L);
        @SuppressWarnings("unchecked")
        Map<String, Long> counts = (Map<String, Long>) stats.get("detectionCounts");
        assertThat(counts).containsEntry("PERSON", 2L)
                .containsEntry("FIRE", 1L)
                .containsEntry("VEHICLE", 0L)
                .containsEntry("ANIMAL", 0L);

        @SuppressWarnings("unchecked")
        Map<String, Long> lastTimes = (Map<String, Long>) stats.get("lastDetectionTimestamps");
        assertThat(lastTimes.get("PERSON")).isPositive();
        assertThat(lastTimes.get("FIRE")).isPositive();
        assertThat(lastTimes.get("VEHICLE")).isZero();
    }
}
