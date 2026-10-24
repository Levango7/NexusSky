package io.aerofleet.mavlink.messages;

import io.aerofleet.mavlink.MavlinkFrame;
import io.aerofleet.mavlink.MavlinkMessageInfo;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Arrays;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * AUTOPILOT_VERSION（msgId=148）的编解码与字节级对等。
 * <p>
 * 与 {@code ExtendedSysStateTest} 同一个判据：**与 pymavlink 生成的官方帧逐字节相等**。
 * CRC_EXTRA 填错时本地一切正常、对端整帧丢弃，只有字节级比对能证明。
 * <p>
 * 本条还钉住一个协议细节：MAVLink 2 的<b>扩展字段不参与 CRC_EXTRA</b>。{@code uid2}
 * （偏移 60、18 字节）若也喂进算法会得到 39，而官方是 178 —— 因为只实现了 v1 的那一端
 * 必须能校验这个帧。官方帧由 pymavlink 生成（sysid=1, compid=1, seq=0）：
 * <pre>
 * capabilities=0x0102030405060708, uid=0x778899aabbccddee,
 * flight_sw=0x11223344, middleware_sw=0x55667788, os_sw=0x99aabbcc, board=0xddeeff00,
 * vendor_id=0x3344, product_id=0x5566,
 * flight_custom=[1..8], middleware_custom=[9..16], os_custom=[17..24], uid2=[25..42]
 * → fd 4e 00 00 00 01 01 94 00 00 08 07 06 05 04 03 02 01 ee dd cc bb aa 99 88 77 44 33 22 11
 *   88 77 66 55 cc bb aa 99 00 ff ee dd 44 33 66 55 01..2a 51 73
 * </pre>
 */
class AutopilotVersionTest {

    private static byte[] hex(String s) {
        byte[] out = new byte[s.length() / 2];
        for (int i = 0; i < out.length; i++) {
            out[i] = (byte) Integer.parseInt(s.substring(i * 2, i * 2 + 2), 16);
        }
        return out;
    }

    private static byte[] range(int from, int count) {
        byte[] out = new byte[count];
        for (int i = 0; i < count; i++) {
            out[i] = (byte) (from + i);
        }
        return out;
    }

    /** 与 pymavlink 生成时完全相同的字段值。 */
    private static AutopilotVersion officialSample() {
        return new AutopilotVersion(
                0x0102030405060708L, 0x778899aabbccddeeL,
                0x11223344, 0x55667788, 0x99aabbcc, 0xddeeff00,
                0x3344, 0x5566,
                range(1, 8), range(9, 8), range(17, 8), range(25, 18));
    }

    @Test
    @DisplayName("完整 v2 帧与 pymavlink 生成物逐字节相等（78 字节 payload）")
    void frameMatchesPymavlinkByteForByte() {
        byte[] actual = officialSample().toFrame(1, 1, 0).encodeV2();
        assertArrayEquals(hex(
                "fd4e0000000101940000"
                        + "0807060504030201"
                        + "eeddccbbaa998877"
                        + "44332211"
                        + "88776655"
                        + "ccbbaa99"
                        + "00ffeedd"
                        + "4433"
                        + "6655"
                        + "0102030405060708"
                        + "090a0b0c0d0e0f10"
                        + "1112131415161718"
                        + "191a1b1c1d1e1f202122232425262728292a"
                        + "5173"),
                actual);
    }

    @Test
    @DisplayName("LEN 与 CRC_EXTRA 与官方一致（178 不含扩展字段 uid2）")
    void lengthAndCrcExtraMatchOfficial() {
        assertEquals(78, MavlinkMessageInfo.lengthOf(AutopilotVersion.ID));
        assertEquals(178, MavlinkMessageInfo.crcExtraOf(AutopilotVersion.ID));
    }

    @Test
    @DisplayName("扩展字段 uid2 照样在 payload 里（它只是不参与 CRC）")
    void extensionFieldStillEncoded() {
        AutopilotVersion m = officialSample();
        byte[] payload = m.encode();
        assertEquals(78, payload.length);
        // 偏移 60 起应为 25..42
        byte[] tail = Arrays.copyOfRange(payload, 60, 78);
        assertArrayEquals(range(25, 18), tail);
    }

    @Test
    @DisplayName("编码→解码往返保持 12 个字段")
    void roundTrip() {
        AutopilotVersion back = AutopilotVersion.decode(officialSample().toFrame(7, 1, 0));
        assertEquals(0x0102030405060708L, back.capabilities);
        assertEquals(0x778899aabbccddeeL, back.uid);
        assertEquals(0x11223344, back.flightSwVersion);
        assertEquals(0xddeeff00, back.boardVersion);
        assertEquals(0x3344, back.vendorId);
        assertEquals(0x5566, back.productId);
        assertArrayEquals(range(1, 8), back.flightCustomVersion);
        assertArrayEquals(range(25, 18), back.uid2);
    }

    @Test
    @DisplayName("MavlinkMessage.decode 能识别 148")
    void dispatchedByGenericDecoder() {
        Object decoded = MavlinkMessage.decode(officialSample().toFrame(1, 1, 0));
        assertTrue(decoded instanceof AutopilotVersion, "148 应被解码成 AutopilotVersion，实际: " + decoded);
    }

    @Test
    @DisplayName("固件版本号按 PX4 口径解析；全 0 返回 null")
    void firmwareVersionParsing() {
        // (major<<24)|(minor<<16)|patch
        AutopilotVersion v = new AutopilotVersion(0, 0, (1 << 24) | (14 << 16) | 7, 0, 0, 0, 0, 0,
                null, null, null, null);
        assertEquals("1.14.7", v.firmwareVersion());

        AutopilotVersion zero = new AutopilotVersion(0, 0, 0, 0, 0, 0, 0, 0,
                null, null, null, null);
        assertNull(zero.firmwareVersion());
    }

    @Test
    @DisplayName("短数组/ null 数组不越界（按 0 补齐）")
    void shortArraysPadded() {
        AutopilotVersion m = new AutopilotVersion(0, 0, 1, 0, 0, 0, 0, 0,
                new byte[]{1, 2}, null, null, null);
        assertEquals(1, m.flightCustomVersion[0]);
        assertEquals(2, m.flightCustomVersion[1]);
        assertEquals(0, m.flightCustomVersion[7]);
    }
}
