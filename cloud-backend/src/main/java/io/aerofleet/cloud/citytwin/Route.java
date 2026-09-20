package io.aerofleet.cloud.citytwin;

/**
 * 疏散路线，用于灾害模拟推演结果中的疏散规划。
 */
public class Route {

    private String id;
    private String name;
    private double startLat;
    private double startLon;
    private double endLat;
    private double endLon;
    private double distanceKm;
    private int estimatedDurationMin;

    public Route() {
    }

    public Route(String id, String name, double startLat, double startLon,
                 double endLat, double endLon, double distanceKm, int estimatedDurationMin) {
        this.id = id;
        this.name = name;
        this.startLat = startLat;
        this.startLon = startLon;
        this.endLat = endLat;
        this.endLon = endLon;
        this.distanceKm = distanceKm;
        this.estimatedDurationMin = estimatedDurationMin;
    }

    public String getId() {
        return id;
    }

    public void setId(String id) {
        this.id = id;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public double getStartLat() {
        return startLat;
    }

    public void setStartLat(double startLat) {
        this.startLat = startLat;
    }

    public double getStartLon() {
        return startLon;
    }

    public void setStartLon(double startLon) {
        this.startLon = startLon;
    }

    public double getEndLat() {
        return endLat;
    }

    public void setEndLat(double endLat) {
        this.endLat = endLat;
    }

    public double getEndLon() {
        return endLon;
    }

    public void setEndLon(double endLon) {
        this.endLon = endLon;
    }

    public double getDistanceKm() {
        return distanceKm;
    }

    public void setDistanceKm(double distanceKm) {
        this.distanceKm = distanceKm;
    }

    public int getEstimatedDurationMin() {
        return estimatedDurationMin;
    }

    public void setEstimatedDurationMin(int estimatedDurationMin) {
        this.estimatedDurationMin = estimatedDurationMin;
    }
}