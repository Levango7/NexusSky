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
                                double latencyMs, long ts, Map<String, KindStat> byKind) {}

    /** 单个 kind 的一帧统计：{@code truth}/{@code TP}/{@code FP}。 */
    public record KindStat(int truth, int truePositives, int falsePositives) {}

    /** 带 kind 的检测距离（用于逐 kind 贪心一对一匹配）。 */
    public record KindDist(double distanceM, String kind) {}

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
    /**
     * 兼容旧调用（按 kind 不带 kind 信息）：把全部真值记到 {@code unknown}，
     * 检出 kind 一律记 {@code unknown}。新调用方请用 {@link #record(long, String,
     * Map, List, double)}。
     */
    public void record(long frameSeq, String source, int truthCount,
                       List<Double> matchDistancesM, double latencyMs) {
        Map<String, Integer> truth = Map.of("unknown", truthCount);
        List<Double> src = matchDistancesM == null ? List.of() : new ArrayList<>(matchDistancesM);
        List<KindDist> matches = src.stream().map(d -> new KindDist(d, "unknown")).toList();
        record(frameSeq, source, truth, matches, latencyMs);
    }

    /**
     * 按 kind 记一帧评测。
     * <p>逐 kind 做贪心一对一匹配（与总级匹配同口径：距离升序、TP 上限=该 kind 帧内真值数、
     * 距离≤阈值且≥0）；{@code matches} 里 kind 为空的一律记 {@code unknown}。
     * 各 kind 统计相加与总量一致（不重叠一对一）。
     */
    public void record(long frameSeq, String source, Map<String, Integer> truthByKind,
                       List<KindDist> matches, double latencyMs) {
        try {
            // 逐 kind 统计：kind 缺位记 unknown
            Map<String, List<Double>> kindDists = new LinkedHashMap<>();
            Map<String, Integer> truth = new LinkedHashMap<>();
            if (truthByKind != null) {
                for (var e : truthByKind.entrySet()) {
                    truth.put(normalizeKind(e.getKey()), Math.max(0, e.getValue()));
                }
            }
            int truthCount = truth.values().stream().mapToInt(Integer::intValue).sum();
            List<KindDist> safeMatches = matches == null ? List.of() : new ArrayList<>(matches);
            for (KindDist m : safeMatches) {
                String k = normalizeKind(m == null ? null : m.kind());
                kindDists.computeIfAbsent(k, x -> new ArrayList<>())
                        .add(m == null ? -1.0 : m.distanceM());
            }
            Map<String, KindStat> byKind = new LinkedHashMap<>();
            int tp = 0;
            int fp = 0;
            for (Map.Entry<String, Integer> e : truth.entrySet()) {
                String kind = e.getKey();
                int cap = e.getValue();
                List<Double> ds = kindDists.remove(kind);
                int[] acc = greedy(ds == null ? List.of() : ds, cap);
                tp += acc[0];
                fp += acc[1];
                byKind.put(kind, new KindStat(cap, acc[0], acc[1]));
            }
            for (var e : kindDists.entrySet()) {
                int[] acc = greedy(e.getValue(), 0);
                fp += acc[1];
                byKind.put(e.getKey(), new KindStat(0, 0, acc[1]));
            }
            synchronized (window) {
                window.addLast(new CvFrameRecord(frameSeq, source, truthCount,
                        tp, fp, latencyMs, System.currentTimeMillis(), Map.copyOf(byKind)));
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

        // 按 kind 聚合（truth/tp/fp 各自累加，与总量一致）
        Map<String, long[]> kindAcc = new LinkedHashMap<>();
        for (CvFrameRecord r : records) {
            for (var e : r.byKind().entrySet()) {
                long[] acc = kindAcc.computeIfAbsent(e.getKey(), k -> new long[3]);
                acc[0] += e.getValue().truth();
                acc[1] += e.getValue().truePositives();
                acc[2] += e.getValue().falsePositives();
            }
        }
        List<Map<String, Object>> byKind = new ArrayList<>();
        for (var e : kindAcc.entrySet()) {
            long[] acc = e.getValue();
            long truthK = acc[0], tpv = acc[1], fpv = acc[2];
            long det = tpv + fpv;
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("kind", e.getKey());
            row.put("truth", truthK);
            row.put("truePositives", tpv);
            row.put("falsePositives", fpv);
            row.put("recall", truthK > 0 ? round3((double) tpv / truthK) : null);
            row.put("falseDetectionRatio", det > 0 ? round3((double) fpv / det) : null);
            byKind.add(row);
        }
        byKind.sort((a, b) -> Long.compare(((Number) b.get("truth")).longValue(),
                ((Number) a.get("truth")).longValue()));
        out.put("byKind", byKind);

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

    /** kind 归一：null/空 → unknown；长度截断 40。 */
    private static String normalizeKind(String kind) {
        if (kind == null) {
            return "unknown";
        }
        String s = kind.trim();
        if (s.isEmpty()) {
            return "unknown";
        }
        return s.length() > 40 ? s.substring(0, 40) : s;
    }

    /** 贪心一对一：距离升序、TP 上限=cap、距离∈[0, threshold]。返回 {tp, fp}。 */
    private int[] greedy(List<Double> dists, int cap) {
        if (dists == null || dists.isEmpty()) {
            return new int[]{0, 0};
        }
        List<Double> sorted = new ArrayList<>(dists);
        sorted.sort(Comparator.comparingDouble(d -> d == null ? Double.MAX_VALUE : d));
        int tp = 0;
        int fp = 0;
        for (Double d : sorted) {
            if (d != null && d >= 0 && d <= matchThresholdM && tp < cap) {
                tp++;
            } else {
                fp++;
            }
        }
        return new int[]{tp, fp};
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
