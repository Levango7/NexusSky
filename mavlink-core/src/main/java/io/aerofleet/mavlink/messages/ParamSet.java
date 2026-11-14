package io.aerofleet.mavlink.messages;

import io.aerofleet.mavlink.MavlinkFrame;
import io.aerofleet.mavlink.PayloadCodec;

import java.nio.ByteBuffer;

/**
 * PARAM_SET (msgId=23, LEN=23, CRC=168)。GCS→组件：写单个参数。
 *
 * <p>官方字段：{@code target_system u8}、{@code target_component u8}、{@code param_id char[16]}、
 * {@code param_value float}、{@code param_type u8}（MAV_PARAM_TYPE）；线序按类型降序稳定排序为
 * {@code param_value f32 @0}、{@code target_system u8 @4}、{@code target_component u8 @5}、
 * {@code param_id char[16] @6}、{@code param_type u8 @22}。
 *
 * <p><b>协议语义（官方）</b>：目标写完后回 {@link ParamValue}——<b>即使写失败也回</b>
 * （值保持原样），失败由请求方按超时/值比对判定；故本仓不在消息层加工失败语义。
 *
 * <p>回归帧取自 pymavlink（v20 dialect）官方打包，见 {@code ParamServiceTest}。
 */
public final class ParamSet extends MavlinkMessage {

    public static final int ID = 23;
    public static final int LEN = 23;

    public final int targetSystem;
    public final int targetComponent;
    public final String paramId;
    public final float paramValue;
    /** MAV_PARAM_TYPE（REAL32=9 …）。 */
    public final int paramType;

    public ParamSet(int targetSystem, int targetComponent, String paramId, float paramValue, int paramType) {
        this.targetSystem = targetSystem;
        this.targetComponent = targetComponent;
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
        PayloadCodec.putU8(buf, 4, targetSystem);
        PayloadCodec.putU8(buf, 5, targetComponent);
        PayloadCodec.putChars(buf, 6, paramId, 16);
        PayloadCodec.putU8(buf, 22, paramType);
        return buf;
    }

    /**
     * 解码。按 {@code payloadLength} 对 {@code param_id} 尾段与 {@code param_type}
     * 做护栏（裁剪帧直接调用时用；入口 {@link MavlinkMessage#decode} 已零填充）。
     */
    public static ParamSet decode(MavlinkFrame f) {
        ByteBuffer b = le(f.getPayload());
        int len = f.getPayloadLength();
        return new ParamSet(
                PayloadCodec.u8(b, 4),
                PayloadCodec.u8(b, 5),
                len > 6 ? PayloadCodec.chars(b, 6, Math.min(16, len - 6)) : "",
                PayloadCodec.f32(b, 0),
                len > 22 ? PayloadCodec.u8(b, 22) : 0);
    }

    @Override
    public String toString() {
        return "ParamSet{id='" + paramId + "', value=" + paramValue + ", sys=" + targetSystem
                + ", comp=" + targetComponent + ", type=" + paramType + "}";
    }
}
