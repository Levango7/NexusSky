package io.aerofleet.cloud.alarm;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link AlarmLinkageRule} 单元测试（M10 报警联动编排，FR-31）。
 * <p>
 * 覆盖规则匹配逻辑：事件类型、严重程度、设备 ID 白名单、全部匹配、不匹配、禁用等。
 */
@DisplayName("AlarmLinkageRule 联动规则 (FR-31)")
class AlarmLinkageRuleTest {

    private static AlarmEvent event(AlarmEvent.EventType type, AlarmEvent.Severity severity,
                                    String deviceId) {
        return new AlarmEvent("e1", deviceId, "dev",
                type, severity, "desc", 39.9, 116.3, 0, 123L, false);
    }

    private static AlarmLinkageRule rule(AlarmEvent.EventType matchType,
                                         AlarmEvent.Severity minSeverity,
                                         Set<String> deviceIds) {
        return new AlarmLinkageRule("r1", "rule1", true,
                matchType, minSeverity, deviceIds,
                AlarmLinkageRule.ActionType.DEPLOY_DRONE,
                2, Double.NaN, Double.NaN, 500, 80, null);
    }

    @Test
    @DisplayName("事件类型匹配：相同类型匹配")
    void matchesSameEventType() {
        AlarmLinkageRule r = rule(AlarmEvent.EventType.FIRE, AlarmEvent.Severity.INFO, Set.of());
        assertThat(r.matches(event(AlarmEvent.EventType.FIRE, AlarmEvent.Severity.WARN, "d1"))).isTrue();
    }

    @Test
    @DisplayName("事件类型不匹配：不同类型不匹配")
    void notMatchesDifferentEventType() {
        AlarmLinkageRule r = rule(AlarmEvent.EventType.FIRE, AlarmEvent.Severity.INFO, Set.of());
        assertThat(r.matches(event(AlarmEvent.EventType.MOTION, AlarmEvent.Severity.WARN, "d1"))).isFalse();
    }

    @Test
    @DisplayName("事件类型 null 表示匹配任意类型")
    void nullEventTypeMatchesAny() {
        AlarmLinkageRule r = rule(null, AlarmEvent.Severity.INFO, Set.of());
        assertThat(r.matches(event(AlarmEvent.EventType.MOTION, AlarmEvent.Severity.INFO, "d1"))).isTrue();
        assertThat(r.matches(event(AlarmEvent.EventType.FIRE, AlarmEvent.Severity.INFO, "d1"))).isTrue();
        assertThat(r.matches(event(AlarmEvent.EventType.DOOR, AlarmEvent.Severity.INFO, "d1"))).isTrue();
    }

    @Test
    @DisplayName("严重程度达标：事件级别 >= 规则最低级别匹配")
    void matchesSeverityAtOrAbove() {
        AlarmLinkageRule r = rule(null, AlarmEvent.Severity.WARN, Set.of());
        assertThat(r.matches(event(AlarmEvent.EventType.MOTION, AlarmEvent.Severity.WARN, "d1"))).isTrue();
        assertThat(r.matches(event(AlarmEvent.EventType.MOTION, AlarmEvent.Severity.CRITICAL, "d1"))).isTrue();
    }

    @Test
    @DisplayName("严重程度不达标：事件级别 < 规则最低级别不匹配")
    void notMatchesSeverityBelow() {
        AlarmLinkageRule r = rule(null, AlarmEvent.Severity.WARN, Set.of());
        assertThat(r.matches(event(AlarmEvent.EventType.MOTION, AlarmEvent.Severity.INFO, "d1"))).isFalse();
    }

    @Test
    @DisplayName("设备 ID 在白名单中匹配")
    void matchesDeviceInWhitelist() {
        AlarmLinkageRule r = rule(null, AlarmEvent.Severity.INFO, Set.of("dev-a", "dev-b"));
        assertThat(r.matches(event(AlarmEvent.EventType.MOTION, AlarmEvent.Severity.INFO, "dev-a"))).isTrue();
        assertThat(r.matches(event(AlarmEvent.EventType.MOTION, AlarmEvent.Severity.INFO, "dev-b"))).isTrue();
    }

    @Test
    @DisplayName("设备 ID 不在白名单中不匹配")
    void notMatchesDeviceNotInWhitelist() {
        AlarmLinkageRule r = rule(null, AlarmEvent.Severity.INFO, Set.of("dev-a", "dev-b"));
        assertThat(r.matches(event(AlarmEvent.EventType.MOTION, AlarmEvent.Severity.INFO, "dev-c"))).isFalse();
    }

