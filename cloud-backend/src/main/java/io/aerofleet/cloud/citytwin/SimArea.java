package io.aerofleet.cloud.citytwin;

/**
 * 模拟区域定义，用于灾害模拟推演。
 */
public class SimArea {

    private double centerLat;
    private double centerLon;
    private double radiusKm;

    public SimArea() {
    }

    public SimArea(double centerLat, double centerLon, double radiusKm) {
        this.centerLat = centerLat;
        this.centerLon = centerLon;
        this.radiusKm = radiusKm;
    }

    public double getCenterLat() {
        return centerLat;
    }

    public void setCenterLat(double centerLat) {
        this.centerLat = centerLat;
    }

    public double getCenterLon() {
        return centerLon;
    }

    public void setCenterLon(double centerLon) {
        this.centerLon = centerLon;
    }

    public double getRadiusKm() {
        return radiusKm;
    }

    public void setRadiusKm(double radiusKm) {
        this.radiusKm = radiusKm;
    }
}