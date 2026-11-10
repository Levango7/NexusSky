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

    /**
     * 从缺陷列表创建工单（≥1 条，缺陷须为 OPEN/CONFIRMED）。
     *
     * <h2>两道守卫（此前**一条都没有**，尽管 javadoc 写着"须为 OPEN/CONFIRMED"）</h2>
     *
     * <ol>
     *   <li><b>状态守卫</b>：只接受 {@link DefectService#DISPATCHABLE}。此前只拒
     *       DISMISSED，于是 RECTIFIED（已消缺）的缺陷也能被重新派单——一个已经被
     *       复检证明修好的点位再次派班组过去。声明与行为不一致，比没有声明更坏。</li>
     *   <li><b>重复派单守卫</b>：同一缺陷不得同时挂在两个未终态工单上。此前没有这道
     *       检查，两个班组会被派到同一个缺陷（"统筹调度"的反面教材），而且两份工单
     *       后来都会各自复检、各自回写，记录互相覆盖说不清。</li>
     * </ol>
     *
     * <p>取消（CANCELLED）会释放占用：派单取消了，缺陷必须还能再派，否则一次误取消
     * 就把这个点位永久锁死。回归见 {@code DefectClosureLoopTest}。
     */
    @Transactional
    public WorkOrderEntity create(String title, String note, List<Long> defectIds, String tenantId) {
        if (defectIds == null || defectIds.isEmpty()) {
            throw new IllegalArgumentException("work order requires at least one defect");
        }
        int i = 0;
        for (Long id : defectIds) {
            i++;
            DefectEntity d = defects.findById(id)
                    .orElseThrow(() -> new IllegalArgumentException("unknown defect " + id));
            // ① 状态守卫（含 DISMISSED / RECTIFIED）
            if (!DefectService.DISPATCHABLE.contains(d.status)) {
                throw new IllegalArgumentException(
                        "defect " + id + " is " + d.status + "; only " + DefectService.DISPATCHABLE
                                + " can be dispatched");
            }
            // ② 重复派单守卫：该缺陷是否已被另一张未终态工单占用
            for (WorkOrderDefectRefEntity ref : refs.findByDefectId(id)) {
                if (ref.workOrderId == null) {
                    continue;
                }
                WorkOrderEntity other = orders.findById(ref.workOrderId).orElse(null);
                if (other == null || other.id == null || machine.isTerminal(other.status)) {
                    continue;
                }
                throw new IllegalArgumentException(
                        "defect " + id + " is already on active work order #" + other.id
                                + " (status " + other.status + "); finish or cancel it first");
            }
        }
        log.debug("work order create: {} defect(s), first={}", i, defectIds.get(0));
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
     * <p>
     * <b>事务边界必须挂在本方法上。</b>复检是一条闭环（改状态 + 记 verifiedAt/verifiedDetail），
     * 但它内部的 {@code transition(id, t, null)} 是**同类自调用** —— Spring AOP 的代理在此被
     * 绕过，{@code transition} 自己那个 {@code @Transactional} 并不生效。若本方法也不带事务，
     * 这条闭环会被拆成两次各自提交的写，中途失败就留下「状态已改、无复检记录」的工单。
     * 回归证明见 {@code WorkOrderVerifyTransactionTest}。
     * <p>
     * 代价（如实记录，不掩饰）：本事务持有连接期间包含 {@link CaptureService#captureAndLocate}
     * 那段真机 I/O（{@code awaitNewShot} 最长 5s），因此单次复检最长可能占住一条数据库连接约
     * 5-7s。这与 {@code FormationService.command()} 的既有形状一致（同样在事务内做 MAVLink 下发），
     * 且复检是操作员触发的低频动作，故接受。若将来复检并发上升，应改为只把末尾两次写包进事务。
     *
     * @return 复检结果（命中缺陷 id 列表可为空）
     */
    @Transactional
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
            // 复检的拍照腿失败：如实上抛（控制器 502），不造假复检结果；
            // 消息只留自建短句，细节（相机/像素管道内部文本）走日志与原因链
            log.warn("recheck capture failed for work order on sysid={}", sysid, e);
            throw new IllegalStateException("verify capture failed", e);
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

        List<Long> attached = defectIdsOf(wo.id);
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

        // ---- 闭环的最后一环：缺陷本身要关闭（此前只改工单、不动缺陷）----
        //
        // 通过：挂着的缺陷全部置 RECTIFIED。少这一步的后果不是"少记一笔"，而是
        //   ① 报告的已消缺数永远为 0，对表"闭环消缺"时拿不出数；
        //   ② 消缺后这个点位再次被拍到，findNearby 会命中这条旧缺陷并刷新
        //      lastSeenAt —— 复发被静默吞掉，不产生任何新告警。
        // 失败：一条都不改。保持 OPEN/CONFIRMED 的意思是"这些点位还没消缺，
        //   可以（也必须）再派单"，而 REOPENED 工单本身也还能 START。
        List<Long> closedDefects = new ArrayList<>();
        List<Long> openDefects = new ArrayList<>();
        for (Long defectId : attached) {
            DefectEntity d = defects.findById(defectId).orElse(null);
            if (d == null) {
                continue;
            }
            if (pass) {
                d.status = DefectService.RECTIFIED;
                // lastSeenAt 在消缺口径下记"关闭时间"，不是"最后看到缺陷的时间"
                d.lastSeenAt = updated.verifiedAt;
                d.note = (d.note == null ? "" : d.note + " | ")
                        + "rectified by work order #" + id + " (verify pass, frame "
                        + shot.get("frameSeq") + ")";
                defects.save(d);
                closedDefects.add(defectId);
            } else {
                openDefects.add(defectId);
            }
        }

        log.info("work order {} verify: {} (hits={}, closed={})",
                id, updated.status, hitDefects, closedDefects);
        Map<String, Object> out = new java.util.LinkedHashMap<>();
        out.put("workOrderId", id);
        out.put("result", updated.status);
        out.put("hitDefects", hitDefects);
        out.put("frameSeq", shot.get("frameSeq"));
        out.put("closedDefects", closedDefects);
        out.put("openDefects", openDefects);
        return out;
    }
}
