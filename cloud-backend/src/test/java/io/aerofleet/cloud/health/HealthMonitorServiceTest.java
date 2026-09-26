package io.aerofleet.cloud.health;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.Collections;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link HealthMonitorService} 健康评分计算单测（P1-2 健康管理）。
 * <p>
 * 直接实例化 Service（无 Spring 上下文），验证各部件评分、总体评分与等级映射。
 */
@DisplayName("HealthMonitorService 健康评分计算")
class HealthMonitorServiceTest {

    private final HealthMonitorService service = new HealthMonitorService(null);

    private TelemetrySnapshot snapshot() {
        TelemetrySnapshot t = new TelemetrySnapshot(System.currentTimeMillis());
        t.setBatteryPct(80);
        t.setMotorRpms(Arrays.asList(5000.0, 5010.0, 4995.0, 5005.0));
        t.setVibrationG(0.3);
        t.setTemperatureC(45);
        t.setRssiDbm(-55);
        t.setImuDrift(0.2);
        t.setGpsSatellites(12);
        t.setGpsHdop(0.8);
        t.setBatteryCycles(50);
        return t;
    }

    // ------------------------------------------------------------------
    // 电池评分
    // ------------------------------------------------------------------

    @Test
    @DisplayName("电池电量>50% 评分高（A 区，>=90）")
    void batteryHighPct_scoresHigh() {
        TelemetrySnapshot t = snapshot();
        t.setBatteryPct(80);
        ComponentScore cs = service.scoreBattery(t);
        assertThat(cs.getScore()).isBetween(90, 100);
        assertThat(cs.getStatus()).isEqualTo(ComponentScore.Status.HEALTHY);
    }

    @Test
    @DisplayName("电池电量100% 满分")
    void batteryFullPct_scoresMax() {
        TelemetrySnapshot t = snapshot();
        t.setBatteryPct(100);
        ComponentScore cs = service.scoreBattery(t);
        assertThat(cs.getScore()).isEqualTo(100);
    }

    @Test
    @DisplayName("电池电量20-50% 评分中等（C 区，60-79）")
    void batteryMidPct_scoresMid() {
        TelemetrySnapshot t = snapshot();
        t.setBatteryPct(35);
        ComponentScore cs = service.scoreBattery(t);
        assertThat(cs.getScore()).isBetween(60, 79);
        assertThat(cs.getStatus()).isEqualTo(ComponentScore.Status.WARNING);
    }

    @Test
    @DisplayName("电池电量<20% 评分低（F 区，<60）")
    void batteryLowPct_scoresLow() {
        TelemetrySnapshot t = snapshot();
        t.setBatteryPct(10);
        ComponentScore cs = service.scoreBattery(t);
        assertThat(cs.getScore()).isLessThan(60);
        assertThat(cs.getStatus()).isEqualTo(ComponentScore.Status.CRITICAL);
        assertThat(cs.getRecommendation()).isNotNull();
    }

    @Test
    @DisplayName("电池循环次数>300 强制降分")
    void batteryHighCycles_degradesScore() {
        TelemetrySnapshot t = snapshot();
        t.setBatteryPct(80);
        t.setBatteryCycles(350);
        ComponentScore cs = service.scoreBattery(t);
        assertThat(cs.getScore()).isLessThanOrEqualTo(60);
        assertThat(cs.getRecommendation()).contains("更换");
    }

    // ------------------------------------------------------------------
    // 电机评分
    // ------------------------------------------------------------------

    @Test
    @DisplayName("电机转速稳定（标准差小）评分高")
    void motorStable_scoresHigh() {
        TelemetrySnapshot t = snapshot();
        t.setMotorRpms(Arrays.asList(5000.0, 5000.0, 5000.0, 5000.0));
        ComponentScore cs = service.scoreMotor(t);
        assertThat(cs.getScore()).isEqualTo(100);
        assertThat(cs.getStatus()).isEqualTo(ComponentScore.Status.HEALTHY);
    }

    @Test
    @DisplayName("电机转速波动大（标准差>150）评分低")
    void motorUnstable_scoresLow() {
        TelemetrySnapshot t = snapshot();
        t.setMotorRpms(Arrays.asList(4000.0, 6000.0, 3500.0, 6500.0));
        ComponentScore cs = service.scoreMotor(t);
        assertThat(cs.getScore()).isLessThan(60);
        assertThat(cs.getStatus()).isEqualTo(ComponentScore.Status.CRITICAL);
    }

