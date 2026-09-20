package io.aerofleet.cloud.citytwin;

/**
 * 城市三维模型实体。
 * <p>
 * 描述一个城市区域的三维模型数据，支持 OSGB、3DTILES、OBJ、GLTF 等格式。
 * 用于数字孪生城市建模与可视化。
 */
public class CityModel {

    public enum ModelType {
        OSGB,
        TILES_3D,
        OBJ,
        GLTF
    }

    public enum ModelStatus {
        LOADED,
        PROCESSING,
        FAILED
    }

    private String id;
    private String name;
    private String version;
    private String description;
    private ModelType modelType;
    private double coverageAreaKm2;
    private double resolutionCm;
    private String sourceUrl;
    private long loadedAt;
    private long lastUpdatedAt;
    private ModelStatus status;

    public CityModel() {
    }

    public CityModel(String id, String name, String version, String description,
                     ModelType modelType, double coverageAreaKm2, double resolutionCm,
                     String sourceUrl, long loadedAt, long lastUpdatedAt, ModelStatus status) {
        this.id = id;
        this.name = name;
        this.version = version;
        this.description = description;
        this.modelType = modelType;
        this.coverageAreaKm2 = coverageAreaKm2;
        this.resolutionCm = resolutionCm;
        this.sourceUrl = sourceUrl;
        this.loadedAt = loadedAt;
        this.lastUpdatedAt = lastUpdatedAt;
        this.status = status;
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

    public String getVersion() {
        return version;
    }

    public void setVersion(String version) {
        this.version = version;
    }

    public String getDescription() {
        return description;
    }

    public void setDescription(String description) {
        this.description = description;
    }

    public ModelType getModelType() {
        return modelType;
    }

    public void setModelType(ModelType modelType) {
        this.modelType = modelType;
    }

    public double getCoverageAreaKm2() {
        return coverageAreaKm2;
    }

    public void setCoverageAreaKm2(double coverageAreaKm2) {
        this.coverageAreaKm2 = coverageAreaKm2;
    }

    public double getResolutionCm() {
        return resolutionCm;
    }

    public void setResolutionCm(double resolutionCm) {
        this.resolutionCm = resolutionCm;
    }

    public String getSourceUrl() {
        return sourceUrl;
    }

    public void setSourceUrl(String sourceUrl) {
        this.sourceUrl = sourceUrl;
    }

    public long getLoadedAt() {
        return loadedAt;
    }

    public void setLoadedAt(long loadedAt) {
        this.loadedAt = loadedAt;
    }

    public long getLastUpdatedAt() {
        return lastUpdatedAt;
    }

    public void setLastUpdatedAt(long lastUpdatedAt) {
        this.lastUpdatedAt = lastUpdatedAt;
    }

    public ModelStatus getStatus() {
        return status;
    }

    public void setStatus(ModelStatus status) {
        this.status = status;
    }
}