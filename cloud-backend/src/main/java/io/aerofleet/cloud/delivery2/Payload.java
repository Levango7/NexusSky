package io.aerofleet.cloud.delivery2;

/**
 * 负载（货物）信息模型。
 * <p>
 * 描述无人机配送货物的物理属性与存储条件。
 */
public class Payload {

    /** 负载类型枚举。 */
    public enum Type {
        MEDICINE, FOOD, WATER, EQUIPMENT, DOCUMENT, BLOOD_SAMPLE
    }

    /** 温度范围枚举。 */
    public enum TemperatureRange {
        AMBIENT, RANGE_2_8C, FROZEN
    }

    private String id;
    private double weightKg;
    private double volumeM3;
    private Type type;
    private TemperatureRange temperatureRange;
    private boolean fragile;
    private String description;

    public Payload() {
    }

    public Payload(String id, double weightKg, double volumeM3, Type type,
                   TemperatureRange temperatureRange, boolean fragile, String description) {
        this.id = id;
        this.weightKg = weightKg;
        this.volumeM3 = volumeM3;
        this.type = type;
        this.temperatureRange = temperatureRange;
        this.fragile = fragile;
        this.description = description;
    }

    public String getId() {
        return id;
    }

    public void setId(String id) {
        this.id = id;
    }

    public double getWeightKg() {
        return weightKg;
    }

    public void setWeightKg(double weightKg) {
        this.weightKg = weightKg;
    }

    public double getVolumeM3() {
        return volumeM3;
    }

    public void setVolumeM3(double volumeM3) {
        this.volumeM3 = volumeM3;
    }

    public Type getType() {
        return type;
    }

    public void setType(Type type) {
        this.type = type;
    }

    public TemperatureRange getTemperatureRange() {
        return temperatureRange;
    }

    public void setTemperatureRange(TemperatureRange temperatureRange) {
        this.temperatureRange = temperatureRange;
    }

    public boolean isFragile() {
        return fragile;
    }

    public void setFragile(boolean fragile) {
        this.fragile = fragile;
    }

    public String getDescription() {
        return description;
    }

    public void setDescription(String description) {
        this.description = description;
    }
}