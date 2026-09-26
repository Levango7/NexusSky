package io.aerofleet.cloud.citytwin;

import java.util.List;
import java.util.Map;

/**
 * 灾害模拟推演实体，描述一次完整的灾害模拟过程及其结果。
 */
public class DisasterSimulation {

    public enum DisasterType {
        FLOOD,
        FIRE,
        EARTHQUAKE,
        GAS_LEAK,
        EVACUATION
    }

    public enum SimStatus {
        RUNNING,
        COMPLETED,
        FAILED
    }

    private String id;
    private DisasterType type;
    private SimArea area;
    private long startTime;
    private int durationMin;
    private SimStatus status;
    private Map<String, Double> parameters;
    private List<String> affectedZones;
    private SimResult result;

    public DisasterSimulation() {
    }

    public DisasterSimulation(String id, DisasterType type, SimArea area, long startTime,
                               int durationMin, SimStatus status, Map<String, Double> parameters,
                               List<String> affectedZones, SimResult result) {
        this.id = id;
        this.type = type;
        this.area = area;
        this.startTime = startTime;
        this.durationMin = durationMin;
        this.status = status;
        this.parameters = parameters;
        this.affectedZones = affectedZones;
        this.result = result;
    }

    public String getId() {
        return id;
    }

    public void setId(String id) {
        this.id = id;
    }

    public DisasterType getType() {
        return type;
    }

    public void setType(DisasterType type) {
        this.type = type;
    }

    public SimArea getArea() {
        return area;
    }

    public void setArea(SimArea area) {
        this.area = area;
    }

    public long getStartTime() {
        return startTime;
    }

    public void setStartTime(long startTime) {
        this.startTime = startTime;
    }

    public int getDurationMin() {
        return durationMin;
    }

    public void setDurationMin(int durationMin) {
        this.durationMin = durationMin;
    }

    public SimStatus getStatus() {
        return status;
    }

    public void setStatus(SimStatus status) {
        this.status = status;
    }

    public Map<String, Double> getParameters() {
        return parameters;
    }

    public void setParameters(Map<String, Double> parameters) {
        this.parameters = parameters;
    }

    public List<String> getAffectedZones() {
        return affectedZones;
    }

    public void setAffectedZones(List<String> affectedZones) {
        this.affectedZones = affectedZones;
    }

    public SimResult getResult() {
        return result;
    }

    public void setResult(SimResult result) {
        this.result = result;
    }
}