package io.aerofleet.cloud.health;

import io.aerofleet.cloud.gateway.BoundedHistory;
import io.aerofleet.cloud.gateway.DeviceRegistry;
import io.aerofleet.cloud.gateway.DroneSnapshot;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 无人机健康监控服务（P1-2 健康管理与预测性维护）。
 * <p>
 * 基于遥测数据计算各部件健康评分，汇总为总体分数与等级（A/B/C/D/F），
 * 并按 {@code @Scheduled} 周期性扫描所有在线无人机，缓存最新评分与历史轨迹。
 * <p>
 * 评分规则（0-100）：
 * <ul>
 *   <li>BATTERY: 电量>50%=A 区, 20-50%=C 区, <20%=F 区</li>
 *   <li>MOTOR: 转速标准差越小越好（<50=A, 50-150=C, >150=F）</li>
 *   <li>VIBRATION: 振动幅值 <0.5g=A, 0.5-1.0g=C, >1.0g=F</li>
 *   <li>TEMPERATURE: 温度 <60°C=A, 60-80°C=C, >80°C=F</li>
 *   <li>COMMUNICATION: RSSI >-60=A, -60~-80=C, <-80=F</li>
 *   <li>IMU: 陀螺仪漂移 <0.5=A, 0.5-2.0=C, >2.0=F</li>
 *   <li>GPS: 卫星数>=10 且 HDOP<1.0=A, 6-9 卫星或 HDOP 1.0-2.0=C, 否则 F</li>
 * </ul>
 */
@Component
public class HealthMonitorService {

    private static final Logger log = LoggerFactory.getLogger(HealthMonitorService.class);

    /** 健康评分历史保留条数（每机）。 */
    private static final int HISTORY_CAPACITY = 200;

    private final DeviceRegistry registry;

    /** 最新健康评分：sysid -> HealthScore。 */
    private final Map<Integer, HealthScore> latestScores = new ConcurrentHashMap<>();

    /** 健康评分历史：sysid -> BoundedHistory<HealthScore>。 */
    private final Map<Integer, BoundedHistory<HealthScore>> histories = new ConcurrentHashMap<>();

    public HealthMonitorService(DeviceRegistry registry) {
        this.registry = registry;
    }

    // ------------------------------------------------------------------
    // 评分计算
    // ------------------------------------------------------------------

    /**
     * 基于遥测快照计算单机健康评分。
     *
     * @param sysid     无人机 systemId
     * @param telemetry 遥测快照
     * @return 健康评分（含各部件评分与总体分数/等级）
     */
    public HealthScore calculateScore(int sysid, TelemetrySnapshot telemetry) {
        Map<ComponentType, ComponentScore> components = new LinkedHashMap<>();
        components.put(ComponentType.BATTERY, scoreBattery(telemetry));
        components.put(ComponentType.MOTOR, scoreMotor(telemetry));
        components.put(ComponentType.VIBRATION, scoreVibration(telemetry));
        components.put(ComponentType.TEMPERATURE, scoreTemperature(telemetry));
        components.put(ComponentType.COMMUNICATION, scoreCommunication(telemetry));
        components.put(ComponentType.IMU, scoreImu(telemetry));
        components.put(ComponentType.GPS, scoreGps(telemetry));

        int overall = computeOverall(components);
        long ts = telemetry.getTimestamp() > 0
                ? telemetry.getTimestamp()
                : System.currentTimeMillis();
        return new HealthScore(sysid, ts, overall, HealthScore.gradeOf(overall), components);
    }

    /** 总体分数 = 各部件分数加权平均（等权）。 */
    private int computeOverall(Map<ComponentType, ComponentScore> components) {
        int sum = 0;
        for (ComponentScore c : components.values()) {
            sum += c.getScore();
        }
        return components.isEmpty() ? 0 : sum / components.size();
    }

    // ------------------------------------------------------------------
    // 各部件评分
    // ------------------------------------------------------------------

