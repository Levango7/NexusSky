package io.aerofleet.mavlink.messages;

import io.aerofleet.mavlink.MavlinkFrame;
import io.aerofleet.mavlink.PayloadCodec;

import java.nio.ByteBuffer;

/**
 * EXTENDED_SYS_STATE (msgId=245, LEN=2) —— MAVLink 官方标准消息（PX4 / ArduPilot 均会周期下发）。
 * <p>
 * 补它的原因（2026-10-24 产品级缺口盘点）：飞行阶段（地面 / 空中 / 起飞中 / 降落中）
 * 此前只能靠 {@code HEARTBEAT.custom_mode} 猜——而 custom_mode 是**机型/固件相关的导航状态**，
 * 各家枚举不同，PX4 与 ArduPilot 对同一个数字的解释都不一样。官方为此单独定义了
 * {@code landed_state}，语义与机型无关。接真机后，GCS 上"降落中 / 起飞中"这两句话
 * 只有在有这条消息时才站得住。
 * <p>
 * 字段布局（小端）：
 * <pre>
 * 偏移  字段          类型    说明
 * 0    vtolState     uint8   MAV_VTOL_STATE：0=UNDEFINED 1=TRANSITION_TO_FW 2=TRANSITION_TO_MC 3=MC 4=FW
 * 1    landedState   uint8   MAV_LANDED_STATE：0=UNDEFINED 1=ON_GROUND 2=IN_AIR 3=TAKEOFF 4=LANDING
 * </pre>
 * CRC_EXTRA = 130（由官方 message_checksum 算法独立重算并核对：见 MavlinkMessageChecksum）。
 */
public final class ExtendedSysState extends MavlinkMessage {

    public static final int ID = 245;
    public static final int LEN = 2;

    /** MAV_VTOL_STATE：垂直起降构型状态。 */
    public static final int VTOL_UNDEFINED = 0;
    public static final int VTOL_TRANSITION_TO_FW = 1;
    public static final int VTOL_TRANSITION_TO_MC = 2;
    public static final int VTOL_MC = 3;
    public static final int VTOL_FW = 4;

    /** MAV_LANDED_STATE：与机型无关的飞行阶段。 */
    public static final int LANDED_UNDEFINED = 0;
    public static final int LANDED_ON_GROUND = 1;
    public static final int LANDED_IN_AIR = 2;
    public static final int LANDED_TAKEOFF = 3;
    public static final int LANDED_LANDING = 4;

    public final int vtolState;
    public final int landedState;

    public ExtendedSysState(int vtolState, int landedState) {
        this.vtolState = vtolState & 0xFF;
        this.landedState = landedState & 0xFF;
    }

    @Override
    public int messageId() {
        return ID;
    }

    @Override
    public byte[] encode() {
        byte[] buf = PayloadCodec.alloc(LEN);
        PayloadCodec.putU8(buf, 0, vtolState);
        PayloadCodec.putU8(buf, 1, landedState);
        return buf;
    }

    public static ExtendedSysState decode(MavlinkFrame f) {
        ByteBuffer b = le(f.getPayload());
        return new ExtendedSysState(PayloadCodec.u8(b, 0), PayloadCodec.u8(b, 1));
    }

    /**
     * 飞行阶段的中文标签。未定义/未知一律返回 {@code null}，由调用方决定显示什么——
     * 不在协议层替 UI 定口径（这是 {@code GAPS} 第八节第 1 条踩过的坑）。
     */
    public static String landedStateName(int landedState) {
        return switch (landedState) {
            case LANDED_ON_GROUND -> "地面";
            case LANDED_IN_AIR -> "空中";
            case LANDED_TAKEOFF -> "起飞中";
            case LANDED_LANDING -> "降落中";
            default -> null;
        };
    }

    @Override
    public String toString() {
        return "ExtendedSysState{vtolState=" + vtolState + ", landedState=" + landedState + "}";
    }
}
