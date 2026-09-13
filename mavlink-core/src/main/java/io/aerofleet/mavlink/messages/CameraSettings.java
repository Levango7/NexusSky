package io.aerofleet.mavlink.messages;

import io.aerofleet.mavlink.MavlinkFrame;
import io.aerofleet.mavlink.PayloadCodec;

import java.nio.ByteBuffer;

/**
 * CAMERA_SETTINGS (msgId=260, LEN=14, min 5, CRC=146). Current camera mode /
 * zoom / focus, answered to MAV_CMD_REQUEST_CAMERA_SETTINGS.
 *
 * Offsets: time_boot_ms@0, mode_id@4, zoomLevel@5, focusLevel@9,
 * camera_device_id@13.
 */
public final class CameraSettings extends MavlinkMessage {

    public static final int ID = 260;
    public static final int LEN = 14;

    /** CAMERA_MODE: 0=IMAGE, 1=VIDEO, 2=IR_DIFFERENCE, 3=ELUX. */
    public final long timeBootMs;
    public final int modeId;
    public final float zoomLevel;
    public final float focusLevel;
    public final int cameraDeviceId;

    public CameraSettings(long timeBootMs, int modeId, float zoomLevel,
                          float focusLevel, int cameraDeviceId) {
        this.timeBootMs = timeBootMs;
        this.modeId = modeId;
        this.zoomLevel = zoomLevel;
        this.focusLevel = focusLevel;
        this.cameraDeviceId = cameraDeviceId;
    }

    @Override
    public int messageId() {
        return ID;
    }

    @Override
    public byte[] encode() {
        byte[] buf = PayloadCodec.alloc(LEN);
        PayloadCodec.putU32(buf, 0, timeBootMs);
        PayloadCodec.putU8(buf, 4, modeId);
        PayloadCodec.putF32(buf, 5, zoomLevel);
        PayloadCodec.putF32(buf, 9, focusLevel);
        PayloadCodec.putU8(buf, 13, cameraDeviceId);
        return buf;
    }

    public static CameraSettings decode(MavlinkFrame f) {
        ByteBuffer b = le(f.getPayload());
        int len = f.getPayloadLength();
        return new CameraSettings(
                PayloadCodec.u32(b, 0),
                PayloadCodec.u8(b, 4),
                len > 5 ? PayloadCodec.f32(b, 5) : Float.NaN,
                len > 9 ? PayloadCodec.f32(b, 9) : Float.NaN,
                len > 13 ? PayloadCodec.u8(b, 13) : 0);
    }
}
