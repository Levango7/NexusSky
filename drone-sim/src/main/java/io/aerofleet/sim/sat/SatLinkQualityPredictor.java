package io.aerofleet.sim.sat;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * 卫星链路质量预测器（FR-23 卫星链路质量预测）。
 * <p>
 * 基于卫星轨道参数与历史链路数据，预测未来 5 分钟的链路质量趋势。
 * <p>
 * 预测维度：
 * <ul>
 *   <li>延迟（ms）— 基于轨道高度传播延迟 + 转发开销</li>
 *   <li>带宽（bps）— 基于共享用户数衰减模型</li>
 *   <li>中断概率（0-1）— 基于仰角趋势与过境窗口剩余时间</li>
 * </ul>
 * <p>
 * 预测方法：线性外推 + 仰角衰减模型。
 * 采样间隔默认 60s，5 分钟共 5 个采样点（t+60s, t+120s, t+180s, t+240s, t+300s）。
 */
public final class SatLinkQualityPredictor {

    /** 预测时间范围（ms）— 未来 5 分钟。 */
    public static final long PREDICTION_HORIZON_MS = 5 * 60 * 1000L;
    /** 采样间隔（ms）— 60 秒。 */
    public static final long SAMPLE_INTERVAL_MS = 60 * 1000L;
    /** 采样点数量。 */
    public static final int NUM_SAMPLES = (int) (PREDICTION_HORIZON_MS / SAMPLE_INTERVAL_MS);

    /** 链路质量预测采样点。 */
    public record QualitySample(
            long timeOffsetMs,
            long predictedDelayMs,
            long predictedBandwidthBps,
            double predictedInterruptionProb
    ) {}

    /** 预测结果。 */
    public record PredictionResult(
            String satId,
            List<QualitySample> samples,
            String trendSummary
    ) {}

    /** 轨道参数输入。 */
    public record OrbitParams(
            double orbitAltitudeKm,
            double currentElevationDeg,
            double elevationRateDegPerMin,
            long windowRemainingMs,
            long currentDelayMs,
            long currentBandwidthBps,
            int currentSharedUsers
    ) {}

    /**
     * 预测未来 5 分钟链路质量趋势。
     *
     * @param satId      卫星标识
     * @param orbitParams 当前轨道参数与链路状态
     * @return 预测结果（5 个采样点的时间序列）
     */
    public PredictionResult predict(String satId, OrbitParams orbitParams) {
        List<QualitySample> samples = new ArrayList<>(NUM_SAMPLES);

        for (int i = 1; i <= NUM_SAMPLES; i++) {
            long offsetMs = i * SAMPLE_INTERVAL_MS;
            long futureMs = offsetMs;

            // 1) 仰角预测：线性外推
            double futureElev = orbitParams.currentElevationDeg()
                    + orbitParams.elevationRateDegPerMin() * (futureMs / 60_000.0);
            futureElev = Math.max(0.0, Math.min(90.0, futureElev));

            // 2) 延迟预测：基于轨道高度（传播延迟稳定）+ 仰角影响（低仰角增加多径延迟）
            double baseDelay = orbitParams.currentDelayMs();
            // 仰角低于 20° 时延迟增加（多径效应）
            double delayMultiplier = 1.0;
            if (futureElev < 20.0) {
                delayMultiplier = 1.0 + (20.0 - futureElev) / 20.0 * 0.5; // 最多增加 50%
            }
            long predictedDelay = Math.max(1L, (long) (baseDelay * delayMultiplier));

            // 3) 带宽预测：共享用户数线性增长模型（每分钟 +1 用户）
            int futureUsers = orbitParams.currentSharedUsers() + (int) (futureMs / 60_000.0);
            long predictedBw = computePredictedBandwidth(
                    orbitParams.currentBandwidthBps(), futureUsers);

            // 4) 中断概率预测：基于仰角趋势与窗口剩余时间
            double interruptionProb = computeInterruptionProbability(
                    futureElev, orbitParams.windowRemainingMs(), futureMs);

            samples.add(new QualitySample(offsetMs, predictedDelay, predictedBw, interruptionProb));
        }

        // 生成趋势摘要
        String summary = buildTrendSummary(samples);

        return new PredictionResult(satId, Collections.unmodifiableList(samples), summary);
    }

