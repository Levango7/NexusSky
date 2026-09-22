package io.aerofleet.cloud.api.service;

import io.aerofleet.cloud.api.dto.CellTowerSnapshot;
import io.aerofleet.cloud.api.dto.CellTowerSnapshot.TerminalInfo;
import io.aerofleet.cloud.gateway.MavlinkMessageEvent;
import io.aerofleet.mavlink.messages.CellHandoverMsg;
import io.aerofleet.mavlink.messages.CellTowerStatusMsg;
import io.aerofleet.mavlink.messages.GroundTerminalRegisterMsg;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 基站拓扑快照存储服务（M6 移动基站载荷抽象，FR-NFER-OBS-02）。
 * <p>
 * 接收各无人机基站上报的 {@link CellTowerStatusMsg}、终端注册 {@link GroundTerminalRegisterMsg}
 * 和漫游切换 {@link CellHandoverMsg}，维护全局基站拓扑快照，
 * 提供只读查询与变化检测（供 REST + WebSocket 推送用）。
 * <p>
 * 内部 {@link ConcurrentHashMap}，线程安全。version 递增标记拓扑变化。
 */
@Service
public class CellTowerTopologyService {

    private static final Logger log = LoggerFactory.getLogger(CellTowerTopologyService.class);

    /** 基站拓扑快照：sysid → snapshot。 */
    private final ConcurrentHashMap<Integer, CellTowerSnapshot> towerSnapshots = new ConcurrentHashMap<>();
    /** 终端注册表：terminalId → TerminalInfo。 */
    private final ConcurrentHashMap<Integer, TerminalInfo> terminalRegistry = new ConcurrentHashMap<>();
    /** 漫游切换历史（最近 256 条）。 */
    private final List<HandoverEvent> handoverHistory = Collections.synchronizedList(new ArrayList<>());
    private static final int MAX_HANDOVER_HISTORY = 256;
    /** 上一轮快照（变化检测用）。 */
    private final ConcurrentHashMap<Integer, CellTowerSnapshot> lastSnapshots = new ConcurrentHashMap<>();
    /** 拓扑版本号（每次变化递增）。 */
    private final AtomicLong version = new AtomicLong(0);

    /**
     * 接收基站状态上报（由 TelemetryIngestService 调用）。
     *
     * @param sysid 上报无人机 sysid
     * @param msg   CellTowerStatusMsg
     */
    public void onCellTowerStatus(int sysid, CellTowerStatusMsg msg) {
        List<TerminalInfo> terminals = getTerminalsForSysid(sysid);
        CellTowerSnapshot snapshot = new CellTowerSnapshot(
                msg.sysid, msg.cellType, msg.centerLat, msg.centerLon,
                msg.coverageRadiusM, msg.connectedTerminals, msg.capacityUtilization,
                System.currentTimeMillis(), terminals);
        towerSnapshots.put(sysid, snapshot);
        version.incrementAndGet();
        log.debug("celltower snapshot updated: sysid={} cellType={} connected={} util={}%",
                sysid, msg.cellType, msg.connectedTerminals, msg.capacityUtilization);
    }

    /**
     * 接收终端注册请求（由 TelemetryIngestService 调用）。
     *
     * @param sysid 接收无人机 sysid
     * @param msg   GroundTerminalRegisterMsg
     */
    public void onTerminalRegister(int sysid, GroundTerminalRegisterMsg msg) {
        TerminalInfo info = new TerminalInfo(msg.terminalId, msg.terminalType, msg.requestedSysid);
        terminalRegistry.put(msg.terminalId, info);
        version.incrementAndGet();
        log.info("terminal registered: terminalId={} type={} reqSysid={} via sysid={}",
                msg.terminalId, msg.terminalType, msg.requestedSysid, sysid);
    }

    /**
     * 接收漫游切换事件（由 TelemetryIngestService 调用）。
     *
     * @param sysid 接收无人机 sysid
     * @param msg   CellHandoverMsg
     */
    public void onHandover(int sysid, CellHandoverMsg msg) {
        HandoverEvent event = new HandoverEvent(
                msg.terminalId, msg.fromSysid, msg.toSysid, msg.handoverReason,
                System.currentTimeMillis());
        synchronized (handoverHistory) {
            handoverHistory.add(event);
            while (handoverHistory.size() > MAX_HANDOVER_HISTORY) {
                handoverHistory.remove(0);
            }
        }
        // 更新终端注册表中的 connectedSysid
        TerminalInfo old = terminalRegistry.get(msg.terminalId);
        if (old != null) {
            terminalRegistry.put(msg.terminalId,
                    new TerminalInfo(msg.terminalId, old.terminalType(), msg.toSysid));
        }
        version.incrementAndGet();
        log.info("handover: terminal={} from={} to={} reason={} via sysid={}",
                msg.terminalId, msg.fromSysid, msg.toSysid, msg.handoverReason, sysid);
    }

    /** 获取单基站快照；不存在返回 null。 */
    public CellTowerSnapshot getSnapshot(int sysid) {
        return towerSnapshots.get(sysid);
    }

    /** 获取所有基站快照（按 sysid 排序）。 */
    public Map<Integer, CellTowerSnapshot> getAllSnapshots() {
        return new TreeMap<>(towerSnapshots);
    }

    /** 获取指定基站接入的终端列表。 */
    public List<TerminalInfo> getTerminalsForSysid(int sysid) {
        List<TerminalInfo> result = new ArrayList<>();
        for (TerminalInfo t : terminalRegistry.values()) {
            if (t.connectedSysid() == sysid) {
                result.add(t);
            }
        }
        return result;
    }

