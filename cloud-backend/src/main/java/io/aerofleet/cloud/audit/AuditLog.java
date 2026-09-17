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
 * </ul>
 * <p>
 * 当前实现为内存存储（{@link AuditService}），生产环境可替换为持久化存储。
 */
public class AuditLog {

    private final Instant timestamp;
    private final String userId;
    private final String action;
    private final String target;
    private final String detail;
    private final String ip;

    public AuditLog(Instant timestamp, String userId, String action, String target, String detail, String ip) {
        this.timestamp = timestamp;
        this.userId = userId;
        this.action = action;
        this.target = target;
        this.detail = detail;
        this.ip = ip;
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

    @Override
    public String toString() {
        return "AuditLog{" +
                "timestamp=" + timestamp +
                ", userId='" + userId + '\'' +
                ", action='" + action + '\'' +
                ", target='" + target + '\'' +
                ", detail='" + detail + '\'' +
                ", ip='" + ip + '\'' +
                '}';
    }
}