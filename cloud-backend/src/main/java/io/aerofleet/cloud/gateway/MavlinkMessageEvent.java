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

    public MavlinkMessageEvent(Object source, int sysid, int msgId, MavlinkMessage message, long timestamp) {
        super(source);
        this.sysid = sysid;
        this.msgId = msgId;
        this.message = message;
        this.timestamp = timestamp;
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
}