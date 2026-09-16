package io.aerofleet.sim.mesh;

/**
 * 路由表项值对象（M5 应急 mesh，FR-17/18/21）。
 * <p>
 * 不可变：每次刷新返回新实例。承载到目标节点的下一跳、跳数、度量、生命周期与主备标志。
 * <p>
 * 数据约束 7.2：targetSysId/nextHop ∈ [1,255]，hopCount ∈ [0,15]，metric ≥ 0。
 */
public final class RouteEntry {

    public final int targetSysId;
    public final int nextHop;
    public final int hopCount;
    public final double metric;
    public final long createdAtMs;
    public final long expireAtMs;
    public final boolean isPrimary;

    public RouteEntry(int targetSysId, int nextHop, int hopCount, double metric,
                      long createdAtMs, long expireAtMs, boolean isPrimary) {
        this.targetSysId = targetSysId;
        this.nextHop = nextHop;
        this.hopCount = hopCount;
        this.metric = metric;
        this.createdAtMs = createdAtMs;
        this.expireAtMs = expireAtMs;
        this.isPrimary = isPrimary;
    }

    /**
     * 是否已过期（FR-21）。
     *
     * @param nowMs 当前时间戳
     * @return true 若 {@code nowMs >= expireAtMs}
     */
    public boolean isExpired(long nowMs) {
        return nowMs >= expireAtMs;
    }

    /**
     * 刷新：返回带新度量与生命周期的新实例（保持 targetSysId/nextHop/hopCount/isPrimary）。
     *
     * @param newMetric  新度量
     * @param nowMs      当前时间戳
     * @param lifetimeMs 新生命周期（ms）
     * @return 刷新后的新 RouteEntry
     */
    public RouteEntry refresh(double newMetric, long nowMs, long lifetimeMs) {
        return new RouteEntry(this.targetSysId, this.nextHop, this.hopCount,
                newMetric, nowMs, nowMs + lifetimeMs, this.isPrimary);
    }

    /**
     * 切换主备标志：返回新实例。
     */
    public RouteEntry withPrimary(boolean primary) {
        return new RouteEntry(this.targetSysId, this.nextHop, this.hopCount,
                this.metric, this.createdAtMs, this.expireAtMs, primary);
    }

    @Override
    public String toString() {
        return "RouteEntry{tgt=" + targetSysId + " via " + nextHop
                + ", hops=" + hopCount + ", metric=" + metric
                + ", expire=" + expireAtMs
                + ", primary=" + isPrimary + "}";
    }
}