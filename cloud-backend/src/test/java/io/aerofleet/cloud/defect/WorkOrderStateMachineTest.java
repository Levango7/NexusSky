package io.aerofleet.cloud.defect;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** 工单状态机全表（spec §1）：合法转移逐一推进、非法逐一拒绝、终态封死。 */
@DisplayName("WorkOrderStateMachine — 转移全表")
class WorkOrderStateMachineTest {

    private final WorkOrderStateMachine m = new WorkOrderStateMachine();

    @Test
    @DisplayName("主链路：OPEN→DISPATCHED→IN_PROGRESS→RESOLVED→VERIFIED")
    void happyPath() {
        assertThat(m.apply("OPEN", WorkOrderStateMachine.Transition.DISPATCH)).isEqualTo("DISPATCHED");
        assertThat(m.apply("DISPATCHED", WorkOrderStateMachine.Transition.START)).isEqualTo("IN_PROGRESS");
        assertThat(m.apply("IN_PROGRESS", WorkOrderStateMachine.Transition.RESOLVE)).isEqualTo("RESOLVED");
        assertThat(m.apply("RESOLVED", WorkOrderStateMachine.Transition.VERIFY_PASS)).isEqualTo("VERIFIED");
    }

    @Test
    @DisplayName("复检失败：RESOLVED→REOPENED，REOPENED 可再处置")
    void verifyFailReopens() {
        assertThat(m.apply("RESOLVED", WorkOrderStateMachine.Transition.VERIFY_FAIL))
                .isEqualTo("REOPENED");
        assertThat(m.apply("REOPENED", WorkOrderStateMachine.Transition.START))
                .isEqualTo("IN_PROGRESS");
    }

    @Test
    @DisplayName("任意未终态可 CANCEL")
    void cancelFromAnyActive() {
        for (String s : new String[]{"OPEN", "DISPATCHED", "IN_PROGRESS", "RESOLVED", "REOPENED"}) {
            assertThat(m.apply(s, WorkOrderStateMachine.Transition.CANCEL)).isEqualTo("CANCELLED");
        }
    }

    @Test
    @DisplayName("跳态非法：OPEN 不能直接 RESOLVE/VERIFY")
    void noSkipping() {
        assertThatThrownBy(() -> m.apply("OPEN", WorkOrderStateMachine.Transition.RESOLVE))
                .isInstanceOf(IllegalWorkOrderTransitionException.class);
        assertThatThrownBy(() -> m.apply("OPEN", WorkOrderStateMachine.Transition.VERIFY_PASS))
                .isInstanceOf(IllegalWorkOrderTransitionException.class);
        assertThatThrownBy(() -> m.apply("DISPATCHED", WorkOrderStateMachine.Transition.RESOLVE))
                .isInstanceOf(IllegalWorkOrderTransitionException.class);
    }

    @Test
    @DisplayName("终态封死：VERIFIED/CANCELLED 拒绝一切转移")
    void terminalStatesSealed() {
        for (String t : new String[]{"VERIFIED", "CANCELLED"}) {
            for (WorkOrderStateMachine.Transition tr : WorkOrderStateMachine.Transition.values()) {
                assertThatThrownBy(() -> m.apply(t, tr))
                        .as("terminal %s must reject %s", t, tr)
                        .isInstanceOf(IllegalWorkOrderTransitionException.class);
            }
        }
        assertThat(m.isTerminal("VERIFIED")).isTrue();
        assertThat(m.isTerminal("CANCELLED")).isTrue();
        assertThat(m.isTerminal("RESOLVED")).isFalse();
    }

    @Test
    @DisplayName("未处置不能复检：IN_PROGRESS 的 VERIFY_PASS 拒绝")
    void verifyOnlyAfterResolve() {
        assertThatThrownBy(() -> m.apply("IN_PROGRESS", WorkOrderStateMachine.Transition.VERIFY_PASS))
                .isInstanceOf(IllegalWorkOrderTransitionException.class);
    }
}
