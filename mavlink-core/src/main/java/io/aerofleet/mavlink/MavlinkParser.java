package io.aerofleet.mavlink;

import java.nio.ByteBuffer;

/**
 * MAVLink v1/v2 流解析器：从任意分片的字节流中切出完整帧并做 CRC 校验。
 *
 * CRC 覆盖范围与官方 mavlink_helpers.h::mavlink_finalize_message_buffer 完全一致：
 * 从 len 字节开始（不含 STX）：[len][incompat/compat(仅 v2)][seq][sysid][compid][msgid(1或3字节)][payload][crc_extra]。
 * v1 帧（STX=0xFE，核心头 6 字节）按 v1 的 msgid 单字节校验，成功后以 v2 语义表示。
 * v2 带签名（incompat bit0）的帧当前接受但不验证签名（骨架阶段不做链路签名验证）。
 */
public final class MavlinkParser {

    private long noiseBytes;
    private long framesParsed;
    private long crcErrors;

    public MavlinkParser() {
    }

    /**
     * 尽力从 buffer 头部解析一帧。成功返回 ParseResult 并消费对应字节；
     * 返回 null 表示数据不足（半帧）——调用方应保留 buffer 等待后续数据。
     * CRC 失败或消息未知的帧被丢弃，从下一字节继续扫描 STX。
     */
    public ParseResult parse(ByteBuffer buffer) {
        while (true) {
            // 跳过 STX 之前的噪声字节
            while (buffer.remaining() >= 1) {
                int first = buffer.get(buffer.position()) & 0xFF;
                if (first == MavlinkFrame.STX_V1 || first == MavlinkFrame.STX_V2) {
                    break;
                }
                buffer.get();
                noiseBytes++;
            }
            if (buffer.remaining() < 1) {
                return null;
            }
            int stx = buffer.get(buffer.position()) & 0xFF;
            // 头部（不含 STX）：v2 = len+2兼容+seq+sys+comp+3*msgid = 10 字节；v1 = 6 字节
            int headerLen = (stx == MavlinkFrame.STX_V2) ? 10 : 6;
            if (buffer.remaining() < headerLen) {
                return null;
            }

            int pos = buffer.position();
            int payloadLen = buffer.get(pos + 1) & 0xFF;
            int incompat = 0;
            int compat = 0;
            int seq;
            int sysId;
            int compId;
            int msgId;
            if (stx == MavlinkFrame.STX_V2) {
                incompat = buffer.get(pos + 2) & 0xFF;
                compat = buffer.get(pos + 3) & 0xFF;
                seq = buffer.get(pos + 4) & 0xFF;
                sysId = buffer.get(pos + 5) & 0xFF;
                compId = buffer.get(pos + 6) & 0xFF;
                msgId = (buffer.get(pos + 7) & 0xFF)
                        | ((buffer.get(pos + 8) & 0xFF) << 8)
                        | ((buffer.get(pos + 9) & 0xFF) << 16);
            } else {
                seq = buffer.get(pos + 2) & 0xFF;
                sysId = buffer.get(pos + 3) & 0xFF;
                compId = buffer.get(pos + 4) & 0xFF;
                msgId = buffer.get(pos + 5) & 0xFF;
            }

            int sigLen = ((incompat & 0x01) != 0) ? 13 : 0;
            int totalLen = headerLen + payloadLen + 2 + sigLen;
            if (buffer.remaining() < totalLen) {
                return null;
            }

            byte[] payload = new byte[payloadLen];
            for (int i = 0; i < payloadLen; i++) {
                payload[i] = buffer.get(pos + headerLen + i);
            }
            int crc = (buffer.get(pos + headerLen + payloadLen) & 0xFF)
                    | ((buffer.get(pos + headerLen + payloadLen + 1) & 0xFF) << 8);

            boolean ok;
            try {
                ok = crc == computeCrc(stx, payloadLen, incompat, compat, seq,
                        sysId, compId, msgId, payload);
            } catch (MavlinkException e) {
                ok = false;
            }
            if (!ok) {
                buffer.position(pos + 1);
                crcErrors++;
                continue;
            }

            buffer.position(pos + totalLen);
            framesParsed++;
            return new ParseResult(new MavlinkFrame(payloadLen, incompat, compat, seq,
                    sysId, compId, msgId, payload, crc), stx == MavlinkFrame.STX_V2);
        }
    }

    /**
     * 与官方实现一致的 CRC 计算：len 起步（跳过 STX），覆盖头剩余字段 + payload + CRC_EXTRA。
     * v1 与 v2 的 msgid 字节数不同（1 vs 3），v2 多两个兼容标志字节，其余一致。
     */
    private int computeCrc(int stx, int payloadLen, int incompat, int compat,
                           int seq, int sysId, int compId, int msgId, byte[] payload) {
        int crc = MavlinkCrc.init();
        crc = MavlinkCrc.accumulate(crc, payloadLen);
        if (stx == MavlinkFrame.STX_V2) {
            crc = MavlinkCrc.accumulate(crc, incompat);
            crc = MavlinkCrc.accumulate(crc, compat);
            crc = MavlinkCrc.accumulate(crc, seq);
            crc = MavlinkCrc.accumulate(crc, sysId);
            crc = MavlinkCrc.accumulate(crc, compId);
            crc = MavlinkCrc.accumulate(crc, msgId & 0xFF);
            crc = MavlinkCrc.accumulate(crc, (msgId >> 8) & 0xFF);
            crc = MavlinkCrc.accumulate(crc, (msgId >> 16) & 0xFF);
        } else {
            crc = MavlinkCrc.accumulate(crc, seq);
            crc = MavlinkCrc.accumulate(crc, sysId);
            crc = MavlinkCrc.accumulate(crc, compId);
            crc = MavlinkCrc.accumulate(crc, msgId & 0xFF);
        }
        crc = MavlinkCrc.accumulate(crc, payload, 0, payload.length);
        crc = MavlinkCrc.accumulate(crc, MavlinkMessageInfo.crcExtraOf(msgId));
        return crc;
    }

    public long getNoiseBytes() {
        return noiseBytes;
    }

    public long getCrcErrors() {
        return crcErrors;
    }

    public long getFramesParsed() {
        return framesParsed;
    }

    public static final class ParseResult {
        public final MavlinkFrame frame;
        public final boolean isV2;

        ParseResult(MavlinkFrame frame, boolean isV2) {
            this.frame = frame;
            this.isV2 = isV2;
        }
    }
}
