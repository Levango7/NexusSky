package io.aerofleet.cloud.citytwin;

import io.aerofleet.cloud.gateway.DeviceRegistry;
import io.aerofleet.cloud.gateway.DroneSnapshot;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import java.util.concurrent.ConcurrentSkipListMap;

/**
 * 实时态势叠加服务，聚合无人机、车辆、人员、告警等动态要素。
 * <p>
 * 从 DeviceRegistry 获取无人机实时位置，模拟生成车辆/人员/告警标记。
 */
@Service
public class SituationOverlayService {

    private static final Logger log = LoggerFactory.getLogger(SituationOverlayService.class);

    private final DeviceRegistry deviceRegistry;

    // 历史态势记录，用于回放 — 使用 ConcurrentSkipListMap 支持有序查找
    private final ConcurrentSkipListMap<Long, RealtimeSituation> situationHistory = new ConcurrentSkipListMap<>();

    // 历史记录最大保留数量，防止内存泄漏
    private static final int MAX_HISTORY_SIZE = 1000;

    // 模拟城市中心坐标
    private static final double CITY_CENTER_LAT = 39.9042;
    private static final double CITY_CENTER_LON = 116.4074;

    public SituationOverlayService(DeviceRegistry deviceRegistry) {
        this.deviceRegistry = deviceRegistry;
    }

    /**
     * 获取当前态势快照。
     */
    public RealtimeSituation getCurrentSituation() {
        long now = System.currentTimeMillis();
        List<DronePosition> drones = collectDronePositions();
        List<VehiclePosition> vehicles = generateSimulatedVehicles(now);
        List<PersonPosition> personnel = generateSimulatedPersonnel(now);
        List<AlertMarker> alerts = generateSimulatedAlerts(now);

        RealtimeSituation situation = new RealtimeSituation(now, drones, vehicles, personnel, alerts);
        // 处理同毫秒覆盖：如果 key 已存在，递增直到找到空位
        long key = now;
        while (situationHistory.putIfAbsent(key, situation) != null) {
            key++;
        }
        // 同毫秒递增时，同步 situation 内部 timestamp 与最终 key 一致
        if (key != now) {
            situation.setTimestamp(key);
        }
        // 限制历史记录数量，移除最旧的条目
        while (situationHistory.size() > MAX_HISTORY_SIZE) {
            Long oldest = situationHistory.firstKey();
            if (oldest != null) {
                situationHistory.remove(oldest);
            } else {
                break;
            }
        }
        return situation;
    }

    /**
     * 获取历史态势（按时间戳查询）。
     */
    public RealtimeSituation getSituationAt(long timestamp) {
        RealtimeSituation situation = situationHistory.get(timestamp);
        if (situation == null) {
            // 使用 floorKey/ceilingKey 查找最接近的历史记录（O(log n)）
            Long floor = situationHistory.floorKey(timestamp);
            Long ceiling = situationHistory.ceilingKey(timestamp);
            Long closestKey = null;
            if (floor != null && ceiling != null) {
                closestKey = (timestamp - floor <= ceiling - timestamp) ? floor : ceiling;
            } else if (floor != null) {
                closestKey = floor;
            } else if (ceiling != null) {
                closestKey = ceiling;
            }
            if (closestKey != null) {
                situation = situationHistory.get(closestKey);
            }
        }
        if (situation == null) {
            // 无历史数据时返回空态势
            situation = new RealtimeSituation(timestamp, List.of(), List.of(), List.of(), List.of());
        }
        return situation;
    }

    /**
     * 获取所有无人机位置。
     */
    public List<DronePosition> getDronePositions() {
        return collectDronePositions();
    }

    /**
     * 获取所有告警标记。
     */
    public List<AlertMarker> getAlertMarkers() {
        return generateSimulatedAlerts(System.currentTimeMillis());
    }

    /**
     * 获取时间范围内的态势列表。
     */
    public List<RealtimeSituation> getSituationsBetween(long from, long to) {
        return new ArrayList<>(situationHistory.subMap(from, true, to, true).values());
    }

