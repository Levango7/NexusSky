package io.aerofleet.cloud.citytwin;

import java.util.List;

/**
 * 态势标绘实体，用于在地图上绘制点、线、面、圆、文本等标记。
 */
public class SituationMarker {

    public enum MarkerType {
        POINT,
        LINE,
        POLYGON,
        CIRCLE,
        TEXT
    }

    private String id;
    private MarkerType type;
    private List<double[]> coordinates;
    private String label;
    private String color;
    private String icon;
    private String createdBy;
    private long timestamp;

    public SituationMarker() {
    }

    public SituationMarker(String id, MarkerType type, List<double[]> coordinates,
                           String label, String color, String icon, String createdBy, long timestamp) {
        this.id = id;
        this.type = type;
        this.coordinates = coordinates;
        this.label = label;
        this.color = color;
        this.icon = icon;
        this.createdBy = createdBy;
        this.timestamp = timestamp;
    }

    public String getId() {
        return id;
    }

    public void setId(String id) {
        this.id = id;
    }

    public MarkerType getType() {
        return type;
    }

    public void setType(MarkerType type) {
        this.type = type;
    }

    public List<double[]> getCoordinates() {
        return coordinates;
    }

    public void setCoordinates(List<double[]> coordinates) {
        this.coordinates = coordinates;
    }

    public String getLabel() {
        return label;
    }

    public void setLabel(String label) {
        this.label = label;
    }

    public String getColor() {
        return color;
    }

    public void setColor(String color) {
        this.color = color;
    }

    public String getIcon() {
        return icon;
    }

    public void setIcon(String icon) {
        this.icon = icon;
    }

    public String getCreatedBy() {
        return createdBy;
    }

    public void setCreatedBy(String createdBy) {
        this.createdBy = createdBy;
    }

    public long getTimestamp() {
        return timestamp;
    }

    public void setTimestamp(long timestamp) {
        this.timestamp = timestamp;
    }
}