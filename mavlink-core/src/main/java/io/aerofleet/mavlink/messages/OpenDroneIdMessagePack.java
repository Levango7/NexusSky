package io.aerofleet.mavlink.messages;

import io.aerofleet.mavlink.MavlinkFrame;
import io.aerofleet.mavlink.PayloadCodec;

import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;

/**
 * OPEN_DRONE_ID_MESSAGE_PACK (msgId=12915, LEN=252, CRC_EXTRA=109)。
 * 将多条 OPEN_DRONE_ID_* 消息打包到一个 payload 中传输。
 * <p>
 * payload 结构：single_msg_size(1B) + msg_pack_size(1B) + messages(250B) = 252B。
 * 每条子消息编码为 25 字节：1字节 msgId + 24字节 payload（截取/填充）。
 * 最大支持 10 条子消息。
 */
public final class OpenDroneIdMessagePack extends MavlinkMessage {

    public static final int ID = 12915;
    public static final int LEN = 252;
    public static final int SINGLE_MSG_SIZE = 25;   // 每条子消息编码大小：1B msgId + 24B payload
    public static final int MAX_MSG_PACK_SIZE = 10;  // 最大打包子消息条数
    public static final int MESSAGES_FIELD_LEN = 250; // messages 字段长度

    private final int singleMsgSize;  // 单条子消息编码大小
    private final int msgPackSize;    // 实际打包的子消息条数
    private final byte[] messages;    // 250字节，存放子消息数据

    public OpenDroneIdMessagePack(int singleMsgSize, int msgPackSize, byte[] messages) {
        this.singleMsgSize = singleMsgSize;
        this.msgPackSize = msgPackSize;
        this.messages = messages;
    }

    @Override
    public int messageId() {
        return ID;
    }

    @Override
    public byte[] encode() {
        byte[] buf = PayloadCodec.alloc(LEN);
        PayloadCodec.putU8(buf, 0, singleMsgSize);
        PayloadCodec.putU8(buf, 1, msgPackSize);
        System.arraycopy(messages, 0, buf, 2, MESSAGES_FIELD_LEN);
        return buf;
    }

    public static OpenDroneIdMessagePack decode(MavlinkFrame f) {
        ByteBuffer b = le(f.getPayload());
        int singleMsgSize = PayloadCodec.u8(b, 0);
        int msgPackSize = PayloadCodec.u8(b, 1);
        byte[] messages = new byte[MESSAGES_FIELD_LEN];
        b.position(2);
        b.get(messages);
        return new OpenDroneIdMessagePack(singleMsgSize, msgPackSize, messages);
    }

    /**
     * 将多条 OPEN_DRONE_ID_* 消息打包为 MessagePack。
     * <p>
     * 每条子消息编码为 25 字节：1字节 msgId + 24字节 payload。
     * payload 从该消息的 encode() 结果截取前 24 字节，不足补 0。
     * 最大 10 条，超过抛 IllegalArgumentException。
     *
     * @param msgs 待打包的 OPEN_DRONE_ID_* 消息列表
     * @return 打包后的 MessagePack 对象
     * @throws IllegalArgumentException 消息条数超过 10
     */
    public static OpenDroneIdMessagePack pack(List<MavlinkMessage> msgs) {
        if (msgs.size() > MAX_MSG_PACK_SIZE) {
            throw new IllegalArgumentException(
                    "MessagePack 最多打包 " + MAX_MSG_PACK_SIZE + " 条消息，实际: " + msgs.size());
        }
        byte[] messages = new byte[MESSAGES_FIELD_LEN];
        int msgPackSize = msgs.size();
        for (int i = 0; i < msgPackSize; i++) {
            MavlinkMessage msg = msgs.get(i);
            byte[] encoded = msg.encode();
            int offset = i * SINGLE_MSG_SIZE;
            // 1字节 msgId
            PayloadCodec.putU8(messages, offset, msg.messageId());
            // 24字节 payload（截取前24字节，不足补0——数组默认初始化为0）
            int copyLen = Math.min(encoded.length, SINGLE_MSG_SIZE - 1);
            System.arraycopy(encoded, 0, messages, offset + 1, copyLen);
        }
        return new OpenDroneIdMessagePack(SINGLE_MSG_SIZE, msgPackSize, messages);
    }

