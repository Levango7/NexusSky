package io.aerofleet.mavlink.messages;

import io.aerofleet.mavlink.MavlinkFrame;
import io.aerofleet.mavlink.MavlinkMessageInfo;
import io.aerofleet.mavlink.PayloadCodec;

import java.nio.ByteBuffer;

/**
 * MAVLink 消息抽象：每个消息提供 payload 编码与帧解码。
 * 消息类不可变，encode() 产出待组装成帧的 payload 字节。
 */
public abstract class MavlinkMessage {

    /** 消息默认系统/组件 ID（发送方可通过 wrap 覆盖）。 */
    protected static final int COMP_ID_AUTOPILOT = 1;

    public abstract int messageId();

    public abstract byte[] encode();

    /** 从帧解码为具体消息；payload 短于所需字段时抛异常。 */
    public static MavlinkMessage decode(MavlinkFrame frame) {
        switch (frame.getMessageId()) {
            case Heartbeat.ID:
                return Heartbeat.decode(frame);
            case SysStatus.ID:
                return SysStatus.decode(frame);
            case SystemTimeMsg.ID:
                return SystemTimeMsg.decode(frame);
            case GpsRawInt.ID:
                return GpsRawInt.decode(frame);
            case Attitude.ID:
                return Attitude.decode(frame);
            case GlobalPositionInt.ID:
                return GlobalPositionInt.decode(frame);
            case VfrHud.ID:
                return VfrHud.decode(frame);
            case MissionCurrent.ID:
                return MissionCurrent.decode(frame);
            case MissionRequest.ID:
                return MissionRequest.decode(frame);
            case MissionCountMsg.ID:
                return MissionCountMsg.decode(frame);
            case MissionAckMsg.ID:
                return MissionAckMsg.decode(frame);
            case MissionRequestInt.ID:
                return MissionRequestInt.decode(frame);
            case MissionRequestList.ID:
                return MissionRequestList.decode(frame);
            case ManualControl.ID:
                return ManualControl.decode(frame);
            case CameraInformation.ID:
                return CameraInformation.decode(frame);
            case CameraSettings.ID:
                return CameraSettings.decode(frame);
            case CameraCaptureStatus.ID:
                return CameraCaptureStatus.decode(frame);
            case CameraImageCaptured.ID:
                return CameraImageCaptured.decode(frame);
            case CameraFovStatus.ID:
                return CameraFovStatus.decode(frame);
            case RadioStatus.ID:
                return RadioStatus.decode(frame);
            case MissionItemInt.ID:
                return MissionItemInt.decode(frame);
            case CommandLong.ID:
                return CommandLong.decode(frame);
            case CommandAck.ID:
                return CommandAck.decode(frame);
            case HomePosition.ID:
                return HomePosition.decode(frame);
            case Statustext.ID:
                return Statustext.decode(frame);
            default:
                return null; // 未知消息：由调用方决定忽略或透传
        }
    }

    /** 组装为可发送帧（自动查 CRC_EXTRA，v2 格式）。 */
    public MavlinkFrame toFrame(int systemId, int componentId, int sequence) {
        return MavlinkFrame.of(systemId, componentId, sequence, messageId(),
                MavlinkMessageInfo.crcExtraOf(messageId()), encode());
    }

    /** 常用解码辅助：小端视图。 */
    protected static ByteBuffer le(byte[] payload) {
        return PayloadCodec.littleEndian(payload);
    }
}