    /**
     * 计算预测带宽（共享用户数衰减模型）。
     * <p>
     * 带宽随共享用户数增加而衰减：bw = baseBw / max(1, users)。
     *
     * @param baseBandwidthBps 当前带宽
     * @param sharedUsers      预测的共享用户数
     * @return 预测带宽（bps）
     */
    private long computePredictedBandwidth(long baseBandwidthBps, int sharedUsers) {
        if (sharedUsers <= 1) {
            return baseBandwidthBps;
        }
        // 带宽随用户数衰减，但不低于标称值的 10%
        long predicted = baseBandwidthBps / sharedUsers;
        return Math.max(baseBandwidthBps / 10, predicted);
    }

    /**
     * 计算中断概率。
     * <p>
     * 中断概率来源：
     * <ol>
     *   <li>仰角低于阈值（10°）→ 高中断概率</li>
     *   <li>过境窗口即将结束 → 中断概率上升</li>
     * </ol>
     *
     * @param futureElev         预测仰角
     * @param windowRemainingMs  当前窗口剩余时间
     * @param futureMs           预测时间偏移
     * @return 中断概率 [0, 1]
     */
    private double computeInterruptionProbability(double futureElev,
                                                   long windowRemainingMs, long futureMs) {
        double prob = 0.0;

        // 仰角因素：低于 10° 时中断概率急剧上升
        if (futureElev < 10.0) {
            prob += (10.0 - futureElev) / 10.0 * 0.8;
        } else if (futureElev < 20.0) {
            prob += (20.0 - futureElev) / 20.0 * 0.2;
        }

        // 窗口因素：预测时间超出窗口剩余 → 必然中断
        if (futureMs >= windowRemainingMs) {
            prob = 1.0;
        } else {
            // 接近窗口末端时概率上升（最后 20% 时间）
            long remainingAfterPredict = windowRemainingMs - futureMs;
            if (remainingAfterPredict < windowRemainingMs * 0.2) {
                prob += 0.3 * (1.0 - (double) remainingAfterPredict / (windowRemainingMs * 0.2));
            }
        }

        return Math.max(0.0, Math.min(1.0, prob));
    }

    /**
     * 生成趋势摘要文字。
     *
     * @param samples 预测采样点
     * @return 趋势摘要
     */
    private String buildTrendSummary(List<QualitySample> samples) {
        if (samples.isEmpty()) return "无预测数据";

        QualitySample first = samples.get(0);
        QualitySample last = samples.get(samples.size() - 1);

        StringBuilder sb = new StringBuilder();
        // 延迟趋势
        if (last.predictedDelayMs() > first.predictedDelayMs()) {
            sb.append("延迟上升");
        } else if (last.predictedDelayMs() < first.predictedDelayMs()) {
            sb.append("延迟下降");
        } else {
            sb.append("延迟稳定");
        }
        sb.append(" (").append(first.predictedDelayMs()).append("→")
                .append(last.predictedDelayMs()).append("ms), ");

        // 带宽趋势
        if (last.predictedBandwidthBps() < first.predictedBandwidthBps()) {
            sb.append("带宽下降");
        } else if (last.predictedBandwidthBps() > first.predictedBandwidthBps()) {
            sb.append("带宽上升");
        } else {
            sb.append("带宽稳定");
        }
        sb.append(", ");

        // 中断概率趋势
        double maxProb = samples.stream()
                .mapToDouble(QualitySample::predictedInterruptionProb)
                .max().orElse(0.0);
        if (maxProb > 0.5) {
            sb.append("高中断风险");
        } else if (maxProb > 0.2) {
            sb.append("中等中断风险");
        } else {
            sb.append("低中断风险");
        }

        return sb.toString();
    }
}