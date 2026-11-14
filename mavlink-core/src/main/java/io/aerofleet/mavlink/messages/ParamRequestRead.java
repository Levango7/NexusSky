package io.aerofleet.mavlink.messages;

import io.aerofleet.mavlink.MavlinkFrame;
import io.aerofleet.mavlink.PayloadCodec;

import java.nio.ByteBuffer;

/**
 * PARAM_REQUEST_READ (msgId=20, LEN=20, CRC=214)。GCS→组件：请求读取单个参数（按名或索引）。
 *
 * <p>官方字段（common.xml）：{@code target_system u8}、{@code target_component u8}、
 * {@code param_id char[16]}、{@code param_index int16}；线序按类型降序稳定排序为
 * {@code param_index i16 @0}、{@code target_system u8 @2}、{@code target_component u8 @3}、
 * {@code param_id char[16] @4}（{@code param_index} 是两字节字段，排最前——这是官方线序，
 * 不是书写顺序）。
 *
 * <p><b>为什么必须实现</b>：参数服务是 GCS 调参/读参的主通路（官方协议序列：
 * 读单个 = {@code PARAM_REQUEST_READ} → 目标回 {@code PARAM_VALUE}；按名或按索引二选一，
 * {@code param_index=-1} 表示按名）。此前 mavlink-core 无任何 {@code PARAM_*} 消息类，
 * 该协议面在仓内完全缺失（见 docs/protocol-vendor-integration.md §5.1，2026-11-13 对照销项）。
 *
 * <p>回归帧取自 pymavlink（v20 dialect）官方打包：全长 20 字节帧与 MAVLink2 尾零裁剪帧
 * （{@code param_id} 尾部 NUL 被裁）各一，见 {@code ParamServiceTest}。
 */
public final class ParamRequestRead extends MavlinkMessage {

    public static final int ID = 20;
    public static final int LEN = 20;

    /** 参数索引；-1 表示按 {@link #paramId} 匹配（官方语义）。 */
    public final int paramIndex;
    public final int targetSystem;
    public final int targetComponent;
    /** 参数名，char[16]（NUL 填充与截断按官方 wire 规则）。 */
    public final String paramId;

    public ParamRequestRead(int paramIndex, int targetSystem, int targetComponent, String paramId) {
        this.paramIndex = paramIndex;
        this.targetSystem = targetSystem;
        this.targetComponent = targetComponent;
        this.paramId = paramId;
    }

    @Override
    public int messageId() {
        return ID;
    }

    @Override
    public byte[] encode() {
        byte[] buf = PayloadCodec.alloc(LEN);
        PayloadCodec.putI16(buf, 0, paramIndex);
        PayloadCodec.putU8(buf, 2, targetSystem);
        PayloadCodec.putU8(buf, 3, targetComponent);
        PayloadCodec.putChars(buf, 4, paramId, 16);
        return buf;
    }

    /**
     * 解码。入口 {@link MavlinkMessage#decode} 已零填充，这里按 {@code payloadLength}
     * 护栏 {@code param_id} 尾段——与 {@link CommandInt#decode} 同款防御（裁剪帧直接调用时用）。
     */
    public static ParamRequestRead decode(MavlinkFrame f) {
        ByteBuffer b = le(f.getPayload());
        int len = f.getPayloadLength();
        return new ParamRequestRead(
                PayloadCodec.i16(b, 0),
                PayloadCodec.u8(b, 2),
                PayloadCodec.u8(b, 3),
                len > 4 ? PayloadCodec.chars(b, 4, Math.min(16, len - 4)) : "");
    }

    @Override
    public String toString() {
        return "ParamRequestRead{index=" + paramIndex + ", sys=" + targetSystem
                + ", comp=" + targetComponent + ", id='" + paramId + "'}";
    }
}