    /**
     * 从 DeviceRegistry 收集无人机实时位置。
     */
    private List<DronePosition> collectDronePositions() {
        List<DronePosition> drones = new ArrayList<>();
        for (DroneSnapshot snapshot : deviceRegistry.all()) {
            if (Double.isNaN(snapshot.lat) || Double.isNaN(snapshot.lon)) {
                continue;
            }
            DronePosition pos = new DronePosition(
                    snapshot.sysid,
                    snapshot.lat,
                    snapshot.lon,
                    Double.isNaN(snapshot.relativeAlt) ? 0 : snapshot.relativeAlt,
                    Double.isNaN(snapshot.heading) ? 0 : snapshot.heading,
                    snapshot.battery,
                    snapshot.mode,
                    snapshot.online ? "ONLINE" : "OFFLINE"
            );
            drones.add(pos);
        }
        return drones;
    }

    /**
     * 模拟生成车辆位置数据。
     */
    private List<VehiclePosition> generateSimulatedVehicles(long timestamp) {
        List<VehiclePosition> vehicles = new ArrayList<>();
        // 基于时间戳生成确定性但变化的车辆位置
        int vehicleCount = 5;
        for (int i = 0; i < vehicleCount; i++) {
            double lat = CITY_CENTER_LAT + Math.sin(timestamp / 10000.0 + i) * 0.01;
            double lon = CITY_CENTER_LON + Math.cos(timestamp / 10000.0 + i) * 0.01;
            double heading = (timestamp / 1000.0 + i * 72) % 360;
            double speed = 20 + Math.sin(timestamp / 5000.0 + i) * 15;
            String type = i % 2 == 0 ? "CAR" : "TRUCK";
            vehicles.add(new VehiclePosition("V" + i, lat, lon, heading, speed, type));
        }
        return vehicles;
    }

    /**
     * 模拟生成人员位置数据。
     */
    private List<PersonPosition> generateSimulatedPersonnel(long timestamp) {
        List<PersonPosition> personnel = new ArrayList<>();
        int personCount = 3;
        String[] roles = {"RESCUE", "MEDIC", "COORDINATOR"};
        for (int i = 0; i < personCount; i++) {
            double lat = CITY_CENTER_LAT + Math.cos(timestamp / 8000.0 + i * 2) * 0.005;
            double lon = CITY_CENTER_LON + Math.sin(timestamp / 8000.0 + i * 2) * 0.005;
            personnel.add(new PersonPosition("P" + i, lat, lon, roles[i], "ACTIVE"));
        }
        return personnel;
    }

    /**
     * 模拟生成告警标记。
     */
    private List<AlertMarker> generateSimulatedAlerts(long timestamp) {
        List<AlertMarker> alerts = new ArrayList<>();
        AlertMarker.AlertType[] types = AlertMarker.AlertType.values();
        // 生成 2-3 个告警
        int alertCount = 2 + (int) (timestamp / 60000 % 2);
        for (int i = 0; i < alertCount && i < types.length; i++) {
            AlertMarker.AlertType type = types[i];
            double lat = CITY_CENTER_LAT + Math.sin(i * 1.5) * 0.02;
            double lon = CITY_CENTER_LON + Math.cos(i * 1.5) * 0.02;
            String severity = i == 0 ? "HIGH" : "MEDIUM";
            String description = describeAlert(type);
            alerts.add(new AlertMarker(
                    UUID.randomUUID().toString(),
                    type,
                    lat,
                    lon,
                    severity,
                    description,
                    timestamp
            ));
        }
        return alerts;
    }

    private String describeAlert(AlertMarker.AlertType type) {
        switch (type) {
            case FIRE:
                return "Building fire detected in sector A";
            case FLOOD:
                return "Water level rising in district B";
            case STRUCTURE:
                return "Structural damage reported at bridge C";
            case GAS_LEAK:
                return "Gas leak detected in zone D";
            case CROWD:
                return "Crowd density exceeding threshold at plaza E";
            default:
                return "Unknown alert";
        }
    }
}