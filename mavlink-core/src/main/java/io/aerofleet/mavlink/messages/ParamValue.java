package io.aerofleet.mavlink.messages;

import io.aerofleet.mavlink.MavlinkFrame;
import io.aerofleet.mavlink.PayloadCodec;

import java.nio.ByteBuffer;

/**
 * PARAM_VALUE (msgId=22, LEN=25, CRC=220)。组件→GCS：单个参数的当前值（广播）。
 *
 * <p>官方字段：{@code param_count u16}（参数总数）、{@code param_index u16}（本条序号）、
 * {@code param_id char[16]}、{@code param_value float}、{@code param_type u8}（MAV_PARAM_TYPE，
 * 如 REAL32=9）；线序按类型降序稳定排序为 {@code param_value f32 @0}、
 * {@code param_count u16 @4}、{@code param_index u16 @6}、{@code param_id char[16] @8}、
 * {@code param_type u8 @24}。
 *
 * <p><b>语义要点（写进 javadoc 以免误用）</b>：{@code param_value} 恒为 float 载体，真实类型由
 * {@code param_type} 解释；读写失败时官方也回本帧（值不变），失败检测靠请求方的
 * 超时/值比对——因此本仓不在消息层加工失败语义。
 *
 * <p>回归帧取自 pymavlink（v20 dialect）官方打包，见 {@code ParamServiceTest}。
 */
public final class ParamValue extends MavlinkMessage {

    public static final int ID = 22;
    public static final int LEN = 25;

    /** 参数总数（官方语义：目标参数表长度，供 GCS 判断收齐）。 */
    public final int paramCount;
    public final int paramIndex;
    public final String paramId;
    /** 参数值（float 载体；类型见 {@link #paramType}）。 */
    public final float paramValue;
    /** MAV_PARAM_TYPE（REAL32=9、INT32=6 …）。 */
    public final int paramType;

    public ParamValue(int paramCount, int paramIndex, String paramId, float paramValue, int paramType) {
        this.paramCount = paramCount;
        this.paramIndex = paramIndex;
        this.paramId = paramId;
        this.paramValue = paramValue;
        this.paramType = paramType;
    }

    @Override
    public int messageId() {
        return ID;
    }

    @Override
    public byte[] encode() {
        byte[] buf = PayloadCodec.alloc(LEN);
        PayloadCodec.putF32(buf, 0, paramValue);
        PayloadCodec.putU16(buf, 4, paramCount);
        PayloadCodec.putU16(buf, 6, paramIndex);
        PayloadCodec.putChars(buf, 8, paramId, 16);
        PayloadCodec.putU8(buf, 24, paramType);
        return buf;
    }

    /**
     * 解码。按 {@code payloadLength} 对 {@code param_id} 尾段与 {@code param_type}
     * 做护栏——MAVLink2 尾零裁剪帧（入口已零填充，这里服务直接调用与裁剪帧）。
     */
    public static ParamValue decode(MavlinkFrame f) {
        ByteBuffer b = le(f.getPayload());
        int len = f.getPayloadLength();
        return new ParamValue(
                PayloadCodec.u16(b, 4),
                PayloadCodec.u16(b, 6),
                len > 8 ? PayloadCodec.chars(b, 8, Math.min(16, len - 8)) : "",
                PayloadCodec.f32(b, 0),
                len > 24 ? PayloadCodec.u8(b, 24) : 0);
    }

    @Override
    public String toString() {
        return "ParamValue{id='" + paramId + "', value=" + paramValue + ", index=" + paramIndex
                + "/" + paramCount + ", type=" + paramType + "}";
    }
}
