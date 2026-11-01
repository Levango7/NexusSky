package io.aerofleet.cloud.api.service;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;

/**
 * 应急编排计划持久化实体（V37；P1#8 裁决：新表独立落库）。
 * <p>
 * snapshot = 计划 Map 的整体 JSON（进度文档，Jackson 对 Map&lt;String,Object&gt; 双向无损）；
 * status/tenant_id 提列供 SQL 直接排查。
 */
@Entity
@Table(name = "emergency_plan")
public class EmergencyPlanEntity {

    @Id
    @Column(name = "plan_id")
    private long planId;

    @Column(name = "tenant_id")
    private Integer tenantId;

    @Column(name = "status", length = 32)
    private String status;

    /**
     * 计划 Map 的整体 JSON 快照。
     * <p>
     * 必须显式 {@code columnDefinition = "TEXT"}：不声明长度时 Hibernate 在
     * {@code ddl-auto=create-drop/update}（测试/dev 的 H2）下建出 {@code varchar(255)}，
     * 而一个计划快照（5 阶段 + 事件列表 + 优先级队列）实测 679 字符起，写穿必然
     * {@code Value too long}；{@code persistPlan} 把异常吞成 WARN，于是「读穿永远是
     * null」——正是本表当初想修的那个病又换个地方复发。生产 V37 迁移里本就是 TEXT，
     * 这里补上声明让两处口径一致（仓内 {@code DockScheduleEntity#waypoints} 等同样写法）。
     */
    @Column(name = "snapshot", columnDefinition = "TEXT")
    private String snapshot;

    @Column(name = "updated_at")
    private Instant updatedAt;

    protected EmergencyPlanEntity() {
        // JPA 用
    }

    public EmergencyPlanEntity(long planId) {
        this.planId = planId;
    }

    public long getPlanId() {
        return planId;
    }

    public Integer getTenantId() {
        return tenantId;
    }

    public void setTenantId(Integer tenantId) {
        this.tenantId = tenantId;
    }

    public String getStatus() {
        return status;
    }

    public void setStatus(String status) {
        this.status = status;
    }

    public String getSnapshot() {
        return snapshot;
    }

    public void setSnapshot(String snapshot) {
        this.snapshot = snapshot;
    }

    public void setUpdatedAt(Instant updatedAt) {
        this.updatedAt = updatedAt;
    }
}
