package io.aerofleet.cloud.defect;

import jakarta.persistence.*;

/** 工单（F4，spec §1）：缺陷的处置流程载体，状态机见 {@link WorkOrderStateMachine}。 */
@Entity
@Table(name = "work_orders")
public class WorkOrderEntity {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    public Long id;

    @Column(name = "tenant_id")
    public String tenantId;

    @Column(nullable = false, length = 200)
    public String title;

    @Column(length = 1000)
    public String note;

    /** OPEN/DISPATCHED/IN_PROGRESS/RESOLVED/VERIFIED/REOPENED/CANCELLED。 */
    @Column(nullable = false, length = 12)
    public String status = "OPEN";

    @Column(name = "cancel_reason", length = 300)
    public String cancelReason;

    @Column(name = "verified_at")
    public Long verifiedAt;

    /** 复检明细：VERIFIED=通过依据；REOPENED=命中缺陷清单。 */
    @Column(name = "verified_detail", length = 500)
    public String verifiedDetail;

    @Column(name = "created_at", nullable = false)
    public long createdAt = System.currentTimeMillis();

    @Column(name = "updated_at", nullable = false)
    public long updatedAt = System.currentTimeMillis();
}