    /**
     * 解包 MESSAGE_PACK，返回子消息列表。
     * <p>
     * 根据 singleMsgSize 切分 messages 数据，每条子消息首字节为 msgId，
     * 根据 msgId 分发到对应 decode 方法。对于 payload 被截断的子消息，
     * 扩展至完整长度后解码（超出截断点的字段默认为 0）。
     *
     * @return 解包后的子消息列表
     */
    public List<MavlinkMessage> unpack() {
        List<MavlinkMessage> result = new ArrayList<>(msgPackSize);
        for (int i = 0; i < msgPackSize; i++) {
            int offset = i * singleMsgSize;
            // OPEN_DRONE_ID_* 消息族 msgId 在 12900-12999 (0x32xx) 范围内，
            // 子消息首字节存储 msgId 的低字节，需还原完整 msgId
            int subMsgId = (messages[offset] & 0xFF) | 0x3200;
            // 提取 24 字节子消息 payload
            byte[] subPayload = new byte[SINGLE_MSG_SIZE - 1];
            System.arraycopy(messages, offset + 1, subPayload, 0, SINGLE_MSG_SIZE - 1);
            // 根据子消息类型确定完整 payload 长度，扩展后构造帧解码
            int fullLen = getSubMessageLen(subMsgId);
            byte[] fullPayload = new byte[fullLen];
            System.arraycopy(subPayload, 0, fullPayload, 0, Math.min(SINGLE_MSG_SIZE - 1, fullLen));
            // 构造帧（CRC 不重要，decode 只读 payload）
            MavlinkFrame subFrame = new MavlinkFrame(fullLen, 0, 0, 0, 1, 1,
                    subMsgId, fullPayload, 0);
            result.add(decodeSubMessage(subMsgId, subFrame));
        }
        return result;
    }

    /** 根据子消息 msgId 获取完整 payload 长度。 */
    private static int getSubMessageLen(int subMsgId) {
        switch (subMsgId) {
            case OpenDroneIdBasicId.ID:    return OpenDroneIdBasicId.LEN;
            case OpenDroneIdLocation.ID:   return OpenDroneIdLocation.LEN;
            case OpenDroneIdSelfId.ID:     return OpenDroneIdSelfId.LEN;
            case OpenDroneIdSystem.ID:     return OpenDroneIdSystem.LEN;
            case OpenDroneIdOperatorId.ID: return OpenDroneIdOperatorId.LEN;
            default: return SINGLE_MSG_SIZE - 1; // 未知消息类型，保持 24 字节
        }
    }

    /** 根据子消息 msgId 分发到对应 decode 方法。 */
    private static MavlinkMessage decodeSubMessage(int subMsgId, MavlinkFrame frame) {
        switch (subMsgId) {
            case OpenDroneIdBasicId.ID:    return OpenDroneIdBasicId.decode(frame);
            case OpenDroneIdLocation.ID:   return OpenDroneIdLocation.decode(frame);
            case OpenDroneIdSelfId.ID:     return OpenDroneIdSelfId.decode(frame);
            case OpenDroneIdSystem.ID:     return OpenDroneIdSystem.decode(frame);
            case OpenDroneIdOperatorId.ID: return OpenDroneIdOperatorId.decode(frame);
            default: return null; // 未知子消息类型
        }
    }

    public int getSingleMsgSize() {
        return singleMsgSize;
    }

    public int getMsgPackSize() {
        return msgPackSize;
    }

    public byte[] getMessages() {
        return messages;
    }

    @Override
    public String toString() {
        return "OpenDroneIdMessagePack{singleMsgSize=" + singleMsgSize
                + ", msgPackSize=" + msgPackSize + "}";
    }
}