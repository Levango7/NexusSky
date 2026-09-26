package io.aerofleet.cloud.citytwin;

/**
 * 告警标记，用于在态势地图上标注灾害或异常事件。
 */
public class AlertMarker {

    public enum AlertType {
        FIRE,
        FLOOD,
        STRUCTURE,
        GAS_LEAK,
        CROWD
    }

    private String id;
    private AlertType type;
    private double lat;
    private double lon;
    private String severity;
    private String description;
    private long timestamp;

    public AlertMarker() {
    }

    public AlertMarker(String id, AlertType type, double lat, double lon,
                       String severity, String description, long timestamp) {
        this.id = id;
        this.type = type;
        this.lat = lat;
        this.lon = lon;
        this.severity = severity;
        this.description = description;
        this.timestamp = timestamp;
    }

    public String getId() {
        return id;
    }

    public void setId(String id) {
        this.id = id;
    }

    public AlertType getType() {
        return type;
    }

    public void setType(AlertType type) {
        this.type = type;
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

    public String getSeverity() {
        return severity;
    }

    public void setSeverity(String severity) {
        this.severity = severity;
    }

    public String getDescription() {
        return description;
    }

    public void setDescription(String description) {
        this.description = description;
    }

    public long getTimestamp() {
        return timestamp;
    }

    public void setTimestamp(long timestamp) {
        this.timestamp = timestamp;
    }
}