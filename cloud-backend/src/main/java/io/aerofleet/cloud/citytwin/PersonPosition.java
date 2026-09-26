package io.aerofleet.cloud.citytwin;

/**
 * 人员实时位置信息，用于态势叠加显示。
 */
public class PersonPosition {

    private String id;
    private double lat;
    private double lon;
    private String role;
    private String status;

    public PersonPosition() {
    }

    public PersonPosition(String id, double lat, double lon, String role, String status) {
        this.id = id;
        this.lat = lat;
        this.lon = lon;
        this.role = role;
        this.status = status;
    }

    public String getId() {
        return id;
    }

    public void setId(String id) {
        this.id = id;
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

    public String getRole() {
        return role;
    }

    public void setRole(String role) {
        this.role = role;
    }

    public String getStatus() {
        return status;
    }

    public void setStatus(String status) {
        this.status = status;
    }
}