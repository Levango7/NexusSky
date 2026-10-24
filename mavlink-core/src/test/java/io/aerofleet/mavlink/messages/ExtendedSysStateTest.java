package io.aerofleet.mavlink.messages;

import io.aerofleet.mavlink.MavlinkFrame;
import io.aerofleet.mavlink.MavlinkMessageInfo;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * EXTENDED_SYS_STATE（msgId=245）的编解码与字节级对等。
 * <p>
 * 为什么值得一条专门的测试：这条消息是「接真机后 UI 能不能说清起飞中/降落中」的唯一依据，
 * 而它的正确性**不能靠读代码确认**——CRC_EXTRA 错了，帧会被对端整帧丢弃，本地却一切正常
 * （我们自己发给自己当然能通）。所以判据是**与 pymavlink 生成的官方帧逐字节相等**。
 * <p>
 * 官方帧由 {@code pymavlink.dialects.v20.common} 生成（sysid=1, compid=1, seq=0）：
 * <pre>
 * vtol=3, landed=1 → fd 02 00 00 00 01 01 f5 00 00 03 01 72 06
 * vtol=3, landed=2 → fd 02 00 00 00 01 01 f5 00 00 03 02 1a 2c
 * vtol=3, landed=3 → fd 02 00 00 00 01 01 f5 00 00 03 03 c2 35
 * vtol=3, landed=4 → fd 02 00 00 00 01 01 f5 00 00 03 04 ca 78
 * </pre>
 * 其中 {@code f5 00 00} 是 msgId 245 的小端，末尾两字节是帧 CRC —— 它同时把 CRC_EXTRA=130
 * 钉死了：若 CRC_EXTRA 填错，这里算出的 CRC 一定对不上。
 */
class ExtendedSysStateTest {

    /** "fd020000000101f5000003017206" → byte[] */
    private static byte[] hex(String s) {
        byte[] out = new byte[s.length() / 2];
        for (int i = 0; i < out.length; i++) {
            out[i] = (byte) Integer.parseInt(s.substring(i * 2, i * 2 + 2), 16);
        }
        return out;
    }

    @Test
    @DisplayName("payload 与官方一致：vtol=3/landed=1 → 03 01")
    void payloadMatchesOfficial() {
        assertArrayEquals(new byte[]{3, 1}, new ExtendedSysState(3, 1).encode());
    }

    @Test
    @DisplayName("完整 v2 帧与 pymavlink 生成物逐字节相等（四种飞行阶段）")
    void frameMatchesPymavlinkByteForByte() {
        String[][] cases = {
                {"1", "fd020000000101f5000003017206"},
                {"2", "fd020000000101f5000003021a2c"},
                {"3", "fd020000000101f500000303c235"},
                {"4", "fd020000000101f500000304ca78"},
        };
        for (String[] c : cases) {
            int landed = Integer.parseInt(c[0]);
            byte[] actual = new ExtendedSysState(3, landed).toFrame(1, 1, 0).encodeV2();
            assertArrayEquals(hex(c[1]), actual,
                    "landed_state=" + landed + " 的帧与 pymavlink 不一致（多半是 CRC_EXTRA 填错）");
        }
    }

    @Test
    @DisplayName("CRC_EXTRA 与消息表登记值一致，且等于官方的 130")
    void crcExtraIs130() {
        assertEquals(130, MavlinkMessageInfo.crcExtraOf(ExtendedSysState.ID));
        assertEquals(2, MavlinkMessageInfo.lengthOf(ExtendedSysState.ID));
    }

    @Test
    @DisplayName("编码→解码往返保持两个字段")
    void roundTrip() {
        ExtendedSysState original = new ExtendedSysState(3, 4);
        MavlinkFrame frame = original.toFrame(7, 1, 0);
        ExtendedSysState back = ExtendedSysState.decode(frame);
        assertEquals(3, back.vtolState);
        assertEquals(4, back.landedState);
    }

    @Test
    @DisplayName("MavlinkMessage.decode 能识别 245（此前落到 default 返回 null 被整帧丢弃）")
    void dispatchedByGenericDecoder() {
        MavlinkFrame frame = new ExtendedSysState(3, 2).toFrame(1, 1, 0);
        Object decoded = MavlinkMessage.decode(frame);
        assertTrue(decoded instanceof ExtendedSysState, "245 应被解码成 ExtendedSysState，实际: " + decoded);
        assertEquals(2, ((ExtendedSysState) decoded).landedState);
    }

    @Test
    @DisplayName("越界字节被掩到 0..255，不抛")
    void masksToByte() {
        ExtendedSysState m = new ExtendedSysState(0x103, 0x204);
        assertEquals(3, m.vtolState);
        assertEquals(4, m.landedState);
    }

    @Test
    @DisplayName("飞行阶段中文标签：未定义/未知返回 null，由 UI 决定显示什么")
    void landedStateNames() {
        assertEquals("地面", ExtendedSysState.landedStateName(ExtendedSysState.LANDED_ON_GROUND));
        assertEquals("空中", ExtendedSysState.landedStateName(ExtendedSysState.LANDED_IN_AIR));
        assertEquals("起飞中", ExtendedSysState.landedStateName(ExtendedSysState.LANDED_TAKEOFF));
        assertEquals("降落中", ExtendedSysState.landedStateName(ExtendedSysState.LANDED_LANDING));
        assertNull(ExtendedSysState.landedStateName(ExtendedSysState.LANDED_UNDEFINED));
        assertNull(ExtendedSysState.landedStateName(200));
    }
}
