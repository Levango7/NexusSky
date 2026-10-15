package io.aerofleet.cloud.rid;

import io.aerofleet.cloud.rid.model.BasicIdData;
import io.aerofleet.cloud.rid.model.LocationData;
import io.aerofleet.cloud.rid.model.OperatorIdData;
import io.aerofleet.cloud.rid.model.RidSnapshot;
import io.aerofleet.cloud.rid.model.SelfIdData;
import io.aerofleet.cloud.rid.model.SystemData;
import io.aerofleet.mavlink.MavlinkFrame;
import io.aerofleet.mavlink.MavlinkMessageInfo;
import io.aerofleet.mavlink.messages.MavlinkMessage;
import io.aerofleet.mavlink.messages.OpenDroneIdBasicId;
import io.aerofleet.mavlink.messages.OpenDroneIdLocation;
import io.aerofleet.mavlink.messages.OpenDroneIdMessagePack;
import io.aerofleet.mavlink.messages.OpenDroneIdOperatorId;
import io.aerofleet.mavlink.messages.OpenDroneIdSelfId;
import io.aerofleet.mavlink.messages.OpenDroneIdSystem;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Objects;

/**
 * Remote ID 消息接入服务。
 * <p>
 * 从 MAVLink 帧中解析 OPEN_DRONE_ID_* 消息（msgId 12900-12999），
 * 更新 {@link RidStateManager} 中的 RID 状态，并通过 {@link RidWebSocketHandler}
 * 推送实时更新到 WebSocket 客户端。
 * <p>
 * 处理流程：
 * <ol>
 *   <li>过滤非 RID 消息（msgId &lt; 12900 或 &gt; 12999）</li>
 *   <li>解码 MAVLink 消息</li>
 *   <li>MessagePack 消息先 unpack 再逐条处理</li>
 *   <li>其他 OPEN_DRONE_ID_* 消息直接处理</li>
 *   <li>更新 StateManager 并推送 WebSocket</li>
 * </ol>
 * <p>
 * 位置一致性校验：RID Location 与遥测数据对比，误差 &gt; 0.01° 时 WARN 日志。
 * 操作者信息脱敏：推送 WebSocket 前对 operatorId 脱敏。
 * <p>
 * 畸形帧处理：payload 短于 dialect 声明的 LEN 时解码器会读越界。这类帧的
 * msgId 是已登记的，能过 {@code MavlinkParser} 的 CRC 校验，因此<strong>真的能从
 * 网络到达这里</strong>。{@link #onFrame} 兜住这类异常并记 WARN，不让它冒到
 * 传输层那个 {@code catch (RuntimeException ignore)} 里被完全静默吞掉。
 *
 * @see RidStateManager
 * @see RidWebSocketHandler
 * @see RidConfig
 */
@Service
public class RidIngestService {

    private static final Logger log = LoggerFactory.getLogger(RidIngestService.class);

    /** OPEN_DRONE_ID_* 消息 ID 范围下界。 */
    private static final int RID_MSG_ID_MIN = 12900;
    /** OPEN_DRONE_ID_* 消息 ID 范围上界。 */
    private static final int RID_MSG_ID_MAX = 12999;

    /** 位置一致性校验误差阈值（度）。 */
    private static final double LOCATION_CONSISTENCY_THRESHOLD = 0.01;

    private final RidStateManager stateManager;
    private final RidWebSocketHandler wsHandler;
    private final RidConfig config;

    /**
     * 构造 RID 接入服务。
     *
     * @param stateManager RID 状态管理器
     * @param wsHandler    WebSocket 推送处理器
     * @param config       RID 配置
     */
    public RidIngestService(RidStateManager stateManager,
                            RidWebSocketHandler wsHandler,
                            RidConfig config) {
        this.stateManager = stateManager;
        this.wsHandler = wsHandler;
        this.config = config;
    }

