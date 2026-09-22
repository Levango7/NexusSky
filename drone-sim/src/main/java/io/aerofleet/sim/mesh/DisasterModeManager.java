package io.aerofleet.sim.mesh;

import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 灾害模式管理（灾害应急通讯组网，FR-03/04/05）。
 * <p>
 * 灾害模式自动激活：检测到灾害触发条件（心跳大面积超时、地形变更事件、手动激活）时，
 * 自动进入灾害模式，启用 QoS 优先级队列、缩短 HELLO 间隔、放宽邻居超时阈值、启用分簇。
 * <p>
 * 灾害模式退出：恢复率>80% + 30分钟无新灾害事件时自动退出。
 * <p>
 * 线程安全：关键字段 volatile，集合 ConcurrentHashMap。
 */
public final class DisasterModeManager {

    /** 灾害触发原因枚举。 */
    public enum TriggerReason {
        /** 心跳大面积超时。 */
        HEARTBEAT_TIMEOUT(0),
        /** 地形变更事件。 */
        TERRAIN_CHANGE(1),
        /** 手动激活。 */
        MANUAL_ACTIVATION(2),
        /** 链路质量大面积下降。 */
        LINK_QUALITY_DROP(3);

        private final int code;

        TriggerReason(int code) {
            this.code = code;
        }

        public int code() {
            return code;
        }

        public static TriggerReason fromCode(int code) {
            return switch (code) {
                case 0 -> HEARTBEAT_TIMEOUT;
                case 1 -> TERRAIN_CHANGE;
                case 2 -> MANUAL_ACTIVATION;
                default -> LINK_QUALITY_DROP;
            };
        }
    }

    // ===== 灾害模式参数（正常 vs 灾害） =====
    /** 正常模式 HELLO 间隔（ms）。 */
    public static final long NORMAL_HELLO_INTERVAL_MS = 1000L;
    /** 灾害模式 HELLO 间隔（ms）。 */
    public static final long DISASTER_HELLO_INTERVAL_MS = 500L;
    /** 正常模式邻居超时（ms）。 */
    public static final long NORMAL_NEIGHBOR_TIMEOUT_MS = 5000L;
    /** 灾害模式邻居超时（ms）。 */
    public static final long DISASTER_NEIGHBOR_TIMEOUT_MS = 3000L;
    /** 正常模式路由生命周期（ms）。 */
    public static final long NORMAL_ROUTE_LIFETIME_MS = 10000L;
    /** 灾害模式路由生命周期（ms）。 */
    public static final long DISASTER_ROUTE_LIFETIME_MS = 5000L;

    /** 退出灾害模式所需恢复率阈值（%）。 */
    public static final double RECOVERY_RATE_THRESHOLD = 80.0;
    /** 退出灾害模式所需无新灾害持续时间（ms）。 */
    public static final long NO_DISASTER_DURATION_MS = 30 * 60 * 1000L; // 30 分钟

    /** 当前是否灾害模式。 */
    private volatile boolean disasterMode = false;

    /** 灾害模式激活时间戳。 */
    private volatile long activatedAtMs = 0;

    /** 最近一次灾害事件时间戳。 */
    private volatile long lastDisasterEventMs = 0;

    /** 当前触发原因。 */
    private volatile TriggerReason triggerReason = null;

    /** 受影响节点集合（sysid）。 */
    private final Set<Integer> affectedNodes = ConcurrentHashMap.newKeySet();

    /** 已恢复节点集合（sysid）。 */
    private final Set<Integer> recoveredNodes = ConcurrentHashMap.newKeySet();

    /** 总节点数（供恢复率计算用）。 */
    private volatile int totalNodeCount = 0;

    /** 当前 HELLO 间隔。 */
    private volatile long helloIntervalMs = NORMAL_HELLO_INTERVAL_MS;
    /** 当前邻居超时。 */
    private volatile long neighborTimeoutMs = NORMAL_NEIGHBOR_TIMEOUT_MS;
    /** 当前路由生命周期。 */
    private volatile long routeLifetimeMs = NORMAL_ROUTE_LIFETIME_MS;

    /**
     * 激活灾害模式（FR-03）。
     * <p>
     * 缩短 HELLO 间隔、放宽邻居超时、启用分簇。
     *
     * @param triggerReason 触发原因
     */
    public void activateDisasterMode(int triggerReason) {
        activateDisasterMode(TriggerReason.fromCode(triggerReason));
    }

