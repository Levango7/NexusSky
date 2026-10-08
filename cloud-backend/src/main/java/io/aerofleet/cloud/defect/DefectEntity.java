package io.aerofleet.cloud.defect;

import jakarta.persistence.*;

/** 缺陷（F4，spec §1）。从 F1 拍照结果晋升或人工立案。 */
@Entity
@Table(name = "defects")
public class DefectEntity {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    public Long id;

    @Column(name = "tenant_id")
    public String tenantId;

    @Column(nullable = false, length = 40)
    public String kind;

    @Column(nullable = false)
    public double confidence;

    @Column(nullable = false)
    public double lat;
    @Column(nullable = false)
    public double lon;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 4)
    public DefectSeverity severity = DefectSeverity.P2;

    /** OPEN / CONFIRMED / DISMISSED。 */
    @Column(nullable = false, length = 12)
    public String status = "OPEN";

    /** auto / manual。 */
    @Column(nullable = false, length = 20)
    public String source = "manual";

    @Column(length = 500)
    public String note;

    /** 最近一次复检命中时间（去重口径的"复现"锚点）。 */
    @Column(name = "last_seen_at")
    public Long lastSeenAt;

    @Column(name = "created_at", nullable = false)
    public long createdAt = System.currentTimeMillis();
}
