package io.aerofleet.sim.celltower;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * HandoverManager 单测（M6 移动基站载荷抽象，FR-HO-01~07）。
 * <p>
 * MeshRouter 传 null（HandoverManager 容忍 null，仅 warn 日志）。
 */
@DisplayName("HandoverManager (FR-HO-01~07)")
class HandoverManagerTest {

    @Test
    @DisplayName("initiateHandover 返回 INITIATED 记录")
    void initiateReturnsRecord() {
        HandoverManager mgr = new HandoverManager(1, null);
        HandoverRecord record = mgr.initiateHandover(1001, 2, HandoverReason.SIGNAL_WEAK, 5000L);

        assertThat(record).isNotNull();
        assertThat(record.terminalId).isEqualTo(1001);
        assertThat(record.fromSysid).isEqualTo(1);
        assertThat(record.toSysid).isEqualTo(2);
        assertThat(record.reason).isEqualTo(HandoverReason.SIGNAL_WEAK);
        assertThat(record.status).isEqualTo(HandoverStatus.INITIATED);
        assertThat(mgr.pendingCount()).isEqualTo(1);
    }

    @Test
    @DisplayName("initiateHandover 拒绝 toSysid == fromSysid")
    void initiateRejectsSameSysid() {
        HandoverManager mgr = new HandoverManager(1, null);
        HandoverRecord record = mgr.initiateHandover(1001, 1, HandoverReason.LOAD_BALANCE, 5000L);

        assertThat(record).isNull();
        assertThat(mgr.pendingCount()).isZero();
    }

    @Test
    @DisplayName("rollback 回滚进行中的漫游")
    void rollbackHandover() {
        HandoverManager mgr = new HandoverManager(1, null);
        mgr.initiateHandover(1001, 2, HandoverReason.SIGNAL_WEAK, 5000L);

        HandoverRecord rolledBack = mgr.rollback(1001, 6000L);
        assertThat(rolledBack).isNotNull();
        assertThat(rolledBack.status).isEqualTo(HandoverStatus.ROLLED_BACK);
        assertThat(rolledBack.durationMs()).isEqualTo(1000L);
        assertThat(mgr.pendingCount()).isZero();
    }

    @Test
    @DisplayName("rollback 不存在的漫游返回 null")
    void rollbackNonExistent() {
        HandoverManager mgr = new HandoverManager(1, null);
        assertThat(mgr.rollback(999, 5000L)).isNull();
    }

    @Test
    @DisplayName("scanTimeouts 检测超时漫游")
    void scanTimeouts() {
        HandoverManager mgr = new HandoverManager(1, null);
        mgr.initiateHandover(1001, 2, HandoverReason.SIGNAL_WEAK, 1000L);

        // 600ms 后扫描（超时阈值 500ms）
        var timeouts = mgr.scanTimeouts(1600L);
        assertThat(timeouts).hasSize(1);
        assertThat(timeouts.get(0).status).isEqualTo(HandoverStatus.TIMEOUT);
        assertThat(mgr.pendingCount()).isZero();
    }

    @Test
    @DisplayName("scanTimeouts 未超时返回空列表")
    void scanTimeoutsNoneExpired() {
        HandoverManager mgr = new HandoverManager(1, null);
        mgr.initiateHandover(1001, 2, HandoverReason.SIGNAL_WEAK, 1000L);

        var timeouts = mgr.scanTimeouts(1200L);
        assertThat(timeouts).isEmpty();
        assertThat(mgr.pendingCount()).isEqualTo(1);
    }
}