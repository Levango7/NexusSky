package io.aerofleet.mavlink;

import io.aerofleet.mavlink.messages.MavlinkMessage;
import io.aerofleet.mavlink.messages.RadioStatus;
import org.junit.jupiter.api.Test;

import java.nio.ByteBuffer;

import static org.junit.jupiter.api.Assertions.*;

/**
 * RADIO_STATUS (109) round-trip (batch E1).
 * <p>
 * CRC_EXTRA=185，取自 MAVLink 官方 common.xml。外部核对由
 * {@code python scripts/mavlink-compatibility-check.py --cross-check} 完成
 * （用 pymavlink 官方定义逐条比对）。
 * <p>
 * 注意：下面的往返用例**只能证明内部自洽**（本类编码 → 本仓库存量表 → 本仓理解码），
 * 不能证明与官方注册表一致——两端用的是同一张表，自证循环。官方值必须靠外部参照物核对。
 * 该值此前是 88，与 pymavlink 官方定义不符。
 */
class RadioStatusTest {

    private static MavlinkMessage roundtrip(RadioStatus rs) {
        MavlinkFrame frame = MavlinkFrame.of(9, 1, 0, RadioStatus.ID,
                MavlinkMessageInfo.crcExtraOf(RadioStatus.ID), rs.encode());
        MavlinkParser.ParseResult r = new MavlinkParser().parse(ByteBuffer.wrap(frame.encodeV2()));
        assertNotNull(r, "parser must accept the frame (CRC registered?)");
        return MavlinkMessage.decode(r.frame);
    }

    @Test
    void infoRegisteredWithOfficialLenCrc() {
        assertEquals(9, MavlinkMessageInfo.lengthOf(RadioStatus.ID));
        assertEquals(185, MavlinkMessageInfo.crcExtraOf(RadioStatus.ID));
    }

    @Test
    void radioStatusRoundTrips() {
        RadioStatus back = (RadioStatus) roundtrip(
                new RadioStatus(180, 172, 35, 40, 42, 7, 3));
        assertEquals(180, back.rssi);
        assertEquals(172, back.remrssi);
        assertEquals(35, back.txbuf);
        assertEquals(40, back.noise);
        assertEquals(42, back.remnoise);
        assertEquals(7, back.rxerrors);
        assertEquals(3, back.fixed);
    }

    @Test
    void invalidMarkerRoundTrips() {
        // UINT8_MAX (255) marks invalid/unknown per spec
        RadioStatus back = (RadioStatus) roundtrip(new RadioStatus(
                RadioStatus.INVALID, 100, 50, RadioStatus.INVALID, 60, 0, 0));
        assertEquals(RadioStatus.INVALID, back.rssi);
        assertEquals(RadioStatus.INVALID, back.noise);
        assertEquals(100, back.remrssi);
    }
}
