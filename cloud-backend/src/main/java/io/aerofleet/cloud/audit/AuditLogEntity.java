package io.aerofleet.cloud.audit;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;

/**
 * 审计日志 JPA 实体，映射 {@code audit_log} 表（V21 迁移）。
 * <p>
 * 持久化模式下 {@link AuditService#record} 每写一条内存记录都会落库一行，
 * 并维护哈希链：{@code prev_hash} 指向上一条的 {@code entry_hash}，
 * {@code entry_hash} = SHA-256(prev_hash + 字段规范串)。首行 prev_hash 为 64 个 0。
 * <p>
 * 列名遵循 Hibernate SpringPhysicalNamingStrategy 约定（下划线命名），
 * 与 V21__audit_log_table.sql 逐列一致（prod 的 ddl-auto=validate 会逐实体校验）。
 */
@Entity
@Table(name = "audit_log")
public class AuditLogEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id")
    private Long id;

    @Column(name = "timestamp")
    private Instant timestamp;

    @Column(name = "user_id")
    private String userId;

    @Column(name = "action")
    private String action;

    @Column(name = "target")
    private String target;

    @Column(name = "detail")
    private String detail;

    @Column(name = "ip")
    private String ip;

    @Column(name = "prev_hash")
    private String prevHash;

    @Column(name = "entry_hash")
    private String entryHash;

    /** JPA 要求的无参构造器 */
    public AuditLogEntity() {
    }

    public AuditLogEntity(Instant timestamp, String userId, String action, String target,
                          String detail, String ip, String prevHash, String entryHash) {
        this.timestamp = timestamp;
        this.userId = userId;
        this.action = action;
        this.target = target;
        this.detail = detail;
        this.ip = ip;
        this.prevHash = prevHash;
        this.entryHash = entryHash;
    }

    /** 从内存记录构造实体（哈希由 AuditService 计算后填入）。 */
    public static AuditLogEntity from(AuditLog log, String prevHash, String entryHash) {
        return new AuditLogEntity(log.getTimestamp(), log.getUserId(), log.getAction(),
                log.getTarget(), log.getDetail(), log.getIp(), prevHash, entryHash);
    }

    /** 转换回查询层使用的内存记录（含哈希，供链校验与响应序列化）。 */
    public AuditLog toAuditLog() {
        return new AuditLog(timestamp, userId, action, target, detail, ip, prevHash, entryHash);
    }

    // --- getters / setters ---

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public Instant getTimestamp() {
        return timestamp;
    }

    public void setTimestamp(Instant timestamp) {
        this.timestamp = timestamp;
    }

    public String getUserId() {
        return userId;
    }

    public void setUserId(String userId) {
        this.userId = userId;
    }

    public String getAction() {
        return action;
    }

    public void setAction(String action) {
        this.action = action;
    }

    public String getTarget() {
        return target;
    }

    public void setTarget(String target) {
        this.target = target;
    }

    public String getDetail() {
        return detail;
    }

    public void setDetail(String detail) {
        this.detail = detail;
    }

    public String getIp() {
        return ip;
    }

    public void setIp(String ip) {
        this.ip = ip;
    }

    public String getPrevHash() {
        return prevHash;
    }

    public void setPrevHash(String prevHash) {
        this.prevHash = prevHash;
    }

    public String getEntryHash() {
        return entryHash;
    }

    public void setEntryHash(String entryHash) {
        this.entryHash = entryHash;
    }
}
