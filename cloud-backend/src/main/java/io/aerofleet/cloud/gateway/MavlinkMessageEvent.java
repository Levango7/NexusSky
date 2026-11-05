package io.aerofleet.cloud.gateway;

import io.aerofleet.mavlink.messages.MavlinkMessage;
import org.springframework.context.ApplicationEvent;

/**
 * MAVLink 消息事件：TelemetryIngestService 解码后发布，各业务模块通过
 * {@code @EventListener(condition = "#event.msgId == XXX")} 自行过滤处理。
 * <p>
 * 事件驱动重构后，TelemetryIngestService 只负责 decode + publish，
 * 所有业务路由逻辑由各模块自行监听，消除 @Lazy 循环依赖。
 * <p>
 * <b>生产者契约（owner 裁决 B'，2026-11-05）</b>——两个构造不是同一个语义：
 * <ul>
 *   <li><b>5 参构造＝机队无人机源帧</b>：{@code sysid} 取自 MAVLink 报头，消费方按
 *       {@code DeviceRegistry} 反查租户归属。报头 sysid 本身不可信（任何 UDP 说话方都能填），
 *       该路径依赖 {@code UdpGateway.onFrame} 的设备白名单
 *       （prod/staging {@code deviceWhitelistEnabled=true}）在 ingest <b>之前</b>拒掉未注册 sysid，
 *       但<b>该门不完备</b>：{@code UdpGateway.java:226-232} 对 {@code GCS_SYSID=255} 与
 *       {@code sysid<=0} 豁免、且频率限制同样豁免。故 <b>不能断言 prod 下 null 只表示
 *       「已登记但未归属」</b>——报头填 255 的帧在 prod 仍能到达消费方。
 *       白名单关闭的 dev 下更是任意 UDP 说话方可造未知 sysid。
 *       消费方须自己做三分解析（已登记未归属 / 未知 / 已归属），
 *       <b>不得把 null 当成「公共基础设施」放行给所有租户</b>。这是配置依赖，不是运行时保证。</li>
 *   <li><b>6 参构造＝非机队设备源帧</b>（安防/监控设备等）：<b>必须</b>用它显式声明租户归属
 *       （含显式 null＝设备未归属）。这类帧的 sysid 是设备 ID 哈希，不是无人机 sysid；
 *       误用 5 参构造会让消费方按哈希反查，撞到无关无人机的租户。</li>
 * </ul>
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

    /**
     * 机队无人机源帧（sysid 取自 MAVLink 报头，租户由消费方按 sysid 反查）。
     * 非机队设备源帧请用带 {@code ownerTenantId} 的 6 参构造——见类 javadoc 的生产者契约。
     */
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