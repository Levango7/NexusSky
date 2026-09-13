package io.aerofleet.mavlink.messages;

import io.aerofleet.mavlink.MavlinkFrame;
import io.aerofleet.mavlink.PayloadCodec;

import java.nio.ByteBuffer;

/**
 * CAMERA_IMAGE_CAPTURED (msgId=263, LEN=255, CRC=133). Broadcast by the
 * camera after every successful capture - the GCS's authoritative photo event.
 *
 * Offsets: time_utc@0 (u64 us), time_boot_ms@8, lat@12 (degE7), lon@16,
 * alt@20 (mm MSL), relative_alt@24 (mm), q[4]@28 (w,x,y,z), image_index@44,
 * camera_id@48, capture_result@49 (i8: 1=ok), file_url[205]@50.
 */
public final class CameraImageCaptured extends MavlinkMessage {

    public static final int ID = 263;
    public static final int LEN = 255;

    public final long timeUtcUs;
    public final long timeBootMs;
    public final int latE7;
    public final int lonE7;
    public final int altMm;
    public final int relativeAltMm;
    public final float[] q;         // quaternion w, x, y, z
    public final int imageIndex;
    public final int cameraId;
    public final int captureResult;
    public final String fileUrl;

    public CameraImageCaptured(long timeUtcUs, long timeBootMs, int latE7, int lonE7,
                               int altMm, int relativeAltMm, float[] q, int imageIndex,
                               int cameraId, int captureResult, String fileUrl) {
        this.timeUtcUs = timeUtcUs;
        this.timeBootMs = timeBootMs;
        this.latE7 = latE7;
        this.lonE7 = lonE7;
        this.altMm = altMm;
        this.relativeAltMm = relativeAltMm;
        this.q = q;
        this.imageIndex = imageIndex;
        this.cameraId = cameraId;
        this.captureResult = captureResult;
        this.fileUrl = fileUrl;
    }

    @Override
    public int messageId() {
        return ID;
    }

    @Override
    public byte[] encode() {
        byte[] buf = PayloadCodec.alloc(LEN);
        PayloadCodec.putU64(buf, 0, timeUtcUs);
        PayloadCodec.putU32(buf, 8, timeBootMs);
        PayloadCodec.putI32(buf, 12, latE7);
        PayloadCodec.putI32(buf, 16, lonE7);
        PayloadCodec.putI32(buf, 20, altMm);
        PayloadCodec.putI32(buf, 24, relativeAltMm);
        for (int i = 0; i < 4; i++) {
            PayloadCodec.putF32(buf, 28 + 4 * i, q[i]);
        }
        PayloadCodec.putI32(buf, 44, imageIndex);
        PayloadCodec.putU8(buf, 48, cameraId);
        PayloadCodec.putI8(buf, 49, captureResult);
        PayloadCodec.putChars(buf, 50, fileUrl, 205);
        return buf;
    }

    public static CameraImageCaptured decode(MavlinkFrame f) {
        ByteBuffer b = le(f.getPayload());
        float[] q = new float[4];
        for (int i = 0; i < 4; i++) {
            q[i] = PayloadCodec.f32(b, 28 + 4 * i);
        }
        return new CameraImageCaptured(
                PayloadCodec.u64(b, 0),
                PayloadCodec.u32(b, 8),
                PayloadCodec.i32(b, 12),
                PayloadCodec.i32(b, 16),
                PayloadCodec.i32(b, 20),
                PayloadCodec.i32(b, 24),
                q,
                PayloadCodec.i32(b, 44),
                PayloadCodec.u8(b, 48),
                PayloadCodec.i8(b, 49),
                PayloadCodec.chars(b, 50, 205));
    }
}
