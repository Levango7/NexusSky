package io.aerofleet.cloud.api.service;

import io.aerofleet.cloud.api.dto.MeshNodeSnapshot;
import io.aerofleet.cloud.api.dto.MeshNodeSnapshot.NeighborDto;
import io.aerofleet.cloud.gateway.MavlinkMessageEvent;
import io.aerofleet.mavlink.messages.MeshNeighborTableMsg;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Service;

import java.util.ArrayList;

import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Mesh 拓扑快照存储服务（M5 应急 mesh，FR-27/28/29）。
 * <p>
 * 接收各节点上报的 {@link MeshNeighborTableMsg}，维护全局拓扑快照，
 * 提供只读查询与变化检测（供 REST + WebSocket 推送用）。
 * <p>
 * 内部 {@link ConcurrentHashMap}，线程安全。version 递增标记拓扑变化。
 */
@Service
public class MeshTopologyService {

    private static final Logger log = LoggerFactory.getLogger(MeshTopologyService.class);

    /** 节点拓扑快照：sysid → snapshot。 */
    private final ConcurrentHashMap<Integer, MeshNodeSnapshot> nodeSnapshots = new ConcurrentHashMap<>();
    /** 上一轮快照（变化检测用）。 */
    private final ConcurrentHashMap<Integer, MeshNodeSnapshot> lastSnapshots = new ConcurrentHashMap<>();
    /** 拓扑版本号（每次变化递增）。 */
    private final AtomicLong version = new AtomicLong(0);

    /**
     * 接收邻居表上报（由 TelemetryIngestService 调用）。
     *
     * @param sysid 上报节点 sysid
     * @param msg   MeshNeighborTableMsg
     */
    public void onNeighborTable(int sysid, MeshNeighborTableMsg msg) {
        List<NeighborDto> neighbors = new ArrayList<>(msg.neighbors.size());
        for (MeshNeighborTableMsg.NeighborInfo info : msg.neighbors) {
            String quality = qualityName(info.linkQualityOrdinal());
            neighbors.add(new NeighborDto(info.sysid(), info.rssiDbm(), quality));
        }
        MeshNodeSnapshot snapshot = new MeshNodeSnapshot(sysid, System.currentTimeMillis(), neighbors);
        nodeSnapshots.put(sysid, snapshot);
        version.incrementAndGet();
        log.debug("mesh snapshot updated: sysid={} neighbors={}", sysid, neighbors.size());
    }

    /** 获取单节点快照；不存在返回 null。 */
    public MeshNodeSnapshot getSnapshot(int sysid) {
        return nodeSnapshots.get(sysid);
    }

    /** 获取所有节点快照（按 sysid 排序）。 */
    public Map<Integer, MeshNodeSnapshot> getAllSnapshots() {
        return new TreeMap<>(nodeSnapshots);
    }

    /** 当前拓扑版本号。 */
    public long currentVersion() {
        return version.get();
    }

    // =====================================================================
    // @EventListener：监听 MavlinkMessageEvent 自行处理 mesh 消息
    // =====================================================================

    @EventListener(condition = "#event.msgId == T(io.aerofleet.mavlink.messages.MeshNeighborTableMsg).ID")
    public void onNeighborTableEvent(MavlinkMessageEvent event) {
        onNeighborTable(event.getSysid(), (MeshNeighborTableMsg) event.getMessage());
    }

