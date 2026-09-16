package io.aerofleet.sim.mesh;

import java.net.InetSocketAddress;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 邻居表容器（M5 应急 mesh，FR-10）。
 * <p>
 * 内部 {@link ConcurrentHashMap}，线程安全。支持邻居的新增/刷新/移除/超时检查/快照。
 * <p>
 * 使用范式：由 {@code MeshRouter} 在收到 HELLO 时调 {@link #upsert}，在 tick 周期调
 * {@link #findExpired} 检查超时并 {@link #remove} 移除。
 */
public final class NeighborTable {

    private final ConcurrentHashMap<Integer, NeighborEntry> table = new ConcurrentHashMap<>();

    /**
     * 新增或刷新邻居表项（FR-10）。
     * <p>
     * 若 sysid 已存在则刷新 lastSeen/rssi/quality；否则新增。
     *
     * @param sysid   邻居 sysid
     * @param addr    邻居对端地址
     * @param nowMs   当前时间戳
     * @param rssiDbm RSSI（dBm）
     */
    public void upsert(int sysid, InetSocketAddress addr, long nowMs, int rssiDbm) {
        NeighborEntry existing = table.get(sysid);
        if (existing != null && existing.addr != null && existing.addr.equals(addr)) {
            // 同地址：刷新时间戳与 RSSI
            table.put(sysid, existing.refresh(nowMs, rssiDbm));
        } else {
            // 新邻居或地址变更：新建
            table.put(sysid, NeighborEntry.of(sysid, addr, nowMs, rssiDbm));
        }
    }

    /** 获取邻居表项；不存在返回 null。 */
    public NeighborEntry get(int sysid) {
        return table.get(sysid);
    }

    /** 移除邻居表项。 */
    public void remove(int sysid) {
        table.remove(sysid);
    }

    /**
     * 返回超时邻居 sysid 列表（FR-11）。
     * <p>
     * 判定条件：{@code nowMs - lastSeenMs > timeoutMs}。
     *
     * @param nowMs     当前时间戳
     * @param timeoutMs 超时阈值（ms）
     * @return 超时邻居 sysid 列表（可能为空）
     */
    public List<Integer> findExpired(long nowMs, long timeoutMs) {
        List<Integer> expired = new ArrayList<>();
        for (NeighborEntry e : table.values()) {
            if (nowMs - e.lastSeenMs > timeoutMs) {
                expired.add(e.sysid);
            }
        }
        return expired;
    }

    /**
     * 返回不可变快照（供 REST/拓扑上报用）。
     */
    public List<NeighborEntry> snapshot() {
        return Collections.unmodifiableList(new ArrayList<>(table.values()));
    }

    /** 当前邻居数。 */
    public int size() {
        return table.size();
    }

    /** 是否包含指定邻居。 */
    public boolean contains(int sysid) {
        return table.containsKey(sysid);
    }

    /** 清空邻居表。 */
    public void clear() {
        table.clear();
    }
}