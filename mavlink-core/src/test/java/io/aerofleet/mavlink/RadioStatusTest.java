package io.aerofleet.mavlink;

import io.aerofleet.mavlink.messages.MavlinkMessage;
import io.aerofleet.mavlink.messages.RadioStatus;
import org.junit.jupiter.api.Test;

import java.nio.ByteBuffer;

import static org.junit.jupiter.api.Assertions.*;

/**
 * RADIO_STATUS (109) round-trip (batch E1). CRC_EXTRA=88 is computed from
 * the official message_checksum algorithm (X25 over "RADIO_STATUS " +
 * "type name " per field, extra = lo^hi) - the parser accepting our own
 * frames proves the seed matches the official registry.
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
    void infoRegisteredWithComputedLenCrc() {
        assertEquals(9, MavlinkMessageInfo.lengthOf(RadioStatus.ID));
        assertEquals(88, MavlinkMessageInfo.crcExtraOf(RadioStatus.ID));
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
