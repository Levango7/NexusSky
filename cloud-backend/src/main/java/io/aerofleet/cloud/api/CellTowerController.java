package io.aerofleet.cloud.api;

import io.aerofleet.cloud.api.dto.CellTowerSnapshot;
import io.aerofleet.cloud.api.dto.CellTowerSnapshot.TerminalInfo;
import io.aerofleet.cloud.gateway.UdpGateway;
import io.aerofleet.cloud.security.RequireRole;
import io.aerofleet.cloud.security.Role;
import io.aerofleet.mavlink.messages.CellHandoverMsg;
import io.aerofleet.mavlink.messages.CellTowerConfigMsg;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 基站拓扑 REST 端点（M6 移动基站载荷抽象，FR-NFER-OBS-02 / FR-CT-05 / FR-HO-03）。
 * <p>
 * 独立路径前缀 /api/v1/celltowers/*，既有端点不受影响（DFX 4.5）。
 * <p>
 * 端点清单：
 * <pre>
 * GET  /api/v1/celltowers                获取全网基站拓扑
 * GET  /api/v1/celltowers/{sysid}        获取单基站状态
 * PUT  /api/v1/celltowers/{sysid}/config 下发基站配置（FR-CT-05）
 * GET  /api/v1/celltowers/{sysid}/terminals 获取基站接入终端列表
 * POST /api/v1/celltowers/{sysid}/handover 触发漫游切换（FR-HO-03）
 * GET  /api/v1/celltowers/handovers      获取漫游切换历史
 * </pre>
 */
@RestController
@RequestMapping("/api/v1/celltowers")
public class CellTowerController {

    private static final Logger log = LoggerFactory.getLogger(CellTowerController.class);

    private final CellTowerTopologyService topologyService;
    private final UdpGateway gateway;
    private final AtomicInteger sequence = new AtomicInteger();

    public CellTowerController(CellTowerTopologyService topologyService, UdpGateway gateway) {
        this.topologyService = topologyService;
        this.gateway = gateway;
    }

    /** 获取全网基站拓扑。 */
    @GetMapping
    public ResponseEntity<Map<String, Object>> getAllTowers() {
        Map<Integer, CellTowerSnapshot> snapshots = topologyService.getAllSnapshots();
        List<Map<String, Object>> towers = new ArrayList<>();
        for (CellTowerSnapshot s : snapshots.values()) {
            towers.add(snapshotView(s));
        }
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("version", topologyService.currentVersion());
        result.put("towerCount", towers.size());
        result.put("terminalCount", topologyService.registeredTerminalCount());
        result.put("towers", towers);
        return ResponseEntity.ok(result);
    }

    /** 获取单基站状态；不存在返回 404。 */
    @GetMapping("/{sysid}")
    public ResponseEntity<Map<String, Object>> getTower(@PathVariable("sysid") int sysid) {
        CellTowerSnapshot s = topologyService.getSnapshot(sysid);
        if (s == null) {
            return ResponseEntity.status(404).body(
                    Map.of("error", "sysid " + sysid + " not found in cell tower topology"));
        }
        return ResponseEntity.ok(snapshotView(s));
    }

    /** 下发基站配置（FR-CT-05）。 */
    @PutMapping("/{sysid}/config")
    @RequireRole(Role.ADMIN)
    public ResponseEntity<Map<String, Object>> configureTower(
            @PathVariable("sysid") int sysid,
            @RequestBody ConfigRequest body) {
        if (body.cellType < 0 || body.cellType > 2) {
            return ResponseEntity.badRequest().body(
                    Map.of("error", "cellType must be 0 (LTE), 1 (WIFI), or 2 (LORA)"));
        }
        // FR: 数值参数范围校验
        if (body.txPowerDbm < -10 || body.txPowerDbm > 30) {
            return ResponseEntity.badRequest().body(
                    Map.of("error", "txPowerDbm must be in [-10, 30] dBm"));
        }
        if (body.maxTerminals < 1 || body.maxTerminals > 1000) {
            return ResponseEntity.badRequest().body(
                    Map.of("error", "maxTerminals must be in [1, 1000]"));
        }
        if (body.frequencyChannel < 0 || body.frequencyChannel > 1000) {
            return ResponseEntity.badRequest().body(
                    Map.of("error", "frequencyChannel must be in [0, 1000]"));
        }
        CellTowerConfigMsg msg = new CellTowerConfigMsg(
                sysid, body.cellType, body.txPowerDbm,
                body.maxTerminals, body.frequencyChannel);
        try {
            gateway.send(sysid, msg.toFrame(UdpGateway.GCS_SYSID, UdpGateway.GCS_COMPID, nextSeq()));
            log.info("celltower config sent: sysid={} cellType={} txPower={} maxTerminals={} freq={}",
                    sysid, body.cellType, body.txPowerDbm, body.maxTerminals, body.frequencyChannel);
        } catch (Exception e) {
            log.warn("failed to send celltower config to sysid={}: {}", sysid, e.getMessage());
            return ResponseEntity.status(502).body(
                    Map.of("error", "failed to send config: " + e.getMessage()));
        }
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("sysid", sysid);
        result.put("cellType", body.cellType);
        result.put("txPowerDbm", body.txPowerDbm);
        result.put("maxTerminals", body.maxTerminals);
        result.put("frequencyChannel", body.frequencyChannel);
        result.put("status", "sent");
        return ResponseEntity.ok(result);
    }

