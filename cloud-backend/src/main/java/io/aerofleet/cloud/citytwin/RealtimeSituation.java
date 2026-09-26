package io.aerofleet.cloud.citytwin;

import java.util.List;

/**
 * 实时态势快照，包含某一时刻的所有动态要素。
 * <p>
 * 用于在城市三维模型上叠加显示无人机、车辆、人员、告警等实时信息。
 */
public class RealtimeSituation {

    private long timestamp;
    private List<DronePosition> drones;
    private List<VehiclePosition> vehicles;
    private List<PersonPosition> personnel;
    private List<AlertMarker> alerts;

    public RealtimeSituation() {
    }

    public RealtimeSituation(long timestamp, List<DronePosition> drones,
                             List<VehiclePosition> vehicles, List<PersonPosition> personnel,
                             List<AlertMarker> alerts) {
        this.timestamp = timestamp;
        this.drones = drones;
        this.vehicles = vehicles;
        this.personnel = personnel;
        this.alerts = alerts;
    }

    public long getTimestamp() {
        return timestamp;
    }

    public void setTimestamp(long timestamp) {
        this.timestamp = timestamp;
    }

    public List<DronePosition> getDrones() {
        return drones;
    }

    public void setDrones(List<DronePosition> drones) {
        this.drones = drones;
    }

    public List<VehiclePosition> getVehicles() {
        return vehicles;
    }

    public void setVehicles(List<VehiclePosition> vehicles) {
        this.vehicles = vehicles;
    }

    public List<PersonPosition> getPersonnel() {
        return personnel;
    }

    public void setPersonnel(List<PersonPosition> personnel) {
        this.personnel = personnel;
    }

    public List<AlertMarker> getAlerts() {
        return alerts;
    }

    public void setAlerts(List<AlertMarker> alerts) {
        this.alerts = alerts;
    }
}