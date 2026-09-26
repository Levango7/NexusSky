package io.aerofleet.cloud.mission.emergency;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link EmergencyCommandPhase} 阶段转移合法性单测。
 * <p>
 * 覆盖 6 个阶段的合法/非法转移、终态/初始态判断、中文名称、序号等。
 */
@DisplayName("EmergencyCommandPhase 阶段转移合法性")
class EmergencyCommandPhaseTest {

    @Test
    @DisplayName("RECEIVED → ASSESSED 合法")
    void receivedToAssessed() {
        assertThat(EmergencyCommandPhase.RECEIVED.canTransitionTo(EmergencyCommandPhase.ASSESSED)).isTrue();
    }

    @Test
    @DisplayName("ASSESSED → DEPLOYED 合法")
    void assessedToDeployed() {
        assertThat(EmergencyCommandPhase.ASSESSED.canTransitionTo(EmergencyCommandPhase.DEPLOYED)).isTrue();
    }

    @Test
    @DisplayName("DEPLOYED → EXECUTING 合法")
    void deployedToExecuting() {
        assertThat(EmergencyCommandPhase.DEPLOYED.canTransitionTo(EmergencyCommandPhase.EXECUTING)).isTrue();
    }

    @Test
    @DisplayName("EXECUTING → EVALUATED 合法")
    void executingToEvaluated() {
        assertThat(EmergencyCommandPhase.EXECUTING.canTransitionTo(EmergencyCommandPhase.EVALUATED)).isTrue();
    }

    @Test
    @DisplayName("EVALUATED → CLOSED 合法")
    void evaluatedToClosed() {
        assertThat(EmergencyCommandPhase.EVALUATED.canTransitionTo(EmergencyCommandPhase.CLOSED)).isTrue();
    }

    @Test
    @DisplayName("RECEIVED → DEPLOYED 非法（跳跃）")
    void receivedToDeployedIllegal() {
        assertThat(EmergencyCommandPhase.RECEIVED.canTransitionTo(EmergencyCommandPhase.DEPLOYED)).isFalse();
    }

    @Test
    @DisplayName("ASSESSED → EXECUTING 非法（跳跃）")
    void assessedToExecutingIllegal() {
        assertThat(EmergencyCommandPhase.ASSESSED.canTransitionTo(EmergencyCommandPhase.EXECUTING)).isFalse();
    }

    @Test
    @DisplayName("同阶段转移非法（自环）")
    void selfTransitionIllegal() {
        for (EmergencyCommandPhase p : EmergencyCommandPhase.values()) {
            assertThat(p.canTransitionTo(p)).as(p + " -> " + p).isFalse();
        }
    }

    @Test
    @DisplayName("回退转移非法（如 ASSESSED → RECEIVED）")
    void backwardTransitionIllegal() {
        assertThat(EmergencyCommandPhase.ASSESSED.canTransitionTo(EmergencyCommandPhase.RECEIVED)).isFalse();
        assertThat(EmergencyCommandPhase.DEPLOYED.canTransitionTo(EmergencyCommandPhase.ASSESSED)).isFalse();
        assertThat(EmergencyCommandPhase.EXECUTING.canTransitionTo(EmergencyCommandPhase.DEPLOYED)).isFalse();
    }

    @Test
    @DisplayName("CLOSED 无后继阶段（终态）")
    void closedIsTerminal() {
        assertThat(EmergencyCommandPhase.CLOSED.isTerminal()).isTrue();
        assertThat(EmergencyCommandPhase.CLOSED.validNextPhases()).isEmpty();
        for (EmergencyCommandPhase p : EmergencyCommandPhase.values()) {
            assertThat(EmergencyCommandPhase.CLOSED.canTransitionTo(p)).isFalse();
        }
    }

    @Test
    @DisplayName("RECEIVED 是初始阶段")
    void receivedIsInitial() {
        assertThat(EmergencyCommandPhase.RECEIVED.isInitial()).isTrue();
        for (EmergencyCommandPhase p : EmergencyCommandPhase.values()) {
            if (p != EmergencyCommandPhase.RECEIVED) {
                assertThat(p.isInitial()).as(p + " not initial").isFalse();
            }
        }
    }

    @Test
    @DisplayName("validNextPhases 返回正确后继集合")
    void validNextPhases() {
        assertThat(EmergencyCommandPhase.RECEIVED.validNextPhases())
                .containsExactly(EmergencyCommandPhase.ASSESSED);
        assertThat(EmergencyCommandPhase.ASSESSED.validNextPhases())
                .containsExactly(EmergencyCommandPhase.DEPLOYED);
        assertThat(EmergencyCommandPhase.DEPLOYED.validNextPhases())
                .containsExactly(EmergencyCommandPhase.EXECUTING);
        assertThat(EmergencyCommandPhase.EXECUTING.validNextPhases())
                .containsExactly(EmergencyCommandPhase.EVALUATED);
        assertThat(EmergencyCommandPhase.EVALUATED.validNextPhases())
                .containsExactly(EmergencyCommandPhase.CLOSED);
    }

    @Test
    @DisplayName("order() 返回 0~5 序号")
    void orderReturnsOrdinal() {
        assertThat(EmergencyCommandPhase.RECEIVED.order()).isEqualTo(0);
        assertThat(EmergencyCommandPhase.ASSESSED.order()).isEqualTo(1);
        assertThat(EmergencyCommandPhase.DEPLOYED.order()).isEqualTo(2);
        assertThat(EmergencyCommandPhase.EXECUTING.order()).isEqualTo(3);
        assertThat(EmergencyCommandPhase.EVALUATED.order()).isEqualTo(4);
        assertThat(EmergencyCommandPhase.CLOSED.order()).isEqualTo(5);
    }

    @Test
    @DisplayName("displayName() 返回中文名称")
    void displayNameChinese() {
        assertThat(EmergencyCommandPhase.RECEIVED.displayName()).isEqualTo("接报");
        assertThat(EmergencyCommandPhase.ASSESSED.displayName()).isEqualTo("研判");
        assertThat(EmergencyCommandPhase.DEPLOYED.displayName()).isEqualTo("部署");
        assertThat(EmergencyCommandPhase.EXECUTING.displayName()).isEqualTo("执行");
        assertThat(EmergencyCommandPhase.EVALUATED.displayName()).isEqualTo("评估");
        assertThat(EmergencyCommandPhase.CLOSED.displayName()).isEqualTo("总结");
    }

    @Test
    @DisplayName("canTransitionTo(null) 返回 false")
    void nullTargetIllegal() {
        assertThat(EmergencyCommandPhase.RECEIVED.canTransitionTo(null)).isFalse();
    }
}