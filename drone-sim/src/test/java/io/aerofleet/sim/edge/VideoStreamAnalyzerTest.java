package io.aerofleet.sim.edge;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * VideoStreamAnalyzer 视频流本地分析单测（M12）。
 * <p>
 * 纯 JUnit 5，覆盖帧分析/检测对象/计数器/置信度。
 */
@DisplayName("VideoStreamAnalyzer 视频流分析 (M12)")
class VideoStreamAnalyzerTest {

    private VideoStreamAnalyzer analyzer;

    @BeforeEach
    void setUp() {
        analyzer = new VideoStreamAnalyzer();
    }

    @Test
    @DisplayName("analyzeFrame 返回 taskId 形如 frame-N，N 随帧数递增")
    void analyzeFrameReturnsIncrementingTaskId() {
        AnalysisResult r1 = analyzer.analyzeFrame(new byte[]{1});
        AnalysisResult r2 = analyzer.analyzeFrame(new byte[]{2});
        AnalysisResult r3 = analyzer.analyzeFrame(new byte[]{3});

        assertThat(r1.taskId).isEqualTo("frame-1");
        assertThat(r2.taskId).isEqualTo("frame-2");
        assertThat(r3.taskId).isEqualTo("frame-3");
    }

    @Test
    @DisplayName("analyzeFrame 检测对象固定为 [vehicle, person]")
    void analyzeFrameDetectsVehicleAndPerson() {
        AnalysisResult result = analyzer.analyzeFrame(new byte[]{1});

        assertThat(result.detectedObjects).hasSize(2);
        assertThat(result.detectedObjects).containsExactly("vehicle", "person");
    }

    @Test
    @DisplayName("analyzeFrame 置信度固定 0.85")
    void analyzeFrameConfidenceAlways085() {
        AnalysisResult result = analyzer.analyzeFrame(new byte[]{1});
        assertThat(result.confidence).isEqualTo(0.85);
    }

    @Test
    @DisplayName("analyzeFrame 处理时间非负")
    void analyzeFrameProcessingTimeNonNegative() {
        AnalysisResult result = analyzer.analyzeFrame(new byte[]{1});
        assertThat(result.processingTimeMs).isGreaterThanOrEqualTo(0);
    }

    @Test
    @DisplayName("getTotalFrames 初始为 0，随分析递增")
    void getTotalFramesIncrements() {
        assertThat(analyzer.getTotalFrames()).isZero();

        analyzer.analyzeFrame(new byte[]{1});
        assertThat(analyzer.getTotalFrames()).isEqualTo(1);

        analyzer.analyzeFrame(new byte[]{2});
        assertThat(analyzer.getTotalFrames()).isEqualTo(2);
    }

    @Test
    @DisplayName("getTotalDetections 每帧 2 个，累积递增")
    void getTotalDetectionsAccumulates() {
        assertThat(analyzer.getTotalDetections()).isZero();

        analyzer.analyzeFrame(new byte[]{1});
        assertThat(analyzer.getTotalDetections()).isEqualTo(2);

        analyzer.analyzeFrame(new byte[]{2});
        assertThat(analyzer.getTotalDetections()).isEqualTo(4);

        analyzer.analyzeFrame(new byte[]{3});
        assertThat(analyzer.getTotalDetections()).isEqualTo(6);
    }

    @Test
    @DisplayName("analyzeFrame 空字节数组也能正常分析")
    void analyzeFrameHandlesEmptyByteArray() {
        AnalysisResult result = analyzer.analyzeFrame(new byte[]{});

        assertThat(result).isNotNull();
        assertThat(result.detectedObjects).hasSize(2);
        assertThat(analyzer.getTotalFrames()).isEqualTo(1);
    }

    @Test
    @DisplayName("多帧连续分析后计数器一致")
    void multipleFramesCountersConsistent() {
        for (int i = 0; i < 10; i++) {
            analyzer.analyzeFrame(new byte[]{(byte) i});
        }

        assertThat(analyzer.getTotalFrames()).isEqualTo(10);
        assertThat(analyzer.getTotalDetections()).isEqualTo(20);
    }
}