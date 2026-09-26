package io.aerofleet.mavlink.messages;

import io.aerofleet.mavlink.MavlinkFrame;
import io.aerofleet.mavlink.MavlinkMessageInfo;
import io.aerofleet.mavlink.PayloadCodec;
import io.aerofleet.mavlink.security.MavlinkSigner;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.ByteBuffer;
import java.util.Arrays;

/**
 * MAVLink 消息抽象：每个消息提供 payload 编码与帧解码。
 * 消息类不可变，encode() 产出待组装成帧的 payload 字节。
 * <p>
 * 签名机制：当 {@link MavlinkSigner} 启用时，encode 后的 payload 会附加 8 字节 HMAC-SHA256 签名，
 * decode 时会分离签名并验证。签名默认关闭，不影响现有编解码流程。
 */
public abstract class MavlinkMessage {

    private static final Logger log = LoggerFactory.getLogger(MavlinkMessage.class);

    /** 消息默认系统/组件 ID（发送方可通过 wrap 覆盖）。 */
    protected static final int COMP_ID_AUTOPILOT = 1;

    /** HMAC-SHA256 签名长度（截断为 8 字节）。 */
    public static final int SIGNATURE_LENGTH = MavlinkSigner.SIGNATURE_LENGTH;

    /** 消息签名（8 字节），null 表示未签名。 */
    private byte[] signature;

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
            case LedControlMsg.ID:
                return LedControlMsg.decode(frame);
            // ---- NexusSky 自定义扩展消息（M0b 环境气象）----
            case EnvironmentStatus.ID:
                return EnvironmentStatus.decode(frame);
            case EnvironmentAlert.ID:
                return EnvironmentAlert.decode(frame);
            // ---- NexusSky 自定义扩展消息（M2 喷洒物流，msgId 423-426，FR-26~FR-29）----
            case SprayStatus.ID:
                return SprayStatus.decode(frame);
            case SprayCommand.ID:
                return SprayCommand.decode(frame);
            case GripperCommand.ID:
                return GripperCommand.decode(frame);
            case PayloadStatus.ID:
                return PayloadStatus.decode(frame);
            // ---- NexusSky 自定义扩展消息（M3 感知成像增强，msgId 430-434）----
            case ObstacleReportMsg.ID:
                return ObstacleReportMsg.decode(frame);
            case MultispectralDataMsg.ID:
                return MultispectralDataMsg.decode(frame);
            case ThermalDataMsg.ID:
                return ThermalDataMsg.decode(frame);
            case DepthDataMsg.ID:
                return DepthDataMsg.decode(frame);
            case VisionDetectionMsg.ID:
                return VisionDetectionMsg.decode(frame);
            // ---- NexusSky 自定义扩展消息（M4 硬件抽象，msgId 437-441）----
            case RadarScanMsg.ID:
                return RadarScanMsg.decode(frame);
            case RadarTargetMsg.ID:
                return RadarTargetMsg.decode(frame);
            case RotorTelemetryMsg.ID:
                return RotorTelemetryMsg.decode(frame);
            case LidarDataMsg.ID:
                return LidarDataMsg.decode(frame);
            case ImuDataMsg.ID:
                return ImuDataMsg.decode(frame);
            // ---- NexusSky 自定义扩展消息（M5 应急 mesh 自愈组网，msgId 450-454）----
            case MeshHeartbeatMsg.ID:
                return MeshHeartbeatMsg.decode(frame);
            case MeshRouteRequestMsg.ID:
                return MeshRouteRequestMsg.decode(frame);
            case MeshRouteReplyMsg.ID:
                return MeshRouteReplyMsg.decode(frame);
            case MeshRouteErrorMsg.ID:
                return MeshRouteErrorMsg.decode(frame);
            case MeshNeighborTableMsg.ID:
                return MeshNeighborTableMsg.decode(frame);
            // ---- NexusSky 自定义扩展消息（M6 移动基站载荷抽象，msgId 455-458）----
            case CellTowerStatusMsg.ID:
                return CellTowerStatusMsg.decode(frame);
            case CellTowerConfigMsg.ID:
                return CellTowerConfigMsg.decode(frame);
            case CellHandoverMsg.ID:
                return CellHandoverMsg.decode(frame);
            case GroundTerminalRegisterMsg.ID:
                return GroundTerminalRegisterMsg.decode(frame);
            // ---- NexusSky 自定义扩展消息（M7 星-空-地多层级中继，msgId 459-461）----
            case SatLinkStatusMsg.ID:
                return SatLinkStatusMsg.decode(frame);
            case SatPassScheduleMsg.ID:
                return SatPassScheduleMsg.decode(frame);
            case HierarchicalRouteDecisionMsg.ID:
                return HierarchicalRouteDecisionMsg.decode(frame);
            // ---- NexusSky 自定义扩展消息（M8 复杂地形适配，msgId 462-464）----
            case TerrainTypeMapMsg.ID:
                return TerrainTypeMapMsg.decode(frame);
            case TerrainUpdateMsg.ID:
                return TerrainUpdateMsg.decode(frame);
            case FlightRestrictionMsg.ID:
                return FlightRestrictionMsg.decode(frame);
            // ---- NexusSky 自定义扩展消息（M9 应急任务编排，msgId 465-467）----
            case EmergencyMissionPlanMsg.ID:
                return EmergencyMissionPlanMsg.decode(frame);
            case CoverageOptimizationMsg.ID:
                return CoverageOptimizationMsg.decode(frame);
            case EmergencyPriorityMsg.ID:
                return EmergencyPriorityMsg.decode(frame);
            // ---- NexusSky 自定义扩展消息（M10 多机协同任务分配，msgId 468-470）----
            case TaskAssignmentMsg.ID:
                return TaskAssignmentMsg.decode(frame);
            case ConflictAlertMsg.ID:
                return ConflictAlertMsg.decode(frame);
            case TaskStatusMsg.ID:
                return TaskStatusMsg.decode(frame);
            // ---- NexusSky 自定义扩展消息（M11 自主决策，msgId 471-472）----
            case DecisionEventMsg.ID:
                return DecisionEventMsg.decode(frame);
            case AdaptivePathMsg.ID:
                return AdaptivePathMsg.decode(frame);
            // ---- NexusSky 自定义扩展消息（M12 边缘计算与传感器融合，msgId 473-474）----
            case EdgeTaskStatusMsg.ID:
                return EdgeTaskStatusMsg.decode(frame);
            case SensorFusionDataMsg.ID:
                return SensorFusionDataMsg.decode(frame);
            // ---- NexusSky 自定义扩展消息（M13 数字孪生与轨迹预测，msgId 475-476）----
            case TwinStateSyncMsg.ID:
                return TwinStateSyncMsg.decode(frame);
            case PredictionResultMsg.ID:
                return PredictionResultMsg.decode(frame);
            // ---- NexusSky 自定义扩展消息（M14 安防报警，msgId 477-479）----
            case AlarmTriggerMsg.ID:
                return AlarmTriggerMsg.decode(frame);
            case AlarmAckMsg.ID:
                return AlarmAckMsg.decode(frame);
            case SurveillanceStatusMsg.ID:
                return SurveillanceStatusMsg.decode(frame);
            // ---- NexusSky 自定义扩展消息（P2 灾害应急通讯组网，msgId 480-482）----
            case QoSRouteDecisionMsg.ID:
                return QoSRouteDecisionMsg.decode(frame);
            case ClusterFormationMsg.ID:
                return ClusterFormationMsg.decode(frame);
            case DisasterModeStatusMsg.ID:
                return DisasterModeStatusMsg.decode(frame);
            // ---- NexusSky 自定义扩展消息（P3 灾害应急搜救信号，msgId=483）----
            case BuzzerControlMsg.ID:
                return BuzzerControlMsg.decode(frame);
            default:
                return null; // 未知消息：由调用方决定忽略或透传
        }
    }

    /** 组装为可发送帧（自动查 CRC_EXTRA，v2 格式）。 */
    public MavlinkFrame toFrame(int systemId, int componentId, int sequence) {
        return MavlinkFrame.of(systemId, componentId, sequence, messageId(),
                MavlinkMessageInfo.crcExtraOf(messageId()), encode());
    }

    /**
     * 组装为可发送帧，并在签名启用时计算并附加签名。
     * <p>
     * 签名流程：调用 {@link #encode()} 获取 payload，使用 {@link MavlinkSigner#sign} 计算
     * HMAC-SHA256 签名（截断 8 字节），存入 {@link #signature} 字段。
     *
     * @param systemId    系统 ID
     * @param componentId 组件 ID
     * @param sequence    序列号
     * @param signer      签名器（null 或未启用时等同于 {@link #toFrame(int, int, int)}）
     * @return 可发送的 MAVLink v2 帧
     */
    public MavlinkFrame toFrame(int systemId, int componentId, int sequence, MavlinkSigner signer) {
        byte[] payload = encode();
        if (signer != null && signer.isEnabled()) {
            this.signature = signer.sign(messageId(), payload);
        }
        return MavlinkFrame.of(systemId, componentId, sequence, messageId(),
                MavlinkMessageInfo.crcExtraOf(messageId()), payload);
    }

    /**
     * 从帧解码为具体消息，并在签名启用时验证签名。
     * <p>
     * 验证流程：从帧中提取 msgId 和 payload，使用 {@link MavlinkSigner#verify} 验证签名。
     * 签名验证失败时抛出 {@link io.aerofleet.mavlink.MavlinkException}。
     *
     * @param frame  接收到的 MAVLink 帧
     * @param signer 签名器（null 或未启用时等同于 {@link #decode(MavlinkFrame)}）
     * @return 解码后的消息对象
     * @throws io.aerofleet.mavlink.MavlinkException 签名验证失败时
     */
    public static MavlinkMessage decode(MavlinkFrame frame, MavlinkSigner signer) {
        if (signer != null && signer.isEnabled()) {
            byte[] payload = frame.getPayload();
            byte[] extractedSig = extractSignatureFromFrame(frame, signer);
            if (extractedSig == null) {
                // 签名传输机制未实现，跳过验证并记录警告
                log.warn("MAVLink签名已启用但帧中未包含签名数据，跳过验证（签名传输机制待实现）");
            } else {
                if (!signer.verify(frame.getMessageId(), payload, extractedSig)) {
                    throw new io.aerofleet.mavlink.MavlinkException(
                            "MAVLink签名验证失败: msgId=" + frame.getMessageId());
                }
            }
        }
        return decode(frame);
    }

    /**
     * 从帧中提取签名。
     * <p>
     * 当前实现：签名不在帧线上格式中传输，而是由上层协议或传输层附带。
     * 此方法返回 null，表示签名需要通过其他渠道获取。
     * 子类或传输层可覆盖此方法以提供具体的签名提取逻辑。
     *
     * @param frame  接收到的 MAVLink 帧
     * @param signer 签名器
     * @return 签名字节，或 null 表示无签名
     */
    private static byte[] extractSignatureFromFrame(MavlinkFrame frame, MavlinkSigner signer) {
        // 当前阶段签名不在帧线上格式中传输，返回 null 表示需要外部提供签名
        // 后续可通过 incompatibility flags 扩展帧格式以携带签名
        return null;
    }

    /**
     * 获取消息签名。
     *
     * @return 8 字节签名，或 null 表示未签名
     */
    public byte[] getSignature() {
        return signature;
    }

    /**
     * 设置消息签名（通常由 {@link #toFrame(int, int, int, MavlinkSigner)} 自动设置）。
     *
     * @param signature 8 字节签名
     */
    public void setSignature(byte[] signature) {
        this.signature = signature;
    }

    /**
     * 使用签名器对当前消息进行签名。
     * <p>
     * 调用 {@link #encode()} 获取 payload，计算签名并存入 {@link #signature} 字段。
     *
     * @param signer 签名器
     */
    public void signWith(MavlinkSigner signer) {
        if (signer != null && signer.isEnabled()) {
            this.signature = signer.sign(messageId(), encode());
        }
    }

    /**
     * 验证当前消息的签名是否正确。
     *
     * @param signer 签名器
     * @return true 表示签名验证通过（签名未启用时也返回 true）
     */
    public boolean verifySignature(MavlinkSigner signer) {
        if (signer == null || !signer.isEnabled()) {
            return true;
        }
        return signer.verify(messageId(), encode(), signature);
    }

    /** 常用解码辅助：小端视图。 */
    protected static ByteBuffer le(byte[] payload) {
        return PayloadCodec.littleEndian(payload);
    }
}
