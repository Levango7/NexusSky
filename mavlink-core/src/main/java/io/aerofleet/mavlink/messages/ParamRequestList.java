package io.aerofleet.mavlink.messages;

import io.aerofleet.mavlink.MavlinkFrame;
import io.aerofleet.mavlink.PayloadCodec;

import java.nio.ByteBuffer;

/**
 * PARAM_REQUEST_LIST (msgId=21, LEN=2, CRC=159)。GCS→组件：请求全量参数清单。
 *
 * <p>官方协议序列（mavlink.io 参数服务页）：GCS 发本帧 → 目标把每个参数各发一条
 * {@link ParamValue}（官方建议分片发送、避免打爆链路），GCS 靠超时判定收齐。
 * 本仓此前无 {@code PARAM_*} 消息类（docs/protocol-vendor-integration.md §5.1，2026-11-13 销项）。
 *
 * <p>线序：{@code target_system u8 @0}、{@code target_component u8 @1}（唯二字段）。
 */
public final class ParamRequestList extends MavlinkMessage {

    public static final int ID = 21;
    public static final int LEN = 2;

    public final int targetSystem;
    public final int targetComponent;

    public ParamRequestList(int targetSystem, int targetComponent) {
        this.targetSystem = targetSystem;
        this.targetComponent = targetComponent;
    }

    @Override
    public int messageId() {
        return ID;
    }

    @Override
    public byte[] encode() {
        byte[] buf = PayloadCodec.alloc(LEN);
        PayloadCodec.putU8(buf, 0, targetSystem);
        PayloadCodec.putU8(buf, 1, targetComponent);
        return buf;
    }

    public static ParamRequestList decode(MavlinkFrame f) {
        ByteBuffer b = le(f.getPayload());
        return new ParamRequestList(PayloadCodec.u8(b, 0), PayloadCodec.u8(b, 1));
    }

    @Override
    public String toString() {
        return "ParamRequestList{sys=" + targetSystem + ", comp=" + targetComponent + "}";
    }
}