    /**
     * 激活灾害模式（枚举版）。
     */
    public void activateDisasterMode(TriggerReason reason) {
        if (disasterMode) {
            // 已在灾害模式：仅更新最近灾害事件时间
            lastDisasterEventMs = System.currentTimeMillis();
            this.triggerReason = reason;
            return;
        }
        this.disasterMode = true;
        this.triggerReason = reason;
        this.activatedAtMs = System.currentTimeMillis();
        this.lastDisasterEventMs = activatedAtMs;

        // 调整参数
        this.helloIntervalMs = DISASTER_HELLO_INTERVAL_MS;
        this.neighborTimeoutMs = DISASTER_NEIGHBOR_TIMEOUT_MS;
        this.routeLifetimeMs = DISASTER_ROUTE_LIFETIME_MS;
    }

    /**
     * 退出灾害模式（FR-05）。
     * <p>
     * 条件：恢复率>80% + 30分钟无新灾害事件。
     *
     * @return true 若成功退出灾害模式
     */
    public boolean deactivateDisasterMode() {
        if (!disasterMode) {
            return true;
        }
        long now = System.currentTimeMillis();
        double recoveryRate = getRecoveryRate();
        long timeSinceLastDisaster = now - lastDisasterEventMs;

        if (recoveryRate > RECOVERY_RATE_THRESHOLD
                && timeSinceLastDisaster >= NO_DISASTER_DURATION_MS) {
            disasterMode = false;
            triggerReason = null;
            activatedAtMs = 0;
            affectedNodes.clear();
            recoveredNodes.clear();

            // 恢复正常参数
            helloIntervalMs = NORMAL_HELLO_INTERVAL_MS;
            neighborTimeoutMs = NORMAL_NEIGHBOR_TIMEOUT_MS;
            routeLifetimeMs = NORMAL_ROUTE_LIFETIME_MS;
            return true;
        }
        return false;
    }

    /**
     * 强制退出灾害模式（手动恢复）。
     */
    public void forceDeactivate() {
        disasterMode = false;
        triggerReason = null;
        activatedAtMs = 0;
        affectedNodes.clear();
        recoveredNodes.clear();
        helloIntervalMs = NORMAL_HELLO_INTERVAL_MS;
        neighborTimeoutMs = NORMAL_NEIGHBOR_TIMEOUT_MS;
        routeLifetimeMs = NORMAL_ROUTE_LIFETIME_MS;
    }

    /**
     * 查询当前是否灾害模式。
     */
    public boolean isDisasterMode() {
        return disasterMode;
    }

    /**
     * 计算恢复率（FR-05）。
     * <p>
     * 恢复率 = 已恢复节点数 / 受影响节点数 × 100%。
     * 若无受影响节点，恢复率为 100%。
     *
     * @return 恢复率（0-100）
     */
    public double getRecoveryRate() {
        if (affectedNodes.isEmpty()) {
            return 100.0;
        }
        return (recoveredNodes.size() * 100.0) / affectedNodes.size();
    }

    /**
     * 添加受影响节点。
     */
    public void addAffectedNode(int sysid) {
        affectedNodes.add(sysid);
        lastDisasterEventMs = System.currentTimeMillis();
    }

    /**
     * 标记节点已恢复。
     */
    public void markNodeRecovered(int sysid) {
        if (affectedNodes.contains(sysid)) {
            recoveredNodes.add(sysid);
        }
    }

    /**
     * 设置总节点数。
     */
    public void setTotalNodeCount(int count) {
        this.totalNodeCount = count;
    }

    /**
     * 获取当前 HELLO 间隔。
     */
    public long getHelloIntervalMs() {
        return helloIntervalMs;
    }

    /**
     * 获取当前邻居超时。
     */
    public long getNeighborTimeoutMs() {
        return neighborTimeoutMs;
    }

    /**
     * 获取当前路由生命周期。
     */
    public long getRouteLifetimeMs() {
        return routeLifetimeMs;
    }

    /**
     * 获取触发原因。
     */
    public TriggerReason getTriggerReason() {
        return triggerReason;
    }

    /**
     * 获取受影响节点集合。
     */
    public Set<Integer> getAffectedNodes() {
        return new HashSet<>(affectedNodes);
    }

    /**
     * 获取已恢复节点集合。
     */
    public Set<Integer> getRecoveredNodes() {
        return new HashSet<>(recoveredNodes);
    }

    /**
     * 获取灾害模式激活时间戳。
     */
    public long getActivatedAtMs() {
        return activatedAtMs;
    }

    /**
     * 获取最近灾害事件时间戳。
     */
    public long getLastDisasterEventMs() {
        return lastDisasterEventMs;
    }

    /**
     * 检查是否满足退出条件（不实际退出）。
     *
     * @return true 若满足退出条件
     */
    public boolean canDeactivate() {
        if (!disasterMode) {
            return true;
        }
        long now = System.currentTimeMillis();
        return getRecoveryRate() > RECOVERY_RATE_THRESHOLD
                && (now - lastDisasterEventMs) >= NO_DISASTER_DURATION_MS;
    }
}