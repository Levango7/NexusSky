package io.aerofleet.mavlink.messages;

import io.aerofleet.mavlink.MavlinkFrame;
import io.aerofleet.mavlink.MavlinkMessageInfo;
import io.aerofleet.mavlink.enums.MavEnums;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * COMMAND_INT (msgId=75, LEN=35, CRC=158) 回归：pymavlink 官方打包帧逐字节/逐字段比对。
 *
 * <p>存在理由（真机契约）：ArduPilot 对携带经纬度的命令（DO_REPOSITION）**强制 COMMAND_INT
 * 报文**——用 COMMAND_LONG 发一律回 {@code MAV_RESULT=8}（COMMAND_INT_ONLY）。AP SITL 实测：
 * 非 GUIDED 与 GUIDED 悬停中两种状态结果相同，是报文格式要求而非模式问题
 * （docs/sitl-integration.md §11.2-⑨）。此前全栈无这条消息，编队修正/队形机动在真机上
 * 完全不工作。
 *
 * <p>下面的帧字节来自 pymavlink（v20 dialect）官方打包，非手工构造；第二帧特意取
 * {@code current=0, autocontinue=0} 触发 MAVLink2 尾零裁剪（33 字节）——顺带钉住
 * 「解码入口零填充」与 #192 同一族根因。
 */
class CommandIntTest {

    private static byte[] hex(String s) {
        byte[] out = new byte[s.length() / 2];
        for (int i = 0; i < out.length; i++) {
            out[i] = (byte) Integer.parseInt(s.substring(i * 2, i * 2 + 2), 16);
        }
        return out;
    }

    /** 官方全 35 字节帧：ts=1 tc=1 frame=6(REL_ALT_INT) cmd=192 current=0 autocontinue=1，
     *  p1=5.0 p2=0 p3=0 p4=1.5 x=-353628130 y=1491652370 z=25.0（sys=255 comp=190 seq=0）。 */
    private static final String OFFICIAL_FULL35 =
            "fd23000000ffbe4b00000000a04000000000000000000000c03f1e10ecea12cfe8580000c841c0000101060001732e";

    /** 官方裁剪帧：同上但 current=0 autocontinue=0 → 尾两字节全零被裁到 33 字节。 */
    private static final String OFFICIAL_TRIM33 =
            "fd21000000ffbe4b00000000a04000000000000000000000c03f1e10ecea12cfe8580000c841c000010106e7ab";

    private static CommandInt sample() {
        return new CommandInt(1, 1, MavEnums.MAV_FRAME_GLOBAL_RELATIVE_ALT_INT,
                MavEnums.MAV_CMD_DO_REPOSITION, 0, 1,
                5.0f, 0f, 0f, 1.5f, -353628130, 1491652370, 25.0f);
    }

    @Test
    @DisplayName("注册表：id=75 / LEN=35 / CRC_EXTRA=158（pymavlink 核验）")
    void registryHasCommandInt() {
        assertEquals(35, MavlinkMessageInfo.lengthOf(CommandInt.ID));
        assertEquals(158, MavlinkMessageInfo.crcExtraOf(CommandInt.ID));
    }

    @Test
    @DisplayName("编码与官方打包帧逐字节一致（含 CRC）")
    void encodeV2MatchesOfficialFrame() {
        byte[] actual = sample().toFrame(255, 190, 0).encodeV2();
        assertArrayEquals(hex(OFFICIAL_FULL35), actual,
                "本仓编码（不裁剪，35 字节全长）应与 pymavlink 官方帧逐字节一致");
    }

    @Test
    @DisplayName("解码官方裁剪帧（33 字节）：分派入口零填充后字段全部正确")
    void decodeOfficialTrimmedFrame() {
        MavlinkFrame f = MavlinkFrame.decodeV2Verified(hex(OFFICIAL_TRIM33));
        assertEquals(33, f.getPayloadLength(), "wire payload 应为 33 字节（尾零被裁）");

        CommandInt ci = (CommandInt) MavlinkMessage.decode(f);   // 走分派（含零填充）
        assertEquals(192, ci.command);
        assertEquals(MavEnums.MAV_FRAME_GLOBAL_RELATIVE_ALT_INT, ci.frame);
        assertEquals(1, ci.targetSystem);
        assertEquals(1, ci.targetComponent);
        assertEquals(0, ci.current);
        assertEquals(0, ci.autocontinue, "被裁掉的 autocontinue 应零填充为 0");
        assertEquals(5.0f, ci.param1);
        assertEquals(1.5f, ci.param4);
        assertEquals(-353628130, ci.x);
        assertEquals(1491652370, ci.y);
        assertEquals(25.0f, ci.z);
    }

    @Test
    @DisplayName("字节级往返：编码 → v2 校验解码 → 字段不变")
    void roundTripThroughBytes() {
        MavlinkFrame wire = MavlinkFrame.decodeV2Verified(sample().toFrame(255, 190, 0).encodeV2());
        CommandInt back = (CommandInt) MavlinkMessage.decode(wire);
        assertEquals(192, back.command);
        assertEquals(MavEnums.MAV_FRAME_GLOBAL_RELATIVE_ALT_INT, back.frame);
        assertEquals(1, back.autocontinue);
        assertEquals(5.0f, back.param1);
        assertEquals(-353628130, back.x);
        assertEquals(1491652370, back.y);
        assertEquals(25.0f, back.z);
    }
}
