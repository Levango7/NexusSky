package io.aerofleet.sim.mesh;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * DisasterModeManager 灾害模式管理单测（灾害应急通讯组网，FR-03/04/05）。
 * <p>
 * 覆盖 activateDisasterMode/deactivateDisasterMode/isDisasterMode/getRecoveryRate/
 * 参数调整（HELLO间隔/邻居超时/路由生命周期）/退出条件。
 */
@DisplayName("DisasterModeManager 灾害模式 (FR-03/04/05)")
class DisasterModeManagerTest {

    @Test
    @DisplayName("初始状态：非灾害模式，正常参数")
    void initialState() {
        DisasterModeManager manager = new DisasterModeManager();
        assertThat(manager.isDisasterMode()).isFalse();
        assertThat(manager.getHelloIntervalMs()).isEqualTo(DisasterModeManager.NORMAL_HELLO_INTERVAL_MS);
        assertThat(manager.getNeighborTimeoutMs()).isEqualTo(DisasterModeManager.NORMAL_NEIGHBOR_TIMEOUT_MS);
        assertThat(manager.getRouteLifetimeMs()).isEqualTo(DisasterModeManager.NORMAL_ROUTE_LIFETIME_MS);
    }

    @Test
    @DisplayName("activateDisasterMode：进入灾害模式，参数自动调整")
    void activateDisasterMode() {
        DisasterModeManager manager = new DisasterModeManager();
        manager.activateDisasterMode(DisasterModeManager.TriggerReason.HEARTBEAT_TIMEOUT);

        assertThat(manager.isDisasterMode()).isTrue();
        assertThat(manager.getTriggerReason()).isEqualTo(DisasterModeManager.TriggerReason.HEARTBEAT_TIMEOUT);
        // HELLO 间隔缩短
        assertThat(manager.getHelloIntervalMs()).isEqualTo(DisasterModeManager.DISASTER_HELLO_INTERVAL_MS);
        // 邻居超时缩短
        assertThat(manager.getNeighborTimeoutMs()).isEqualTo(DisasterModeManager.DISASTER_NEIGHBOR_TIMEOUT_MS);
        // 路由生命周期缩短
        assertThat(manager.getRouteLifetimeMs()).isEqualTo(DisasterModeManager.DISASTER_ROUTE_LIFETIME_MS);
    }

    @Test
    @DisplayName("activateDisasterMode(int)：通过 code 激活")
    void activateDisasterModeByCode() {
        DisasterModeManager manager = new DisasterModeManager();
        manager.activateDisasterMode(0); // HEARTBEAT_TIMEOUT

        assertThat(manager.isDisasterMode()).isTrue();
        assertThat(manager.getTriggerReason()).isEqualTo(DisasterModeManager.TriggerReason.HEARTBEAT_TIMEOUT);

        manager.forceDeactivate();
        manager.activateDisasterMode(2); // MANUAL_ACTIVATION
        assertThat(manager.getTriggerReason()).isEqualTo(DisasterModeManager.TriggerReason.MANUAL_ACTIVATION);
    }

    @Test
    @DisplayName("重复激活：仅更新触发原因和时间戳，不重新调整参数")
    void reactivateUpdatesReason() {
        DisasterModeManager manager = new DisasterModeManager();
        manager.activateDisasterMode(DisasterModeManager.TriggerReason.HEARTBEAT_TIMEOUT);
        long firstActivated = manager.getActivatedAtMs();

        // 再次激活（不同原因）
        manager.activateDisasterMode(DisasterModeManager.TriggerReason.TERRAIN_CHANGE);
        // 激活时间不变（已在灾害模式中）
        assertThat(manager.getActivatedAtMs()).isEqualTo(firstActivated);
        assertThat(manager.getTriggerReason()).isEqualTo(DisasterModeManager.TriggerReason.TERRAIN_CHANGE);
    }

    @Test
    @DisplayName("getRecoveryRate：无受影响节点时为 100%")
    void recoveryRateNoAffectedNodes() {
        DisasterModeManager manager = new DisasterModeManager();
        assertThat(manager.getRecoveryRate()).isEqualTo(100.0);
    }

    @Test
    @DisplayName("getRecoveryRate：部分恢复")
    void recoveryRatePartial() {
        DisasterModeManager manager = new DisasterModeManager();
        manager.activateDisasterMode(DisasterModeManager.TriggerReason.HEARTBEAT_TIMEOUT);
        manager.addAffectedNode(1);
        manager.addAffectedNode(2);
        manager.addAffectedNode(3);
        manager.addAffectedNode(4);

        manager.markNodeRecovered(1);
        manager.markNodeRecovered(2);

        // 2/4 = 50%
        assertThat(manager.getRecoveryRate()).isEqualTo(50.0);
    }

    @Test
    @DisplayName("getRecoveryRate：全部恢复")
    void recoveryRateAllRecovered() {
        DisasterModeManager manager = new DisasterModeManager();
        manager.activateDisasterMode(DisasterModeManager.TriggerReason.HEARTBEAT_TIMEOUT);
        manager.addAffectedNode(1);
        manager.addAffectedNode(2);

        manager.markNodeRecovered(1);
        manager.markNodeRecovered(2);

        assertThat(manager.getRecoveryRate()).isEqualTo(100.0);
    }

