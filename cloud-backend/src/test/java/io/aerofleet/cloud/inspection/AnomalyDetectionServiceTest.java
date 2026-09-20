package io.aerofleet.cloud.inspection;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link AnomalyDetectionService} 单测。
 * <p>
 * 测试异常检测逻辑：行业类型映射、异常生成、批量检测。
 */
@DisplayName("AnomalyDetectionService 异常检测 (P1-1)")
class AnomalyDetectionServiceTest {

    private AnomalyDetectionService detector;

    @BeforeEach
    void setUp() {
        detector = new AnomalyDetectionService();
    }

    private InspectionPhoto photo(IndustryType industry) {
        return new InspectionPhoto(
                "photo-1", "task-1", 0,
                39.90, 116.40, 80.0,
                90.0, 90.0, Instant.now(), null, industry);
    }

    @Test
    @DisplayName("电力线路异常类型为绝缘子破损")
    void powerLineAnomalyType() {
        assertThat(AnomalyDetectionService.anomalyTypeForIndustry(IndustryType.POWER_LINE))
                .isEqualTo("绝缘子破损");
    }

    @Test
    @DisplayName("油气管道异常类型为管道泄漏")
    void oilGasPipeAnomalyType() {
        assertThat(AnomalyDetectionService.anomalyTypeForIndustry(IndustryType.OIL_GAS_PIPE))
                .isEqualTo("管道泄漏");
    }

    @Test
    @DisplayName("铁路沿线异常类型为轨道裂缝")
    void railwayAnomalyType() {
        assertThat(AnomalyDetectionService.anomalyTypeForIndustry(IndustryType.RAILWAY))
                .isEqualTo("轨道裂缝");
    }

    @Test
    @DisplayName("光伏电站异常类型为面板裂纹")
    void solarFarmAnomalyType() {
        assertThat(AnomalyDetectionService.anomalyTypeForIndustry(IndustryType.SOLAR_FARM))
                .isEqualTo("面板裂纹");
    }

    @Test
    @DisplayName("风力电场异常类型为叶片损伤")
    void windFarmAnomalyType() {
        assertThat(AnomalyDetectionService.anomalyTypeForIndustry(IndustryType.WIND_FARM))
                .isEqualTo("叶片损伤");
    }

    @Test
    @DisplayName("桥梁异常类型为结构锈蚀")
    void bridgeAnomalyType() {
        assertThat(AnomalyDetectionService.anomalyTypeForIndustry(IndustryType.BRIDGE))
                .isEqualTo("结构锈蚀");
    }

    @Test
    @DisplayName("detect 返回的异常包含正确行业类型")
    void detectReturnsAnomalyWithCorrectType() {
        // 多次检测以提高触发概率
        boolean foundPowerLine = false;
        for (int i = 0; i < 50; i++) {
            List<Anomaly> anomalies = detector.detect(photo(IndustryType.POWER_LINE));
            if (!anomalies.isEmpty()) {
                assertThat(anomalies.get(0).type()).isEqualTo("绝缘子破损");
                foundPowerLine = true;
                break;
            }
        }
        assertThat(foundPowerLine).as("应至少触发一次异常检测").isTrue();
    }

    @Test
    @DisplayName("detect 返回的异常置信度在 70~95% 范围")
    void detectConfidenceInRange() {
        for (int i = 0; i < 100; i++) {
            List<Anomaly> anomalies = detector.detect(photo(IndustryType.RAILWAY));
            for (Anomaly a : anomalies) {
                assertThat(a.confidencePct()).isBetween(70.0, 95.0);
            }
        }
    }

    @Test
    @DisplayName("detect 返回的异常包含 GPS 坐标")
    void detectAnomalyHasGpsCoordinates() {
        for (int i = 0; i < 100; i++) {
            List<Anomaly> anomalies = detector.detect(photo(IndustryType.SOLAR_FARM));
            for (Anomaly a : anomalies) {
                assertThat(a.lat()).isEqualTo(39.90);
                assertThat(a.lon()).isEqualTo(116.40);
                assertThat(a.photoId()).isEqualTo("photo-1");
            }
        }
    }

    @Test
    @DisplayName("detect 返回的异常严重度为 HIGH/MEDIUM/LOW 之一")
    void detectSeverityValid() {
        for (int i = 0; i < 100; i++) {
            List<Anomaly> anomalies = detector.detect(photo(IndustryType.BRIDGE));
            for (Anomaly a : anomalies) {
                assertThat(a.severity()).isIn(
                        Anomaly.Severity.HIGH,
                        Anomaly.Severity.MEDIUM,
                        Anomaly.Severity.LOW);
            }
        }
    }

    @Test
    @DisplayName("detect 返回的异常包含描述与检测时间")
    void detectAnomalyHasDescriptionAndTimestamp() {
        for (int i = 0; i < 100; i++) {
            List<Anomaly> anomalies = detector.detect(photo(IndustryType.WIND_FARM));
            for (Anomaly a : anomalies) {
                assertThat(a.description()).isNotBlank();
                assertThat(a.detectedAt()).isNotNull();
                assertThat(a.id()).isNotBlank();
            }
        }
    }

    @Test
    @DisplayName("detectAll 批量检测多张照片")
    void detectAllMultiplePhotos() {
        var photos = List.of(
                photo(IndustryType.POWER_LINE),
                photo(IndustryType.OIL_GAS_PIPE),
                photo(IndustryType.RAILWAY),
                photo(IndustryType.SOLAR_FARM),
                photo(IndustryType.WIND_FARM),
                photo(IndustryType.BRIDGE));

        int totalAnomalies = 0;
        for (int i = 0; i < 20; i++) {
            List<Anomaly> anomalies = detector.detectAll(photos);
            totalAnomalies += anomalies.size();
        }
        // 6 张照片 × 20 次 × 30% 概率 → 期望约 36 个异常，至少应 > 0
        assertThat(totalAnomalies).isGreaterThan(0);
    }

    @Test
    @DisplayName("recommendationForIndustry 为每种行业返回维护建议")
    void recommendationForAllIndustries() {
        assertThat(AnomalyDetectionService.recommendationForIndustry(IndustryType.POWER_LINE))
                .contains("绝缘子");
        assertThat(AnomalyDetectionService.recommendationForIndustry(IndustryType.OIL_GAS_PIPE))
                .contains("泄漏");
        assertThat(AnomalyDetectionService.recommendationForIndustry(IndustryType.RAILWAY))
                .contains("限速");
        assertThat(AnomalyDetectionService.recommendationForIndustry(IndustryType.SOLAR_FARM))
                .contains("面板");
        assertThat(AnomalyDetectionService.recommendationForIndustry(IndustryType.WIND_FARM))
                .contains("叶片");
        assertThat(AnomalyDetectionService.recommendationForIndustry(IndustryType.BRIDGE))
                .contains("锈");
    }
}