    @Test
    @DisplayName("电机转速数据缺失返回默认评分")
    void motorEmptyRpms_returnsDefault() {
        TelemetrySnapshot t = snapshot();
        t.setMotorRpms(Collections.emptyList());
        ComponentScore cs = service.scoreMotor(t);
        assertThat(cs.getScore()).isEqualTo(70);
    }

    // ------------------------------------------------------------------
    // 振动评分
    // ------------------------------------------------------------------

    @Test
    @DisplayName("振动<0.5g 评分高（A 区）")
    void vibrationLow_scoresHigh() {
        TelemetrySnapshot t = snapshot();
        t.setVibrationG(0.2);
        ComponentScore cs = service.scoreVibration(t);
        assertThat(cs.getScore()).isBetween(90, 100);
    }

    @Test
    @DisplayName("振动0.5-1.0g 评分中等（C 区）")
    void vibrationMid_scoresMid() {
        TelemetrySnapshot t = snapshot();
        t.setVibrationG(0.7);
        ComponentScore cs = service.scoreVibration(t);
        assertThat(cs.getScore()).isBetween(60, 79);
    }

    @Test
    @DisplayName("振动>1.0g 评分低（F 区）")
    void vibrationHigh_scoresLow() {
        TelemetrySnapshot t = snapshot();
        t.setVibrationG(1.5);
        ComponentScore cs = service.scoreVibration(t);
        assertThat(cs.getScore()).isLessThan(60);
        assertThat(cs.getRecommendation()).isNotNull();
    }

    // ------------------------------------------------------------------
    // 温度评分
    // ------------------------------------------------------------------

    @Test
    @DisplayName("温度<60°C 评分高（A 区）")
    void temperatureLow_scoresHigh() {
        TelemetrySnapshot t = snapshot();
        t.setTemperatureC(40);
        ComponentScore cs = service.scoreTemperature(t);
        assertThat(cs.getScore()).isGreaterThanOrEqualTo(90);
    }

    @Test
    @DisplayName("温度60-80°C 评分中等（C 区）")
    void temperatureMid_scoresMid() {
        TelemetrySnapshot t = snapshot();
        t.setTemperatureC(70);
        ComponentScore cs = service.scoreTemperature(t);
        assertThat(cs.getScore()).isBetween(60, 79);
    }

    @Test
    @DisplayName("温度>80°C 评分低（F 区）")
    void temperatureHigh_scoresLow() {
        TelemetrySnapshot t = snapshot();
        t.setTemperatureC(90);
        ComponentScore cs = service.scoreTemperature(t);
        assertThat(cs.getScore()).isLessThan(60);
    }

    // ------------------------------------------------------------------
    // 通信评分
    // ------------------------------------------------------------------

    @Test
    @DisplayName("RSSI>-60 评分高（A 区）")
    void commStrong_scoresHigh() {
        TelemetrySnapshot t = snapshot();
        t.setRssiDbm(-50);
        ComponentScore cs = service.scoreCommunication(t);
        assertThat(cs.getScore()).isGreaterThanOrEqualTo(90);
    }

    @Test
    @DisplayName("RSSI -60~-80 评分中等（C 区）")
    void commMid_scoresMid() {
        TelemetrySnapshot t = snapshot();
        t.setRssiDbm(-70);
        ComponentScore cs = service.scoreCommunication(t);
        assertThat(cs.getScore()).isBetween(60, 79);
    }

    @Test
    @DisplayName("RSSI<-80 评分低（F 区）")
    void commWeak_scoresLow() {
        TelemetrySnapshot t = snapshot();
        t.setRssiDbm(-90);
        ComponentScore cs = service.scoreCommunication(t);
        assertThat(cs.getScore()).isLessThan(60);
    }

    // ------------------------------------------------------------------
    // 总体评分与等级
    // ------------------------------------------------------------------

    @Test
    @DisplayName("全部件健康 总体评分高 等级 A")
    void allHealthy_overallGradeA() {
        TelemetrySnapshot t = snapshot();
        HealthScore hs = service.calculateScore(1, t);
        assertThat(hs.getOverallScore()).isGreaterThanOrEqualTo(90);
        assertThat(hs.getGrade()).isEqualTo(HealthScore.Grade.A);
        assertThat(hs.getComponentScores()).hasSize(7);
    }

    @Test
    @DisplayName("全部件危险 总体评分低 等级 F")
    void allCritical_overallGradeF() {
        TelemetrySnapshot t = snapshot();
        t.setBatteryPct(5);
        t.setMotorRpms(Arrays.asList(3000.0, 7000.0, 2000.0, 8000.0));
        t.setVibrationG(2.0);
        t.setTemperatureC(95);
        t.setRssiDbm(-95);
        t.setImuDrift(3.0);
        t.setGpsSatellites(3);
        t.setGpsHdop(5.0);
        HealthScore hs = service.calculateScore(1, t);
        assertThat(hs.getOverallScore()).isLessThan(60);
        assertThat(hs.getGrade()).isEqualTo(HealthScore.Grade.F);
    }