    /** 电池评分：电量>50%=A 区(90-100), 20-50%=C 区(60-79), <20%=F 区(0-59)。 */
    ComponentScore scoreBattery(TelemetrySnapshot t) {
        double pct = t.getBatteryPct();
        Map<String, Double> metrics = new LinkedHashMap<>();
        metrics.put("batteryPct", pct);
        metrics.put("batteryCycles", (double) t.getBatteryCycles());

        int score;
        String recommendation = null;
        if (pct < 0) {
            score = 50;
            recommendation = "电池数据缺失，请检查遥测链路";
        } else if (pct > 50) {
            // 50->90, 100->100
            score = (int) Math.round(90 + (pct - 50) * 0.2);
        } else if (pct >= 20) {
            // 20->60, 50->79
            score = (int) Math.round(60 + (pct - 20) * (19.0 / 30.0));
        } else {
            // 0->0, 20->59
            score = (int) Math.round(pct * (59.0 / 20.0));
            recommendation = "电量过低，立即返航充电";
        }
        if (t.getBatteryCycles() > 300) {
            recommendation = "电池循环次数超 300，建议更换";
            score = Math.min(score, 60);
        }
        return new ComponentScore(ComponentType.BATTERY, score,
                ComponentScore.statusOf(score), metrics, recommendation);
    }

    /** 电机评分：转速标准差越小越好（<50=A, 50-150=C, >150=F）。 */
    ComponentScore scoreMotor(TelemetrySnapshot t) {
        List<Double> rpms = t.getMotorRpms();
        Map<String, Double> metrics = new LinkedHashMap<>();
        double stddev = rpmStddev(rpms);
        metrics.put("rpmStddev", stddev);
        if (!rpms.isEmpty()) {
            metrics.put("rpmMean", rpmMean(rpms));
        }

        int score;
        String recommendation = null;
        if (rpms.isEmpty()) {
            score = 70;
            recommendation = "电机转速数据缺失";
        } else if (stddev < 50) {
            // 0->100, 50->90
            score = (int) Math.round(100 - stddev * 0.2);
        } else if (stddev < 150) {
            // 50->79, 150->60
            score = (int) Math.round(79 - (stddev - 50) * (19.0 / 100.0));
        } else {
            // 150->59, 300->0
            score = (int) Math.max(0, Math.round(59 - (stddev - 150) * (59.0 / 150.0)));
            recommendation = "转速波动过大，检查电机/桨叶";
        }
        return new ComponentScore(ComponentType.MOTOR, score,
                ComponentScore.statusOf(score), metrics, recommendation);
    }

    /** 振动评分：幅值 <0.5g=A, 0.5-1.0g=C, >1.0g=F。 */
    ComponentScore scoreVibration(TelemetrySnapshot t) {
        double g = t.getVibrationG();
        Map<String, Double> metrics = new LinkedHashMap<>();
        metrics.put("vibrationG", g);

        int score;
        String recommendation = null;
        if (g < 0.5) {
            // 0->100, 0.5->90
            score = (int) Math.round(100 - g * 20);
        } else if (g <= 1.0) {
            // 0.5->79, 1.0->60
            score = (int) Math.round(79 - (g - 0.5) * 38);
        } else {
            // 1.0->59, 2.0->0
            score = (int) Math.max(0, Math.round(59 - (g - 1.0) * 59));
            recommendation = "振动幅值过高，检查轴承/平衡";
        }
        return new ComponentScore(ComponentType.VIBRATION, score,
                ComponentScore.statusOf(score), metrics, recommendation);
    }

    /** 温度评分：<60°C=A, 60-80°C=C, >80°C=F。 */
    ComponentScore scoreTemperature(TelemetrySnapshot t) {
        double c = t.getTemperatureC();
        Map<String, Double> metrics = new LinkedHashMap<>();
        metrics.put("temperatureC", c);

        int score;
        String recommendation = null;
        if (c < 60) {
            // 25->100, 60->90
            score = (int) Math.min(100, Math.round(100 - (c - 25) * 0.285));
        } else if (c <= 80) {
            // 60->79, 80->60
            score = (int) Math.round(79 - (c - 60) * 0.95);
        } else {
            // 80->59, 100->0
            score = (int) Math.max(0, Math.round(59 - (c - 80) * 2.95));
            recommendation = "温度过高，检查散热系统";
        }
        return new ComponentScore(ComponentType.TEMPERATURE, score,
                ComponentScore.statusOf(score), metrics, recommendation);
    }

