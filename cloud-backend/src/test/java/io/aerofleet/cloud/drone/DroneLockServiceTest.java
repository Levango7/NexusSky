package io.aerofleet.cloud.drone;

import io.aerofleet.cloud.gateway.DeviceRegistry;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * {@link DroneLockService} 单元测试：远程锁定/解锁/状态管理。
 * <p>
 * 直接实例化（无 Spring 上下文），用 AssertJ 断言。
 */
@DisplayName("DroneLockService 远程锁定/解锁")
class DroneLockServiceTest {

    private DroneLockService newService(DeviceRegistry registry) {
        return new DroneLockService(registry);
    }

    // ------------------------------------------------------------------
    // lock
    // ------------------------------------------------------------------

    @Test
    @DisplayName("testLockUnregistered: 未注册无人机锁定抛 IllegalStateException")
    void testLockUnregistered() {
        DeviceRegistry registry = new DeviceRegistry();
        DroneLockService svc = newService(registry);

        assertThatThrownBy(() -> svc.lock(99, "被盗", "admin", LockState.Action.DISARM))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("99")
                .hasMessageContaining("not registered");
    }

    @Test
    @DisplayName("testLockAndUnlock: 正常锁定解锁流程")
    void testLockAndUnlock() {
        DeviceRegistry registry = new DeviceRegistry();
        registry.registerIfAbsent(1);
        DroneLockService svc = newService(registry);

        // 锁定
        LockState locked = svc.lock(1, "被盗", "admin", LockState.Action.FORCE_LAND);
        assertThat(locked.isLocked()).isTrue();
        assertThat(locked.getSysid()).isEqualTo(1);
        assertThat(locked.getLockReason()).isEqualTo("被盗");
        assertThat(locked.getLockedBy()).isEqualTo("admin");
        assertThat(locked.getAction()).isEqualTo(LockState.Action.FORCE_LAND);
        assertThat(locked.getLockTimeMs()).isGreaterThan(0L);
        assertThat(locked.getUnlockTimeMs()).isEqualTo(0L);

        // 解锁
        LockState unlocked = svc.unlock(1, "admin2");
        assertThat(unlocked.isLocked()).isFalse();
        assertThat(unlocked.getLockTimeMs()).isEqualTo(locked.getLockTimeMs());
        assertThat(unlocked.getUnlockTimeMs()).isGreaterThan(0L);
        // 原始锁定信息保留
        assertThat(unlocked.getLockReason()).isEqualTo("被盗");
        assertThat(unlocked.getLockedBy()).isEqualTo("admin");
    }

    @Test
    @DisplayName("testDoubleLock: 重复锁定幂等返回当前状态")
    void testDoubleLock() {
        DeviceRegistry registry = new DeviceRegistry();
        registry.registerIfAbsent(1);
        DroneLockService svc = newService(registry);

        LockState first = svc.lock(1, "被盗", "admin", LockState.Action.DISARM);
        LockState second = svc.lock(1, "遗失", "admin2", LockState.Action.FORCE_LAND);

        // 幂等：返回当前状态，不覆盖
        assertThat(second.isLocked()).isTrue();
        assertThat(second.getLockedBy()).isEqualTo("admin");
        assertThat(second.getLockReason()).isEqualTo("被盗");
        assertThat(second.getAction()).isEqualTo(LockState.Action.DISARM);
        assertThat(second.getLockTimeMs()).isEqualTo(first.getLockTimeMs());
    }

    // ------------------------------------------------------------------
    // unlock
    // ------------------------------------------------------------------

