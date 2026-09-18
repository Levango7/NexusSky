package io.aerofleet.cloud.alarm;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link AlarmEvent} 单元测试（M10 报警联动编排，FR-31）。
 * <p>
 * 覆盖构造器、工厂方法、字段验证、枚举解析、确认操作等。
 */
@DisplayName("AlarmEvent 报警事件模型 (FR-31)")
class AlarmEventTest {

    @Test
    @DisplayName("构造器正确设置所有字段")
    void constructorSetsAllFields() {
        AlarmEvent event = new AlarmEvent(
                "evt-1", "dev-001", "海康摄像头A",
                AlarmEvent.EventType.INTRUSION,
                AlarmEvent.Severity.CRITICAL,
                "周界入侵告警",
                39.9, 116.3, 50.0,
                1700000000000L, false);

        assertThat(event.getId()).isEqualTo("evt-1");
        assertThat(event.getSourceDeviceId()).isEqualTo("dev-001");
        assertThat(event.getSourceDeviceName()).isEqualTo("海康摄像头A");
        assertThat(event.getEventType()).isEqualTo(AlarmEvent.EventType.INTRUSION);
        assertThat(event.getSeverity()).isEqualTo(AlarmEvent.Severity.CRITICAL);
        assertThat(event.getDescription()).isEqualTo("周界入侵告警");
        assertThat(event.getLat()).isEqualTo(39.9);
        assertThat(event.getLon()).isEqualTo(116.3);
        assertThat(event.getAlt()).isEqualTo(50.0);
        assertThat(event.getTimestampMs()).isEqualTo(1700000000000L);
        assertThat(event.isAcknowledged()).isFalse();
    }

    @Test
    @DisplayName("工厂方法 from 生成 UUID 与时间戳")
    void fromGeneratesUuidAndTimestamp() {
        AlarmEvent event = AlarmEvent.from("dev-002", "大华摄像头B",
                "MOTION", 40.0, 117.0, "移动侦测告警");

        assertThat(event.getId()).isNotBlank();
        assertThat(event.getSourceDeviceId()).isEqualTo("dev-002");
        assertThat(event.getSourceDeviceName()).isEqualTo("大华摄像头B");
        assertThat(event.getEventType()).isEqualTo(AlarmEvent.EventType.MOTION);
        assertThat(event.getLat()).isEqualTo(40.0);
        assertThat(event.getLon()).isEqualTo(117.0);
        assertThat(event.getDescription()).isEqualTo("移动侦测告警");
        assertThat(event.getTimestampMs()).isGreaterThan(0);
        assertThat(event.isAcknowledged()).isFalse();
    }

    @Test
    @DisplayName("工厂方法 from 默认严重程度为 WARN")
    void fromDefaultsToWarnSeverity() {
        AlarmEvent event = AlarmEvent.from("dev-1", "name", "FIRE", 30.0, 110.0, "fire");
        assertThat(event.getSeverity()).isEqualTo(AlarmEvent.Severity.WARN);
    }

    @Test
    @DisplayName("工厂方法 from 默认海拔为 0")
    void fromDefaultsToZeroAltitude() {
        AlarmEvent event = AlarmEvent.from("dev-1", "name", "FIRE", 30.0, 110.0, "fire");
        assertThat(event.getAlt()).isEqualTo(0.0);
    }

    @Test
    @DisplayName("parseEventType 大小写不敏感解析")
    void parseEventTypeCaseInsensitive() {
        assertThat(AlarmEvent.parseEventType("motion")).isEqualTo(AlarmEvent.EventType.MOTION);
        assertThat(AlarmEvent.parseEventType("INTRUSION")).isEqualTo(AlarmEvent.EventType.INTRUSION);
        assertThat(AlarmEvent.parseEventType("Fire")).isEqualTo(AlarmEvent.EventType.FIRE);
        assertThat(AlarmEvent.parseEventType("DOOR")).isEqualTo(AlarmEvent.EventType.DOOR);
        assertThat(AlarmEvent.parseEventType("custom")).isEqualTo(AlarmEvent.EventType.CUSTOM);
    }

    @Test
    @DisplayName("parseEventType 未识别字符串返回 CUSTOM")
    void parseEventTypeUnknownReturnsCustom() {
        assertThat(AlarmEvent.parseEventType("UNKNOWN")).isEqualTo(AlarmEvent.EventType.CUSTOM);
        assertThat(AlarmEvent.parseEventType("smoke")).isEqualTo(AlarmEvent.EventType.CUSTOM);
    }

    @Test
    @DisplayName("parseEventType null 返回 CUSTOM")
    void parseEventTypeNullReturnsCustom() {
        assertThat(AlarmEvent.parseEventType(null)).isEqualTo(AlarmEvent.EventType.CUSTOM);
    }

    @Test
    @DisplayName("Severity.fromString 大小写不敏感解析")
    void severityFromStringCaseInsensitive() {
        assertThat(AlarmEvent.Severity.fromString("info")).isEqualTo(AlarmEvent.Severity.INFO);
        assertThat(AlarmEvent.Severity.fromString("WARN")).isEqualTo(AlarmEvent.Severity.WARN);
        assertThat(AlarmEvent.Severity.fromString("Critical")).isEqualTo(AlarmEvent.Severity.CRITICAL);
    }

    @Test
    @DisplayName("Severity.fromString 未识别返回 INFO")
    void severityFromStringUnknownReturnsInfo() {
        assertThat(AlarmEvent.Severity.fromString("xxx")).isEqualTo(AlarmEvent.Severity.INFO);
        assertThat(AlarmEvent.Severity.fromString(null)).isEqualTo(AlarmEvent.Severity.INFO);
    }

    @Test
    @DisplayName("Severity.level 返回正确层级数值")
    void severityLevel() {
        assertThat(AlarmEvent.Severity.INFO.level()).isEqualTo(0);
        assertThat(AlarmEvent.Severity.WARN.level()).isEqualTo(1);
        assertThat(AlarmEvent.Severity.CRITICAL.level()).isEqualTo(2);
    }

    @Test
    @DisplayName("acknowledge 将事件标记为已确认")
    void acknowledgeSetsFlag() {
        AlarmEvent event = AlarmEvent.from("dev-1", "name", "MOTION", 30.0, 110.0, "test");
        assertThat(event.isAcknowledged()).isFalse();
        event.acknowledge();
        assertThat(event.isAcknowledged()).isTrue();
    }

    @Test
    @DisplayName("toString 包含关键字段")
    void toStringContainsKeyFields() {
        AlarmEvent event = new AlarmEvent(
                "evt-x", "dev-1", "cam",
                AlarmEvent.EventType.FIRE, AlarmEvent.Severity.CRITICAL,
                "fire!", 39.9, 116.3, 0, 123L, false);
        String s = event.toString();
        assertThat(s).contains("evt-x").contains("dev-1").contains("FIRE").contains("CRITICAL");
    }
}