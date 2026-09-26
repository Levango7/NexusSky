package io.aerofleet.cloud.citytwin;

/**
 * 车辆实时位置信息，用于态势叠加显示。
 */
public class VehiclePosition {

    private String id;
    private double lat;
    private double lon;
    private double heading;
    private double speed;
    private String type;

    public VehiclePosition() {
    }

    public VehiclePosition(String id, double lat, double lon, double heading,
                           double speed, String type) {
        this.id = id;
        this.lat = lat;
        this.lon = lon;
        this.heading = heading;
        this.speed = speed;
        this.type = type;
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

    public double getHeading() {
        return heading;
    }

    public void setHeading(double heading) {
        this.heading = heading;
    }

    public double getSpeed() {
        return speed;
    }

    public void setSpeed(double speed) {
        this.speed = speed;
    }

    public String getType() {
        return type;
    }

    public void setType(String type) {
        this.type = type;
    }
}