package io.aerofleet.mavlink;

import io.aerofleet.mavlink.messages.CameraCaptureStatus;
import io.aerofleet.mavlink.messages.CameraFovStatus;
import io.aerofleet.mavlink.messages.CameraImageCaptured;
import io.aerofleet.mavlink.messages.CameraInformation;
import io.aerofleet.mavlink.messages.CameraSettings;
import io.aerofleet.mavlink.messages.MavlinkMessage;
import org.junit.jupiter.api.Test;

import java.nio.ByteBuffer;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Camera-protocol message round-trip tests (batch A1). LEN/CRC_EXTRA pairs
 * are pinned from the official c_library_v2 headers - the parser accepting
 * our own frames proves the CRC seed matches the official one.
 */
class CameraMessagesTest {

    private static MavlinkMessage roundtrip(MavlinkMessage msg) {
        MavlinkFrame frame = MavlinkFrame.of(255, 190, 0, msg.messageId(),
                MavlinkMessageInfo.crcExtraOf(msg.messageId()), msg.encode());
        MavlinkParser.ParseResult r = new MavlinkParser().parse(ByteBuffer.wrap(frame.encodeV2()));
        assertNotNull(r, "parser must accept the frame (CRC registered?)");
        return MavlinkMessage.decode(r.frame);
    }

    @Test
    void infoRegisteredWithOfficialLenCrc() {
        assertEquals(237, MavlinkMessageInfo.lengthOf(CameraInformation.ID));
        assertEquals(92, MavlinkMessageInfo.crcExtraOf(CameraInformation.ID));
        assertEquals(14, MavlinkMessageInfo.lengthOf(CameraSettings.ID));
        assertEquals(146, MavlinkMessageInfo.crcExtraOf(CameraSettings.ID));
        assertEquals(23, MavlinkMessageInfo.lengthOf(CameraCaptureStatus.ID));
        assertEquals(12, MavlinkMessageInfo.crcExtraOf(CameraCaptureStatus.ID));
        assertEquals(255, MavlinkMessageInfo.lengthOf(CameraImageCaptured.ID));
        assertEquals(133, MavlinkMessageInfo.crcExtraOf(CameraImageCaptured.ID));
        assertEquals(53, MavlinkMessageInfo.lengthOf(CameraFovStatus.ID));
        assertEquals(22, MavlinkMessageInfo.crcExtraOf(CameraFovStatus.ID));
    }

    @Test
    void cameraInformationRoundTrips() {
        CameraInformation back = (CameraInformation) roundtrip(new CameraInformation(
                12345L, 0x01020304L, 4.5f, 6.4f, 4.8f,
                0x03FFL, 1920, 1080, 1,
                "AeroFleet", "SIM-CAM-90", 0, "mavlinkftp://camdef.xml.xz", 1, 0));
        assertEquals(12345L, back.timeBootMs);
        assertEquals(0x01020304L, back.firmwareVersion);
        assertEquals(4.5f, back.focalLengthMm);
        assertEquals(6.4f, back.sensorSizeHMm);
        assertEquals(4.8f, back.sensorSizeVMm);
        assertEquals(0x03FFL, back.flags);
        assertEquals(1920, back.resolutionH);
        assertEquals(1080, back.resolutionV);
        assertEquals(1, back.camDefinitionVersion);
        assertEquals("AeroFleet", back.vendorName);
        assertEquals("SIM-CAM-90", back.modelName);
        assertEquals(0, back.lensId);
        assertEquals("mavlinkftp://camdef.xml.xz", back.camDefinitionUri);
        assertEquals(1, back.gimbalDeviceId);
        assertEquals(0, back.cameraDeviceId);
    }

