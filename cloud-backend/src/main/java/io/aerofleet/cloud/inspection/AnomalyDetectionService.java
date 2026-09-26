package io.aerofleet.cloud.inspection;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

/**
 * 异常检测服务。
 * <p>
 * 分析巡检照片，基于照片元数据与行业类型生成异常报告。异常类型按行业映射：
 * <ul>
 *   <li>POWER_LINE  → 绝缘子破损</li>
 *   <li>OIL_GAS_PIPE → 管道泄漏</li>
 *   <li>RAILWAY    → 轨道裂缝</li>
 *   <li>SOLAR_FARM  → 面板裂纹</li>
 *   <li>WIND_FARM   → 叶片损伤</li>
 *   <li>BRIDGE     → 结构锈蚀</li>
 * </ul>
 * <p>
 * 当前为模拟实现：基于照片元数据（航高、相机角度、行业类型）按概率生成异常，
 * 置信度由模拟分数推导。后续可接入真实视觉模型推理。
 */
@Service
public class AnomalyDetectionService {

    private static final Logger log = LoggerFactory.getLogger(AnomalyDetectionService.class);

    /** 模拟检测：每张照片的异常触发概率。 */
    private static final double ANOMALY_PROBABILITY = 0.3;
    /** 模拟检测：高严重度触发概率（在已触发异常中）。 */
    private static final double HIGH_SEVERITY_PROB = 0.25;
    /** 模拟检测：中严重度触发概率。 */
    private static final double MEDIUM_SEVERITY_PROB = 0.45;

    /**
     * 分析巡检照片，检测异常。
     *
     * @param photo 巡检照片（含 GPS 标注与行业类型）
     * @return 检测到的异常列表（可能为空）
     */
    public List<Anomaly> detect(InspectionPhoto photo) {
        List<Anomaly> anomalies = new ArrayList<>();
        ThreadLocalRandom rng = ThreadLocalRandom.current();

        if (rng.nextDouble() < ANOMALY_PROBABILITY) {
            String anomalyType = anomalyTypeForIndustry(photo.industryType());
            Anomaly.Severity severity = randomSeverity(rng);
            double confidence = 70 + rng.nextDouble() * 25; // 70%~95%
            String id = UUID.randomUUID().toString();
            String desc = buildDescription(anomalyType, severity, photo);

            anomalies.add(new Anomaly(
                    id, anomalyType, severity,
                    photo.lat(), photo.lon(), photo.id(),
                    desc, Instant.now(), confidence));
        }
        log.debug("Anomaly detection: photo={} found={}", photo.id(), anomalies.size());
        return anomalies;
    }

    /** 批量检测多张照片。 */
    public List<Anomaly> detectAll(List<InspectionPhoto> photos) {
        List<Anomaly> all = new ArrayList<>();
        for (InspectionPhoto p : photos) {
            all.addAll(detect(p));
        }
        return all;
    }

    /** 按行业类型映射异常类型名称。 */
    public static String anomalyTypeForIndustry(IndustryType industry) {
        return switch (industry) {
            case POWER_LINE -> "绝缘子破损";
            case OIL_GAS_PIPE -> "管道泄漏";
            case RAILWAY -> "轨道裂缝";
            case SOLAR_FARM -> "面板裂纹";
            case WIND_FARM -> "叶片损伤";
            case BRIDGE -> "结构锈蚀";
        };
    }

    /** 按行业类型映射维护建议。 */
    public static String recommendationForIndustry(IndustryType industry) {
        return switch (industry) {
            case POWER_LINE -> "更换破损绝缘子，复查相邻杆塔";
            case OIL_GAS_PIPE -> "派遣人工复核泄漏点，准备抢修";
            case RAILWAY -> "上报工务段，限速运行直至修复";
            case SOLAR_FARM -> "更换裂纹面板，检查接线";
            case WIND_FARM -> "停机检修叶片，评估是否需更换";
            case BRIDGE -> "除锈防腐处理，复查结构焊缝";
        };
    }

    private static Anomaly.Severity randomSeverity(ThreadLocalRandom rng) {
        double r = rng.nextDouble();
        if (r < HIGH_SEVERITY_PROB) return Anomaly.Severity.HIGH;
        if (r < HIGH_SEVERITY_PROB + MEDIUM_SEVERITY_PROB) return Anomaly.Severity.MEDIUM;
        return Anomaly.Severity.LOW;
    }

    private static String buildDescription(String type, Anomaly.Severity severity,
                                           InspectionPhoto photo) {
        return String.format("%s（%s）：航高 %.0fm，航向 %.0f°，航点 #%d",
                type, severity.name(), photo.altM(), photo.headingDeg(), photo.waypointSeq());
    }
}