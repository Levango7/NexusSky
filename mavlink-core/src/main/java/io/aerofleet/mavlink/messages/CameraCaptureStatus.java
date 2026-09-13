package io.aerofleet.mavlink.messages;

import io.aerofleet.mavlink.MavlinkFrame;
import io.aerofleet.mavlink.PayloadCodec;

import java.nio.ByteBuffer;

/**
 * CAMERA_CAPTURE_STATUS (msgId=262, LEN=23, min 18, CRC=12). Capture counters
 * and storage status, answered to MAV_CMD_REQUEST_CAMERA_CAPTURE_STATUS.
 *
 * Offsets: time_boot_ms@0, image_interval@4, recording_time_ms@8,
 * available_capacity@12, image_status@16, video_status@17, image_count@18
 * (i32), camera_device_id@22.
 */
public final class CameraCaptureStatus extends MavlinkMessage {

    public static final int ID = 262;
    public static final int LEN = 23;

    /** image_status: 0=idle, 1=capture in progress, 2=interval set idle, 3=interval+capturing. */
    public final long timeBootMs;
    public final float imageIntervalS;
    public final long recordingTimeMs;
    public final float availableCapacityMiB;
    public final int imageStatus;
    public final int videoStatus;
    public final int imageCount;
    public final int cameraDeviceId;

    public CameraCaptureStatus(long timeBootMs, float imageIntervalS, long recordingTimeMs,
                               float availableCapacityMiB, int imageStatus, int videoStatus,
                               int imageCount, int cameraDeviceId) {
        this.timeBootMs = timeBootMs;
        this.imageIntervalS = imageIntervalS;
        this.recordingTimeMs = recordingTimeMs;
        this.availableCapacityMiB = availableCapacityMiB;
        this.imageStatus = imageStatus;
        this.videoStatus = videoStatus;
        this.imageCount = imageCount;
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
        PayloadCodec.putF32(buf, 4, imageIntervalS);
        PayloadCodec.putU32(buf, 8, recordingTimeMs);
        PayloadCodec.putF32(buf, 12, availableCapacityMiB);
        PayloadCodec.putU8(buf, 16, imageStatus);
        PayloadCodec.putU8(buf, 17, videoStatus);
        PayloadCodec.putI32(buf, 18, imageCount);
        PayloadCodec.putU8(buf, 22, cameraDeviceId);
        return buf;
    }

    public static CameraCaptureStatus decode(MavlinkFrame f) {
        ByteBuffer b = le(f.getPayload());
        int len = f.getPayloadLength();
        return new CameraCaptureStatus(
                PayloadCodec.u32(b, 0),
                len > 4 ? PayloadCodec.f32(b, 4) : 0,
                len > 8 ? PayloadCodec.u32(b, 8) : 0,
                len > 12 ? PayloadCodec.f32(b, 12) : 0,
                len > 16 ? PayloadCodec.u8(b, 16) : 0,
                len > 17 ? PayloadCodec.u8(b, 17) : 0,
                len > 18 ? PayloadCodec.i32(b, 18) : 0,
                len > 22 ? PayloadCodec.u8(b, 22) : 0);
    }
}