    /** 通信评分：RSSI >-60=A, -60~-80=C, <-80=F。 */
    ComponentScore scoreCommunication(TelemetrySnapshot t) {
        double rssi = t.getRssiDbm();
        Map<String, Double> metrics = new LinkedHashMap<>();
        metrics.put("rssiDbm", rssi);

        int score;
        String recommendation = null;
        if (rssi > -60) {
            // -60->90, -40->95, -20->100（rssi 越接近 0 越好）
            score = (int) Math.min(100, Math.round(90 + (rssi + 60) * 0.25));
        } else if (rssi >= -80) {
            // -60->79, -80->60
            score = (int) Math.round(79 - (-60 - rssi) * 0.95);
        } else {
            // -80->59, -100->0
            score = (int) Math.max(0, Math.round(59 - (-80 - rssi) * 2.95));
            recommendation = "信号弱，检查天线/通信模块";
        }
        return new ComponentScore(ComponentType.COMMUNICATION, score,
                ComponentScore.statusOf(score), metrics, recommendation);
    }

    /** IMU 评分：陀螺仪漂移 <0.5=A, 0.5-2.0=C, >2.0=F。 */
    ComponentScore scoreImu(TelemetrySnapshot t) {
        double drift = t.getImuDrift();
        Map<String, Double> metrics = new LinkedHashMap<>();
        metrics.put("imuDrift", drift);

        int score;
        String recommendation = null;
        if (drift < 0.5) {
            // 0->100, 0.5->90
            score = (int) Math.round(100 - drift * 20);
        } else if (drift <= 2.0) {
            // 0.5->79, 2.0->60
            score = (int) Math.round(79 - (drift - 0.5) * (19.0 / 1.5));
        } else {
            // 2.0->59, 4.0->0
            score = (int) Math.max(0, Math.round(59 - (drift - 2.0) * 29.5));
            recommendation = "IMU 漂移过大，需重新校准";
        }
        return new ComponentScore(ComponentType.IMU, score,
                ComponentScore.statusOf(score), metrics, recommendation);
    }

    /** GPS 评分：卫星数>=10 且 HDOP<1.0=A, 6-9 卫星或 HDOP 1.0-2.0=C, 否则 F。 */
    ComponentScore scoreGps(TelemetrySnapshot t) {
        int sats = t.getGpsSatellites();
        double hdop = t.getGpsHdop();
        Map<String, Double> metrics = new LinkedHashMap<>();
        metrics.put("gpsSatellites", (double) sats);
        metrics.put("gpsHdop", hdop);

        int score;
        String recommendation = null;
        if (sats >= 10 && hdop < 1.0) {
            // 卫星越多、HDOP 越小分数越高
            score = (int) Math.min(100, Math.round(90 + (sats - 10) + (1.0 - hdop) * 5));
        } else if (sats >= 6 && hdop <= 2.0) {
            // 6 卫星/HDOP 2.0 -> 60, 9 卫星/HDOP 1.0 -> 79
            int satPart = (sats - 6) * 4;
            int hdopPart = (int) Math.round((2.0 - hdop) * 11);
            score = Math.min(79, 60 + satPart + hdopPart);
        } else {
            score = Math.max(0, sats < 6 ? sats * 8 : 30);
            recommendation = "GPS 定位差，检查天线/开阔度";
        }
        return new ComponentScore(ComponentType.GPS, score,
                ComponentScore.statusOf(score), metrics, recommendation);
    }

    // ------------------------------------------------------------------
    // 统计辅助
    // ------------------------------------------------------------------

    private double rpmMean(List<Double> rpms) {
        if (rpms.isEmpty()) {
            return 0;
        }
        double sum = 0;
        for (double r : rpms) {
            sum += r;
        }
        return sum / rpms.size();
    }

