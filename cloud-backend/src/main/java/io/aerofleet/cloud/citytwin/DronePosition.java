package io.aerofleet.cloud.citytwin;

/**
 * 无人机实时位置信息，用于态势叠加显示。
 */
public class DronePosition {

    private int sysid;
    private double lat;
    private double lon;
    private double alt;
    private double heading;
    private int battery;
    private String mode;
    private String status;

    public DronePosition() {
    }

    public DronePosition(int sysid, double lat, double lon, double alt,
                         double heading, int battery, String mode, String status) {
        this.sysid = sysid;
        this.lat = lat;
        this.lon = lon;
        this.alt = alt;
        this.heading = heading;
        this.battery = battery;
        this.mode = mode;
        this.status = status;
    }

    public int getSysid() {
        return sysid;
    }

    public void setSysid(int sysid) {
        this.sysid = sysid;
    }

    public double getLat() {
        return lat;
    }

    public void setLat(double lat) {
        this.lat = lat;
    }

    public double getLon() {
        return lon;
    }

    public void setLon(double lon) {
        this.lon = lon;
    }

    public double getAlt() {
        return alt;
    }

    public void setAlt(double alt) {
        this.alt = alt;
    }

    public double getHeading() {
        return heading;
    }

    public void setHeading(double heading) {
        this.heading = heading;
    }

    public int getBattery() {
        return battery;
    }

    public void setBattery(int battery) {
        this.battery = battery;
    }

    public String getMode() {
        return mode;
    }

    public void setMode(String mode) {
        this.mode = mode;
    }

    public String getStatus() {
        return status;
    }

    public void setStatus(String status) {
        this.status = status;
    }
}