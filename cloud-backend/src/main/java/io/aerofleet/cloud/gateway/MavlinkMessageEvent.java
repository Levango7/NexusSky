package io.aerofleet.cloud.gateway;

import io.aerofleet.mavlink.messages.MavlinkMessage;
import org.springframework.context.ApplicationEvent;

/**
 * MAVLink 消息事件：TelemetryIngestService 解码后发布，各业务模块通过
 * {@code @EventListener(condition = "#event.msgId == XXX")} 自行过滤处理。
 * <p>
 * 事件驱动重构后，TelemetryIngestService 只负责 decode + publish，
 * 所有业务路由逻辑由各模块自行监听，消除 @Lazy 循环依赖。
 */
public class MavlinkMessageEvent extends ApplicationEvent {

    private final int sysid;
    private final int msgId;
    private final MavlinkMessage message;
    private final long timestamp;
    /**
     * 显式租户归属（设备源帧用）。
     * <p>
     * null 且 {@code tenantExplicit == false}：未指定，消费方按 sysid 推导（无人机源帧惯例）；
     * null 且 {@code tenantExplicit == true}：显式声明"设备未归属租户"（全局域，三态租户语义）；
     * 非 null：帧归属该租户，{@code TelemetryWebSocketHandler#onMavlinkMessage} 以此为准，
     * 不再用 sysid 反查（安防设备源帧的 sysid 是设备 ID 哈希，不是无人机 sysid，
     * 反查会撞到无关无人机的租户）。
     */
    private final Integer ownerTenantId;
    /** ownerTenantId 是否为显式指定（区分"未指定"与"显式未归属"）。 */
    private final boolean tenantExplicit;

    public MavlinkMessageEvent(Object source, int sysid, int msgId, MavlinkMessage message, long timestamp) {
        this(source, sysid, msgId, message, timestamp, false, null);
    }

    /**
     * 带显式租户归属的构造（安防设备等非无人机源帧）。
     *
     * @param ownerTenantId 设备所属租户 ID，null 表示设备未归属任何租户
     */
    public MavlinkMessageEvent(Object source, int sysid, int msgId, MavlinkMessage message, long timestamp,
                               Integer ownerTenantId) {
        this(source, sysid, msgId, message, timestamp, true, ownerTenantId);
    }

    private MavlinkMessageEvent(Object source, int sysid, int msgId, MavlinkMessage message, long timestamp,
                                boolean tenantExplicit, Integer ownerTenantId) {
        super(source);
        this.sysid = sysid;
        this.msgId = msgId;
        this.message = message;
        this.timestamp = timestamp;
        this.tenantExplicit = tenantExplicit;
        this.ownerTenantId = ownerTenantId;
    }

    public int getSysid() {
        return sysid;
    }

    public int getMsgId() {
        return msgId;
    }

    public MavlinkMessage getMessage() {
        return message;
    }

    /** 消息时间戳（避免覆盖 ApplicationEvent 的 final getTimestamp()）。 */
    public long getMsgTimestamp() {
        return timestamp;
    }

    /** 显式租户归属（仅 {@link #isTenantExplicit()} 为 true 时有意义，否则 null）。 */
    public Integer getOwnerTenantId() {
        return ownerTenantId;
    }

    /** 是否显式指定了租户归属（false = 消费方按 sysid 推导）。 */
    public boolean isTenantExplicit() {
        return tenantExplicit;
    }
}