    @Test
    @DisplayName("空设备 ID 集合表示匹配全部设备")
    void emptyDeviceIdsMatchesAll() {
        AlarmLinkageRule r = rule(null, AlarmEvent.Severity.INFO, Set.of());
        assertThat(r.matches(event(AlarmEvent.EventType.MOTION, AlarmEvent.Severity.INFO, "any-1"))).isTrue();
        assertThat(r.matches(event(AlarmEvent.EventType.MOTION, AlarmEvent.Severity.INFO, "any-2"))).isTrue();
    }

    @Test
    @DisplayName("禁用规则不匹配任何事件")
    void disabledRuleNotMatches() {
        AlarmLinkageRule r = new AlarmLinkageRule("r1", "rule1", false,
                null, AlarmEvent.Severity.INFO, Set.of(),
                AlarmLinkageRule.ActionType.DEPLOY_DRONE,
                2, Double.NaN, Double.NaN, 500, 80, null);
        assertThat(r.matches(event(AlarmEvent.EventType.MOTION, AlarmEvent.Severity.CRITICAL, "d1"))).isFalse();
    }

    @Test
    @DisplayName("全部条件满足时匹配")
    void matchesAllConditions() {
        AlarmLinkageRule r = rule(AlarmEvent.EventType.INTRUSION, AlarmEvent.Severity.CRITICAL,
                Set.of("cam-01"));
        assertThat(r.matches(event(AlarmEvent.EventType.INTRUSION, AlarmEvent.Severity.CRITICAL, "cam-01"))).isTrue();
    }

    @Test
    @DisplayName("多条件部分不满足时不匹配")
    void notMatchesWhenPartialConditionFails() {
        AlarmLinkageRule r = rule(AlarmEvent.EventType.INTRUSION, AlarmEvent.Severity.CRITICAL,
                Set.of("cam-01"));
        // 类型不匹配
        assertThat(r.matches(event(AlarmEvent.EventType.MOTION, AlarmEvent.Severity.CRITICAL, "cam-01"))).isFalse();
        // 严重程度不匹配
        assertThat(r.matches(event(AlarmEvent.EventType.INTRUSION, AlarmEvent.Severity.INFO, "cam-01"))).isFalse();
        // 设备不匹配
        assertThat(r.matches(event(AlarmEvent.EventType.INTRUSION, AlarmEvent.Severity.CRITICAL, "cam-02"))).isFalse();
    }

    @Test
    @DisplayName("setEnabled 运行时切换启停状态")
    void setEnabledTogglesState() {
        AlarmLinkageRule r = rule(null, AlarmEvent.Severity.INFO, Set.of());
        assertThat(r.isEnabled()).isTrue();
        r.setEnabled(false);
        assertThat(r.isEnabled()).isFalse();
        assertThat(r.matches(event(AlarmEvent.EventType.MOTION, AlarmEvent.Severity.INFO, "d1"))).isFalse();
        r.setEnabled(true);
        assertThat(r.matches(event(AlarmEvent.EventType.MOTION, AlarmEvent.Severity.INFO, "d1"))).isTrue();
    }

    @Test
    @DisplayName("matchDeviceIds 返回不可变快照")
    void matchDeviceIdsIsImmutable() {
        AlarmLinkageRule r = rule(null, AlarmEvent.Severity.INFO, Set.of("a", "b"));
        assertThat(r.getMatchDeviceIds()).containsExactlyInAnyOrder("a", "b");
        assertThat(r.getMatchDeviceIds()).isUnmodifiable();
    }

    @Test
    @DisplayName("null matchDeviceIds 视为空集合")
    void nullDeviceIdsTreatedAsEmpty() {
        AlarmLinkageRule r = new AlarmLinkageRule("r1", "rule1", true,
                null, AlarmEvent.Severity.INFO, null,
                AlarmLinkageRule.ActionType.DEPLOY_DRONE,
                1, Double.NaN, Double.NaN, 100, 50, null);
        assertThat(r.getMatchDeviceIds()).isEmpty();
        assertThat(r.matches(event(AlarmEvent.EventType.MOTION, AlarmEvent.Severity.INFO, "any"))).isTrue();
    }
}