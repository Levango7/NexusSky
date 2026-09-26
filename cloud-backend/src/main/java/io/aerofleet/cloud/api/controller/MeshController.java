package io.aerofleet.cloud.api.controller;

import io.aerofleet.cloud.api.dto.MeshNodeSnapshot;
import io.aerofleet.cloud.api.dto.MeshNodeSnapshot.LinkDto;
import io.aerofleet.cloud.api.dto.MeshNodeSnapshot.NeighborDto;
import io.aerofleet.cloud.api.service.MeshTopologyService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Mesh 拓扑 REST 端点（M5 应急 mesh，FR-28）。
 * <p>
 * 独立路径前缀 /api/v1/mesh/*，既有端点不受影响（DFX 4.5）。
 * <p>
 * 端点清单（全部只读 GET）：
 * <pre>
 * GET /api/v1/mesh/topology           获取全网拓扑
 * GET /api/v1/mesh/topology/{sysid}   获取单节点拓扑
 * GET /api/v1/mesh/routes/{sysid}     获取单节点路由表（暂未实现，返回空）
 * GET /api/v1/mesh/neighbors/{sysid}  获取单节点邻居表
 * GET /api/v1/mesh/links              获取所有链路及质量分级
 * </pre>
 */
@RestController
@RequestMapping("/api/v1/mesh")
public class MeshController {

    private final MeshTopologyService topologyService;

    public MeshController(MeshTopologyService topologyService) {
        this.topologyService = topologyService;
    }

    /** FR-28 获取全网拓扑。 */
    @GetMapping("/topology")
    public ResponseEntity<Map<String, Object>> getTopology() {
        Map<Integer, MeshNodeSnapshot> snapshots = topologyService.getAllSnapshots();
        List<Map<String, Object>> nodes = new ArrayList<>();
        for (MeshNodeSnapshot s : snapshots.values()) {
            nodes.add(snapshotView(s));
        }
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("version", topologyService.currentVersion());
        result.put("nodeCount", nodes.size());
        result.put("nodes", nodes);
        return ResponseEntity.ok(result);
    }

    /** FR-28 获取单节点拓扑；不存在返回 404。 */
    @GetMapping("/topology/{sysid}")
    public ResponseEntity<Map<String, Object>> getNodeTopology(@PathVariable("sysid") int sysid) {
        MeshNodeSnapshot s = topologyService.getSnapshot(sysid);
        if (s == null) {
            return ResponseEntity.status(404).body(
                    Map.of("error", "sysid " + sysid + " not found in mesh topology"));
        }
        return ResponseEntity.ok(snapshotView(s));
    }

    /** FR-28 获取单节点路由表（暂未实现，返回空）。 */
    @GetMapping("/routes/{sysid}")
    public ResponseEntity<Map<String, Object>> getRoutes(@PathVariable("sysid") int sysid) {
        MeshNodeSnapshot s = topologyService.getSnapshot(sysid);
        if (s == null) {
            return ResponseEntity.status(404).body(
                    Map.of("error", "sysid " + sysid + " not found in mesh topology"));
        }
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("sysid", sysid);
        result.put("routes", List.of());
        return ResponseEntity.ok(result);
    }

    /** FR-28 获取单节点邻居表；不存在返回 404。 */
    @GetMapping("/neighbors/{sysid}")
    public ResponseEntity<Map<String, Object>> getNeighbors(@PathVariable("sysid") int sysid) {
        MeshNodeSnapshot s = topologyService.getSnapshot(sysid);
        if (s == null) {
            return ResponseEntity.status(404).body(
                    Map.of("error", "sysid " + sysid + " not found in mesh topology"));
        }
        List<Map<String, Object>> neighborList = new ArrayList<>();
        for (NeighborDto n : s.neighbors) {
            Map<String, Object> nv = new LinkedHashMap<>();
            nv.put("sysid", n.sysid());
            nv.put("rssiDbm", n.rssiDbm());
            nv.put("quality", n.quality());
            neighborList.add(nv);
        }
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("sysid", sysid);
        result.put("neighborCount", neighborList.size());
        result.put("neighbors", neighborList);
        return ResponseEntity.ok(result);
    }

    /** FR-28 获取所有链路及质量分级（A-B 与 B-A 合并）。 */
    @GetMapping("/links")
    public ResponseEntity<Map<String, Object>> getLinks() {
        List<LinkDto> links = topologyService.getAllLinks();
        List<Map<String, Object>> linkList = new ArrayList<>();
        for (LinkDto l : links) {
            Map<String, Object> lv = new LinkedHashMap<>();
            lv.put("from", l.from());
            lv.put("to", l.to());
            lv.put("rssiDbm", l.rssiDbm());
            lv.put("quality", l.quality());
            linkList.add(lv);
        }
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("linkCount", linkList.size());
        result.put("links", linkList);
        return ResponseEntity.ok(result);
    }

    /** 快照视图辅助。 */
    private Map<String, Object> snapshotView(MeshNodeSnapshot s) {
        Map<String, Object> v = new LinkedHashMap<>();
        v.put("sysid", s.sysid);
        v.put("lastUpdateMs", s.lastUpdateMs);
        List<Map<String, Object>> neighborList = new ArrayList<>();
        for (NeighborDto n : s.neighbors) {
            Map<String, Object> nv = new LinkedHashMap<>();
            nv.put("sysid", n.sysid());
            nv.put("rssiDbm", n.rssiDbm());
            nv.put("quality", n.quality());
            neighborList.add(nv);
        }
        v.put("neighborCount", neighborList.size());
        v.put("neighbors", neighborList);
        return v;
    }
}