package io.aerofleet.cloud.rid;

import io.aerofleet.cloud.rid.model.BasicIdData;
import io.aerofleet.cloud.rid.model.LocationData;
import io.aerofleet.cloud.rid.model.OperatorIdData;
import io.aerofleet.cloud.rid.model.RidComplianceState;
import io.aerofleet.cloud.rid.model.RidSnapshot;
import io.aerofleet.cloud.rid.model.SelfIdData;
import io.aerofleet.cloud.rid.model.SystemData;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Remote ID 状态管理器（内存态）。
 * <p>
 * 使用 {@link ConcurrentHashMap} 存储每架无人机的 RID 快照，提供状态更新、超时检测与查询功能。
 * 状态流转规则：
 * <ul>
 *   <li>{@code NOT_BROADCASTING → BROADCASTING} — 收到任何 RID 消息时自动转换</li>
 *   <li>{@code BROADCASTING → BROADCASTING_ERROR} — 超时未收到消息时自动转换</li>
 *   <li>{@code BROADCASTING_ERROR → BROADCASTING} — 收到新消息时恢复（不允许回退到 NOT_BROADCASTING）</li>
 * </ul>
 * 不允许从 {@code BROADCASTING_ERROR} 直接回退到 {@code NOT_BROADCASTING}。
 *
 * @see RidSnapshot
 * @see RidComplianceState
 * @see RidConfig
 */
@Component
public class RidStateManager {

    private static final Logger log = LoggerFactory.getLogger(RidStateManager.class);

    /** sysid → RidSnapshot，内存态存储。 */
    private final ConcurrentHashMap<Integer, RidSnapshot> snapshotMap = new ConcurrentHashMap<>();

    /** RID 配置，提供超时周期数与广播间隔。 */
    private final RidConfig config;

    /**
     * 构造 RID 状态管理器。
     *
     * @param config RID 配置
     */
    public RidStateManager(RidConfig config) {
        this.config = config;
    }

    /**
     * 更新 Basic ID 数据。
     * <p>
     * 收到消息时自动将状态转为 {@code BROADCASTING}，并更新 lastReceivedTime。
     *
     * @param sysid 无人机系统标识
     * @param data   Basic ID 数据
     */
    public void updateBasicId(int sysid, BasicIdData data) {
        snapshotMap.compute(sysid, (key, existing) -> {
            long now = System.currentTimeMillis();
            if (existing == null) {
                RidSnapshot snapshot = new RidSnapshot(
                        sysid, RidComplianceState.BROADCASTING, data, null, null, null, null, now);
                log.info("RID 状态初始化: sysid={}, status=BROADCASTING (BasicId)", sysid);
                return snapshot;
            }
            RidComplianceState newStatus = transitionOnMessage(existing.ridStatus());
            RidSnapshot updated = new RidSnapshot(
                    sysid, newStatus, data, existing.location(), existing.system(),
                    existing.selfId(), existing.operatorId(), now);
            logStatusChange(sysid, existing.ridStatus(), newStatus);
            return updated;
        });
    }

    /**
     * 更新 Location 数据。
     * <p>
     * 收到消息时自动将状态转为 {@code BROADCASTING}，并更新 lastReceivedTime。
     *
     * @param sysid 无人机系统标识
     * @param data   Location 数据
     */
    public void updateLocation(int sysid, LocationData data) {
        snapshotMap.compute(sysid, (key, existing) -> {
            long now = System.currentTimeMillis();
            if (existing == null) {
                RidSnapshot snapshot = new RidSnapshot(
                        sysid, RidComplianceState.BROADCASTING, null, data, null, null, null, now);
                log.info("RID 状态初始化: sysid={}, status=BROADCASTING (Location)", sysid);
                return snapshot;
            }
            RidComplianceState newStatus = transitionOnMessage(existing.ridStatus());
            RidSnapshot updated = new RidSnapshot(
                    sysid, newStatus, existing.basicId(), data, existing.system(),
                    existing.selfId(), existing.operatorId(), now);
            logStatusChange(sysid, existing.ridStatus(), newStatus);
            return updated;
        });
    }

    /**
     * 更新 System 数据。
     * <p>
     * 收到消息时自动将状态转为 {@code BROADCASTING}，并更新 lastReceivedTime。
     *
     * @param sysid 无人机系统标识
     * @param data   System 数据
     */
    public void updateSystem(int sysid, SystemData data) {
        snapshotMap.compute(sysid, (key, existing) -> {
            long now = System.currentTimeMillis();
            if (existing == null) {
                RidSnapshot snapshot = new RidSnapshot(
                        sysid, RidComplianceState.BROADCASTING, null, null, data, null, null, now);
                log.info("RID 状态初始化: sysid={}, status=BROADCASTING (System)", sysid);
                return snapshot;
            }
            RidComplianceState newStatus = transitionOnMessage(existing.ridStatus());
            RidSnapshot updated = new RidSnapshot(
                    sysid, newStatus, existing.basicId(), existing.location(), data,
                    existing.selfId(), existing.operatorId(), now);
            logStatusChange(sysid, existing.ridStatus(), newStatus);
            return updated;
        });
    }

