package io.aerofleet.sim.sat;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;

/**
 * 多卫星最优选择器（FR-21 多卫星最优选择）。
 * <p>
 * 当多颗卫星同时可见时，按以下优先级选择最优卫星：
 * <ol>
 *   <li>仰角最高（多径最小）— 权重 0.4</li>
 *   <li>链路带宽最大 — 权重 0.3</li>
 *   <li>过境窗口最长 — 权重 0.2</li>
 *   <li>延迟最小 — 权重 0.1</li>
 * </ol>
 * <p>
 * 评分公式：score = elevation_norm × 0.4 + bandwidth_norm × 0.3 + window_norm × 0.2 + delay_norm × 0.1
 * <p>
 * 各维度归一化到 [0, 1]，延迟维度取反（延迟越小分数越高）。
 */
public final class MultiSatSelector {

    /** 仰角权重。 */
    public static final double W_ELEVATION = 0.4;
    /** 带宽权重。 */
    public static final double W_BANDWIDTH = 0.3;
    /** 过境窗口权重。 */
    public static final double W_WINDOW = 0.2;
    /** 延迟权重。 */
    public static final double W_DELAY = 0.1;

    /** 可见卫星候选条目。 */
    public record SatCandidate(
            String satId,
            SatLinkProvider.SatType satType,
            double elevationDeg,
            long bandwidthBps,
            long windowRemainingMs,
            long delayMs
    ) {
        /**
         * 计算综合评分（归一化后加权求和）。
         *
         * @param maxElev   候选集合中最大仰角
         * @param maxBw     候选集合中最大带宽
         * @param maxWindow 候选集合中最大窗口
         * @param minDelay  候选集合中最小延迟
         * @param maxDelay  候选集合中最大延迟
         * @return 综合评分 [0, 1]
         */
        double score(double maxElev, long maxBw, long maxWindow,
                     long minDelay, long maxDelay) {
            double elevNorm = maxElev > 0 ? elevationDeg / maxElev : 0.0;
            double bwNorm = maxBw > 0 ? (double) bandwidthBps / maxBw : 0.0;
            double windowNorm = maxWindow > 0 ? (double) windowRemainingMs / maxWindow : 0.0;
            // 延迟归一化取反：延迟越小分数越高
            double delayNorm = (maxDelay > minDelay)
                    ? 1.0 - (double) (delayMs - minDelay) / (maxDelay - minDelay)
                    : 1.0;
            return elevNorm * W_ELEVATION
                    + bwNorm * W_BANDWIDTH
                    + windowNorm * W_WINDOW
                    + delayNorm * W_DELAY;
        }
    }

    /** 选择结果。 */
    public record SelectionResult(
            SatCandidate best,
            List<ScoredSat> ranked
    ) {
        /** 带评分的排序候选。 */
        public record ScoredSat(SatCandidate candidate, double score) {}
    }

    /**
     * 从可见卫星列表中选择最优卫星。
     *
     * @param candidates 可见卫星候选列表（含仰角/带宽/窗口/延迟）
     * @return 选择结果（最优卫星 + 全部排序）
     */
    public SelectionResult select(List<SatCandidate> candidates) {
        if (candidates == null || candidates.isEmpty()) {
            return new SelectionResult(null, Collections.emptyList());
        }
        if (candidates.size() == 1) {
            SatCandidate only = candidates.get(0);
            return new SelectionResult(only,
                    List.of(new SelectionResult.ScoredSat(only, 1.0)));
        }

        // 计算归一化所需的最大/最小值
        double maxElev = 0.0;
        long maxBw = 0L;
        long maxWindow = 0L;
        long minDelay = Long.MAX_VALUE;
        long maxDelay = Long.MIN_VALUE;

        for (SatCandidate c : candidates) {
            maxElev = Math.max(maxElev, c.elevationDeg());
            maxBw = Math.max(maxBw, c.bandwidthBps());
            maxWindow = Math.max(maxWindow, c.windowRemainingMs());
            minDelay = Math.min(minDelay, c.delayMs());
            maxDelay = Math.max(maxDelay, c.delayMs());
        }

        // 计算评分并排序
        List<SelectionResult.ScoredSat> scored = new ArrayList<>();
        for (SatCandidate c : candidates) {
            double s = c.score(maxElev, maxBw, maxWindow, minDelay, maxDelay);
            scored.add(new SelectionResult.ScoredSat(c, s));
        }

        // 按评分降序排序
        scored.sort(Comparator.comparingDouble(SelectionResult.ScoredSat::score).reversed());

        return new SelectionResult(scored.get(0).candidate(), Collections.unmodifiableList(scored));
    }

    /**
     * 从 SatLinkProvider 列表构建候选并选择最优卫星。
     * <p>
     * 过境窗口默认设为 Long.MAX_VALUE（表示持续可见），延迟/带宽/仰角取 provider 当前值。
     *
     * @param providers 已连接的卫星链路提供者列表
     * @return 选择结果
     */
    public SelectionResult selectFromProviders(List<SatLinkProvider> providers) {
        if (providers == null || providers.isEmpty()) {
            return new SelectionResult(null, Collections.emptyList());
        }
        List<SatCandidate> candidates = new ArrayList<>();
        for (SatLinkProvider p : providers) {
            if (!p.isConnected()) continue;
            candidates.add(new SatCandidate(
                    p.getSatId(),
                    p.getSatType(),
                    p.getElevation(),
                    p.getBandwidth(),
                    Long.MAX_VALUE,
                    p.getDelay()
            ));
        }
        return select(candidates);
    }
}