package io.aerofleet.cloud.delivery2;

/**
 * 路线航点模型。
 * <p>
 * 描述配送路线中的每个航点，包含位置、动作类型和停留时间。
 */
public class RouteWaypoint {

    /** 航点动作类型。 */
    public enum Action {
        PICKUP, DELIVER, HOVER, FLY
    }

    private int seq;
    private double lat;
    private double lon;
    private Action action;
    private double holdTimeSec;
    private String taskId;

    public RouteWaypoint() {
    }

    public RouteWaypoint(int seq, double lat, double lon, Action action,
                         double holdTimeSec, String taskId) {
        this.seq = seq;
        this.lat = lat;
        this.lon = lon;
        this.action = action;
        this.holdTimeSec = holdTimeSec;
        this.taskId = taskId;
    }

    public int getSeq() {
        return seq;
    }

    public void setSeq(int seq) {
        this.seq = seq;
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

    public Action getAction() {
        return action;
    }

    public void setAction(Action action) {
        this.action = action;
    }

    public double getHoldTimeSec() {
        return holdTimeSec;
    }

    public void setHoldTimeSec(double holdTimeSec) {
        this.holdTimeSec = holdTimeSec;
    }

    public String getTaskId() {
        return taskId;
    }

    public void setTaskId(String taskId) {
        this.taskId = taskId;
    }
}