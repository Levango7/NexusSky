package io.aerofleet.mavlink;

import io.aerofleet.mavlink.messages.ManualControl;
import io.aerofleet.mavlink.messages.MavlinkMessage;
import io.aerofleet.mavlink.messages.MissionRequestList;
import org.junit.jupiter.api.Test;

import java.nio.ByteBuffer;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Round-trip tests for the new P2 messages: MANUAL_CONTROL (signed axes)
 * and MISSION_REQUEST_LIST (mission download opener).
 */
class P2MessagesTest {

    /** Encode -> parse -> decode, the full wire path both directions use. */
    private static MavlinkMessage roundtrip(MavlinkMessage msg) {
        MavlinkFrame frame = MavlinkFrame.of(255, 190, 0, msg.messageId(),
                MavlinkMessageInfo.crcExtraOf(msg.messageId()), msg.encode());
        MavlinkParser.ParseResult r = new MavlinkParser().parse(ByteBuffer.wrap(frame.encodeV2()));
        assertNotNull(r, "parser must accept the frame (CRC_EXTRA registered?)");
        return MavlinkMessage.decode(r.frame);
    }

    @Test
    void manualControlRoundTrips() {
        Object back = roundtrip(new ManualControl(-800, 650, 420, -250, 0b1011, 1, 1));
        assertInstanceOf(ManualControl.class, back);
        ManualControl in = (ManualControl) back;
        assertEquals(-800, in.x, "signed x axis survives");
        assertEquals(650, in.y, "signed y axis survives");
        assertEquals(420, in.z, "throttle is unsigned");
        assertEquals(-250, in.r, "signed r axis survives");
        assertEquals(0b1011, in.buttons);
        assertEquals(1, in.targetSystem);
        assertEquals(1, in.targetComponent);
    }

    @Test
    void manualControlExtremeAxes() {
        ManualControl in = (ManualControl) roundtrip(
                new ManualControl(-1000, 1000, 0, 1000, 0, 1, 1));
        assertEquals(-1000, in.x);
        assertEquals(1000, in.y);
        assertEquals(0, in.z);
        assertEquals(1000, in.r);
    }

    @Test
    void missionRequestListRoundTrips() {
        Object back = roundtrip(new MissionRequestList(1, 1, 0));
        assertInstanceOf(MissionRequestList.class, back);
        MissionRequestList in = (MissionRequestList) back;
        assertEquals(1, in.targetSystem);
        assertEquals(1, in.targetComponent);
        assertEquals(0, in.missionType);
    }
}