    /**
     * 处理 MAVLink 帧。
     * <p>
     * 过滤非 RID 消息，解码后分发到 {@link #processRidMessage}。
     * MessagePack 消息先 unpack 再逐条处理。
     * <p>
     * 解码与解包都在 {@link #decodeRidMessages} 里兜住 {@link RuntimeException}：
     * 一帧的 payload 长度由发送方决定，短于 dialect 声明的 LEN 时解码器会读越界
     * （{@link java.nio.BufferUnderflowException}）。不兜住的话异常会一路抛到
     * {@code UdpMavlinkTransport} 的 {@code catch (RuntimeException ignore)} ——
     * 收包循环死不了，但连一行日志都没有，截短的 RID 帧被完全静默丢弃、
     * 运维无从发现。兜住之后至少留下一条带 msgId/payloadLen 的 WARN。
     *
     * @param frame MAVLink 帧
     */
    public void onFrame(MavlinkFrame frame) {
        int msgId = frame.getMessageId();
        if (msgId < RID_MSG_ID_MIN || msgId > RID_MSG_ID_MAX) {
            return;
        }

        List<MavlinkMessage> messages = decodeRidMessages(frame, msgId);
        if (messages == null) {
            // 失败原因已由 decodeRidMessages 记入 WARN 日志
            return;
        }

        int sysid = frame.getSystemId();
        for (MavlinkMessage message : messages) {
            processRidMessage(sysid, message);
        }
    }

    /**
     * 解码一帧 RID 消息，必要时把 MessagePack 拆开。
     * <p>
     * 返回空列表表示这一帧没有任何可处理的消息（解包结果全为 null）；
     * 返回 {@code null} 表示解码失败（已记 WARN）。
     *
     * @param frame  MAVLink 帧，msgId 已确认落在 RID 区间内
     * @param msgId  帧的 msgId，仅用于日志
     * @return 待处理的 RID 消息列表；解码失败时返回 {@code null}
     */
    private List<MavlinkMessage> decodeRidMessages(MavlinkFrame frame, int msgId) {
        MavlinkMessage msg;
        try {
            msg = MavlinkMessage.decode(frame);
        } catch (RuntimeException e) {
            log.warn("RID 消息解码异常，该帧已丢弃: msgId={}, sysid={}, payloadLen={}（dialect 期望 {} 字节）, cause={}",
                    msgId, frame.getSystemId(), frame.getPayloadLength(),
                    MavlinkMessageInfo.lengthOf(msgId), e.toString());
            return null;
        }

        if (msg == null) {
            // 防御分支：RID 区间内未在 MavlinkMessageInfo 登记的 msgId，会在 CRC 阶段
            // 就被 MavlinkParser 丢弃（crcExtraOf 抛 MavlinkException），生产路径到不了这里。
            // 保留它是为了有人绕过解析器直接调用 onFrame 时不至于 NPE。
            log.warn("RID 消息解码失败（无对应解码器）: msgId={}, sysid={}", msgId, frame.getSystemId());
            return null;
        }

        if (msg instanceof OpenDroneIdMessagePack pack) {
            try {
                List<MavlinkMessage> subMessages = pack.unpack();
                return subMessages.stream().filter(Objects::nonNull).toList();
            } catch (RuntimeException e) {
                log.warn("RID MessagePack 解包异常，该帧已丢弃: msgId={}, sysid={}, cause={}",
                        msgId, frame.getSystemId(), e.toString());
                return null;
            }
        }
        return List.of(msg);
    }

