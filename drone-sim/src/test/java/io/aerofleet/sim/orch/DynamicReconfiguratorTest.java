package io.aerofleet.sim.orch;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * DynamicReconfigurator 动态重构器单测（M9 应急任务编排，T4 动态重构）。
 * <p>
 * 覆盖：损毁覆盖下降 &lt; 10% → MESH_SELF_HEAL、≥ 10% → FULL_REPLAN；
 * 低电量 &lt; 15% → NO_ACTION、&lt; 30% 有替换 → PARTIAL_REPLAN、≥ 30% → NO_ACTION；
 * 地形变化 → PARTIAL_REPLAN。
 */
@DisplayName("DynamicReconfigurator 动态重构器 (T4)")
class DynamicReconfiguratorTest {

    /** 标准阈值：低电量 30%，危急 15%，覆盖下降 10%。 */
    private DynamicReconfigurator newReconfigurator() {
        return new DynamicReconfigurator(5000L, 30, 15, 0.10);
    }

    // ===== onDroneLost =====

    @Test
    @DisplayName("onDroneLost 覆盖下降 < 10% → MESH_SELF_HEAL")
    void onDroneLostSmallDeclineMeshSelfHeal() {
        DynamicReconfigurator r = newReconfigurator();
        // 下降 5%：(100 - 95) / 100 = 0.05 < 0.10
        ReconfigResult result = r.onDroneLost(1L, 5, 100.0, 95.0);

        assertThat(result.type).isEqualTo(ReconfigResult.Type.MESH_SELF_HEAL);
        assertThat(result.description).contains("self-heal");
    }

    @Test
    @DisplayName("onDroneLost 覆盖下降 = 5% → MESH_SELF_HEAL（边界 < 10%）")
    void onDroneLostFivePercentDecline() {
        DynamicReconfigurator r = newReconfigurator();
        ReconfigResult result = r.onDroneLost(1L, 5, 80.0, 76.0);

        assertThat(result.type).isEqualTo(ReconfigResult.Type.MESH_SELF_HEAL);
    }

    @Test
    @DisplayName("onDroneLost 覆盖下降 ≥ 10% → FULL_REPLAN")
    void onDroneLostLargeDeclineFullReplan() {
        DynamicReconfigurator r = newReconfigurator();
        // 下降 20%：(100 - 80) / 100 = 0.20 ≥ 0.10
        ReconfigResult result = r.onDroneLost(1L, 5, 100.0, 80.0);

        assertThat(result.type).isEqualTo(ReconfigResult.Type.FULL_REPLAN);
        assertThat(result.description).contains("full replan");
    }

    @Test
    @DisplayName("onDroneLost 覆盖下降 = 10% → FULL_REPLAN（边界 ≥ 10%）")
    void onDroneLostTenPercentDeclineFullReplan() {
        DynamicReconfigurator r = newReconfigurator();
        // 下降正好 10%：(100 - 90) / 100 = 0.10 ≥ 0.10
        ReconfigResult result = r.onDroneLost(1L, 5, 100.0, 90.0);

        assertThat(result.type).isEqualTo(ReconfigResult.Type.FULL_REPLAN);
    }

    @Test
    @DisplayName("onDroneLost 无覆盖损失 → MESH_SELF_HEAL")
    void onDroneLostNoDecline() {
        DynamicReconfigurator r = newReconfigurator();
        ReconfigResult result = r.onDroneLost(1L, 5, 100.0, 100.0);

        assertThat(result.type).isEqualTo(ReconfigResult.Type.MESH_SELF_HEAL);
    }

    // ===== onLowBattery =====

    @Test
    @DisplayName("onLowBattery battery < 15 → NO_ACTION（必须返航）")
    void onLowBatteryCriticalNoAction() {
        DynamicReconfigurator r = newReconfigurator();
        ReconfigResult result = r.onLowBattery(1L, 5, 10, true);

        assertThat(result.type).isEqualTo(ReconfigResult.Type.NO_ACTION);
        assertThat(result.description).contains("must return");
    }

    @Test
    @DisplayName("onLowBattery battery = 14 且有替换 → NO_ACTION（危急优先，不轮换）")
    void onLowBatteryCriticalWithReplacementStillNoAction() {
        DynamicReconfigurator r = newReconfigurator();
        ReconfigResult result = r.onLowBattery(1L, 5, 14, true);

        assertThat(result.type).isEqualTo(ReconfigResult.Type.NO_ACTION);
    }

