package io.aerofleet.cloud.drone;

import io.aerofleet.cloud.gateway.DeviceRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 无人机远程锁定/解锁服务。
 * <p>
 * 将电瓶车"远程锁车/解锁"能力迁移到无人机：当无人机被盗或遗失时，
 * 远程锁定电机（禁止起飞 / 强制降落 / 返航锁定）。
 * <p>
 * 状态以 {@link LockState} 记录，保存在内存 {@link ConcurrentHashMap} 中，
 * 键为 MAVLink systemId。所有操作均校验无人机是否已在
 * {@link DeviceRegistry} 注册。
 * <p>
 * 线程安全：使用 {@link ConcurrentHashMap} + compute 原子操作。
 */
@Service
public class DroneLockService {

    private static final Logger log = LoggerFactory.getLogger(DroneLockService.class);

    private final Map<Integer, LockState> lockStates = new ConcurrentHashMap<>();
    private final DeviceRegistry registry;

    public DroneLockService(DeviceRegistry registry) {
        this.registry = registry;
    }

    /**
     * 锁定无人机。
     * <p>
     * 幂等：若无人机已锁定，返回当前锁定状态而不覆盖（保留首次锁定信息）。
     *
     * @param sysid    无人机 systemId
     * @param reason   锁定原因
     * @param lockedBy 操作人/系统标识
     * @param action   锁定动作（DISARM/FORCE_LAND/RETURN_TO_LAUNCH）
     * @return 更新后的锁定状态
     * @throws IllegalStateException 无人机未注册
     */
    public LockState lock(int sysid, String reason, String lockedBy, LockState.Action action) {
        requireRegistered(sysid);
        LockState[] result = new LockState[1];
        boolean[] idempotent = new boolean[1];
        lockStates.compute(sysid, (id, existing) -> {
            if (existing != null && existing.isLocked()) {
                // 幂等：已锁定时返回当前状态，不覆盖（保留首次锁定信息）
                result[0] = existing;
                idempotent[0] = true;
                return existing;
            }
            LockState newState = LockState.locked(sysid, reason, lockedBy, action);
            result[0] = newState;
            return newState;
        });
        if (idempotent[0]) {
            log.warn("Drone already locked (idempotent return): sysid={} lockedBy={} reason={}",
                    sysid, result[0].getLockedBy(), result[0].getLockReason());
        } else {
            log.info("Drone locked: sysid={} reason={} by={} action={}",
                    sysid, reason, lockedBy, action);
        }
        return result[0];
    }

    /**
     * 解锁无人机。
     *
     * @param sysid      无人机 systemId
     * @param unlockedBy 操作人/系统标识
     * @return 更新后的锁定状态
     * @throws IllegalStateException 无人机未注册或未锁定
     */
    public LockState unlock(int sysid, String unlockedBy) {
        requireRegistered(sysid);
        LockState current = lockStates.get(sysid);
        if (current == null || !current.isLocked()) {
            throw new IllegalStateException("drone " + sysid + " is not locked");
        }
        LockState unlocked = new LockState(sysid, false, current.getLockTimeMs(),
                System.currentTimeMillis(), current.getLockReason(),
                current.getLockedBy(), current.getAction());
        lockStates.put(sysid, unlocked);
        log.info("Drone unlocked: sysid={} by={} (was locked by {} at {})",
                sysid, unlockedBy, current.getLockedBy(), current.getLockTimeMs());
        return unlocked;
    }

    /**
     * 获取锁定状态；未记录时返回 {@link LockState#unlocked(int)}。
     */
    public LockState getLockState(int sysid) {
        LockState state = lockStates.get(sysid);
        return state != null ? state : LockState.unlocked(sysid);
    }

    /** 是否已锁定。 */
    public boolean isLocked(int sysid) {
        LockState state = lockStates.get(sysid);
        return state != null && state.isLocked();
    }

    /** 获取所有已锁定无人机（按 sysid 升序）。 */
    public List<LockState> getLockedDrones() {
        List<LockState> list = new ArrayList<>();
        for (LockState state : lockStates.values()) {
            if (state.isLocked()) {
                list.add(state);
            }
        }
        list.sort((a, b) -> Integer.compare(a.getSysid(), b.getSysid()));
        return list;
    }

    /** 获取所有无人机锁定状态（按 sysid 升序）。 */
    public List<LockState> getAllLockStates() {
        List<LockState> list = new ArrayList<>(lockStates.values());
        list.sort((a, b) -> Integer.compare(a.getSysid(), b.getSysid()));
        return list;
    }

    /**
     * 清除锁定状态记录（管理端点）。
     * <p>
     * 与 {@link #unlock} 不同：直接删除记录，不生成解锁事件。
     */
    public void clearLockState(int sysid) {
        LockState removed = lockStates.remove(sysid);
        if (removed != null) {
            log.info("Drone lock state cleared: sysid={} wasLocked={}", sysid, removed.isLocked());
        }
    }

    private void requireRegistered(int sysid) {
        if (registry.get(sysid) == null) {
            throw new IllegalStateException("drone " + sysid + " is not registered");
        }
    }
}