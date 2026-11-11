package io.aerofleet.cloud.defect;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * 缺陷服务（F4，spec §1/§2）：立案、去重（10m 同 kind）、自动晋升挂钩。
 * <p>
 * 自动晋升是**纯旁路**（与 F1 的 CvEvalService.record 同纪律）：拍照主路径不因缺陷
 * 立案失败而失败；{@link #onCapture} 全程吞异常只记 WARN。
 */
@Service
public class DefectService {

    private static final Logger log = LoggerFactory.getLogger(DefectService.class);

    /** 去重半径（米）：同 kind 且 ≤ 此距离视为同一缺陷的复现。 */
    static final double DEDUP_RADIUS_M = 10.0;

    /**
     * 已消缺：缺陷经工单复检通过（或人工裁决）关闭。
     *
     * <p><b>为什么必须有这个状态</b>：在此之前缺陷只有 OPEN/CONFIRMED/DISMISSED，
     * 而 {@code WorkOrderService.verify()} 通过后**只改工单状态、不动缺陷**。后果不是
     * "少记一笔"，而是两件事：
     * <ol>
     *   <li>报告里 {@code defectsOpen} 永远大于 0，"闭环消缺"少最后一环，招投标对表时
     *       拿不出已消缺数量；</li>
     *   <li><b>消缺后复发的缺陷会被静默并回旧单</b>：{@link #findNearby} 只看
     *       OPEN/CONFIRMED，已修好的点位再次被拍到时会命中那条旧缺陷并刷新
     *       {@code lastSeenAt}，而不是产生新告警——复发被吞掉，运维永远不知道。</li>
     * </ol>
     * 有了 RECTIFIED 之后，复发自然走"立新单 + 旧记录留痕"，既能审计（这个点位修过
     * 几次）又不吞复发。
     */
    static final String RECTIFIED = "RECTIFIED";

    /** 缺陷状态全集（人工裁决与工单回写都只能产生这四种）。 */
    static final List<String> STATUSES = List.of("OPEN", "CONFIRMED", RECTIFIED, "DISMISSED");

    /**
     * 可派单状态：工单只能挂这两种缺陷。
     *
     * <p>与 {@link #STATUSES} 分开是因为口径不同：DISMISSED 是否决、RECTIFIED 是已关闭，
     * 都不该再出现在新工单上；而 {@link #findNearby} 用的是**同一份**集合——这保证了
     * "能派单的缺陷才会被去重折叠"，不会出现"已消缺的缺陷还在吸收新观测"。
     */
    public static final List<String> DISPATCHABLE = List.of("OPEN", "CONFIRMED");

    /** 自动晋升置信度阈值（可配 aerofleet.defect.auto-promote-confidence）。 */
    private final double autoPromoteConfidence;

    private final DefectRepository defects;

    public DefectService(DefectRepository defects,
                         org.springframework.core.env.Environment env) {
        this.defects = defects;
        this.autoPromoteConfidence = env.getProperty(
                "aerofleet.defect.auto-promote-confidence", Double.class, 0.6);
    }

    // ------------------------------------------------------------------
    // 立案与去重
    // ------------------------------------------------------------------

    /** 人工立案（note 必填——人工单没说明等于没立案）。 */
    @Transactional
    public DefectEntity createManual(String kind, double lat, double lon,
                                     double confidence, String note, String tenantId) {
        return createManual(kind, lat, lon, confidence, note, tenantId, null);
    }

    /**
     * 人工立案（带杆塔号——"按杆塔号+缺陷复检"的口径入口）。
     *
     * <p>杆塔号可空：AI 拍照晋升的缺陷拿不到杆塔号（相机不知道自己飞的是哪个塔），
     * null 不等于非法，只是不参与"按杆塔号"筛选与复检航线自动收录。
     */
    @Transactional
    public DefectEntity createManual(String kind, double lat, double lon,
                                     double confidence, String note, String tenantId,
                                     String towerNo) {
        if (note == null || note.isBlank()) {
            throw new IllegalArgumentException("manual defect requires 'note'");
        }
        if (towerNo != null && towerNo.isBlank()) {
            throw new IllegalArgumentException("towerNo must be null or non-blank (got blank string)");
        }
        String normalized = towerNo == null ? null : towerNo.trim();
        DefectEntity d = persist(kind, lat, lon, confidence, note, "manual", tenantId);
        if (normalized != null) {
            d.towerNo = normalized;
            d = defects.save(d);
        }
        return d;
    }

    /** 按杆塔号列出指定可派单状态的缺陷（"T-012 还有几个未消缺"的口径）。 */
    public List<DefectEntity> listByTowerNo(String towerNo, String status) {
        List<String> wanted = status != null && !status.isBlank()
                ? List.of(status) : DISPATCHABLE;
        return defects.findByTowerNoAndStatusInOrderByCreatedAtDesc(towerNo, wanted);
    }

    /**
     * 拍照结果旁路钩子（CaptureService 在 capture 响应组装后调用）。
     * 对每条 confidence ≥ 阈值的检测：命中既有单则更新，否则立案。
     */
    public void onCapture(long frameSeq, int sysid,
                          List<java.util.Map<String, Object>> detections, String tenantId) {
        // 可观测性：这是 F4 自动晋升的唯一入口，e2e/线上排查第一跳就看这行
        log.info("defect onCapture: frame={} sysid={} -> {} detection(s) (threshold={})",
                frameSeq, sysid, detections == null ? 0 : detections.size(), autoPromoteConfidence);
        for (java.util.Map<String, Object> d : detections) {
            // 单条 detection 单独 try：旁路纪律（spec §4 / F1 N3）只要求「立案失败不影响
            // 拍照主路径」，不要求「一条失败静默丢掉本帧后续所有 detection」。此前 try 包住
            // 整个 for，某一条 save 抛异常会让同帧余下检测全部不被处理——缺陷静默消失，
            // 而日志只有一行 warn，看不出丢了多少条。
            try {
                Object confRaw = d.get("confidence");
                Object kindRaw = d.get("kind");
                Object latRaw = d.get("lat");
                Object lonRaw = d.get("lon");
                if (!(confRaw instanceof Number conf) || !(kindRaw instanceof String kind)
                        || !(latRaw instanceof Number latN) || !(lonRaw instanceof Number lonN)) {
                    continue;
                }
                if (conf.doubleValue() < autoPromoteConfidence) {
                    continue;
                }
                double lat = latN.doubleValue();
                double lon = lonN.doubleValue();
                DefectEntity hit = findNearby(kind, lat, lon);
                if (hit != null) {
                    // 复现：只更新观测，不重复立案（去重口径锚点）
                    hit.confidence = conf.doubleValue();
                    hit.lastSeenAt = System.currentTimeMillis();
                    defects.save(hit);
                    log.debug("defect {} re-seen at frame {} (kind={}, conf={})",
                            hit.id, frameSeq, kind, conf.doubleValue());
                } else {
                    DefectEntity created = persist(kind, lat, lon, conf.doubleValue(),
                            "auto from frame " + frameSeq + " sysid " + sysid, "auto", tenantId);
                    log.info("defect auto-promoted: id={} kind={} conf={} lat={} lon={} frame={}",
                            created.id, kind, conf.doubleValue(), lat, lon, frameSeq);
                }
            } catch (Exception e) {
                // 纯旁路纪律（spec §4 / F1 N3 同款）：单条立案失败不影响拍照主路径，
                // 也不影响同帧余下检测
                log.warn("defect auto-promote failed (ignored, frame {}): {}", frameSeq, e.getMessage());
            }
        }
    }

    /**
     * 找 ≤ DEDUP_RADIUS_M 且同 kind 的可派单缺陷（OPEN/CONFIRMED）。
     *
     * <p>RECTIFIED **刻意不在**这个集合里：已消缺的点位再次被拍到必须立新单，而不是
     * 刷新旧缺陷的 {@code lastSeenAt}（那会让复发无声无息地消失）。
     */
    DefectEntity findNearby(String kind, double lat, double lon) {
        List<DefectEntity> active = defects.findByStatusInOrderByCreatedAtDesc(DISPATCHABLE);
        DefectEntity best = null;
        double bestM = Double.MAX_VALUE;
        for (DefectEntity d : active) {
            if (!d.kind.equals(kind)) {
                continue;
            }
            double m = haversineM(lat, lon, d.lat, d.lon);
            if (m <= DEDUP_RADIUS_M && m < bestM) {
                best = d;
                bestM = m;
            }
        }
        return best;
    }

    private DefectEntity persist(String kind, double lat, double lon,
                                 double confidence, String note, String source, String tenantId) {
        DefectEntity d = new DefectEntity();
        d.kind = kind;
        d.lat = lat;
        d.lon = lon;
        d.confidence = confidence;
        d.severity = DefectSeverity.classify(kind, confidence);
        d.note = note;
        d.source = source;
        d.tenantId = tenantId;
        d.lastSeenAt = System.currentTimeMillis();
        return defects.save(d);
    }

    // ------------------------------------------------------------------
    // 查询与状态
    // ------------------------------------------------------------------

    public List<DefectEntity> list(String status) {
        if (status != null && !status.isBlank()) {
            return defects.findByStatusInOrderByCreatedAtDesc(List.of(status));
        }
        return defects.findAll(org.springframework.data.domain.Sort.by(org.springframework.data.domain.Sort.Direction.DESC, "createdAt"));
    }

    public DefectEntity get(Long id) {
        return defects.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("unknown defect " + id));
    }

    /** 球面距离（米）。仓库惯例：各类内私有实现（AutoDispatchService/InspectionArea 同款）。 */
    static double haversineM(double lat1, double lon1, double lat2, double lon2) {
        double dLat = Math.toRadians(lat2 - lat1);
        double dLon = Math.toRadians(lon2 - lon1);
        double a = Math.sin(dLat / 2) * Math.sin(dLat / 2)
                + Math.cos(Math.toRadians(lat1)) * Math.cos(Math.toRadians(lat2))
                * Math.sin(dLon / 2) * Math.sin(dLon / 2);
        return 6371000.0 * 2 * Math.atan2(Math.sqrt(a), Math.sqrt(1 - a));
    }

    /**
     * 缺陷状态人工裁决：OPEN↔CONFIRMED↔DISMISSED + →RECTIFIED（与工单状态独立）。
     *
     * <p>RECTIFIED 与另三个不同：它是**关闭**语义，必须有说明才允许手工置入——
     * 与 {@link #createManual} 的"人工单没说明等于没立案"同一条纪律。没写说明的
     * 关闭在审计里等于"不知道谁修的、什么时候修的"。
     */
    @Transactional
    public DefectEntity setStatus(Long id, String status, String note) {
        if (!STATUSES.contains(status)) {
            throw new IllegalArgumentException("invalid defect status: " + status
                    + " (supported: " + STATUSES + ")");
        }
        if (RECTIFIED.equals(status) && (note == null || note.isBlank())) {
            throw new IllegalArgumentException(
                    "marking a defect RECTIFIED requires a note (who/when/how)");
        }
        DefectEntity d = get(id);
        d.status = status;
        if (RECTIFIED.equals(status)) {
            // 消缺口径下 lastSeenAt 记为"关闭时间"，不是"最后一次看到缺陷的时间"，
            // 两者混用会让报告里的复发时间线读反
            d.lastSeenAt = System.currentTimeMillis();
        }
        if (note != null && !note.isBlank()) {
            d.note = (d.note == null ? "" : d.note + " | ") + note;
        }
        return defects.save(d);
    }
}
