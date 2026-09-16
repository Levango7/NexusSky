package io.aerofleet.sim.mesh;

import java.net.InetSocketAddress;

/**
 * 邻居表项值对象（M5 应急 mesh，FR-10）。
 * <p>
 * 不可变：每次刷新返回新实例。承载邻居 sysid、对端地址、最近见报时间、RSSI 与派生的链路质量分级。
 * <p>
 * 数据约束 7.1：sysid ∈ [1,255]，rssiDbm ∈ [-120,0]。
 */
public final class NeighborEntry {

    public final int sysid;
    public final InetSocketAddress addr;
    public final long lastSeenMs;
    public final int rssiDbm;
    public final LinkQuality linkQuality;

    private NeighborEntry(int sysid, InetSocketAddress addr, long lastSeenMs,
                          int rssiDbm, LinkQuality linkQuality) {
        this.sysid = sysid;
        this.addr = addr;
        this.lastSeenMs = lastSeenMs;
        this.rssiDbm = rssiDbm;
        this.linkQuality = linkQuality;
    }

    /**
     * 工厂方法：自动调用 {@link LinkQuality#fromRssi} 派生分级。
     *
     * @param sysid    邻居 sysid（1-255）
     * @param addr     邻居对端地址
     * @param nowMs    当前时间戳（epoch ms）
     * @param rssiDbm  RSSI（dBm，[-120,0]）
     */
    public static NeighborEntry of(int sysid, InetSocketAddress addr, long nowMs, int rssiDbm) {
        return new NeighborEntry(sysid, addr, nowMs, rssiDbm, LinkQuality.fromRssi(rssiDbm));
    }

    /**
     * 刷新：返回带新时间戳与 RSSI 的新实例（地址保持不变）。
     *
     * @param nowMs   新时间戳
     * @param rssiDbm 新 RSSI
     * @return 刷新后的新 NeighborEntry
     */
    public NeighborEntry refresh(long nowMs, int rssiDbm) {
        return new NeighborEntry(this.sysid, this.addr, nowMs, rssiDbm,
                LinkQuality.fromRssi(rssiDbm));
    }

    @Override
    public String toString() {
        return "NeighborEntry{sysid=" + sysid + ", addr=" + addr
                + ", lastSeen=" + lastSeenMs + ", rssi=" + rssiDbm
                + ", quality=" + linkQuality + "}";
    }
}