package io.aerofleet.cloud.api.dto;

import java.util.Collections;
import java.util.List;

/**
 * Mesh 节点拓扑快照 DTO（M5 应急 mesh，FR-27/28）。
 * <p>
 * 承载单个节点的邻居表快照，供 REST 查询与 WebSocket 推送用。
 */
public final class MeshNodeSnapshot {

    /** 节点 sysid。 */
    public final int sysid;
    /** 最近一次更新时间戳（epoch ms）。 */
    public final long lastUpdateMs;
    /** 邻居列表。 */
    public final List<NeighborDto> neighbors;

    public MeshNodeSnapshot(int sysid, long lastUpdateMs, List<NeighborDto> neighbors) {
        this.sysid = sysid;
        this.lastUpdateMs = lastUpdateMs;
        this.neighbors = neighbors == null
                ? Collections.emptyList()
                : Collections.unmodifiableList(neighbors);
    }

    /** 邻居项 DTO。 */
    public record NeighborDto(int sysid, int rssiDbm, String quality) {}

    /** 路由表项 DTO。 */
    public record RouteEntryDto(int targetSysId, int nextHop, int hopCount,
                                double metric, boolean isPrimary, long expireAtMs) {}

    /** 链路 DTO（A-B 与 B-A 合并后的无向边）。 */
    public record LinkDto(int from, int to, int rssiDbm, String quality) {}

    /** 全网拓扑 DTO。 */
    public record MeshTopologyDto(long version, List<MeshNodeSnapshot> nodes) {}
}