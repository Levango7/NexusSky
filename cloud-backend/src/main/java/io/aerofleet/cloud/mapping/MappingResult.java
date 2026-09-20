package io.aerofleet.cloud.mapping;

import java.time.Instant;

/**
 * 测绘成果（航拍测绘生成的最终产出物）。
 * <p>
 * 包含正射影像、DEM、三维模型的下载链接与质量元数据。
 */
public final class MappingResult {

    /** 成果状态枚举。 */
    public enum Status {
        /** 正在处理。 */
        PROCESSING,
        /** 已完成。 */
        COMPLETED,
        /** 处理失败。 */
        FAILED
    }

    /** 成果 ID。 */
    private String id;
    /** 所属测绘任务 ID。 */
    private String taskId;
    /** 测绘类型。 */
    private MappingType type;
    /** 成果状态。 */
    private Status status;
    /** 生成时间。 */
    private Instant generatedAt;
    /** 正射影像下载 URL。 */
    private String orthophotoUrl;
    /** DEM 下载 URL。 */
    private String demUrl;
    /** 三维模型下载 URL。 */
    private String modelUrl;
    /** 覆盖率（%）。 */
    private double coveragePct;
    /** 分辨率（cm/pixel）。 */
    private double resolutionCm;
    /** 处理耗时（秒）。 */
    private long processingTimeSec;
    /** 文件大小（MB）。 */
    private double fileSizeMB;

    public MappingResult() {
    }

    public MappingResult(String id, String taskId, MappingType type, Status status,
                         Instant generatedAt, String orthophotoUrl, String demUrl,
                         String modelUrl, double coveragePct, double resolutionCm,
                         long processingTimeSec, double fileSizeMB) {
        this.id = id;
        this.taskId = taskId;
        this.type = type;
        this.status = status;
        this.generatedAt = generatedAt;
        this.orthophotoUrl = orthophotoUrl;
        this.demUrl = demUrl;
        this.modelUrl = modelUrl;
        this.coveragePct = coveragePct;
        this.resolutionCm = resolutionCm;
        this.processingTimeSec = processingTimeSec;
        this.fileSizeMB = fileSizeMB;
    }

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }

    public String getTaskId() { return taskId; }
    public void setTaskId(String taskId) { this.taskId = taskId; }

    public MappingType getType() { return type; }
    public void setType(MappingType type) { this.type = type; }

    public Status getStatus() { return status; }
    public void setStatus(Status status) { this.status = status; }

    public Instant getGeneratedAt() { return generatedAt; }
    public void setGeneratedAt(Instant generatedAt) { this.generatedAt = generatedAt; }

    public String getOrthophotoUrl() { return orthophotoUrl; }
    public void setOrthophotoUrl(String orthophotoUrl) { this.orthophotoUrl = orthophotoUrl; }

    public String getDemUrl() { return demUrl; }
    public void setDemUrl(String demUrl) { this.demUrl = demUrl; }

    public String getModelUrl() { return modelUrl; }
    public void setModelUrl(String modelUrl) { this.modelUrl = modelUrl; }

    public double getCoveragePct() { return coveragePct; }
    public void setCoveragePct(double coveragePct) { this.coveragePct = coveragePct; }

    public double getResolutionCm() { return resolutionCm; }
    public void setResolutionCm(double resolutionCm) { this.resolutionCm = resolutionCm; }

    public long getProcessingTimeSec() { return processingTimeSec; }
    public void setProcessingTimeSec(long processingTimeSec) { this.processingTimeSec = processingTimeSec; }

    public double getFileSizeMB() { return fileSizeMB; }
    public void setFileSizeMB(double fileSizeMB) { this.fileSizeMB = fileSizeMB; }
}