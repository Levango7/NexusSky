package io.aerofleet.cloud.mission.spray;

import jakarta.persistence.AttributeConverter;
import jakarta.persistence.Column;
import jakarta.persistence.Convert;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/**
 * SprayTask 的 JPA 持久化实体。
 * <p>
 * 采用混合模式：内存缓存（{@link SprayTaskService} 中的 {@link java.util.concurrent.ConcurrentHashMap}）
 * 保证并发读性能，本实体负责重启后的状态恢复。
 * <p>
 * 经验参考：从不可变值对象迁移到 JPA Entity 时，需去掉字段 {@code final} 修饰符、
 * 添加无参构造器、为所有字段添加 setter。
 */
@Entity
@Table(name = "spray_tasks")
public class SprayTaskEntity {

    @Id
    @Column(name = "id")
    private int id;

    @Column(name = "sysid", nullable = false)
    private int sysid;

    /** 任务状态：PENDING / RUNNING / PAUSED / COMPLETED / FAILED。 */
    @Column(name = "status", nullable = false, length = 20)
    private String status;

    /** 目标流量 mL/s。 */
    @Column(name = "flow_rate")
    private double flowRate;

    /** 药箱总容量 mL。 */
    @Column(name = "total_volume")
    private double totalVolume;

    /** 已喷洒量 mL（= totalVolume - remainingChemical）。 */
    @Column(name = "sprayed_volume")
    private double sprayedVolume;

    /** 喷洒器是否开启（status == RUNNING 时为 true）。 */
    @Column(name = "gripper_open")
    private boolean gripperOpen;

    /** 已覆盖面积 m²。 */
    @Column(name = "covered_area")
    private double coveredArea;

    /** 喷幅 m。 */
    @Column(name = "spray_width")
    private double sprayWidth;

    /** 当前段序号。 */
    @Column(name = "current_segment")
    private int currentSegment;

    /** 剩余药量 mL。 */
    @Column(name = "remaining_chemical")
    private double remainingChemical;