    /**
     * 更新 Self ID 数据。
     * <p>
     * 收到消息时自动将状态转为 {@code BROADCASTING}，并更新 lastReceivedTime。
     *
     * @param sysid 无人机系统标识
     * @param data   Self ID 数据
     */
    public void updateSelfId(int sysid, SelfIdData data) {
        snapshotMap.compute(sysid, (key, existing) -> {
            long now = System.currentTimeMillis();
            if (existing == null) {
                RidSnapshot snapshot = new RidSnapshot(
                        sysid, RidComplianceState.BROADCASTING, null, null, null, data, null, now);
                log.info("RID 状态初始化: sysid={}, status=BROADCASTING (SelfId)", sysid);
                return snapshot;
            }
            RidComplianceState newStatus = transitionOnMessage(existing.ridStatus());
            RidSnapshot updated = new RidSnapshot(
                    sysid, newStatus, existing.basicId(), existing.location(), existing.system(),
                    data, existing.operatorId(), now);
            logStatusChange(sysid, existing.ridStatus(), newStatus);
            return updated;
        });
    }

    /**
     * 更新 Operator ID 数据。
     * <p>
     * 收到消息时自动将状态转为 {@code BROADCASTING}，并更新 lastReceivedTime。
     *
     * @param sysid 无人机系统标识
     * @param data   Operator ID 数据
     */
    public void updateOperatorId(int sysid, OperatorIdData data) {
        snapshotMap.compute(sysid, (key, existing) -> {
            long now = System.currentTimeMillis();
            if (existing == null) {
                RidSnapshot snapshot = new RidSnapshot(
                        sysid, RidComplianceState.BROADCASTING, null, null, null, null, data, now);
                log.info("RID 状态初始化: sysid={}, status=BROADCASTING (OperatorId)", sysid);
                return snapshot;
            }
            RidComplianceState newStatus = transitionOnMessage(existing.ridStatus());
            RidSnapshot updated = new RidSnapshot(
                    sysid, newStatus, existing.basicId(), existing.location(), existing.system(),
                    existing.selfId(), data, now);
            logStatusChange(sysid, existing.ridStatus(), newStatus);
            return updated;
        });
    }

    /**
     * 超时检测：每秒检查所有 RID 快照。
     * <p>
     * 超过 {@code timeoutPeriods × broadcastInterval} 秒未收到消息时，
     * 状态从 {@code BROADCASTING} 转为 {@code BROADCASTING_ERROR}。
     * 状态变更时输出 INFO 日志。
     */
    @Scheduled(fixedRate = 1000)
    public void checkTimeout() {
        long now = System.currentTimeMillis();
        long timeoutMs = (long) (config.getTimeoutPeriods() * config.getBroadcastInterval() * 1000);

        for (var entry : snapshotMap.entrySet()) {
            int sysid = entry.getKey();
            RidSnapshot snapshot = entry.getValue();

            if (snapshot.ridStatus() == RidComplianceState.BROADCASTING) {
                long elapsed = now - snapshot.lastReceivedTime();
                if (elapsed > timeoutMs) {
                    snapshotMap.compute(sysid, (key, existing) -> {
                        if (existing == null || existing.ridStatus() != RidComplianceState.BROADCASTING) {
                            return existing; // 状态已变更，跳过
                        }
                        RidSnapshot updated = new RidSnapshot(
                                existing.sysid(), RidComplianceState.BROADCASTING_ERROR,
                                existing.basicId(), existing.location(), existing.system(),
                                existing.selfId(), existing.operatorId(), existing.lastReceivedTime());
                        log.info("RID 状态超时变更: sysid={}, from=BROADCASTING, to=BROADCASTING_ERROR, "
                                        + "elapsed={}ms, timeout={}ms",
                                sysid, elapsed, timeoutMs);
                        return updated;
                    });
                }
            }
        }
    }

    /**
     * 查询单架无人机的 RID 快照。
     *
     * @param sysid 无人机系统标识
     * @return RID 快照，若不存在返回 {@code null}
     */
    public RidSnapshot get(int sysid) {
        return snapshotMap.get(sysid);
    }

    /**
     * 查询全部无人机的 RID 快照列表。
     *
     * @return RID 快照列表（按 sysid 升序排列）
     */
    public List<RidSnapshot> getAll() {
        List<RidSnapshot> list = new ArrayList<>(snapshotMap.values());
        list.sort((a, b) -> Integer.compare(a.sysid(), b.sysid()));
        return list;
    }

    /**
     * 移除单架无人机的 RID 快照（设备离线清理）。
     *
     * @param sysid 无人机系统标识
     * @return 被移除的 RID 快照，若不存在返回 {@code null}
     */
    public RidSnapshot remove(int sysid) {
        RidSnapshot removed = snapshotMap.remove(sysid);
        if (removed != null) {
            log.info("RID 快照移除: sysid={}, status={}", sysid, removed.ridStatus());
        }
        return removed;
    }

    // --- 私有辅助方法 ---

    /**
     * 收到消息时的状态流转逻辑。
     * <p>
     * - {@code NOT_BROADCASTING} → {@code BROADCASTING}（开始广播）<br>
     * - {@code BROADCASTING} → {@code BROADCASTING}（保持广播）<br>
     * - {@code BROADCASTING_ERROR} → {@code BROADCASTING}（恢复广播，不允许回退到 NOT_BROADCASTING）
     *
     * @param current 当前状态
     * @return 新状态
     */
    private RidComplianceState transitionOnMessage(RidComplianceState current) {
        return switch (current) {
            case NOT_BROADCASTING, BROADCASTING_ERROR -> RidComplianceState.BROADCASTING;
            case BROADCASTING -> RidComplianceState.BROADCASTING;
        };
    }

    /**
     * 输出状态变更日志（仅在状态实际变化时输出）。
     *
     * @param sysid     无人机系统标识
     * @param oldStatus 旧状态
     * @param newStatus 新状态
     */
    private void logStatusChange(int sysid, RidComplianceState oldStatus, RidComplianceState newStatus) {
        if (oldStatus != newStatus) {
            log.info("RID 状态变更: sysid={}, from={}, to={}", sysid, oldStatus, newStatus);
        }
    }
}