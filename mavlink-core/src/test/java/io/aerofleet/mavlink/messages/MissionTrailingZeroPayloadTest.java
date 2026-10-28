package io.aerofleet.mavlink.messages;

import io.aerofleet.mavlink.MavlinkFrame;
import io.aerofleet.mavlink.enums.MavEnums;
import org.junit.jupiter.api.Test;

import java.util.Arrays;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * MAVLink2 trailing-zero 裁剪回归（SITL 首验实测抓包）。
 *
 * <p>MAVLink v2 允许发送方裁掉 payload 尾部的全零字节；真固件（ArduPilot 的 C 库）
 * 默认就这么做。本例抓自真实 ArduPilot SITL：任务上传完成后，ACCEPTED 的
 * MISSION_ACK 尾部 type/mission_type/opaque_id 全零被裁，wire payload 只剩
 * [255, 190] 两字节；而 ERROR（type=1）尾部非零得以保留。修复前前者按固定偏移
 * 直读越界、被静默丢帧——上传永远"看不到"成功，直到 2.5s 后重发收到 ERROR 被
 * 误判为拒绝。
 *
 * <p>下面的帧字节是抓包原文（16 进制），直接喂给解码入口，防止再退化。
 */
class MissionTrailingZeroPayloadTest {

    private static byte[] hex(String s) {
        byte[] out = new byte[s.length() / 2];
        for (int i = 0; i < out.length; i++) {
            out[i] = (byte) Integer.parseInt(s.substring(i * 2, i * 2 + 2), 16);
        }
        return out;
    }

    @Test
    void trimmedAcceptedAckDecodes() {
        // MISSION_ACK: target=255/190, type=ACCEPTED(0), mission_type=0, opaque_id=0
        // 尾部全零被裁 → wire payload 只剩 ff be（2 字节）。
        MavlinkFrame frame = MavlinkFrame.decodeV2Verified(
                hex("fd0200008e01012f0000ffbede4b"));
        assertEquals(2, frame.getPayloadLength(), "wire payload 必须是 2 字节（被裁剪）");

        MavlinkMessage msg = MavlinkMessage.decode(frame);
        assertTrue(msg instanceof MissionAckMsg, "trimmed ACK 必须能解码，实际: " + msg);
        MissionAckMsg ack = (MissionAckMsg) msg;
        assertEquals(255, ack.targetSystem);
        assertEquals(190, ack.targetComponent);
        assertEquals(MavEnums.MAV_MISSION_ACCEPTED, ack.type,
                "缺省 type 字节必须按 0 解读 = ACCEPTED");
        assertEquals(0, ack.missionType);
        assertEquals(0, ack.opaqueId);
    }

    @Test
    void untrimmedErrorAckStillDecodes() {
        // 同一协议点位的 ERROR（type=1 非零 → 尾部不被裁），修复前后都必须能解。
        MavlinkFrame frame = MavlinkFrame.decodeV2Verified(
                hex("fd030000a101012f0000ffbe01a9e5"));
        assertEquals(3, frame.getPayloadLength());

        MavlinkMessage msg = MavlinkMessage.decode(frame);
        assertTrue(msg instanceof MissionAckMsg);
        assertEquals(1, ((MissionAckMsg) msg).type);
    }

    @Test
    void trimmedMissionItemDecodes() {
        // MISSION_ITEM_INT 的 RTL 条目：x/y/z 全为 0（真实上传就这么发），尾部
        // current/autocontinue/mission_type 全零被裁。全长 38 字节 → 保留到 frame
        // 字段（35 字节）。
        MissionItemInt full = new MissionItemInt(1, 1, 1,
                MavEnums.MAV_FRAME_GLOBAL_RELATIVE_ALT, MavEnums.MAV_CMD_NAV_RETURN_TO_LAUNCH,
                0, 0, 0f, 0f, 0f, 0f, 0, 0, 0f, MavEnums.MAV_MISSION_TYPE_MISSION);
        byte[] payload = full.encode();
        // 模拟发送方裁剪：去掉尾部全零字节
        int last = payload.length - 1;
        while (last >= 0 && payload[last] == 0) {
            last--;
        }
        byte[] trimmed = Arrays.copyOf(payload, last + 1);
        assertTrue(trimmed.length < payload.length, "测试前提：RTL 条目尾部必须确有全零可裁");

        MavlinkFrame frame = new MavlinkFrame(trimmed.length, 0, 0, 0,
                1, 1, MissionItemInt.ID, trimmed, 0);
        MavlinkMessage msg = MavlinkMessage.decode(frame);
        assertTrue(msg instanceof MissionItemInt, "trimmed item 必须能解码，实际: " + msg);
        MissionItemInt item = (MissionItemInt) msg;
        assertEquals(1, item.seq);
        assertEquals(MavEnums.MAV_CMD_NAV_RETURN_TO_LAUNCH, item.command);
        assertEquals(MavEnums.MAV_FRAME_GLOBAL_RELATIVE_ALT, item.frame);
        assertEquals(1, item.targetSystem);
        assertEquals(0, item.z);
        assertEquals(0, item.current);
        assertEquals(0, item.missionType);
    }

    @Test
    void paddingDoesNotChangeWireLengthSemantics() {
        // 补零只发生在解码入口：getPayloadLength() 仍返回线上（裁剪后）长度，
        // 各解码器"可选字段是否在线上"的判断不受影响；原始 payload 数组不被改写。
        MavlinkFrame frame = MavlinkFrame.decodeV2Verified(
                hex("fd0200008e01012f0000ffbede4b"));
        MavlinkMessage.decode(frame);
        assertEquals(2, frame.getPayloadLength());
        assertEquals(2, frame.getPayload().length, "原始帧对象不得被改写");
    }
}
