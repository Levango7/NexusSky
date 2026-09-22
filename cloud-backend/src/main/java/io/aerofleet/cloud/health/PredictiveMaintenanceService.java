package io.aerofleet.cloud.health;


import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 预测性维护服务（P1-2 预测性维护）。
 * <p>
 * 基于健康评分历史趋势与遥测指标，预测各部件的维护需求：
 * <ul>
 *   <li>电池循环次数 → 预计更换时间（循环 >300 次 IMMEDIATE，>200 次 7 天内）</li>
 *   <li>电机振动趋势 → 预计轴承失效时间（振动 >1.0g IMMEDIATE，>0.8g 7 天内）</li>
 *   <li>温度趋势 → 散热系统健康（温度 >80°C IMMEDIATE，>70°C 7 天内）</li>
 *   <li>通信质量下降趋势 → 天线/模块问题（RSSI <-80 IMMEDIATE，<-75 7 天内）</li>
 * </ul>
 * <p>
 * 依赖 {@link HealthMonitorService} 获取最新评分与历史趋势。
 */
@Component
public class PredictiveMaintenanceService {


    /** 电池循环次数阈值。 */
    private static final int BATTERY_CYCLES_CRITICAL = 300;
    private static final int BATTERY_CYCLES_WARN = 200;
    private static final int BATTERY_CYCLES_NORMAL = 100;

    /** 振动幅值阈值（g）。 */
    private static final double VIBRATION_CRITICAL = 1.0;
    private static final double VIBRATION_WARN = 0.8;

    /** 温度阈值（°C）。 */
    private static final double TEMP_CRITICAL = 80.0;
    private static final double TEMP_WARN = 70.0;

    /** RSSI 阈值（dBm）。 */
    private static final double RSSI_CRITICAL = -80.0;
    private static final double RSSI_WARN = -75.0;

    private final HealthMonitorService monitorService;

    public PredictiveMaintenanceService(HealthMonitorService monitorService) {
        this.monitorService = monitorService;
    }

    /**
     * 预测单机维护需求。
     * <p>
     * 综合各部件评分与历史趋势，返回最紧迫的维护建议列表（按紧迫度排序）。
     *
     * @param sysid 无人机 systemId
     * @return 维护预测建议列表（可能为空）
     */
    public List<MaintenancePrediction> predict(int sysid) {
        List<MaintenancePrediction> predictions = new ArrayList<>();
        HealthScore latest = monitorService.getLatest(sysid);
        if (latest == null) {
            return predictions;
        }
        List<HealthScore> history = monitorService.getHistory(sysid);

        predictions.add(predictBattery(sysid, latest, history));
        predictions.add(predictMotor(sysid, latest, history));
        predictions.add(predictTemperature(sysid, latest, history));
        predictions.add(predictCommunication(sysid, latest, history));

        // 按紧迫度排序：IMMEDIATE > WITHIN_7_DAYS > WITHIN_30_DAYS > NORMAL
        predictions.sort((a, b) -> Integer.compare(
                a.getUrgency().ordinal(), b.getUrgency().ordinal()));
        return predictions;
    }

    /** 预测所有已评分无人机的维护需求。 */
    public List<MaintenancePrediction> predictAll() {
        List<MaintenancePrediction> all = new ArrayList<>();
        for (HealthScore hs : monitorService.getFleetScores()) {
            all.addAll(predict(hs.getSysid()));
        }
        return all;
    }

    // ------------------------------------------------------------------
    // 各部件预测
    // ------------------------------------------------------------------

