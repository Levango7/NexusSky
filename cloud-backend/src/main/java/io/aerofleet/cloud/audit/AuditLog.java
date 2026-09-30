package io.aerofleet.cloud.audit;

import java.time.Instant;

/**
 * 操作审计日志实体。
 * <p>
 * 记录每一次写操作（POST/PUT/DELETE）的上下文信息，用于安全审计和操作追溯。
 * <p>
 * 字段说明：
 * <ul>
 *   <li>{@code timestamp} — 操作时间（UTC）</li>
 *   <li>{@code userId} — 操作者标识（JWT subject，未认证时为 "anonymous"）</li>
 *   <li>{@code action} — HTTP 方法（POST / PUT / DELETE）</li>
 *   <li>{@code target} — 请求路径</li>
 *   <li>{@code detail} — 操作详情（可选，如请求体摘要）</li>
 *   <li>{@code ip} — 客户端 IP 地址</li>
 *   <li>{@code prevHash} — 上一条记录的哈希（哈希链指针，首条为 64 个 0）</li>
 *   <li>{@code entryHash} — 本条记录的 SHA-256（prevHash + 字段规范串）</li>
 * </ul>
 * <p>
 * 存储策略见 {@link AuditService}：内存缓冲 + 可选落库（{@code aerofleet.audit.persist-to-db}），
 * 哈希链在两种模式下都维护。
 */
public class AuditLog {

    private final Instant timestamp;
    private final String userId;
    private final String action;
    private final String target;
    private final String detail;
    private final String ip;
    private final String prevHash;
    private final String entryHash;

    public AuditLog(Instant timestamp, String userId, String action, String target,
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

    public Instant getTimestamp() {
        return timestamp;
    }

    public String getUserId() {
        return userId;
    }

    public String getAction() {
        return action;
    }

    public String getTarget() {
        return target;
    }

    public String getDetail() {
        return detail;
    }

    public String getIp() {
        return ip;
    }

    public String getPrevHash() {
        return prevHash;
    }

    public String getEntryHash() {
        return entryHash;
    }

    @Override
    public String toString() {
        return "AuditLog{" +
                "timestamp=" + timestamp +
                ", userId='" + userId + '\'' +
                ", action='" + action + '\'' +
                ", target='" + target + '\'' +
                ", detail='" + detail + '\'' +
                ", ip='" + ip + '\'' +
                ", entryHash='" + entryHash + '\'' +
                '}';
    }
}