    /** 航点序列 JSON（用于重建 SprayTask）。 */
    @Column(name = "waypoints", length = 4000)
    @Convert(converter = WaypointListConverter.class)
    private List<double[]> waypoints;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "updated_at")
    private Instant updatedAt;

    /** 租户 ID（数据隔离）。 */
    @Column(name = "tenant_id")
    private Integer tenantId;

    /** JPA 要求的无参构造器。 */
    public SprayTaskEntity() {
    }

    // --- getter / setter ---

    public int getId() { return id; }
    public void setId(int id) { this.id = id; }

    public int getSysid() { return sysid; }
    public void setSysid(int sysid) { this.sysid = sysid; }

    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }

    public double getFlowRate() { return flowRate; }
    public void setFlowRate(double flowRate) { this.flowRate = flowRate; }

    public double getTotalVolume() { return totalVolume; }
    public void setTotalVolume(double totalVolume) { this.totalVolume = totalVolume; }

    public double getSprayedVolume() { return sprayedVolume; }
    public void setSprayedVolume(double sprayedVolume) { this.sprayedVolume = sprayedVolume; }

    public boolean isGripperOpen() { return gripperOpen; }
    public void setGripperOpen(boolean gripperOpen) { this.gripperOpen = gripperOpen; }

    public double getCoveredArea() { return coveredArea; }
    public void setCoveredArea(double coveredArea) { this.coveredArea = coveredArea; }

    public double getSprayWidth() { return sprayWidth; }
    public void setSprayWidth(double sprayWidth) { this.sprayWidth = sprayWidth; }

    public int getCurrentSegment() { return currentSegment; }
    public void setCurrentSegment(int currentSegment) { this.currentSegment = currentSegment; }

    public double getRemainingChemical() { return remainingChemical; }
    public void setRemainingChemical(double remainingChemical) { this.remainingChemical = remainingChemical; }

    public List<double[]> getWaypoints() { return waypoints; }
    public void setWaypoints(List<double[]> waypoints) { this.waypoints = waypoints; }

    public Instant getCreatedAt() { return createdAt; }
    public void setCreatedAt(Instant createdAt) { this.createdAt = createdAt; }

    public Instant getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(Instant updatedAt) { this.updatedAt = updatedAt; }

    public Integer getTenantId() { return tenantId; }
    public void setTenantId(Integer tenantId) { this.tenantId = tenantId; }

    // --- 与值对象的转换 ---

    /**
     * 从 SprayTask 值对象创建 JPA Entity。
     *
     * @param task     SprayTask 值对象
     * @param tenantId 租户 ID
     * @return JPA Entity
     */
    public static SprayTaskEntity fromTask(SprayTask task, Integer tenantId) {
        SprayTaskEntity entity = new SprayTaskEntity();
        entity.setId(task.taskId());
        entity.setSysid(task.targetSysid());
        entity.setStatus(task.state().name());
        entity.setFlowRate(task.targetRate());
        entity.setTotalVolume(task.capacityMl());
        entity.setSprayedVolume(task.capacityMl() - task.remainingChemical());
        entity.setGripperOpen(task.state() == SprayTask.State.RUNNING);
        entity.setCoveredArea(task.coveredArea());
        entity.setSprayWidth(task.sprayWidth());
        entity.setCurrentSegment(task.currentSegment());
        entity.setRemainingChemical(task.remainingChemical());
        // 从 segments 反推 waypoints
        List<double[]> waypoints = new ArrayList<>();
        List<SpraySegment> segs = task.segments();
        if (!segs.isEmpty()) {
            waypoints.add(new double[]{segs.get(0).startLat(), segs.get(0).startLon()});
            for (SpraySegment seg : segs) {
                waypoints.add(new double[]{seg.endLat(), seg.endLon()});
            }
        }
        entity.setWaypoints(waypoints);
        entity.setCreatedAt(Instant.now());
        entity.setUpdatedAt(Instant.now());
        entity.setTenantId(tenantId);
        return entity;
    }

    /**
     * 将 JPA Entity 转换回 SprayTask 值对象。
     * <p>
     * 使用 waypoints 重建 SprayTask（构造函数会重新计算 segments 和 totalArea），
     * 然后恢复运行时状态（state, currentSegment, coveredArea, remainingChemical）。
     *
     * @return SprayTask 值对象
     */
    public SprayTask toTask() {
        SprayTask task = new SprayTask(id, sysid, waypoints,
                flowRate, totalVolume, sprayWidth);
        // 恢复运行时状态
        SprayTask.State stateEnum = SprayTask.State.valueOf(status);
        switch (stateEnum) {
            case RUNNING -> task.start();
            case PAUSED -> { task.start(); task.pause(); }
            case COMPLETED -> { task.start(); task.stop(); }
            case FAILED -> task.fail();
            case PENDING -> { /* 默认 PENDING，无需操作 */ }
        }
        // 恢复段进度
        for (int i = 0; i < currentSegment; i++) {
            task.markSegmentComplete();
        }
        task.updateProgress(coveredArea, remainingChemical);
        return task;
    }

    /**
     * 更新 Entity 的运行时字段（状态变更时调用）。
     *
     * @param task 当前 SprayTask 值对象
     */
    public void updateFromTask(SprayTask task) {
        this.status = task.state().name();
        this.gripperOpen = task.state() == SprayTask.State.RUNNING;
        this.coveredArea = task.coveredArea();
        this.currentSegment = task.currentSegment();
        this.remainingChemical = task.remainingChemical();
        this.sprayedVolume = task.capacityMl() - task.remainingChemical();
        this.updatedAt = Instant.now();
    }
}

/**
 * {@code List<double[]>} 航点序列的 JSON 序列化转换器。
 * <p>
 * 不引入 Jackson 依赖，手动拼接 {@code [[lat,lon],...]} 格式。
 * 空列表序列化为空字符串，反序列化空字符串为空列表。
 */
class WaypointListConverter implements AttributeConverter<List<double[]>, String> {

    @Override
    public String convertToDatabaseColumn(List<double[]> waypoints) {
        if (waypoints == null || waypoints.isEmpty()) {
            return "";
        }
        StringBuilder sb = new StringBuilder("[");
        for (int i = 0; i < waypoints.size(); i++) {
            if (i > 0) {
                sb.append(",");
            }
            double[] wp = waypoints.get(i);
            sb.append("[").append(wp[0]).append(",").append(wp[1]).append("]");
        }
        sb.append("]");
        return sb.toString();
    }

    @Override
    public List<double[]> convertToEntityAttribute(String json) {
        if (json == null || json.isBlank()) {
            return new ArrayList<>();
        }
        List<double[]> result = new ArrayList<>();
        // 手动解析 [[lat,lon],[lat,lon],...] 格式
        int i = 0;
        while (i < json.length()) {
            int openBracket = json.indexOf('[', i);
            if (openBracket == -1) {
                break;
            }
            // 找到匹配的逗号（lat 和 lon 之间）
            int comma = json.indexOf(',', openBracket + 1);
            if (comma == -1) {
                break;
            }
            int closeBracket = json.indexOf(']', comma + 1);
            if (closeBracket == -1) {
                break;
            }
            double lat = Double.parseDouble(json.substring(openBracket + 1, comma).trim());
            double lon = Double.parseDouble(json.substring(comma + 1, closeBracket).trim());
            result.add(new double[]{lat, lon});
            i = closeBracket + 1;
        }
        return result;
    }
}