    @Test
    void cameraInformationTruncatesLongStrings() {
        // 40+ char strings must fit the fixed 32-byte fields without breaking
        CameraInformation back = (CameraInformation) roundtrip(new CameraInformation(
                1, 0, 1, 1, 1, 0, 1, 1, 0,
                "V".repeat(50), "M".repeat(50), 0, "U".repeat(300), 0, 0));
        assertEquals(32, back.vendorName.length(), "vendor clipped to field size");
        assertEquals(32, back.modelName.length());
        assertTrue(back.camDefinitionUri.length() <= 140);
    }

    @Test
    void cameraSettingsRoundTrips() {
        CameraSettings back = (CameraSettings) roundtrip(new CameraSettings(
                98765L, 0, 50.0f, 25.5f, 0));
        assertEquals(98765L, back.timeBootMs);
        assertEquals(0, back.modeId);
        assertEquals(50.0f, back.zoomLevel);
        assertEquals(25.5f, back.focusLevel);
        assertEquals(0, back.cameraDeviceId);
    }

    @Test
    void cameraCaptureStatusRoundTrips() {
        CameraCaptureStatus back = (CameraCaptureStatus) roundtrip(new CameraCaptureStatus(
                1000L, 2.0f, 5000L, 4096.0f, 3, 1, 42, 0));
        assertEquals(1000L, back.timeBootMs);
        assertEquals(2.0f, back.imageIntervalS);
        assertEquals(5000L, back.recordingTimeMs);
        assertEquals(4096.0f, back.availableCapacityMiB);
        assertEquals(3, back.imageStatus);
        assertEquals(1, back.videoStatus);
        assertEquals(42, back.imageCount);
        assertEquals(0, back.cameraDeviceId);
    }

    @Test
    void cameraImageCapturedRoundTrips() {
        float[] q = {1.0f, 0.1f, -0.2f, 0.05f};
        CameraImageCaptured back = (CameraImageCaptured) roundtrip(new CameraImageCaptured(
                1789245000000000L, 54321L, 225907000, 1139345000,
                30000, 60000, q, 7, 0, 1, "http://127.0.0.1:18080/camera/shots"));
        assertEquals(1789245000000000L, back.timeUtcUs);
        assertEquals(54321L, back.timeBootMs);
        assertEquals(225907000, back.latE7);
        assertEquals(1139345000, back.lonE7);
        assertEquals(30000, back.altMm);
        assertEquals(60000, back.relativeAltMm);
        assertArrayEquals(q, back.q, 1e-6f);
        assertEquals(7, back.imageIndex);
        assertEquals(1, back.captureResult);
        assertEquals("http://127.0.0.1:18080/camera/shots", back.fileUrl);
    }

    @Test
    void cameraFovStatusRoundTrips() {
        float[] q = {0.99f, 0.01f, 0.02f, 0.03f};
        CameraFovStatus back = (CameraFovStatus) roundtrip(new CameraFovStatus(
                111L, 225907000, 1139345000, 60000,
                225908000, 1139346000, 0, q, 90.0f, 55.0f, 0));
        assertEquals(111L, back.timeBootMs);
        assertEquals(225907000, back.latCameraE7);
        assertEquals(1139345000, back.lonCameraE7);
        assertEquals(60000, back.altCameraMm);
        assertEquals(225908000, back.latImageE7);
        assertEquals(1139346000, back.lonImageE7);
        assertEquals(0, back.altImageMm);
        assertArrayEquals(q, back.q, 1e-6f);
        assertEquals(90.0f, back.hfovDeg);
        assertEquals(55.0f, back.vfovDeg);
        assertEquals(0, back.cameraDeviceId);
    }

    @Test
    void vfrHudSizedFieldsStayPut() {
        // Regression guard: adding camera messages must not disturb the
        // existing message table (spot-check a couple of neighbors).
        assertEquals(20, MavlinkMessageInfo.lengthOf(74));   // VFR_HUD
        assertEquals(20, MavlinkMessageInfo.crcExtraOf(74));
        assertEquals(54, MavlinkMessageInfo.lengthOf(253));  // STATUSTEXT
        assertEquals(83, MavlinkMessageInfo.crcExtraOf(253));
    }
}
