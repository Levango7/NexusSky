package io.aerofleet.mavlink.messages;

import io.aerofleet.mavlink.MavlinkFrame;
import io.aerofleet.mavlink.MavlinkMessageInfo;
import io.aerofleet.mavlink.PayloadCodec;
import io.aerofleet.mavlink.security.MavlinkSigner;
import io.aerofleet.mavlink.security.TimestampTracker;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.ByteBuffer;
import java.util.Arrays;

/**
 * MAVLink 消息抽象：每个消息提供 payload 编码与帧解码。
 * 消息类不可变，encode() 产出待组装成帧的 payload 字节。
 * <p>
 * 签名机制：当 {@link MavlinkSigner} 启用时，编码产出的帧会带上 13 字节官方签名块
 * （linkId + 6B 小端时间戳 + 6B sha256_48），解码时分离并验证。签名默认关闭，不影响现有编解码流程。
 */
public abstract class MavlinkMessage {

    private static final Logger log = LoggerFactory.getLogger(MavlinkMessage.class);

    /** 消息默认系统/组件 ID（发送方可通过 wrap 覆盖）。 */
    protected static final int COMP_ID_AUTOPILOT = 1;

    /** 官方签名长度（sha256_48 = SHA-256 摘要前 6 字节）。 */
    public static final int SIGNATURE_LENGTH = MavlinkSigner.SIGNATURE_LENGTH;

