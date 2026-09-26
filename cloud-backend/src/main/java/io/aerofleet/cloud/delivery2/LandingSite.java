package io.aerofleet.cloud.delivery2;

/**
 * 降落点模型。
 * <p>
 * 描述候选降落/空投位置的物理属性与可访问性。
 */
public class LandingSite {

    /** 地面类型枚举。 */
    public enum SurfaceType {
        FLAT, GRASS, CONCRETE, ROOF, WATER
    }

    /** 可访问性枚举。 */
    public enum Accessibility {
        OPEN, RESTRICTED, NO_ACCESS
    }

    private String id;
    private double lat;
    private double lon;
    private double radiusM;
    private SurfaceType surfaceType;
    private double clearanceM;
    private double slopeDeg;
    private Accessibility accessibility;
    private boolean verified;

    public LandingSite() {
    }

    public LandingSite(String id, double lat, double lon, double radiusM,
                       SurfaceType surfaceType, double clearanceM, double slopeDeg,
                       Accessibility accessibility, boolean verified) {
        this.id = id;
        this.lat = lat;
        this.lon = lon;
        this.radiusM = radiusM;
        this.surfaceType = surfaceType;
        this.clearanceM = clearanceM;
        this.slopeDeg = slopeDeg;
        this.accessibility = accessibility;
        this.verified = verified;
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

    public double getRadiusM() {
        return radiusM;
    }

    public void setRadiusM(double radiusM) {
        this.radiusM = radiusM;
    }

    public SurfaceType getSurfaceType() {
        return surfaceType;
    }

    public void setSurfaceType(SurfaceType surfaceType) {
        this.surfaceType = surfaceType;
    }

    public double getClearanceM() {
        return clearanceM;
    }

    public void setClearanceM(double clearanceM) {
        this.clearanceM = clearanceM;
    }

    public double getSlopeDeg() {
        return slopeDeg;
    }

    public void setSlopeDeg(double slopeDeg) {
        this.slopeDeg = slopeDeg;
    }

    public Accessibility getAccessibility() {
        return accessibility;
    }

    public void setAccessibility(Accessibility accessibility) {
        this.accessibility = accessibility;
    }

    public boolean isVerified() {
        return verified;
    }

    public void setVerified(boolean verified) {
        this.verified = verified;
    }
}