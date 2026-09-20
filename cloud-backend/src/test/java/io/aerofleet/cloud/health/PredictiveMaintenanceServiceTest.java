package io.aerofleet.cloud.health;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Arrays;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link PredictiveMaintenanceService} 预测性维护单测（P1-2 预测性维护）。
 * <p>
 * 验证电池老化、振动趋势、温度趋势、通信质量下降的预测逻辑。
 */
@DisplayName("PredictiveMaintenanceService 预测性维护")
class PredictiveMaintenanceServiceTest {

    private HealthMonitorService monitor;
    private PredictiveMaintenanceService service;

    private void setup() {
        monitor = new HealthMonitorService(null);
        service = new PredictiveMaintenanceService(monitor);
    }

    private TelemetrySnapshot healthySnapshot() {
        TelemetrySnapshot t = new TelemetrySnapshot(System.currentTimeMillis());
        t.setBatteryPct(80);
        t.setMotorRpms(Arrays.asList(5000.0, 5000.0, 5000.0, 5000.0));
        t.setVibrationG(0.2);
        t.setTemperatureC(40);
        t.setRssiDbm(-50);
        t.setImuDrift(0.1);
        t.setGpsSatellites(12);
        t.setGpsHdop(0.8);
        t.setBatteryCycles(50);
        return t;
    }

    // ------------------------------------------------------------------
    // 电池老化预测
    // ------------------------------------------------------------------

    @Test
    @DisplayName("电池循环次数>300 → IMMEDIATE")
    void batteryHighCycles_immediate() {
        setup();
        TelemetrySnapshot t = healthySnapshot();
        t.setBatteryCycles(350);
        monitor.updateScore(monitor.calculateScore(1, t));

        MaintenancePrediction p = service.predict(1).stream()
                .filter(x -> x.getPredictedComponent() == ComponentType.BATTERY)
                .findFirst().orElseThrow();

        assertThat(p.getUrgency()).isEqualTo(MaintenancePrediction.Urgency.IMMEDIATE);
        assertThat(p.getConfidencePct()).isGreaterThanOrEqualTo(85);
        assertThat(p.getReason()).contains("立即更换");
    }

    @Test
    @DisplayName("电池循环次数200-300 → WITHIN_7_DAYS")
    void batteryMidCycles_within7Days() {
        setup();
        TelemetrySnapshot t = healthySnapshot();
        t.setBatteryCycles(250);
        monitor.updateScore(monitor.calculateScore(1, t));

        MaintenancePrediction p = service.predict(1).stream()
                .filter(x -> x.getPredictedComponent() == ComponentType.BATTERY)
                .findFirst().orElseThrow();

        assertThat(p.getUrgency()).isEqualTo(MaintenancePrediction.Urgency.WITHIN_7_DAYS);
        assertThat(p.getPredictedFailureDate()).isNotNull();
    }

    @Test
    @DisplayName("电池循环次数<100 → NORMAL")
    void batteryLowCycles_normal() {
        setup();
        TelemetrySnapshot t = healthySnapshot();
        t.setBatteryCycles(30);
        monitor.updateScore(monitor.calculateScore(1, t));

        MaintenancePrediction p = service.predict(1).stream()
                .filter(x -> x.getPredictedComponent() == ComponentType.BATTERY)
                .findFirst().orElseThrow();

        assertThat(p.getUrgency()).isEqualTo(MaintenancePrediction.Urgency.NORMAL);
    }

    @Test
    @DisplayName("电量<20% 提升电池紧迫度至 IMMEDIATE")
    void batteryLowPct_escalatesUrgency() {
        setup();
        TelemetrySnapshot t = healthySnapshot();
        t.setBatteryPct(10);
        t.setBatteryCycles(50);
        monitor.updateScore(monitor.calculateScore(1, t));

        MaintenancePrediction p = service.predict(1).stream()
                .filter(x -> x.getPredictedComponent() == ComponentType.BATTERY)
                .findFirst().orElseThrow();

        assertThat(p.getUrgency()).isEqualTo(MaintenancePrediction.Urgency.IMMEDIATE);
    }

    // ------------------------------------------------------------------
    // 振动趋势预测（电机轴承）
    // ------------------------------------------------------------------

    @Test
    @DisplayName("振动>1.0g → IMMEDIATE（轴承可能失效）")
    void vibrationHigh_immediate() {
        setup();
        TelemetrySnapshot t = healthySnapshot();
        t.setVibrationG(1.5);
        monitor.updateScore(monitor.calculateScore(1, t));

        MaintenancePrediction p = service.predict(1).stream()
                .filter(x -> x.getPredictedComponent() == ComponentType.MOTOR)
                .findFirst().orElseThrow();

        assertThat(p.getUrgency()).isEqualTo(MaintenancePrediction.Urgency.IMMEDIATE);
        assertThat(p.getReason()).contains("轴承");
    }

    @Test
    @DisplayName("振动0.8-1.0g → WITHIN_7_DAYS")
    void vibrationMid_within7Days() {
        setup();
        TelemetrySnapshot t = healthySnapshot();
        t.setVibrationG(0.9);
        monitor.updateScore(monitor.calculateScore(1, t));

        MaintenancePrediction p = service.predict(1).stream()
                .filter(x -> x.getPredictedComponent() == ComponentType.MOTOR)
                .findFirst().orElseThrow();

        assertThat(p.getUrgency()).isEqualTo(MaintenancePrediction.Urgency.WITHIN_7_DAYS);
    }

