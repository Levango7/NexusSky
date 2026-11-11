package io.aerofleet.cloud.route;

import io.aerofleet.cloud.defect.DefectEntity;
import io.aerofleet.cloud.defect.DefectRepository;
import io.aerofleet.cloud.defect.DefectService;
import io.aerofleet.cloud.defect.WorkOrderDefectRefEntity;
import io.aerofleet.cloud.defect.WorkOrderDefectRefRepository;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 缺陷复检航线生成（P1-8 收口："电力杆塔（按杆塔号 + 缺陷复检）"）。
 *
 * <p><b>数据来源（如实说明）</b>：杆塔坐标不另建档——取自对应缺陷自己的立案坐标
 * （AI 拍照晋升或人工立案时的定位点）。同一杆塔号多个缺陷取**最新立案**的那条做
 * 航线锚点；若某杆塔只有已消缺/被驳回的缺陷，它不会出现在复检航线里。
 * 这是刻意的：复检航线只飞"还欠着一笔未消缺"的杆塔。
 *
 * <p><b>不驱动飞控</b>：与工单复检（WorkOrderService.verify）的移动边界一致——
 * 这里只生成航线，下发走既有 mission 链路（前端 RouteTemplatePanel 同一条路）。
 */
@Service
public class DefectRecheckRouteService {

    private final DefectRepository defects;
    private final WorkOrderDefectRefRepository refs;

    public DefectRecheckRouteService(DefectRepository defects, WorkOrderDefectRefRepository refs) {
        this.defects = defects;
        this.refs = refs;
    }

    /**
     * 为未消缺缺陷生成复检航线。
     *
     * @param towerNos     杆塔号集合（可空）；非空则只生成这些塔的
     * @param workOrderId  工单（可空）；非空则生成该工单里挂着的未消缺缺陷的塔
     * @param altM         巡航高度（米），null ⇒ 60
     * @param orbitRadiusM 环绕半径（米），null ⇒ 25
     * @param orbitPoints  环绕点位数，null ⇒ 4
     * @param hoverSec     每塔拍照驻留秒数，null ⇒ 5
     */
    public Map<String, Object> generate(List<String> towerNos, Long workOrderId,
                                        Double altM, Double orbitRadiusM,
                                        Integer orbitPoints, Double hoverSec) {
        if ((towerNos == null || towerNos.isEmpty()) && workOrderId == null) {
            throw new IllegalArgumentException("towerNos or workOrderId required");
        }

        List<String> normalized = towerNos == null ? List.of()
                : towerNos.stream()
                        .map(s -> s == null ? null : s.trim())
                        .filter(s -> s != null && !s.isEmpty())
                        .distinct()
                        .toList();

        List<DefectEntity> candidates;
        if (workOrderId != null) {
            List<WorkOrderDefectRefEntity> attached = refs.findByWorkOrderId(workOrderId);
            List<Long> ids = attached.stream()
                    .map(ref -> ref.defectId)
                    .toList();
            Map<Long, DefectEntity> byId = new LinkedHashMap<>();
            if (!ids.isEmpty()) {
                defects.findAllById(ids).forEach(d -> byId.put(d.id, d));
            }
            candidates = new ArrayList<>();
            for (WorkOrderDefectRefEntity ref : attached) {
                DefectEntity d = byId.get(ref.defectId);
                if (d != null
                        && DefectService.DISPATCHABLE.contains(d.status)
                        && (normalized.isEmpty()
                        || (d.towerNo != null && normalized.contains(d.towerNo)))) {
                    candidates.add(d);
                }
            }
        } else {
            candidates = defects.findByTowerNoInAndStatusInOrderByCreatedAtDesc(
                    normalized, DefectService.DISPATCHABLE);
        }

        // 按杆塔号分组（无杆塔号的缺陷跳过——复检航线只飞"能说清是哪个塔"的）
        Map<String, List<DefectEntity>> byTower = new LinkedHashMap<>();
        List<Long> unlinked = new ArrayList<>();
        for (DefectEntity d : candidates) {
            if (d.towerNo == null || d.towerNo.isBlank()) {
                unlinked.add(d.id);
                continue;
            }
            byTower.computeIfAbsent(d.towerNo.trim(), k -> new ArrayList<>()).add(d);
        }

        final double alt = altM == null ? 60 : altM;
        final double radius = orbitRadiusM == null ? 25 : orbitRadiusM;
        final int points = orbitPoints == null ? 4 : orbitPoints;
        final double hover = hoverSec == null ? 5 : hoverSec;

        // 每塔取最新立案缺陷的坐标做锚点（同塔必同杆塔号，lat/lon 差只是立案拍照位置差）
        List<RouteGenerator.Tower> towers = new ArrayList<>();
        List<Map<String, Object>> towerBriefs = new ArrayList<>();
        for (Map.Entry<String, List<DefectEntity>> e : byTower.entrySet()) {
            List<DefectEntity> list = e.getValue();
            DefectEntity latest = list.get(0);   // repository 已按 createdAt DESC
            towers.add(new RouteGenerator.Tower(e.getKey(), latest.lat, latest.lon));
            Map<String, Object> brief = new LinkedHashMap<>();
            brief.put("towerNo", e.getKey());
            brief.put("defectIds", list.stream().map(d -> d.id).toList());
            brief.put("defectCount", list.size());
            brief.put("anchorDefectId", latest.id);
            brief.put("anchorCreatedAt", latest.createdAt);
            towerBriefs.add(brief);
        }

        if (towers.isEmpty()) {
            throw new IllegalArgumentException(
                    "no unrectified defects with a tower number matched "
                            + (workOrderId != null ? "work order #" + workOrderId
                                    : "towerNos " + normalized)
                            + "; nothing to fly back to");
        }

        RouteGenerator.Result result = RouteGenerator.tower(towers, alt, radius, points, hover);
        Map<String, Object> out = RouteTemplateController.view(result);
        out.put("type", "defect-recheck");
        out.put("towerCount", towers.size());
        out.put("towers", towerBriefs);
        out.put("sourceDefectIds", candidates.stream().map(d -> d.id).toList());
        out.put("unlinkedDefectIds", unlinked);
        if (workOrderId != null) {
            out.put("workOrderId", workOrderId);
        }
        return out;
    }
}
