package io.aerofleet.cloud.defect;

import io.aerofleet.cloud.vision.CaptureService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 工单服务（F4，spec §1）：创建、状态机流转、复检闭环。
 * <p>
 * 复检 = 调 F1 既有 {@link CaptureService#captureAndLocate(int, String)} 真拍一张，
 * 对工单关联缺陷逐条比对（≤10m 同 kind）。**不驱动飞控**——派飞到缺陷点是运维
 * 经既有 mission 链路的职责，工单只做"拍 + 判"（spec §4 边界）。
 */
@Service
public class WorkOrderService {

    private static final Logger log = LoggerFactory.getLogger(WorkOrderService.class);

    private final WorkOrderRepository orders;
    private final WorkOrderDefectRefRepository refs;
    private final DefectRepository defects;
    private final WorkOrderStateMachine machine;
    private final CaptureService capture;

    public WorkOrderService(WorkOrderRepository orders, WorkOrderDefectRefRepository refs,
                            DefectRepository defects, WorkOrderStateMachine machine,
                            CaptureService capture) {
        this.orders = orders;
        this.refs = refs;
        this.defects = defects;
        this.machine = machine;
        this.capture = capture;
    }

    // ------------------------------------------------------------------
    // 创建与查询
    // ------------------------------------------------------------------

    /** 从缺陷列表创建工单（≥1 条，缺陷须为 OPEN/CONFIRMED）。 */
    @Transactional
    public WorkOrderEntity create(String title, String note, List<Long> defectIds, String tenantId) {
        if (defectIds == null || defectIds.isEmpty()) {
            throw new IllegalArgumentException("work order requires at least one defect");
        }
        for (Long id : defectIds) {
            DefectEntity d = defects.findById(id)
                    .orElseThrow(() -> new IllegalArgumentException("unknown defect " + id));
            if ("DISMISSED".equals(d.status)) {
                throw new IllegalArgumentException("defect " + id + " is DISMISSED");
            }
        }
        WorkOrderEntity wo = new WorkOrderEntity();
        wo.title = title == null || title.isBlank() ? "缺陷处置 #" + System.currentTimeMillis() : title;
        wo.note = note;
        wo.tenantId = tenantId;
        wo = orders.save(wo);
        for (Long id : defectIds) {
            WorkOrderDefectRefEntity r = new WorkOrderDefectRefEntity();
            r.workOrderId = wo.id;
            r.defectId = id;
            refs.save(r);
        }
        return wo;
    }

    public List<WorkOrderEntity> list() {
        return orders.findAllByOrderByCreatedAtDesc();
    }

    public WorkOrderEntity get(Long id) {
        return orders.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("unknown work order " + id));
    }

    public List<Long> defectIdsOf(Long workOrderId) {
        return refs.findByWorkOrderId(workOrderId).stream().map(r -> r.defectId).toList();
    }

    // ------------------------------------------------------------------
    // 状态机流转
    // ------------------------------------------------------------------

    @Transactional
    public WorkOrderEntity transition(Long id, WorkOrderStateMachine.Transition t, String note) {
        WorkOrderEntity wo = get(id);
        if (machine.isTerminal(wo.status)) {
            throw new IllegalWorkOrderTransitionException(wo.status, t);
        }
        String target = machine.apply(wo.status, t);
        wo.status = target;
        wo.updatedAt = System.currentTimeMillis();
        if (t == WorkOrderStateMachine.Transition.CANCEL) {
            wo.cancelReason = note == null ? "cancelled" : note;
        } else if (note != null && !note.isBlank()) {
            wo.note = (wo.note == null ? "" : wo.note + " | ") + t + ": " + note;
        }
        return orders.save(wo);
    }

    // ------------------------------------------------------------------
    // 复检闭环（spec §1"智能诊断"）
    // ------------------------------------------------------------------

    /**
     * 复检：真拍一张 + 逐缺陷比对。
     *
     * @return 复检结果（命中缺陷 id 列表可为空）
     */
    public Map<String, Object> verify(Long id, int sysid) {
        WorkOrderEntity wo = get(id);
        if (machine.isTerminal(wo.status)) {
            throw new IllegalWorkOrderTransitionException(wo.status,
                    WorkOrderStateMachine.Transition.VERIFY_PASS);
        }
        if (!"RESOLVED".equals(wo.status)) {
            // 复检语义只发生在 RESOLVED（处置完成后的验证）；其余态直接按非法转移拒绝
            throw new IllegalWorkOrderTransitionException(wo.status,
                    WorkOrderStateMachine.Transition.VERIFY_PASS);
        }

        Map<String, Object> shot;
        try {
            shot = capture.captureAndLocate(sysid, null);
        } catch (Exception e) {
            // 复检的拍照腿失败：如实上抛（控制器 502），不造假复检结果
            throw new IllegalStateException("verify capture failed: " + e.getMessage(), e);
        }
        if ("error".equals(shot.get("status"))) {
            // F1 语义：飞控/链路失败时 capture 内部吞异常返回 status=error
            throw new IllegalStateException("verify capture failed: " + shot.get("result"));
        }
        Object raw = shot.get("detections");
        List<Map<String, Object>> detections = raw instanceof List<?> l
                ? (List<Map<String, Object>>) l : List.of();

        List<Long> hitDefects = new ArrayList<>();
        for (Long defectId : defectIdsOf(wo.id)) {
            DefectEntity d = defects.findById(defectId).orElse(null);
            if (d == null) {
                continue;
            }
            boolean hit = detections.stream().anyMatch(det -> {
                if (!d.kind.equals(det.get("kind"))) {
                    return false;
                }
                Object latO = det.get("lat");
                Object lonO = det.get("lon");
                if (!(latO instanceof Number latN) || !(lonO instanceof Number lonN)) {
                    return false;
                }
                return DefectService.haversineM(latN.doubleValue(), lonN.doubleValue(),
                        d.lat, d.lon) <= DefectService.DEDUP_RADIUS_M;
            });
            if (hit) {
                hitDefects.add(defectId);
            }
        }

        boolean pass = hitDefects.isEmpty();
        WorkOrderStateMachine.Transition t = pass
                ? WorkOrderStateMachine.Transition.VERIFY_PASS
                : WorkOrderStateMachine.Transition.VERIFY_FAIL;
        WorkOrderEntity updated = transition(id, t, null);
        updated.verifiedAt = System.currentTimeMillis();
        updated.verifiedDetail = pass
                ? "verify pass at " + updated.verifiedAt + " (frame " + shot.get("frameSeq") + ")"
                : "reopened, hits=" + hitDefects + " (frame " + shot.get("frameSeq") + ")";
        orders.save(updated);

        log.info("work order {} verify: {} (hits={})", id, updated.status, hitDefects);
        return Map.of("workOrderId", id, "result", updated.status,
                "hitDefects", hitDefects, "frameSeq", shot.get("frameSeq"));
    }
}