    // ------------------------------------------------------------------
    // 边界值
    // ------------------------------------------------------------------

    @Test
    @DisplayName("等级映射：0 分 → F")
    void gradeBoundary_zeroIsF() {
        assertThat(HealthScore.gradeOf(0)).isEqualTo(HealthScore.Grade.F);
        assertThat(HealthScore.gradeOf(59)).isEqualTo(HealthScore.Grade.F);
    }

    @Test
    @DisplayName("等级映射：60 分 → D")
    void gradeBoundary_60IsD() {
        assertThat(HealthScore.gradeOf(60)).isEqualTo(HealthScore.Grade.D);
        assertThat(HealthScore.gradeOf(69)).isEqualTo(HealthScore.Grade.D);
    }

    @Test
    @DisplayName("等级映射：70 分 → C")
    void gradeBoundary_70IsC() {
        assertThat(HealthScore.gradeOf(70)).isEqualTo(HealthScore.Grade.C);
        assertThat(HealthScore.gradeOf(79)).isEqualTo(HealthScore.Grade.C);
    }

    @Test
    @DisplayName("等级映射：80 分 → B")
    void gradeBoundary_80IsB() {
        assertThat(HealthScore.gradeOf(80)).isEqualTo(HealthScore.Grade.B);
        assertThat(HealthScore.gradeOf(89)).isEqualTo(HealthScore.Grade.B);
    }

    @Test
    @DisplayName("等级映射：90-100 分 → A")
    void gradeBoundary_90to100IsA() {
        assertThat(HealthScore.gradeOf(90)).isEqualTo(HealthScore.Grade.A);
        assertThat(HealthScore.gradeOf(100)).isEqualTo(HealthScore.Grade.A);
    }

    @Test
    @DisplayName("ComponentScore.statusOf 状态映射")
    void componentStatusMapping() {
        assertThat(ComponentScore.statusOf(100)).isEqualTo(ComponentScore.Status.HEALTHY);
        assertThat(ComponentScore.statusOf(80)).isEqualTo(ComponentScore.Status.HEALTHY);
        assertThat(ComponentScore.statusOf(79)).isEqualTo(ComponentScore.Status.WARNING);
        assertThat(ComponentScore.statusOf(60)).isEqualTo(ComponentScore.Status.WARNING);
        assertThat(ComponentScore.statusOf(59)).isEqualTo(ComponentScore.Status.CRITICAL);
        assertThat(ComponentScore.statusOf(0)).isEqualTo(ComponentScore.Status.CRITICAL);
    }

    // ------------------------------------------------------------------
    // 评分缓存与历史
    // ------------------------------------------------------------------

    @Test
    @DisplayName("updateScore 后 getLatest 返回最新评分")
    void updateScore_getLatest() {
        TelemetrySnapshot t = snapshot();
        HealthScore hs = service.calculateScore(1, t);
        service.updateScore(hs);
        assertThat(service.getLatest(1)).isSameAs(hs);
    }

    @Test
    @DisplayName("updateScore 后 getHistory 返回历史列表")
    void updateScore_getHistory() {
        TelemetrySnapshot t1 = snapshot();
        TelemetrySnapshot t2 = snapshot();
        HealthScore hs1 = service.calculateScore(1, t1);
        HealthScore hs2 = service.calculateScore(1, t2);
        service.updateScore(hs1);
        service.updateScore(hs2);
        assertThat(service.getHistory(1)).hasSize(2);
    }

    @Test
    @DisplayName("getWarnings 返回非健康部件")
    void getWarnings_returnsNonHealthy() {
        TelemetrySnapshot t = snapshot();
        t.setBatteryPct(10); // CRITICAL
        t.setVibrationG(1.5); // CRITICAL
        HealthScore hs = service.calculateScore(1, t);
        service.updateScore(hs);
        assertThat(service.getWarnings()).hasSize(2);
    }

    @Test
    @DisplayName("getFleetScores 返回所有已评分无人机")
    void getFleetScores() {
        TelemetrySnapshot t = snapshot();
        service.updateScore(service.calculateScore(1, t));
        service.updateScore(service.calculateScore(2, t));
        assertThat(service.getFleetScores()).hasSize(2);
    }
}