    /** 获取基站接入终端列表。 */
    @GetMapping("/{sysid}/terminals")
    public ResponseEntity<Map<String, Object>> getTerminals(@PathVariable("sysid") int sysid) {
        List<TerminalInfo> terminals = topologyService.getTerminalsForSysid(sysid);
        List<Map<String, Object>> terminalList = new ArrayList<>();
        for (TerminalInfo t : terminals) {
            Map<String, Object> tv = new LinkedHashMap<>();
            tv.put("terminalId", t.terminalId());
            tv.put("terminalType", t.terminalType());
            tv.put("connectedSysid", t.connectedSysid());
            terminalList.add(tv);
        }
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("sysid", sysid);
        result.put("terminalCount", terminalList.size());
        result.put("terminals", terminalList);
        return ResponseEntity.ok(result);
    }

    /** 触发漫游切换（FR-HO-03）。 */
    @PostMapping("/{sysid}/handover")
    @RequireRole(Role.OPERATOR)
    public ResponseEntity<Map<String, Object>> triggerHandover(
            @PathVariable("sysid") int sysid,
            @RequestBody HandoverRequest body) {
        if (body.toSysid <= 0) {
            return ResponseEntity.badRequest().body(
                    Map.of("error", "toSysid must be a valid drone sysid"));
        }
        CellHandoverMsg msg = new CellHandoverMsg(
                body.terminalId, sysid, body.toSysid, body.reason);
        try {
            gateway.send(sysid, msg.toFrame(UdpGateway.GCS_SYSID, UdpGateway.GCS_COMPID, nextSeq()));
            log.info("handover triggered: terminal={} from={} to={} reason={}",
                    body.terminalId, sysid, body.toSysid, body.reason);
        } catch (Exception e) {
            log.warn("failed to send handover to sysid={}: {}", sysid, e.getMessage());
            return ResponseEntity.status(502).body(
                    Map.of("error", "failed to send handover: " + e.getMessage()));
        }
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("terminalId", body.terminalId);
        result.put("fromSysid", sysid);
        result.put("toSysid", body.toSysid);
        result.put("reason", body.reason);
        result.put("status", "sent");
        return ResponseEntity.ok(result);
    }

    /** 获取漫游切换历史。 */
    @GetMapping("/handovers")
    public ResponseEntity<Map<String, Object>> getHandoverHistory() {
        List<CellTowerTopologyService.HandoverEvent> history = topologyService.getHandoverHistory();
        List<Map<String, Object>> eventList = new ArrayList<>();
        for (CellTowerTopologyService.HandoverEvent e : history) {
            Map<String, Object> ev = new LinkedHashMap<>();
            ev.put("terminalId", e.terminalId());
            ev.put("fromSysid", e.fromSysid());
            ev.put("toSysid", e.toSysid());
            ev.put("reason", e.reason());
            ev.put("timestamp", e.timestamp());
            eventList.add(ev);
        }
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("count", eventList.size());
        result.put("handovers", eventList);
        return ResponseEntity.ok(result);
    }

    // =====================================================================
    // 请求体 DTO
    // =====================================================================

    /** 基站配置请求体。 */
    public static final class ConfigRequest {
        public int cellType;          // 0=LTE, 1=WIFI, 2=LORA
        public int txPowerDbm;        // -10..30
        public int maxTerminals;      // 最大并发终端数
        public int frequencyChannel;  // 频段编号
    }

    /** 漫游切换请求体。 */
    public static final class HandoverRequest {
        public int terminalId;  // 被切换终端 ID
        public int toSysid;     // 目标无人机 sysid
        public int reason;      // 0=SIGNAL_WEAK, 1=LOAD_BALANCE, 2=CELL_SHUTDOWN
    }

    // =====================================================================
    // 响应视图
    // =====================================================================

    private Map<String, Object> snapshotView(CellTowerSnapshot s) {
        Map<String, Object> v = new LinkedHashMap<>();
        v.put("sysid", s.sysid);
        v.put("cellType", s.cellType);
        v.put("cellTypeName", cellTypeName(s.cellType));
        v.put("centerLat", s.centerLatE7 / 1_000_000.0);
        v.put("centerLon", s.centerLonE7 / 1_000_000.0);
        v.put("coverageRadiusM", s.coverageRadiusM);
        v.put("connectedTerminals", s.connectedTerminals);
        v.put("capacityUtilization", s.capacityUtilization);
        v.put("lastUpdateMs", s.lastUpdateMs);
        List<Map<String, Object>> terminalList = new ArrayList<>();
        for (TerminalInfo t : s.terminals) {
            Map<String, Object> tv = new LinkedHashMap<>();
            tv.put("terminalId", t.terminalId());
            tv.put("terminalType", t.terminalType());
            tv.put("connectedSysid", t.connectedSysid());
            terminalList.add(tv);
        }
        v.put("terminals", terminalList);
        return v;
    }

    private static String cellTypeName(int cellType) {
        return switch (cellType) {
            case 0 -> "LTE_MICRO_CELL";
            case 1 -> "WIFI_MESH";
            case 2 -> "LORA";
            default -> "UNKNOWN";
        };
    }

    private int nextSeq() {
        return sequence.getAndIncrement() & 0xFF;
    }
}