    /** 获取所有已注册终端。 */
    public Map<Integer, TerminalInfo> getAllTerminals() {
        return new TreeMap<>(terminalRegistry);
    }

    /** 获取漫游切换历史。 */
    public List<HandoverEvent> getHandoverHistory() {
        synchronized (handoverHistory) {
            return new ArrayList<>(handoverHistory);
        }
    }

    /** 当前拓扑版本号。 */
    public long currentVersion() {
        return version.get();
    }

    // =====================================================================
    // @EventListener：监听 MavlinkMessageEvent 自行处理业务消息
    // =====================================================================

    @EventListener(condition = "#event.msgId == T(io.aerofleet.mavlink.messages.CellTowerStatusMsg).ID")
    public void onCellTowerStatusEvent(MavlinkMessageEvent event) {
        onCellTowerStatus(event.getSysid(), (CellTowerStatusMsg) event.getMessage());
    }

    @EventListener(condition = "#event.msgId == T(io.aerofleet.mavlink.messages.GroundTerminalRegisterMsg).ID")
    public void onTerminalRegisterEvent(MavlinkMessageEvent event) {
        onTerminalRegister(event.getSysid(), (GroundTerminalRegisterMsg) event.getMessage());
    }

    @EventListener(condition = "#event.msgId == T(io.aerofleet.mavlink.messages.CellHandoverMsg).ID")
    public void onHandoverEvent(MavlinkMessageEvent event) {
        onHandover(event.getSysid(), (CellHandoverMsg) event.getMessage());
    }

    /**
     * 检测拓扑变化（供 WebSocket 推送用）。
     * <p>
     * 比较当前快照与上一轮快照，返回变化事件列表：
     * TOWER_ONLINE / TOWER_OFFLINE / COVERAGE_CHANGED / TERMINAL_JOINED / TERMINAL_LEFT / HANDOVER。
     * <p>
     * 若 {@code now - lastUpdateMs > offlineThreshold} 则生成 TOWER_OFFLINE 事件并从 towerSnapshots 移除。
     *
     * @return 变化事件列表（可能为空）
     */
    public List<CellTowerEvent> detectChanges() {
        List<CellTowerEvent> events = new ArrayList<>();
        long now = System.currentTimeMillis();
        long offlineThreshold = 10_000;  // 10s 未上报视为离线

        // 检测超时离线基站
        List<Integer> offlineSysids = new ArrayList<>();
        for (CellTowerSnapshot snap : towerSnapshots.values()) {
            if (now - snap.lastUpdateMs > offlineThreshold) {
                offlineSysids.add(snap.sysid);
            }
        }
        for (int sysid : offlineSysids) {
            towerSnapshots.remove(sysid);
            events.add(new CellTowerEvent(EventType.TOWER_OFFLINE, sysid, 0, now));
            log.info("celltower offline (timeout {}ms): sysid={}", offlineThreshold, sysid);
        }

        // 检测 TOWER_ONLINE / COVERAGE_CHANGED
        for (CellTowerSnapshot current : towerSnapshots.values()) {
            CellTowerSnapshot previous = lastSnapshots.get(current.sysid);
            if (previous == null) {
                events.add(new CellTowerEvent(EventType.TOWER_ONLINE, current.sysid, 0, now));
            } else {
                if (previous.coverageRadiusM != current.coverageRadiusM
                        || previous.centerLatE7 != current.centerLatE7
                        || previous.centerLonE7 != current.centerLonE7) {
                    events.add(new CellTowerEvent(EventType.COVERAGE_CHANGED, current.sysid, 0, now));
                }
                if (previous.connectedTerminals < current.connectedTerminals) {
                    events.add(new CellTowerEvent(EventType.TERMINAL_JOINED, current.sysid, 0, now));
                }
                if (previous.connectedTerminals > current.connectedTerminals) {
                    events.add(new CellTowerEvent(EventType.TERMINAL_LEFT, current.sysid, 0, now));
                }
            }
        }

        // 检测 TOWER_OFFLINE（上一轮有但当前没有的）
        for (Integer sysid : lastSnapshots.keySet()) {
            if (!towerSnapshots.containsKey(sysid)) {
                boolean alreadyOffline = events.stream()
                        .anyMatch(e -> e.type() == EventType.TOWER_OFFLINE && e.fromSysid() == sysid);
                if (!alreadyOffline) {
                    events.add(new CellTowerEvent(EventType.TOWER_OFFLINE, sysid, 0, now));
                }
            }
        }

        // 更新 lastSnapshots 为当前快照
        lastSnapshots.clear();
        lastSnapshots.putAll(towerSnapshots);

        return events;
    }

    /** 获取当前在线基站数。 */
    public int onlineTowerCount() {
        return towerSnapshots.size();
    }

    /** 获取当前已注册终端数。 */
    public int registeredTerminalCount() {
        return terminalRegistry.size();
    }

    // =====================================================================
    // 内部类型
    // =====================================================================

    /** 拓扑事件类型枚举。 */
    public enum EventType {
        TOWER_ONLINE, TOWER_OFFLINE, COVERAGE_CHANGED,
        TERMINAL_JOINED, TERMINAL_LEFT, HANDOVER
    }

    /** 拓扑事件。 */
    public record CellTowerEvent(EventType type, int fromSysid, int toSysid, long timestamp) {}

    /** 漫游切换历史记录。 */
    public record HandoverEvent(int terminalId, int fromSysid, int toSysid, int reason, long timestamp) {}
}