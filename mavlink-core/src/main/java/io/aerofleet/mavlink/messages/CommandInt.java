package io.aerofleet.mavlink.messages;

import io.aerofleet.mavlink.MavlinkFrame;
import io.aerofleet.mavlink.PayloadCodec;

import java.nio.ByteBuffer;

/**
 * COMMAND_INT (msgId=75, LEN=35, CRC=158)。GCS→飞控：携带经纬度的长命令（INT 口径）。
 *
 * <p>与 {@link CommandLong} 的区别：经纬度用 int32（x=lat*1e7、y=lon*1e7）表达——float 只
 * 有 ~7 位有效数字，承载 1e-7 度级坐标会丢精度；并且显式携带 frame/current/autocontinue。
 *
 * <p><b>为什么必须实现</b>：ArduPilot 对"携带位置"的命令（如 MAV_CMD_DO_REPOSITION）**强制
 * 要求 COMMAND_INT**——用 COMMAND_LONG 发会回 {@code MAV_RESULT=8}（COMMAND_INT_ONLY，
 * MAVLink 标准码）。真机实测（AP SITL，2026-10-30）：DO_REPOSITION 在非 GUIDED 与 GUIDED
 * 悬停中两种状态下经 COMMAND_LONG 发送均回 8——与模式无关，是报文格式契约；此前的编队
 * 修正/队形机动因此在该固件上完全不工作。见 docs/sitl-integration.md §11.2-⑨。
 *
 * <p>字段线序与官方一致（按类型降序 + 声明序稳定）：
 * param1..4 f32 @0/4/8/12，x i32 @16，y i32 @20，z f32 @24，command u16 @28，
 * target_system u8 @30，target_component u8 @31，frame u8 @32，current u8 @33，
 * autocontinue u8 @34。回归测试用 pymavlink 官方打包帧逐字节比对
 * （{@code CommandIntTest}，含 v2 尾零裁剪变体）。
 */
public final class CommandInt extends MavlinkMessage {

    public static final int ID = 75;
    public static final int LEN = 35;

    public final int targetSystem;
    public final int targetComponent;
    public final int frame;        // MAV_FRAME（GLOBAL_INT 系用 5/6）
    public final int command;      // MAV_CMD
    public final int current;
    public final int autocontinue;
    public final float param1;
    public final float param2;
    public final float param3;
    public final float param4;
    public final int x;            // GLOBAL_INT: lat*1e7
    public final int y;            // GLOBAL_INT: lon*1e7
    public final float z;          // GLOBAL: 高度 m

    public CommandInt(int targetSystem, int targetComponent, int frame, int command,
                      int current, int autocontinue,
                      float param1, float param2, float param3, float param4,
                      int x, int y, float z) {
        this.targetSystem = targetSystem;
        this.targetComponent = targetComponent;
        this.frame = frame;
        this.command = command;
        this.current = current;
        this.autocontinue = autocontinue;
        this.param1 = param1;
        this.param2 = param2;
        this.param3 = param3;
        this.param4 = param4;
        this.x = x;
        this.y = y;
        this.z = z;
    }

    @Override
    public int messageId() {
        return ID;
    }

    @Override
    public byte[] encode() {
        byte[] buf = PayloadCodec.alloc(LEN);
        PayloadCodec.putF32(buf, 0, param1);
        PayloadCodec.putF32(buf, 4, param2);
        PayloadCodec.putF32(buf, 8, param3);
        PayloadCodec.putF32(buf, 12, param4);
        PayloadCodec.putI32(buf, 16, x);
        PayloadCodec.putI32(buf, 20, y);
        PayloadCodec.putF32(buf, 24, z);
        PayloadCodec.putU16(buf, 28, command);
        PayloadCodec.putU8(buf, 30, targetSystem);
        PayloadCodec.putU8(buf, 31, targetComponent);
        PayloadCodec.putU8(buf, 32, frame);
        PayloadCodec.putU8(buf, 33, current);
        PayloadCodec.putU8(buf, 34, autocontinue);
        return buf;
    }

    /**
     * 解码。尾部 current/autocontinue 在 MAVLink2 尾零裁剪下可能不在 wire 上
     * （值全零被发送方裁掉），故按 {@code payloadLength} 逐字段护栏——与
     * {@link MissionItemInt#decode} 同款防御；正常路径（{@code MavlinkMessage.decode}）
     * 已在入口零填充，这里护栏服务于直接调用与裁剪帧。
     */
    public static CommandInt decode(MavlinkFrame f) {
        ByteBuffer b = le(f.getPayload());
        int len = f.getPayloadLength();
        return new CommandInt(
                PayloadCodec.u8(b, 30),
                PayloadCodec.u8(b, 31),
                len > 32 ? PayloadCodec.u8(b, 32) : 0,
                PayloadCodec.u16(b, 28),
                len > 33 ? PayloadCodec.u8(b, 33) : 0,
                len > 34 ? PayloadCodec.u8(b, 34) : 0,
                PayloadCodec.f32(b, 0),
                PayloadCodec.f32(b, 4),
                PayloadCodec.f32(b, 8),
                PayloadCodec.f32(b, 12),
                PayloadCodec.i32(b, 16),
                PayloadCodec.i32(b, 20),
                PayloadCodec.f32(b, 24));
    }

    @Override
    public String toString() {
        return "CommandInt{cmd=" + command + ", frame=" + frame + ", x=" + x
                + ", y=" + y + ", z=" + z + "}";
    }
}
