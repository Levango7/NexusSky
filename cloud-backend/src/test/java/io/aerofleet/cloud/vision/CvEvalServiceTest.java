package io.aerofleet.cloud.vision;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link CvEvalService} 评测指标层测试（F1）。
 * <p>
 * 覆盖：贪心一对一匹配、阈值边界、TP 上限约束、分母为零返回 null、
 * 滑动窗口淘汰、reset、source 过滤、p95 计算（spec §2.4 + §4.1）。
 */
@DisplayName("CvEvalService CV 评测指标层")
class CvEvalServiceTest {

    private CvEvalService newService() {
        return new CvEvalService(500, 5.0);
    }

    @Test
    @DisplayName("距离≤阈值 → TP；超阈值 → FP")
    void matchWithinThreshold() {
        CvEvalService svc = newService();
        svc.record(1, "external", 2, List.of(3.0, 8.0), 10.0);

        Map<String, Object> m = svc.metrics(null);
        assertThat(m.get("truePositives")).isEqualTo(1);
        assertThat(m.get("falsePositives")).isEqualTo(1);
        assertThat(m.get("truthTotal")).isEqualTo(2L);
        // 识别率 1/2，误检比 1/2
        assertThat(m.get("recall")).isEqualTo(0.5);
        assertThat(m.get("falseDetectionRatio")).isEqualTo(0.5);
    }

    @Test
    @DisplayName("恰好等于阈值 5m 记 TP（边界包含）")
    void thresholdBoundaryInclusive() {
        CvEvalService svc = newService();
        svc.record(1, "external", 1, List.of(5.0), 5.0);

        assertThat(svc.metrics(null).get("truePositives")).isEqualTo(1);
    }

    @Test
    @DisplayName("TP 上限 = 帧内真值数（一对一约束）：2 检出命中同一真值 → 1TP 1FP")
    void tpCappedAtTruthCount() {
        CvEvalService svc = newService();
        // 两个检出都在阈值内，但真值只有 1 个 → 最近的 TP，另一个 FP
        svc.record(1, "pixels", 1, List.of(2.0, 3.0), 10.0);

        Map<String, Object> m = svc.metrics(null);
        assertThat(m.get("truePositives")).isEqualTo(1);
        assertThat(m.get("falsePositives")).isEqualTo(1);
    }

    @Test
    @DisplayName("距离为负（真值不可用）→ FP")
    void negativeDistanceIsFalsePositive() {
        CvEvalService svc = newService();
        svc.record(1, "pixels", 1, List.of(-1.0), 10.0);

        Map<String, Object> m = svc.metrics(null);
        assertThat(m.get("truePositives")).isEqualTo(0);
        assertThat(m.get("falsePositives")).isEqualTo(1);
    }

    @Test
    @DisplayName("无真值帧 → recall 为 null（不显示 100% 假象）")
    void recallNullWhenNoTruth() {
        CvEvalService svc = newService();
        svc.record(1, "external", 0, List.of(), 5.0);

        assertThat(svc.metrics(null).get("recall")).isNull();
    }

    @Test
    @DisplayName("无检出 → 误检比为 null")
    void fdrNullWhenNoDetections() {
        CvEvalService svc = newService();
        svc.record(1, "external", 3, List.of(), 5.0);

        Map<String, Object> m = svc.metrics(null);
        assertThat(m.get("falseDetectionRatio")).isNull();
        assertThat(m.get("recall")).isEqualTo(0.0);
    }

    @Test
    @DisplayName("滑动窗口淘汰最旧帧")
    void windowEviction() {
        CvEvalService svc = new CvEvalService(3, 5.0);
        for (int i = 1; i <= 5; i++) {
            svc.record(i, "truth", 1, List.of(1.0), i);
        }

        Map<String, Object> m = svc.metrics(null);
        assertThat(m.get("frames")).isEqualTo(3);
        List<Map<String, Object>> perFrame = castList(m.get("perFrame"));
        // 最旧的 1/2 帧被淘汰，保留 3/4/5
        assertThat(perFrame.get(0).get("frameSeq")).isEqualTo(3L);
        assertThat(perFrame.get(2).get("frameSeq")).isEqualTo(5L);
    }

    @Test
    @DisplayName("reset 清空窗口")
    void resetClearsWindow() {
        CvEvalService svc = newService();
        svc.record(1, "truth", 1, List.of(1.0), 5.0);
        svc.reset();

        assertThat(svc.metrics(null).get("frames")).isEqualTo(0);
    }

    @Test
    @DisplayName("source 过滤只聚合指定源")
    void sourceFilter() {
        CvEvalService svc = newService();
        svc.record(1, "truth", 1, List.of(1.0), 5.0);
        svc.record(2, "pixels", 1, List.of(9.0), 20.0);

        Map<String, Object> m = svc.metrics("pixels");
        assertThat(m.get("frames")).isEqualTo(1);
        assertThat(m.get("falsePositives")).isEqualTo(1);
    }

    @Test
    @DisplayName("p95 取窗口内 95 分位延迟")
    void p95Calculation() {
        CvEvalService svc = newService();
        // 100 帧：延迟 1..100 ms，p95 = ceil(0.95*100)-1 = 94 → 第 95 小 = 95ms
        for (int i = 1; i <= 100; i++) {
            svc.record(i, "truth", 1, List.of(1.0), i);
        }

        assertThat(svc.metrics(null).get("latencyP95Ms")).isEqualTo(95.0);
    }

    @Test
    @DisplayName("reference 参考线（国网口径 85% / ≤15%）")
    void referenceLine() {
        CvEvalService svc = newService();
        @SuppressWarnings("unchecked")
        Map<String, Object> ref = (Map<String, Object>) svc.metrics(null).get("reference");
        assertThat(ref.get("recall")).isEqualTo(0.85);
        assertThat(ref.get("falseDetectionRatio")).isEqualTo(0.15);
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> castList(Object o) {
        return (List<Map<String, Object>>) o;
    }
}
