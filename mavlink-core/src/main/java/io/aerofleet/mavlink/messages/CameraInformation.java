package io.aerofleet.mavlink.messages;

import io.aerofleet.mavlink.MavlinkFrame;
import io.aerofleet.mavlink.PayloadCodec;

import java.nio.ByteBuffer;

/**
 * CAMERA_INFORMATION (msgId=259, LEN=237, min 235, CRC=92). Camera capability
 * announcement, answered to MAV_CMD_REQUEST_CAMERA_INFORMATION.
 *
 * Field offsets from the official c_library_v2 header (2026-09 snapshot):
 * time_boot_ms@0, firmware_version@4, focal_length@8, sensor_size_h@12,
 * sensor_size_v@16, flags@20, resolution_h@24, resolution_v@26,
 * cam_definition_version@28, vendor_name[32]@30, model_name[32]@62,
 * lens_id@94, cam_definition_uri[140]@95, gimbal_device_id@235,
 * camera_device_id@236.
 */
public final class CameraInformation extends MavlinkMessage {

    public static final int ID = 259;
    public static final int LEN = 237;

    public final long timeBootMs;
    public final long firmwareVersion;
    public final float focalLengthMm;
    public final float sensorSizeHMm;
    public final float sensorSizeVMm;
    public final long flags;
    public final int resolutionH;
    public final int resolutionV;
    public final int camDefinitionVersion;
    public final String vendorName;
    public final String modelName;
    public final int lensId;
    public final String camDefinitionUri;
    public final int gimbalDeviceId;
    public final int cameraDeviceId;

    public CameraInformation(long timeBootMs, long firmwareVersion, float focalLengthMm,
                             float sensorSizeHMm, float sensorSizeVMm, long flags,
                             int resolutionH, int resolutionV, int camDefinitionVersion,
                             String vendorName, String modelName, int lensId,
                             String camDefinitionUri, int gimbalDeviceId, int cameraDeviceId) {
        this.timeBootMs = timeBootMs;
        this.firmwareVersion = firmwareVersion;
        this.focalLengthMm = focalLengthMm;
        this.sensorSizeHMm = sensorSizeHMm;
        this.sensorSizeVMm = sensorSizeVMm;
        this.flags = flags;
        this.resolutionH = resolutionH;
        this.resolutionV = resolutionV;
        this.camDefinitionVersion = camDefinitionVersion;
        this.vendorName = vendorName;
        this.modelName = modelName;
        this.lensId = lensId;
        this.camDefinitionUri = camDefinitionUri;
        this.gimbalDeviceId = gimbalDeviceId;
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
        PayloadCodec.putU32(buf, 4, firmwareVersion);
        PayloadCodec.putF32(buf, 8, focalLengthMm);
        PayloadCodec.putF32(buf, 12, sensorSizeHMm);
        PayloadCodec.putF32(buf, 16, sensorSizeVMm);
        PayloadCodec.putU32(buf, 20, flags);
        PayloadCodec.putU16(buf, 24, resolutionH);
        PayloadCodec.putU16(buf, 26, resolutionV);
        PayloadCodec.putU16(buf, 28, camDefinitionVersion);
        PayloadCodec.putChars(buf, 30, vendorName, 32);
        PayloadCodec.putChars(buf, 62, modelName, 32);
        PayloadCodec.putU8(buf, 94, lensId);
        PayloadCodec.putChars(buf, 95, camDefinitionUri, 140);
        PayloadCodec.putU8(buf, 235, gimbalDeviceId);
        PayloadCodec.putU8(buf, 236, cameraDeviceId);
        return buf;
    }

    public static CameraInformation decode(MavlinkFrame f) {
        ByteBuffer b = le(f.getPayload());
        int len = f.getPayloadLength();
        return new CameraInformation(
                PayloadCodec.u32(b, 0),
                PayloadCodec.u32(b, 4),
                PayloadCodec.f32(b, 8),
                PayloadCodec.f32(b, 12),
                PayloadCodec.f32(b, 16),
                PayloadCodec.u32(b, 20),
                PayloadCodec.u16(b, 24),
                PayloadCodec.u16(b, 26),
                len > 28 ? PayloadCodec.u16(b, 28) : 0,
                PayloadCodec.chars(b, 30, 32),
                PayloadCodec.chars(b, 62, 32),
                len > 94 ? PayloadCodec.u8(b, 94) : 0,
                len > 95 ? PayloadCodec.chars(b, 95, 140) : "",
                len > 235 ? PayloadCodec.u8(b, 235) : 0,
                len > 236 ? PayloadCodec.u8(b, 236) : 0);
    }
}