    /**
     * 处理单条 RID 消息。
     * <p>
     * 根据消息类型调用对应的 stateManager.updateXxx() 方法，
     * LOCATION 消息时额外进行位置一致性校验，
     * 处理后推送 WebSocket 更新。
     *
     * @param sysid 无人机系统标识
     * @param msg    RID 消息
     */
    public void processRidMessage(int sysid, MavlinkMessage msg) {
        if (msg instanceof OpenDroneIdBasicId basicId) {
            BasicIdData data = new BasicIdData(
                    basicId.idType, basicId.uaType,
                    bytesToString(basicId.uasId));
            stateManager.updateBasicId(sysid, data);
            log.debug("RID BasicId 更新: sysid={}, idType={}, uaType={}",
                    sysid, basicId.idType, basicId.uaType);
        } else if (msg instanceof OpenDroneIdLocation location) {
            LocationData data = new LocationData(
                    location.status, location.direction,
                    location.speedHorizontal, location.speedVertical,
                    location.lat(), location.lon(),
                    location.altitudeBarometric, location.altitudeGeodetic,
                    location.heightReference, 0, // height 字段不在消息中，默认 0
                    location.horizontalAccuracy, location.verticalAccuracy,
                    location.barometerAccuracy, location.speedAccuracy,
                    location.timestamp);
            stateManager.updateLocation(sysid, data);
            verifyLocationConsistency(sysid, location.lat(), location.lon());
            log.debug("RID Location 更新: sysid={}, lat={}, lon={}",
                    sysid, location.lat(), location.lon());
        } else if (msg instanceof OpenDroneIdSystem system) {
            SystemData data = new SystemData(
                    system.operatorLocationType,
                    system.operatorLat(), system.operatorLon(),
                    system.areaCount, system.areaRadius,
                    system.areaCeiling, system.areaFloor);
            stateManager.updateSystem(sysid, data);
            log.debug("RID System 更新: sysid={}, operatorLat={}, operatorLon={}",
                    sysid, system.operatorLat(), system.operatorLon());
        } else if (msg instanceof OpenDroneIdSelfId selfId) {
            SelfIdData data = new SelfIdData(
                    selfId.descriptionType,
                    bytesToString(selfId.description));
            stateManager.updateSelfId(sysid, data);
            log.debug("RID SelfId 更新: sysid={}, descriptionType={}",
                    sysid, selfId.descriptionType);
        } else if (msg instanceof OpenDroneIdOperatorId operatorId) {
            OperatorIdData data = new OperatorIdData(
                    operatorId.operatorIdType,
                    bytesToString(operatorId.operatorId));
            stateManager.updateOperatorId(sysid, data);
            log.debug("RID OperatorId 更新: sysid={}, operatorIdType={}",
                    sysid, operatorId.operatorIdType);
        } else {
            log.debug("未识别的 RID 消息类型: sysid={}, msgClass={}",
                    sysid, msg.getClass().getSimpleName());
        }

        // 推送 WebSocket 更新（操作者信息在 wsHandler 中脱敏）
        RidSnapshot snapshot = stateManager.get(sysid);
        if (snapshot != null) {
            wsHandler.pushRidUpdate(sysid, snapshot);
        }
    }

    /**
     * 查询单架无人机的 RID 快照。
     *
     * @param sysid 无人机系统标识
     * @return RID 快照，若不存在返回 {@code null}
     */
    public RidSnapshot getSnapshot(int sysid) {
        return stateManager.get(sysid);
    }

    /**
     * 查询全部无人机的 RID 快照列表。
     *
     * @return RID 快照列表
     */
    public List<RidSnapshot> getAllSnapshots() {
        return stateManager.getAll();
    }

    // --- 私有辅助方法 ---

    /**
     * 位置一致性校验：将 RID Location 与遥测数据对比。
     * <p>
     * 误差 > 0.01° 时输出 WARN 日志。当前实现仅记录日志，
     * 后续可接入遥测服务进行实时对比。
     *
     * @param sysid 无人机系统标识
     * @param ridLat RID 报告纬度
     * @param ridLon RID 报告经度
     */
    private void verifyLocationConsistency(int sysid, double ridLat, double ridLon) {
        // TODO: 接入遥测服务获取实时 GPS 数据进行对比
        // 当前阶段仅记录 RID 位置，后续 C3 阶段接入 TelemetryIngestService
        log.debug("RID 位置一致性校验: sysid={}, ridLat={}, ridLon={}", sysid, ridLat, ridLon);
    }

    /**
     * 将字节数组转换为字符串（去除尾部 0 填充）。
     *
     * @param bytes 字节数组
     * @return 字符串
     */
    private static String bytesToString(byte[] bytes) {
        if (bytes == null) {
            return "";
        }
        // 找到第一个 0 字节的位置，截取有效部分
        int len = bytes.length;
        for (int i = 0; i < bytes.length; i++) {
            if (bytes[i] == 0) {
                len = i;
                break;
            }
        }
        return new String(bytes, 0, len, StandardCharsets.UTF_8);
    }
}