    /** 电池老化预测：基于循环次数与电量趋势。 */
    MaintenancePrediction predictBattery(int sysid, HealthScore latest, List<HealthScore> history) {
        ComponentScore battery = latest.getComponentScores().get(ComponentType.BATTERY);
        int cycles = battery != null && battery.getMetrics().containsKey("batteryCycles")
                ? battery.getMetrics().get("batteryCycles").intValue()
                : 0;
        double pct = battery != null && battery.getMetrics().containsKey("batteryPct")
                ? battery.getMetrics().get("batteryPct")
                : -1;

        MaintenancePrediction.Urgency urgency;
        LocalDate failureDate;
        int confidence;
        String reason;

        if (cycles >= BATTERY_CYCLES_CRITICAL) {
            urgency = MaintenancePrediction.Urgency.IMMEDIATE;
            failureDate = LocalDate.now().plusDays(7);
            confidence = 90;
            reason = String.format("电池循环次数 %d 超过临界值 %d，需立即更换", cycles, BATTERY_CYCLES_CRITICAL);
        } else if (cycles >= BATTERY_CYCLES_WARN) {
            urgency = MaintenancePrediction.Urgency.WITHIN_7_DAYS;
            // 循环次数越多，预计剩余可用天数越少
            int daysLeft = (int) Math.max(1, (BATTERY_CYCLES_CRITICAL - cycles) * 0.5);
            failureDate = LocalDate.now().plusDays(daysLeft);
            confidence = 75;
            reason = String.format("电池循环次数 %d 接近临界值，建议 %d 天内更换", cycles, daysLeft);
        } else if (cycles >= BATTERY_CYCLES_NORMAL) {
            urgency = MaintenancePrediction.Urgency.WITHIN_30_DAYS;
            int daysLeft = (int) Math.max(7, (BATTERY_CYCLES_CRITICAL - cycles) * 1.0);
            failureDate = LocalDate.now().plusDays(Math.min(daysLeft, 30));
            confidence = 60;
            reason = String.format("电池循环次数 %d，预计 %d 天后需更换", cycles, daysLeft);
        } else {
            urgency = MaintenancePrediction.Urgency.NORMAL;
            int daysLeft = (BATTERY_CYCLES_CRITICAL - cycles) * 2;
            failureDate = LocalDate.now().plusDays(daysLeft);
            confidence = 40;
            reason = String.format("电池健康，循环次数 %d，预计可用 %d 天", cycles, daysLeft);
        }

        // 电量骤降趋势提升紧迫度
        if (pct >= 0 && pct < 20) {
            urgency = MaintenancePrediction.Urgency.IMMEDIATE;
            confidence = Math.max(confidence, 85);
            reason = reason + "; 当前电量 " + pct + "% 过低";
        }
        return new MaintenancePrediction(sysid, ComponentType.BATTERY,
                urgency, failureDate, confidence, reason);
    }

    /** 电机轴承失效预测：基于振动趋势。 */
    MaintenancePrediction predictMotor(int sysid, HealthScore latest, List<HealthScore> history) {
        ComponentScore vibration = latest.getComponentScores().get(ComponentType.VIBRATION);
        double vibG = vibration != null && vibration.getMetrics().containsKey("vibrationG")
                ? vibration.getMetrics().get("vibrationG")
                : 0;

        // 振动趋势：计算历史振动斜率
        double trend = vibrationTrend(history);

        MaintenancePrediction.Urgency urgency;
        LocalDate failureDate;
        int confidence;
        String reason;

        if (vibG >= VIBRATION_CRITICAL) {
            urgency = MaintenancePrediction.Urgency.IMMEDIATE;
            failureDate = LocalDate.now().plusDays(3);
            confidence = 88;
            reason = String.format("振动幅值 %.2fg 超过临界值 %.2fg，轴承可能即将失效", vibG, VIBRATION_CRITICAL);
        } else if (vibG >= VIBRATION_WARN) {
            urgency = MaintenancePrediction.Urgency.WITHIN_7_DAYS;
            int daysLeft = (int) Math.max(1, (VIBRATION_CRITICAL - vibG) * 20);
            failureDate = LocalDate.now().plusDays(Math.min(daysLeft, 7));
            confidence = 70;
            reason = String.format("振动幅值 %.2fg 接近临界值，建议 %d 天内检修轴承", vibG, daysLeft);
        } else if (trend > 0.01) {
            // 振动持续上升
            urgency = MaintenancePrediction.Urgency.WITHIN_30_DAYS;
            failureDate = LocalDate.now().plusDays(20);
            confidence = 55;
            reason = String.format("振动呈上升趋势（斜率 %.4f），关注轴承磨损", trend);
        } else {
            urgency = MaintenancePrediction.Urgency.NORMAL;
            failureDate = LocalDate.now().plusDays(90);
            confidence = 35;
            reason = String.format("振动正常（%.2fg），轴承状态良好", vibG);
        }
        return new MaintenancePrediction(sysid, ComponentType.MOTOR,
                urgency, failureDate, confidence, reason);
    }

