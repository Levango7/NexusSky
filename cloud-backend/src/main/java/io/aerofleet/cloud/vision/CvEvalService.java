package io.aerofleet.cloud.vision;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * CV 评测指标层（F1）：按国网机巡缺陷算法评测口径内建
 * 识别率 / 误检比 / 处理时间 三指标。
 * <p>
 * 评测为拍摄主路径的纯旁路：{@link #record} 全程吞异常（spec N3），
 * 数据保存在进程内滑动窗口（默认 500 帧，重启清零，spec 1.5-5）。
 * <p>
 * 指标口径（spec §2.4）：
 * <ul>
 *   <li>识别率 = ΣTP / Σ真值数 —— 真值数 0 的帧不计入分母</li>
 *   <li>误检比 = ΣFP / (ΣTP + ΣFP) —— 无检出时不计</li>
 *   <li>处理时间 = 检测+定位端到端 ms，聚合 avg 与 p95</li>
 * </ul>
 * 分母为零时指标为 {@code null}（前端显示 "--"），不产 100%/0% 假象。
 */
@Service
public class CvEvalService {

    private static final Logger log = LoggerFactory.getLogger(CvEvalService.class);

    /** 单帧评测记录（不可变）。 */
    public record CvFrameRecord(long frameSeq, String source, int truthCount,
                                int truePositives, int falsePositives,
                                double latencyMs, long ts) {}

    /** 滑动窗口（synchronized 访问）。 */
    private final ArrayDeque<CvFrameRecord> window = new ArrayDeque<>();

    private final int windowSize;
    private final double matchThresholdM;

    public CvEvalService(
            @Value("${aerofleet.cv-eval.window-size:500}") int windowSize,
            @Value("${aerofleet.cv-eval.match-threshold-m:5.0}") double matchThresholdM) {
        this.windowSize = Math.max(1, windowSize);
        this.matchThresholdM = matchThresholdM;
    }

    /**
     * 记录一帧评测（贪心一对一真值匹配）。
     * <p>
     * TP 判定：每个检出与帧内真值的最近距离 ≤ {@code matchThresholdM}；
     * 按距离升序贪心占坑，每个真值目标最多匹配一次，未占到坑的检出记 FP。
     *
     * @param frameSeq        帧号
     * @param source          检测源标识（truth / vision-source / pixels / external / truth(fallback)）
     * @param truthCount      帧内可见真值目标数
     * @param matchDistancesM 每个检出与最近真值的距离（米，&lt;0 表示真值不可用 → FP）
     * @param latencyMs       检测+定位端到端耗时
     */
    public void record(long frameSeq, String source, int truthCount,
                       List<Double> matchDistancesM, double latencyMs) {
        try {
            List<Double> dists = matchDistancesM == null
                    ? List.of() : new ArrayList<>(matchDistancesM);
            // 距离升序贪心：距离 ≤ 阈值的检出先占真值名额，
            // TP 上限 = 帧内真值数（一对一约束），占不到坑的记 FP
            dists.sort(Comparator.comparingDouble(d -> d == null ? Double.MAX_VALUE : d));
            int tp = 0;
            int fp = 0;
            for (Double d : dists) {
                if (d != null && d >= 0 && d <= matchThresholdM && tp < truthCount) {
                    tp++;
                } else {
                    fp++;
                }
            }
            synchronized (window) {
                window.addLast(new CvFrameRecord(frameSeq, source, truthCount,
                        tp, fp, latencyMs, System.currentTimeMillis()));
                while (window.size() > windowSize) {
                    window.removeFirst();
                }
            }
        } catch (Exception e) {
            log.warn("cv-eval record failed (ignored): {}", e.getMessage());
        }
    }

    /**
     * 聚合三指标。
     *
     * @param sourceFilter 检测源过滤（null/空 = 全部）
     * @return frames / recall / falseDetectionRatio / latencyAvgMs / latencyP95Ms /
     *         windowSize / perFrame（最近 50 帧摘要）
     */
    public Map<String, Object> metrics(String sourceFilter) {
        List<CvFrameRecord> records;
        synchronized (window) {
            records = new ArrayList<>(window);
        }
        if (sourceFilter != null && !sourceFilter.isBlank()) {
            records.removeIf(r -> !sourceFilter.equals(r.source()));
        }

        long truthTotal = 0;
        int tpTotal = 0;
        int fpTotal = 0;
        List<Double> latencies = new ArrayList<>();
        for (CvFrameRecord r : records) {
            truthTotal += r.truthCount();
            tpTotal += r.truePositives();
            fpTotal += r.falsePositives();
            latencies.add(r.latencyMs());
        }

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("frames", records.size());
        out.put("recall", truthTotal > 0 ? round3((double) tpTotal / truthTotal) : null);
        int detections = tpTotal + fpTotal;
        out.put("falseDetectionRatio", detections > 0 ? round3((double) fpTotal / detections) : null);
        out.put("latencyAvgMs", latencies.isEmpty() ? null
                : round3(latencies.stream().mapToDouble(Double::doubleValue).average().orElse(0)));
        out.put("latencyP95Ms", latencies.isEmpty() ? null : round3(percentile95(latencies)));
        out.put("windowSize", windowSize);
        out.put("truePositives", tpTotal);
        out.put("falsePositives", fpTotal);
        out.put("truthTotal", truthTotal);
        // 行业参考线（国网机巡评测口径，调研文档 [29]）
        out.put("reference", Map.of("recall", 0.85, "falseDetectionRatio", 0.15));

        List<CvFrameRecord> recent = records.size() > 50
                ? records.subList(records.size() - 50, records.size()) : records;
        List<Map<String, Object>> perFrame = new ArrayList<>();
        for (CvFrameRecord r : recent) {
            perFrame.add(new LinkedHashMap<>(Map.of(
                    "frameSeq", r.frameSeq(), "source", r.source(),
                    "truthCount", r.truthCount(), "tp", r.truePositives(),
                    "fp", r.falsePositives(), "latencyMs", round3(r.latencyMs()))));
        }
        out.put("perFrame", perFrame);
        return out;
    }

    /** 清空评测窗口（spec E4）。 */
    public void reset() {
        synchronized (window) {
            window.clear();
        }
    }

    /** 指标接口的只读窗口快照（e2e/调试用）。 */
    public List<CvFrameRecord> snapshot() {
        synchronized (window) {
            return Collections.unmodifiableList(new ArrayList<>(window));
        }
    }

    private static double percentile95(List<Double> sorted) {
        List<Double> copy = new ArrayList<>(sorted);
        Collections.sort(copy);
        int idx = (int) Math.ceil(0.95 * copy.size()) - 1;
        return copy.get(Math.max(0, Math.min(idx, copy.size() - 1)));
    }

    private static double round3(double v) {
        return Math.round(v * 1000.0) / 1000.0;
    }
}