    @Test
    @DisplayName("testUnlockNotLocked: 解锁未锁定的无人机抛 IllegalStateException")
    void testUnlockNotLocked() {
        DeviceRegistry registry = new DeviceRegistry();
        registry.registerIfAbsent(1);
        DroneLockService svc = newService(registry);

        assertThatThrownBy(() -> svc.unlock(1, "admin"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("not locked");
    }

    @Test
    @DisplayName("testUnlockUnregistered: 解锁未注册无人机抛 IllegalStateException")
    void testUnlockUnregistered() {
        DeviceRegistry registry = new DeviceRegistry();
        DroneLockService svc = newService(registry);

        assertThatThrownBy(() -> svc.unlock(99, "admin"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("not registered");
    }

    // ------------------------------------------------------------------
    // getLockState
    // ------------------------------------------------------------------

    @Test
    @DisplayName("testGetLockState: 获取状态（未记录返回 unlocked）")
    void testGetLockState() {
        DeviceRegistry registry = new DeviceRegistry();
        registry.registerIfAbsent(1);
        DroneLockService svc = newService(registry);

        // 未锁定时返回 unlocked
        LockState state = svc.getLockState(1);
        assertThat(state.isLocked()).isFalse();
        assertThat(state.getSysid()).isEqualTo(1);

        // 锁定后返回锁定状态
        svc.lock(1, "test", "op", LockState.Action.DISARM);
        LockState locked = svc.getLockState(1);
        assertThat(locked.isLocked()).isTrue();
        assertThat(locked.getLockedBy()).isEqualTo("op");
    }

    // ------------------------------------------------------------------
    // isLocked
    // ------------------------------------------------------------------

    @Test
    @DisplayName("testIsLocked: 判断锁定状态")
    void testIsLocked() {
        DeviceRegistry registry = new DeviceRegistry();
        registry.registerIfAbsent(1);
        DroneLockService svc = newService(registry);

        assertThat(svc.isLocked(1)).isFalse();
        svc.lock(1, "test", "op", LockState.Action.DISARM);
        assertThat(svc.isLocked(1)).isTrue();
        svc.unlock(1, "op");
        assertThat(svc.isLocked(1)).isFalse();
    }

    // ------------------------------------------------------------------
    // getLockedDrones
    // ------------------------------------------------------------------

    @Test
    @DisplayName("testGetLockedDrones: 获取所有已锁定无人机（按 sysid 升序）")
    void testGetLockedDrones() {
        DeviceRegistry registry = new DeviceRegistry();
        registry.registerIfAbsent(1);
        registry.registerIfAbsent(2);
        registry.registerIfAbsent(3);
        DroneLockService svc = newService(registry);

        svc.lock(2, "reason2", "op", LockState.Action.DISARM);
        svc.lock(1, "reason1", "op", LockState.Action.FORCE_LAND);
        // sysid=3 未锁定

        List<LockState> locked = svc.getLockedDrones();
        assertThat(locked).hasSize(2);
        assertThat(locked.get(0).getSysid()).isEqualTo(1);
        assertThat(locked.get(1).getSysid()).isEqualTo(2);

        // 解锁后不再包含
        svc.unlock(1, "op");
        List<LockState> after = svc.getLockedDrones();
        assertThat(after).hasSize(1);
        assertThat(after.get(0).getSysid()).isEqualTo(2);
    }

    @Test
    @DisplayName("testGetLockedDrones: 无锁定返回空列表")
    void testGetLockedDrones_empty() {
        DeviceRegistry registry = new DeviceRegistry();
        DroneLockService svc = newService(registry);

        assertThat(svc.getLockedDrones()).isEmpty();
    }

    // ------------------------------------------------------------------
    // getAllLockStates
    // ------------------------------------------------------------------

    @Test
    @DisplayName("testGetAllLockStates: 获取所有锁定状态记录")
    void testGetAllLockStates() {
        DeviceRegistry registry = new DeviceRegistry();
        registry.registerIfAbsent(1);
        registry.registerIfAbsent(2);
        DroneLockService svc = newService(registry);

        svc.lock(1, "r1", "op", LockState.Action.DISARM);
        svc.lock(2, "r2", "op", LockState.Action.FORCE_LAND);
        svc.unlock(2, "op");

        List<LockState> all = svc.getAllLockStates();
        assertThat(all).hasSize(2);
        assertThat(all.get(0).getSysid()).isEqualTo(1);
        assertThat(all.get(0).isLocked()).isTrue();
        assertThat(all.get(1).getSysid()).isEqualTo(2);
        assertThat(all.get(1).isLocked()).isFalse();
    }

    // ------------------------------------------------------------------
    // clearLockState
    // ------------------------------------------------------------------

    @Test
    @DisplayName("testClearLockState: 清除锁定状态记录")
    void testClearLockState() {
        DeviceRegistry registry = new DeviceRegistry();
        registry.registerIfAbsent(1);
        DroneLockService svc = newService(registry);

        svc.lock(1, "test", "op", LockState.Action.DISARM);
        assertThat(svc.isLocked(1)).isTrue();

        svc.clearLockState(1);
        assertThat(svc.isLocked(1)).isFalse();
        // 清除后 getLockState 返回 unlocked
        LockState state = svc.getLockState(1);
        assertThat(state.isLocked()).isFalse();
        assertThat(state.getLockTimeMs()).isEqualTo(0L);
    }

    @Test
    @DisplayName("testClearLockState: 清除不存在的记录无副作用")
    void testClearLockState_nonExisting() {
        DeviceRegistry registry = new DeviceRegistry();
        DroneLockService svc = newService(registry);

        // 不抛异常
        svc.clearLockState(99);
    }
}