    /** 散热系统预测：基于温度趋势。 */
    MaintenancePrediction predictTemperature(int sysid, HealthScore latest, List<HealthScore> history) {
        ComponentScore temp = latest.getComponentScores().get(ComponentType.TEMPERATURE);
        double tempC = temp != null && temp.getMetrics().containsKey("temperatureC")
                ? temp.getMetrics().get("temperatureC")
                : 25;

        double trend = temperatureTrend(history);

        MaintenancePrediction.Urgency urgency;
        LocalDate failureDate;
        int confidence;
        String reason;

        if (tempC >= TEMP_CRITICAL) {
            urgency = MaintenancePrediction.Urgency.IMMEDIATE;
            failureDate = LocalDate.now().plusDays(2);
            confidence = 85;
            reason = String.format("温度 %.1f°C 超过临界值 %.1f°C，散热系统可能故障", tempC, TEMP_CRITICAL);
        } else if (tempC >= TEMP_WARN) {
            urgency = MaintenancePrediction.Urgency.WITHIN_7_DAYS;
            int daysLeft = (int) Math.max(1, (TEMP_CRITICAL - tempC) * 0.5);
            failureDate = LocalDate.now().plusDays(Math.min(daysLeft, 7));
            confidence = 68;
            reason = String.format("温度 %.1f°C 偏高，建议 %d 天内检查散热", tempC, daysLeft);
        } else if (trend > 0.1) {
            urgency = MaintenancePrediction.Urgency.WITHIN_30_DAYS;
            failureDate = LocalDate.now().plusDays(25);
            confidence = 50;
            reason = String.format("温度呈上升趋势（斜率 %.3f），关注散热效率", trend);
        } else {
            urgency = MaintenancePrediction.Urgency.NORMAL;
            failureDate = LocalDate.now().plusDays(120);
            confidence = 30;
            reason = String.format("温度正常（%.1f°C），散热系统良好", tempC);
        }
        return new MaintenancePrediction(sysid, ComponentType.TEMPERATURE,
                urgency, failureDate, confidence, reason);
    }

    /** 通信模块预测：基于 RSSI 趋势。 */
    MaintenancePrediction predictCommunication(int sysid, HealthScore latest, List<HealthScore> history) {
        ComponentScore comm = latest.getComponentScores().get(ComponentType.COMMUNICATION);
        double rssi = comm != null && comm.getMetrics().containsKey("rssiDbm")
                ? comm.getMetrics().get("rssiDbm")
                : -70;

        double trend = rssiTrend(history);

        MaintenancePrediction.Urgency urgency;
        LocalDate failureDate;
        int confidence;
        String reason;

        if (rssi <= RSSI_CRITICAL) {
            urgency = MaintenancePrediction.Urgency.IMMEDIATE;
            failureDate = LocalDate.now().plusDays(1);
            confidence = 82;
            reason = String.format("RSSI %.0fdBm 低于临界值 %.0fdBm，通信模块可能故障", rssi, RSSI_CRITICAL);
        } else if (rssi <= RSSI_WARN) {
            urgency = MaintenancePrediction.Urgency.WITHIN_7_DAYS;
            int daysLeft = (int) Math.max(1, (rssi - RSSI_CRITICAL) * 0.5);
            failureDate = LocalDate.now().plusDays(Math.min(daysLeft, 7));
            confidence = 65;
            reason = String.format("RSSI %.0fdBm 偏弱，建议 %d 天内检查天线/模块", rssi, daysLeft);
        } else if (trend < -0.05) {
            // RSSI 持续下降
            urgency = MaintenancePrediction.Urgency.WITHIN_30_DAYS;
            failureDate = LocalDate.now().plusDays(20);
            confidence = 48;
            reason = String.format("RSSI 呈下降趋势（斜率 %.3f），关注天线/模块老化", trend);
        } else {
            urgency = MaintenancePrediction.Urgency.NORMAL;
            failureDate = LocalDate.now().plusDays(100);
            confidence = 32;
            reason = String.format("通信正常（RSSI %.0fdBm）", rssi);
        }
        return new MaintenancePrediction(sysid, ComponentType.COMMUNICATION,
                urgency, failureDate, confidence, reason);
    }

    // ------------------------------------------------------------------
    // 趋势计算
    // ------------------------------------------------------------------

    /** 振动幅值趋势斜率（每采样点变化量，>0 表示上升）。 */
    private double vibrationTrend(List<HealthScore> history) {
        return metricTrend(history, ComponentType.VIBRATION, "vibrationG");
    }

    /** 温度趋势斜率。 */
    private double temperatureTrend(List<HealthScore> history) {
        return metricTrend(history, ComponentType.TEMPERATURE, "temperatureC");
    }

    /** RSSI 趋势斜率（<0 表示下降）。 */
    private double rssiTrend(List<HealthScore> history) {
        return metricTrend(history, ComponentType.COMMUNICATION, "rssiDbm");
    }

    /**
     * 通用指标趋势斜率计算：取历史首尾均值差 / 采样数。
     */
    private double metricTrend(List<HealthScore> history, ComponentType type, String metricKey) {
        if (history == null || history.size() < 2) {
            return 0;
        }
        List<Double> values = new ArrayList<>();
        for (HealthScore hs : history) {
            ComponentScore cs = hs.getComponentScores().get(type);
            if (cs != null) {
                Map<String, Double> m = cs.getMetrics();
                if (m.containsKey(metricKey)) {
                    values.add(m.get(metricKey));
                }
            }
        }
        if (values.size() < 2) {
            return 0;
        }
        int n = values.size();
        double first = values.get(0);
        double last = values.get(n - 1);
        return (last - first) / (n - 1);
    }
}