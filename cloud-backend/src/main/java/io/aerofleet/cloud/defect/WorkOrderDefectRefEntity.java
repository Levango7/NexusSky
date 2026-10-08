package io.aerofleet.cloud.defect;

import jakarta.persistence.*;

/** 工单-缺陷关联（多对多落地表，UK 防重复挂载）。 */
@Entity
@Table(name = "work_order_defects")
public class WorkOrderDefectRefEntity {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    public Long id;

    @Column(name = "work_order_id", nullable = false)
    public Long workOrderId;

    @Column(name = "defect_id", nullable = false)
    public Long defectId;
}
