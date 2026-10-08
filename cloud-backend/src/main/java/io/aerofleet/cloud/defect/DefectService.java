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
        if (note == null || note.isBlank()) {
            throw new IllegalArgumentException("manual defect requires 'note'");
        }
        return persist(kind, lat, lon, confidence, note, "manual", tenantId);
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
        try {
            for (java.util.Map<String, Object> d : detections) {
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
            }
        } catch (Exception e) {
            // 纯旁路纪律（spec §4 / F1 N3 同款）：立案失败不影响拍照主路径
            log.warn("defect auto-promote failed (ignored, frame {}): {}", frameSeq, e.getMessage());
        }
    }

    /** 找 ≤ DEDUP_RADIUS_M 且同 kind 的活动缺陷（OPEN/CONFIRMED）。 */
    DefectEntity findNearby(String kind, double lat, double lon) {
        List<DefectEntity> active = defects.findByStatusInOrderByCreatedAtDesc(
                List.of("OPEN", "CONFIRMED"));
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

    /** 缺陷状态流转：OPEN↔CONFIRMED↔DISMISSED（人工裁决，与工单状态独立）。 */
    @Transactional
    public DefectEntity setStatus(Long id, String status, String note) {
        if (!List.of("OPEN", "CONFIRMED", "DISMISSED").contains(status)) {
            throw new IllegalArgumentException("invalid defect status: " + status);
        }
        DefectEntity d = get(id);
        d.status = status;
        if (note != null && !note.isBlank()) {
            d.note = (d.note == null ? "" : d.note + " | ") + note;
        }
        return defects.save(d);
    }
}