    /**
     * 检测拓扑变化（供 WebSocket 推送用）。
     * <p>
     * 比较当前快照与上一轮快照，返回变化事件列表：
     * NODE_JOINED / NODE_LEFT / LINK_UP / LINK_DOWN / ROUTE_CHANGED。
     * <p>
     * P1-fix(Major 10): 使用 offlineThreshold 判定节点离线。
     * 若 {@code now - lastUpdateMs > offlineThreshold} 则生成 NODE_LEFT 事件并从 nodeSnapshots 移除。
     *
     * @return 变化事件列表（可能为空）
     */
    public List<MeshTopologyEvent> detectChanges() {
        List<MeshTopologyEvent> events = new ArrayList<>();
        long now = System.currentTimeMillis();
        // 节点离线判定：6s 未上报视为离线
        long offlineThreshold = 6_000;

        // P1-fix(Major 10): 先检测超时离线节点，生成 NODE_LEFT 并从 nodeSnapshots 移除
        List<Integer> offlineSysids = new ArrayList<>();
        for (MeshNodeSnapshot snap : nodeSnapshots.values()) {
            if (now - snap.lastUpdateMs > offlineThreshold) {
                offlineSysids.add(snap.sysid);
            }
        }
        for (int sysid : offlineSysids) {
            nodeSnapshots.remove(sysid);
            events.add(new MeshTopologyEvent(EventType.NODE_LEFT, sysid, 0, now));
            log.info("mesh node offline (timeout {}ms): sysid={}", offlineThreshold, sysid);
        }

        // 检测 NODE_JOINED / LINK_UP / LINK_DOWN / ROUTE_CHANGED
        for (MeshNodeSnapshot current : nodeSnapshots.values()) {
            MeshNodeSnapshot previous = lastSnapshots.get(current.sysid);
            if (previous == null) {
                events.add(new MeshTopologyEvent(EventType.NODE_JOINED, current.sysid, 0, now));
                for (NeighborDto n : current.neighbors) {
                    events.add(new MeshTopologyEvent(EventType.LINK_UP, current.sysid, n.sysid(), now));
                }
            } else {
                // 比较邻居变化
                for (NeighborDto n : current.neighbors) {
                    if (previous.neighbors.stream().noneMatch(p -> p.sysid() == n.sysid())) {
                        events.add(new MeshTopologyEvent(EventType.LINK_UP, current.sysid, n.sysid(), now));
                    }
                }
                for (NeighborDto p : previous.neighbors) {
                    if (current.neighbors.stream().noneMatch(c -> c.sysid() == p.sysid())) {
                        events.add(new MeshTopologyEvent(EventType.LINK_DOWN, current.sysid, p.sysid(), now));
                    }
                }
            }
        }

        // 检测 NODE_LEFT（上一轮有但当前没有的节点，含刚被超时移除的）
        for (Integer sysid : lastSnapshots.keySet()) {
            if (!nodeSnapshots.containsKey(sysid)) {
                // 避免对已因超时生成过 NODE_LEFT 的节点重复添加
                boolean alreadyOffline = events.stream()
                        .anyMatch(e -> e.type() == EventType.NODE_LEFT && e.fromSysid() == sysid);
                if (!alreadyOffline) {
                    events.add(new MeshTopologyEvent(EventType.NODE_LEFT, sysid, 0, now));
                }
            }
        }

        // 更新 lastSnapshots 为当前快照
        lastSnapshots.clear();
        lastSnapshots.putAll(nodeSnapshots);

        return events;
    }

    /**
     * 获取所有链路（A-B 与 B-A 合并后的无向边）。
     */
    public List<MeshNodeSnapshot.LinkDto> getAllLinks() {
        List<MeshNodeSnapshot.LinkDto> links = new ArrayList<>();
        java.util.Set<String> seen = new java.util.HashSet<>();
        for (MeshNodeSnapshot snapshot : nodeSnapshots.values()) {
            for (NeighborDto n : snapshot.neighbors) {
                int a = Math.min(snapshot.sysid, n.sysid());
                int b = Math.max(snapshot.sysid, n.sysid());
                String key = a + "-" + b;
                if (seen.add(key)) {
                    links.add(new MeshNodeSnapshot.LinkDto(a, b, n.rssiDbm(), n.quality()));
                }
            }
        }
        return links;
    }

    /** 链路质量序数 → 名称。 */
    private static String qualityName(int ordinal) {
        return switch (ordinal) {
            case 0 -> "EXCELLENT";
            case 1 -> "GOOD";
            case 2 -> "FAIR";
            default -> "POOR";
        };
    }

    /** 拓扑事件类型枚举。 */
    public enum EventType {
        NODE_JOINED, NODE_LEFT, LINK_UP, LINK_DOWN, ROUTE_CHANGED
    }

    /** 拓扑事件。 */
    public record MeshTopologyEvent(EventType type, int fromSysid, int toSysid, long timestamp) {}
}