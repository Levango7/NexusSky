package io.aerofleet.mavlink.messages;

import io.aerofleet.mavlink.MavlinkFrame;
import io.aerofleet.mavlink.PayloadCodec;

import java.nio.ByteBuffer;

/**
 * CAMERA_FOV_STATUS (msgId=271, LEN=53, min 52, CRC=22). Where the camera and
 * the image center look right now - the GCS draws its view frustum from this.
 *
 * Offsets: time_boot_ms@0, lat/lon/alt_camera@4/8/12, lat/lon/alt_image@16/
 * 20/24 (image = boresight ground hit), q[4]@28, hfov@44, vfov@48,
 * camera_device_id@52.
 */
public final class CameraFovStatus extends MavlinkMessage {

    public static final int ID = 271;
    public static final int LEN = 53;

    public final long timeBootMs;
    public final int latCameraE7;
    public final int lonCameraE7;
    public final int altCameraMm;
    public final int latImageE7;
    public final int lonImageE7;
    public final int altImageMm;
    public final float[] q;
    public final float hfovDeg;
    public final float vfovDeg;
    public final int cameraDeviceId;

    public CameraFovStatus(long timeBootMs, int latCameraE7, int lonCameraE7, int altCameraMm,
                           int latImageE7, int lonImageE7, int altImageMm, float[] q,
                           float hfovDeg, float vfovDeg, int cameraDeviceId) {
        this.timeBootMs = timeBootMs;
        this.latCameraE7 = latCameraE7;
        this.lonCameraE7 = lonCameraE7;
        this.altCameraMm = altCameraMm;
        this.latImageE7 = latImageE7;
        this.lonImageE7 = lonImageE7;
        this.altImageMm = altImageMm;
        this.q = q;
        this.hfovDeg = hfovDeg;
        this.vfovDeg = vfovDeg;
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
        PayloadCodec.putI32(buf, 4, latCameraE7);
        PayloadCodec.putI32(buf, 8, lonCameraE7);
        PayloadCodec.putI32(buf, 12, altCameraMm);
        PayloadCodec.putI32(buf, 16, latImageE7);
        PayloadCodec.putI32(buf, 20, lonImageE7);
        PayloadCodec.putI32(buf, 24, altImageMm);
        for (int i = 0; i < 4; i++) {
            PayloadCodec.putF32(buf, 28 + 4 * i, q[i]);
        }
        PayloadCodec.putF32(buf, 44, hfovDeg);
        PayloadCodec.putF32(buf, 48, vfovDeg);
        PayloadCodec.putU8(buf, 52, cameraDeviceId);
        return buf;
    }

    public static CameraFovStatus decode(MavlinkFrame f) {
        ByteBuffer b = le(f.getPayload());
        float[] q = new float[4];
        for (int i = 0; i < 4; i++) {
            q[i] = PayloadCodec.f32(b, 28 + 4 * i);
        }
        int len = f.getPayloadLength();
        return new CameraFovStatus(
                PayloadCodec.u32(b, 0),
                PayloadCodec.i32(b, 4),
                PayloadCodec.i32(b, 8),
                PayloadCodec.i32(b, 12),
                PayloadCodec.i32(b, 16),
                PayloadCodec.i32(b, 20),
                PayloadCodec.i32(b, 24),
                q,
                PayloadCodec.f32(b, 44),
                len > 48 ? PayloadCodec.f32(b, 48) : Float.NaN,
                len > 52 ? PayloadCodec.u8(b, 52) : 0);
    }
}
