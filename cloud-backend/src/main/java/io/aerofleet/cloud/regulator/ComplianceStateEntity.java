package io.aerofleet.cloud.regulator;

import io.aerofleet.cloud.regulator.model.ComplianceState;
import io.aerofleet.cloud.regulator.model.ComplianceStatus;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;

/**
 * 合规状态持久化实体（V32；审计 §2.1 第 7 条）。
 *
 * <p>与域记录 {@link ComplianceStatus} 一对一。三个 last* 时间戳：域里 0 = "从未发生"，
 * 库里 NULL 同样表达该义——转换层把 NULL 归一为 0，保证单一表示。
 */
@Entity
@Table(name = "compliance_state")
public class ComplianceStateEntity {

    @Id
    @Column(name = "sysid")
    private int sysid;

    @Column(name = "product_serial_no", length = 64)
    private String productSerialNo;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", length = 32, nullable = false)
    private ComplianceState status;

    @Column(name = "activation_id", length = 64)
    private String activationId;

    @Column(name = "cancellation_id", length = 64)
    private String cancellationId;

    @Column(name = "last_verify_time")
    private Long lastVerifyTime;

    @Column(name = "last_activation_time")
    private Long lastActivationTime;

    @Column(name = "last_telemetry_report_time")
    private Long lastTelemetryReportTime;

    @Column(name = "updated_at")
    private Instant updatedAt;

    protected ComplianceStateEntity() {
        // JPA 用
    }

    public static ComplianceStateEntity fromStatus(ComplianceStatus s) {
        ComplianceStateEntity e = new ComplianceStateEntity();
        e.applyFrom(s);
        return e;
    }

    /** 覆盖式更新（状态机只前进，整行覆写即可）。 */
    public void applyFrom(ComplianceStatus s) {
        this.sysid = s.sysid();
        this.productSerialNo = s.productSerialNo();
        this.status = s.status();
        this.activationId = s.activationId();
        this.cancellationId = s.cancellationId();
        this.lastVerifyTime = s.lastVerifyTime();
        this.lastActivationTime = s.lastActivationTime();
        this.lastTelemetryReportTime = s.lastTelemetryReportTime();
        this.updatedAt = Instant.now();
    }

    public ComplianceStatus toStatus() {
        return new ComplianceStatus(
                sysid,
                productSerialNo,
                status,
                activationId,
                cancellationId,
                lastVerifyTime == null ? 0L : lastVerifyTime,
                lastActivationTime == null ? 0L : lastActivationTime,
                lastTelemetryReportTime == null ? 0L : lastTelemetryReportTime);
    }

    public int getSysid() {
        return sysid;
    }
}