    /** 消息签名（6 字节），null 表示未签名。 */
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
            // ---- C2 开放无人机标识（OPEN_DRONE_ID_*，msgId=12900-12905）----
            case OpenDroneIdBasicId.ID:
                return OpenDroneIdBasicId.decode(frame);
            case OpenDroneIdLocation.ID:
                return OpenDroneIdLocation.decode(frame);
            case OpenDroneIdSelfId.ID:
                return OpenDroneIdSelfId.decode(frame);
            case OpenDroneIdSystem.ID:
                return OpenDroneIdSystem.decode(frame);
            case OpenDroneIdOperatorId.ID:
                return OpenDroneIdOperatorId.decode(frame);
            case OpenDroneIdMessagePack.ID:
                return OpenDroneIdMessagePack.decode(frame);
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
     * 组装为可发送帧，并在签名启用时使用标准协议签名。
     * <p>
     * 标准签名流程：
     * <ol>
     *   <li>用 {@link MavlinkFrame#ofSigned} 构造临时签名帧（占位签名）</li>
     *   <li>调用 {@link MavlinkFrame#encodeV2()} 获取帧字节，截取帧头至 CRC 部分</li>
     *   <li>用 {@link MavlinkSigner#sign(byte[], int, long)} 计算标准协议签名</li>
     *   <li>用 {@link MavlinkFrame#ofSigned} 构造最终签名帧</li>
     * </ol>
     *
     * @param systemId    系统 ID
     * @param componentId 组件 ID
     * @param sequence    序列号
     * @param signer      签名器（null 或未启用时等同于 {@link #toFrame(int, int, int)}）
     * @return 可发送的 MAVLink v2 帧
     */
    public MavlinkFrame toFrame(int systemId, int componentId, int sequence, MavlinkSigner signer) {
        byte[] payload = encode();
        int crcExtra = MavlinkMessageInfo.crcExtraOf(messageId());

        if (signer == null || !signer.isEnabled()) {
            return MavlinkFrame.of(systemId, componentId, sequence, messageId(), crcExtra, payload);
        }

        // 标准协议签名：先构造临时签名帧获取 frameBytes（帧头至 CRC），再对其计算真正的签名
        int linkId = signer.getLinkId();
        // 官方时间戳口径：48 位、单位 10 微秒、纪元 2015-01-01（不是 10ms tick）
        long timestamp = MavlinkSigner.currentSigningTimestamp();

        // 用占位签名构造临时帧，以获取正确的帧字节（INC=0x01 的 CRC）
        byte[] placeholderSig = new byte[SIGNATURE_LENGTH];
        MavlinkFrame tempFrame = MavlinkFrame.ofSigned(systemId, componentId, sequence,
                messageId(), crcExtra, payload, linkId, timestamp, placeholderSig);
        byte[] allBytes = tempFrame.encodeV2();
        // 截取帧头至 CRC（不含签名数据）：12 + payloadLength 字节
        int frameBytesLen = 12 + payload.length;
        byte[] frameBytes = Arrays.copyOf(allBytes, frameBytesLen);

        // 计算真正的签名
        this.signature = signer.sign(frameBytes, linkId, timestamp);

        // 构造最终签名帧
        return MavlinkFrame.ofSigned(systemId, componentId, sequence,
                messageId(), crcExtra, payload, linkId, timestamp, this.signature);
    }

    /**
     * 从帧解码为具体消息，并在签名启用时验证签名。
     * <p>
     * 验证流程：
     * <ul>
     *   <li>从帧字段直接获取 signature（{@link MavlinkFrame#getSignature()}）</li>
     *   <li>signature 为 null：按 {@link MavlinkSigner#isRejectUnsigned()} 决定拒绝或告警通过</li>
     *   <li>signature 非 null：调用 {@link MavlinkSigner#verify(byte[], int, long, byte[])} 验证，
     *       失败抛 {@link io.aerofleet.mavlink.MavlinkException}</li>
     * </ul>
     *
     * @param frame  接收到的 MAVLink 帧
     * @param signer 签名器（null 或未启用时等同于 {@link #decode(MavlinkFrame)}）
     * @return 解码后的消息对象
     * @throws io.aerofleet.mavlink.MavlinkException 签名验证失败或拒绝未签名消息时
     */
    public static MavlinkMessage decode(MavlinkFrame frame, MavlinkSigner signer) {
        if (signer != null && signer.isEnabled()) {
            byte[] extractedSig = frame.getSignature();
            if (extractedSig == null) {
                // 帧中未包含签名数据
                if (signer.isRejectUnsigned()) {
                    throw new io.aerofleet.mavlink.MavlinkException(
                            "MAVLink签名已启用且rejectUnsigned=true，拒绝未签名消息: msgId="
                                    + frame.getMessageId());
                }
                log.warn("MAVLink签名已启用但帧中未包含签名数据，告警通过（rejectUnsigned=false）: msgId={}",
                        frame.getMessageId());
            } else {
                // 获取帧字节（帧头至 CRC，不含签名数据）用于标准协议签名验证
                byte[] allBytes = frame.encodeV2();
                int frameBytesLen = 12 + frame.getPayloadLength();
                byte[] frameBytes = Arrays.copyOf(allBytes, frameBytesLen);

                if (!signer.verify(frameBytes, frame.getLinkId(), frame.getTimestamp(), extractedSig)) {
                    throw new io.aerofleet.mavlink.MavlinkException(
                            "MAVLink签名验证失败: msgId=" + frame.getMessageId());
                }
            }
        }
        return decode(frame);
    }

    /**
     * 从帧解码为具体消息，验证签名后额外校验 timestamp 单调性。
     * <p>
     * 在 {@link #decode(MavlinkFrame, MavlinkSigner)} 的基础上，使用 {@link TimestampTracker}
     * 校验签名帧的 timestamp 是否满足单调性要求（防止重放攻击）。
     * timestamp 校验失败时抛 {@link io.aerofleet.mavlink.MavlinkException}。
     *
     * @param frame     接收到的 MAVLink 帧
     * @param signer    签名器
     * @param tsTracker 时间戳跟踪器
     * @return 解码后的消息对象
     * @throws io.aerofleet.mavlink.MavlinkException 签名验证失败、拒绝未签名消息或 timestamp 回退时
     */
    public static MavlinkMessage decode(MavlinkFrame frame, MavlinkSigner signer, TimestampTracker tsTracker) {
        // 先执行签名验证（包含 rejectUnsigned 逻辑）
        MavlinkMessage msg = decode(frame, signer);

        // 签名启用且帧包含签名数据时，额外校验 timestamp 单调性（官方按 link+sysid+compid 分流）
        if (signer != null && signer.isEnabled() && frame.isSigned()) {
            if (tsTracker != null && !tsTracker.check(frame.getLinkId(), frame.getSystemId(),
                    frame.getComponentId(), frame.getTimestamp())) {
                throw new io.aerofleet.mavlink.MavlinkException(
                        "MAVLink timestamp单调性校验失败（疑似重放攻击）: linkId="
                                + frame.getLinkId() + ", sysid=" + frame.getSystemId()
                                + ", compid=" + frame.getComponentId()
                                + ", timestamp=" + frame.getTimestamp());
            }
        }

        return msg;
    }

    /**
     * 获取消息签名。
     *
     * @return 6 字节 sha256_48 签名，或 null 表示未签名
     */
    public byte[] getSignature() {
        return signature;
    }

    /**
     * 设置消息签名（通常由 {@link #toFrame(int, int, int, MavlinkSigner)} 自动设置）。
     *
     * @param signature 6 字节 sha256_48 签名
     */
    public void setSignature(byte[] signature) {
        this.signature = signature;
    }

    /** 常用解码辅助：小端视图。 */
    protected static ByteBuffer le(byte[] payload) {
        return PayloadCodec.littleEndian(payload);
    }
}