    @Test
    @DisplayName("onLowBattery battery < 30 && hasReplacement → PARTIAL_REPLAN")
    void onLowBatteryLowWithReplacementPartialReplan() {
        DynamicReconfigurator r = newReconfigurator();
        ReconfigResult result = r.onLowBattery(1L, 5, 25, true);

        assertThat(result.type).isEqualTo(ReconfigResult.Type.PARTIAL_REPLAN);
        assertThat(result.description).contains("rotation");
    }

    @Test
    @DisplayName("onLowBattery battery < 30 && !hasReplacement → NO_ACTION")
    void onLowBatteryLowWithoutReplacementNoAction() {
        DynamicReconfigurator r = newReconfigurator();
        ReconfigResult result = r.onLowBattery(1L, 5, 25, false);

        assertThat(result.type).isEqualTo(ReconfigResult.Type.NO_ACTION);
        assertThat(result.description).contains("no replacement");
    }

    @Test
    @DisplayName("onLowBattery battery >= 30 → NO_ACTION")
    void onLowBatterySufficientNoAction() {
        DynamicReconfigurator r = newReconfigurator();
        ReconfigResult result = r.onLowBattery(1L, 5, 50, true);

        assertThat(result.type).isEqualTo(ReconfigResult.Type.NO_ACTION);
    }

    @Test
    @DisplayName("onLowBattery battery = 30 → NO_ACTION（边界 >= 30）")
    void onLowBatteryBoundaryThirtyNoAction() {
        DynamicReconfigurator r = newReconfigurator();
        ReconfigResult result = r.onLowBattery(1L, 5, 30, true);

        assertThat(result.type).isEqualTo(ReconfigResult.Type.NO_ACTION);
    }

    @Test
    @DisplayName("onLowBattery battery = 15 → 不算危急，进入低电量分支")
    void onLowBatteryBoundaryFifteen() {
        DynamicReconfigurator r = newReconfigurator();
        // battery=15 不 < 15（critical），进入 low 分支
        ReconfigResult withReplacement = r.onLowBattery(1L, 5, 15, true);
        assertThat(withReplacement.type).isEqualTo(ReconfigResult.Type.PARTIAL_REPLAN);

        ReconfigResult noReplacement = r.onLowBattery(1L, 5, 15, false);
        assertThat(noReplacement.type).isEqualTo(ReconfigResult.Type.NO_ACTION);
    }

    // ===== onTerrainChanged =====

    @Test
    @DisplayName("onTerrainChanged → PARTIAL_REPLAN")
    void onTerrainChangedPartialReplan() {
        DynamicReconfigurator r = newReconfigurator();
        ReconfigResult result = r.onTerrainChanged(1L, 12, 34);

        assertThat(result.type).isEqualTo(ReconfigResult.Type.PARTIAL_REPLAN);
        assertThat(result.description).contains("terrain changed");
        assertThat(result.durationMs).isGreaterThan(0L);
    }

    @Test
    @DisplayName("自定义阈值生效：覆盖下降阈值 20%")
    void customThresholds() {
        DynamicReconfigurator r = new DynamicReconfigurator(3000L, 40, 20, 0.20);
        // 下降 15% < 20% → MESH_SELF_HEAL
        ReconfigResult small = r.onDroneLost(1L, 5, 100.0, 85.0);
        assertThat(small.type).isEqualTo(ReconfigResult.Type.MESH_SELF_HEAL);

        // 下降 25% ≥ 20% → FULL_REPLAN
        ReconfigResult large = r.onDroneLost(1L, 5, 100.0, 75.0);
        assertThat(large.type).isEqualTo(ReconfigResult.Type.FULL_REPLAN);

        // battery=25 < 40(low) 且有替换 → PARTIAL_REPLAN
        ReconfigResult low = r.onLowBattery(1L, 5, 25, true);
        assertThat(low.type).isEqualTo(ReconfigResult.Type.PARTIAL_REPLAN);

        // battery=18 < 20(critical) → NO_ACTION
        ReconfigResult critical = r.onLowBattery(1L, 5, 18, true);
        assertThat(critical.type).isEqualTo(ReconfigResult.Type.NO_ACTION);
    }
}