    @Test
    @DisplayName("deactivateDisasterMode：恢复率不足时不退出")
    void deactivateLowRecoveryRate() {
        DisasterModeManager manager = new DisasterModeManager();
        manager.activateDisasterMode(DisasterModeManager.TriggerReason.HEARTBEAT_TIMEOUT);
        manager.addAffectedNode(1);
        manager.addAffectedNode(2);
        manager.markNodeRecovered(1);

        // 恢复率 50% < 80% 阈值
        assertThat(manager.deactivateDisasterMode()).isFalse();
        assertThat(manager.isDisasterMode()).isTrue();
    }

    @Test
    @DisplayName("deactivateDisasterMode：恢复率>80% 但时间不足时不退出")
    void deactivateRecoveryOkButTimeNotEnough() {
        DisasterModeManager manager = new DisasterModeManager();
        manager.activateDisasterMode(DisasterModeManager.TriggerReason.HEARTBEAT_TIMEOUT);
        manager.addAffectedNode(1);
        manager.markNodeRecovered(1);

        // 恢复率 100% > 80%，但无新灾害时间不足 30 分钟
        assertThat(manager.deactivateDisasterMode()).isFalse();
        assertThat(manager.isDisasterMode()).isTrue();
    }

    @Test
    @DisplayName("forceDeactivate：强制退出灾害模式")
    void forceDeactivate() {
        DisasterModeManager manager = new DisasterModeManager();
        manager.activateDisasterMode(DisasterModeManager.TriggerReason.HEARTBEAT_TIMEOUT);
        manager.addAffectedNode(1);

        manager.forceDeactivate();
        assertThat(manager.isDisasterMode()).isFalse();
        assertThat(manager.getTriggerReason()).isNull();
        assertThat(manager.getHelloIntervalMs()).isEqualTo(DisasterModeManager.NORMAL_HELLO_INTERVAL_MS);
        assertThat(manager.getNeighborTimeoutMs()).isEqualTo(DisasterModeManager.NORMAL_NEIGHBOR_TIMEOUT_MS);
        assertThat(manager.getRouteLifetimeMs()).isEqualTo(DisasterModeManager.NORMAL_ROUTE_LIFETIME_MS);
    }

    @Test
    @DisplayName("canDeactivate：检查退出条件不实际退出")
    void canDeactivate() {
        DisasterModeManager manager = new DisasterModeManager();
        // 非灾害模式时 canDeactivate 返回 true
        assertThat(manager.canDeactivate()).isTrue();

        manager.activateDisasterMode(DisasterModeManager.TriggerReason.HEARTBEAT_TIMEOUT);
        manager.addAffectedNode(1);
        // 恢复率 0% < 80%
        assertThat(manager.canDeactivate()).isFalse();
    }

    @Test
    @DisplayName("TriggerReason fromCode 反查")
    void triggerReasonFromCode() {
        assertThat(DisasterModeManager.TriggerReason.fromCode(0))
                .isEqualTo(DisasterModeManager.TriggerReason.HEARTBEAT_TIMEOUT);
        assertThat(DisasterModeManager.TriggerReason.fromCode(1))
                .isEqualTo(DisasterModeManager.TriggerReason.TERRAIN_CHANGE);
        assertThat(DisasterModeManager.TriggerReason.fromCode(2))
                .isEqualTo(DisasterModeManager.TriggerReason.MANUAL_ACTIVATION);
        assertThat(DisasterModeManager.TriggerReason.fromCode(3))
                .isEqualTo(DisasterModeManager.TriggerReason.LINK_QUALITY_DROP);
        // 越界默认 LINK_QUALITY_DROP
        assertThat(DisasterModeManager.TriggerReason.fromCode(99))
                .isEqualTo(DisasterModeManager.TriggerReason.LINK_QUALITY_DROP);
    }

    @Test
    @DisplayName("addAffectedNode 更新最近灾害事件时间")
    void addAffectedNodeUpdatesLastEvent() {
        DisasterModeManager manager = new DisasterModeManager();
        manager.activateDisasterMode(DisasterModeManager.TriggerReason.HEARTBEAT_TIMEOUT);
        long initialEvent = manager.getLastDisasterEventMs();

        // 稍等后添加新受影响节点
        try { Thread.sleep(10); } catch (InterruptedException ignored) {}
        manager.addAffectedNode(1);

        assertThat(manager.getLastDisasterEventMs()).isGreaterThan(initialEvent);
    }

    @Test
    @DisplayName("markNodeRecovered 仅标记受影响节点")
    void markRecoveredOnlyAffected() {
        DisasterModeManager manager = new DisasterModeManager();
        manager.activateDisasterMode(DisasterModeManager.TriggerReason.HEARTBEAT_TIMEOUT);
        manager.addAffectedNode(1);

        // 节点2未受影响，标记恢复无效
        manager.markNodeRecovered(2);
        assertThat(manager.getRecoveredNodes()).doesNotContain(2);

        // 节点1受影响，标记恢复有效
        manager.markNodeRecovered(1);
        assertThat(manager.getRecoveredNodes()).contains(1);
    }
}