    @Test
    @DisplayName("振动正常 → NORMAL")
    void vibrationNormal_normal() {
        setup();
        TelemetrySnapshot t = healthySnapshot();
        t.setVibrationG(0.3);
        monitor.updateScore(monitor.calculateScore(1, t));

        MaintenancePrediction p = service.predict(1).stream()
                .filter(x -> x.getPredictedComponent() == ComponentType.MOTOR)
                .findFirst().orElseThrow();

        assertThat(p.getUrgency()).isEqualTo(MaintenancePrediction.Urgency.NORMAL);
    }

    // ------------------------------------------------------------------
    // 温度趋势预测
    // ------------------------------------------------------------------

    @Test
    @DisplayName("温度>80°C → IMMEDIATE（散热故障）")
    void temperatureHigh_immediate() {
        setup();
        TelemetrySnapshot t = healthySnapshot();
        t.setTemperatureC(85);
        monitor.updateScore(monitor.calculateScore(1, t));

        MaintenancePrediction p = service.predict(1).stream()
                .filter(x -> x.getPredictedComponent() == ComponentType.TEMPERATURE)
                .findFirst().orElseThrow();

        assertThat(p.getUrgency()).isEqualTo(MaintenancePrediction.Urgency.IMMEDIATE);
        assertThat(p.getReason()).contains("散热");
    }

    @Test
    @DisplayName("温度70-80°C → WITHIN_7_DAYS")
    void temperatureMid_within7Days() {
        setup();
        TelemetrySnapshot t = healthySnapshot();
        t.setTemperatureC(75);
        monitor.updateScore(monitor.calculateScore(1, t));

        MaintenancePrediction p = service.predict(1).stream()
                .filter(x -> x.getPredictedComponent() == ComponentType.TEMPERATURE)
                .findFirst().orElseThrow();

        assertThat(p.getUrgency()).isEqualTo(MaintenancePrediction.Urgency.WITHIN_7_DAYS);
    }

    @Test
    @DisplayName("温度正常 → NORMAL")
    void temperatureNormal_normal() {
        setup();
        TelemetrySnapshot t = healthySnapshot();
        t.setTemperatureC(45);
        monitor.updateScore(monitor.calculateScore(1, t));

        MaintenancePrediction p = service.predict(1).stream()
                .filter(x -> x.getPredictedComponent() == ComponentType.TEMPERATURE)
                .findFirst().orElseThrow();

        assertThat(p.getUrgency()).isEqualTo(MaintenancePrediction.Urgency.NORMAL);
    }

    // ------------------------------------------------------------------
    // 通信质量预测
    // ------------------------------------------------------------------

    @Test
    @DisplayName("RSSI<-80 → IMMEDIATE（通信模块故障）")
    void rssiLow_immediate() {
        setup();
        TelemetrySnapshot t = healthySnapshot();
        t.setRssiDbm(-90);
        monitor.updateScore(monitor.calculateScore(1, t));

        MaintenancePrediction p = service.predict(1).stream()
                .filter(x -> x.getPredictedComponent() == ComponentType.COMMUNICATION)
                .findFirst().orElseThrow();

        assertThat(p.getUrgency()).isEqualTo(MaintenancePrediction.Urgency.IMMEDIATE);
    }

    @Test
    @DisplayName("RSSI -75~-80 → WITHIN_7_DAYS")
    void rssiMid_within7Days() {
        setup();
        TelemetrySnapshot t = healthySnapshot();
        t.setRssiDbm(-78);
        monitor.updateScore(monitor.calculateScore(1, t));

        MaintenancePrediction p = service.predict(1).stream()
                .filter(x -> x.getPredictedComponent() == ComponentType.COMMUNICATION)
                .findFirst().orElseThrow();

        assertThat(p.getUrgency()).isEqualTo(MaintenancePrediction.Urgency.WITHIN_7_DAYS);
    }

    @Test
    @DisplayName("RSSI 正常 → NORMAL")
    void rssiNormal_normal() {
        setup();
        TelemetrySnapshot t = healthySnapshot();
        t.setRssiDbm(-55);
        monitor.updateScore(monitor.calculateScore(1, t));

        MaintenancePrediction p = service.predict(1).stream()
                .filter(x -> x.getPredictedComponent() == ComponentType.COMMUNICATION)
                .findFirst().orElseThrow();

        assertThat(p.getUrgency()).isEqualTo(MaintenancePrediction.Urgency.NORMAL);
    }

    // ------------------------------------------------------------------
    // 综合预测
    // ------------------------------------------------------------------

    @Test
    @DisplayName("predict 返回 4 个部件预测，按紧迫度排序")
    void predict_returns4SortedPredictions() {
        setup();
        TelemetrySnapshot t = healthySnapshot();
        t.setBatteryCycles(350); // IMMEDIATE
        t.setVibrationG(1.5);    // IMMEDIATE
        monitor.updateScore(monitor.calculateScore(1, t));

        java.util.List<MaintenancePrediction> predictions = service.predict(1);
        assertThat(predictions).hasSize(4);
        // 第一个应是最紧迫的
        assertThat(predictions.get(0).getUrgency().ordinal())
                .isLessThanOrEqualTo(predictions.get(3).getUrgency().ordinal());
    }

    @Test
    @DisplayName("predict 无评分数据返回空列表")
    void predict_noScore_returnsEmpty() {
        setup();
        assertThat(service.predict(999)).isEmpty();
    }

    @Test
    @DisplayName("predictAll 返回所有无人机预测")
    void predictAll_returnsAll() {
        setup();
        TelemetrySnapshot t = healthySnapshot();
        monitor.updateScore(monitor.calculateScore(1, t));
        monitor.updateScore(monitor.calculateScore(2, t));

        java.util.List<MaintenancePrediction> all = service.predictAll();
        // 每机 4 个预测
        assertThat(all).hasSize(8);
    }
}