    private double rpmStddev(List<Double> rpms) {
        if (rpms.size() < 2) {
            return 0;
        }
        double mean = rpmMean(rpms);
        double sumSq = 0;
        for (double r : rpms) {
            sumSq += (r - mean) * (r - mean);
        }
        return Math.sqrt(sumSq / rpms.size());
    }

    // ------------------------------------------------------------------
    // DroneSnapshot -> TelemetrySnapshot 转换
    // ------------------------------------------------------------------

    /**
     * 从 {@link DroneSnapshot} 提取遥测指标构造 {@link TelemetrySnapshot}。
     *
     * @param d 无人机快照
     * @return 健康监控用遥测快照
     */
    public TelemetrySnapshot toTelemetry(DroneSnapshot d) {
        TelemetrySnapshot t = new TelemetrySnapshot(System.currentTimeMillis());
        t.setBatteryPct(d.battery);
        t.setTemperatureC(Double.isNaN(d.envTemperature) ? 25 : d.envTemperature);
        t.setRssiDbm(Double.isNaN(d.rssiDbm) ? -70 : d.rssiDbm);
        t.setGpsSatellites(d.satellites);
        t.setGpsHdop(d.eph < 0 ? 99 : d.eph / 100.0);
        // IMU 漂移由 roll/pitch/yaw 抖动近似（无专用字段时置 0）
        t.setImuDrift(0);
        // 电机转速/振动无直接字段，留空由调用方补充
        return t;
    }

    // ------------------------------------------------------------------
    // 周期监控
    // ------------------------------------------------------------------

    /**
     * 周期性计算所有在线无人机的健康评分（默认 5 秒一次）。
     * <p>
     * 结果写入 {@link #latestScores} 与 {@link #histories}。
     */
    @Scheduled(fixedDelayString = "${aerofleet.health.check-interval-ms:5000}")
    public void monitorFleet() {
        try {
            for (DroneSnapshot d : registry.all()) {
                if (!d.online) {
                    continue;
                }
                HealthScore score = calculateScore(d.sysid, toTelemetry(d));
                updateScore(score);
            }
        } catch (RuntimeException e) {
            log.warn("Health monitor sweep failed: {}", e.getMessage());
        }
    }

    /** 更新最新评分与历史轨迹。 */
    public void updateScore(HealthScore score) {
        latestScores.put(score.getSysid(), score);
        histories.computeIfAbsent(score.getSysid(),
                k -> new BoundedHistory<>(HISTORY_CAPACITY)).add(score);
    }

    // ------------------------------------------------------------------
    // 查询接口
    // ------------------------------------------------------------------

    /** 获取单机最新健康评分（无数据返回 null）。 */
    public HealthScore getLatest(int sysid) {
        return latestScores.get(sysid);
    }

    /** 获取单机健康评分历史（按时间升序，无数据返回空列表）。 */
    public List<HealthScore> getHistory(int sysid) {
        BoundedHistory<HealthScore> h = histories.get(sysid);
        return h == null ? new ArrayList<>() : h.toList();
    }

    /** 获取所有已评分无人机的最新评分列表。 */
    public List<HealthScore> getFleetScores() {
        return new ArrayList<>(latestScores.values());
    }

    /** 获取所有健康告警（状态为 WARNING 或 CRITICAL 的部件）。 */
    public List<ComponentScore> getWarnings() {
        List<ComponentScore> warnings = new ArrayList<>();
        for (HealthScore hs : latestScores.values()) {
            for (ComponentScore cs : hs.getComponentScores().values()) {
                if (cs.getStatus() != ComponentScore.Status.HEALTHY) {
                    warnings.add(cs);
                }
            }
        }
        return warnings;
    }

    /** 获取单部件最新评分（无数据返回 null）。 */
    public ComponentScore getComponent(int sysid, ComponentType type) {
        HealthScore hs = latestScores.get(sysid);
        return hs == null ? null : hs.getComponentScores().get(type);
    }
}