package io.aerofleet.mavlink.messages;

import io.aerofleet.mavlink.MavlinkFrame;

import io.aerofleet.mavlink.MavlinkParser;
import org.junit.jupiter.api.Test;

import java.nio.ByteBuffer;

import static org.junit.jupiter.api.Assertions.*;

/**
 * M4 硬件抽象自定义消息编解码单测（FR-18/FR-19/FR-20/FR-21/FR-22/FR-23）：
 * 437-441 encode→decode 往返一致、msgId 正确、decode 分发。
 */
class HardwareMsgTest {

    /** 构造 v2 帧 → 解析 → decode 往返。 */
    private static MavlinkMessage roundtrip(MavlinkMessage msg) {
        MavlinkFrame frame = msg.toFrame(1, 1, 0);
        MavlinkParser.ParseResult r = new MavlinkParser().parse(ByteBuffer.wrap(frame.encodeV2()));
        assertNotNull(r, "parser must accept the frame (CRC registered?)");
        return MavlinkMessage.decode(r.frame);
    }

    // ===== RADAR_SCAN (437) =====

    @Test
    void radarScanEncodeDecode() {
        RadarScanMsg orig = new RadarScanMsg(1, 45.0f, 10.0f, 1000, 5, 2, 123456L);
        RadarScanMsg back = (RadarScanMsg) roundtrip(orig);
        assertEquals(orig.mode, back.mode);
        assertEquals(orig.beamAzim, back.beamAzim, 1e-6f);
        assertEquals(orig.beamElev, back.beamElev, 1e-6f);
        assertEquals(orig.scanPeriodMs, back.scanPeriodMs);
        assertEquals(orig.targetCount, back.targetCount);
        assertEquals(orig.sysid, back.sysid);
        assertEquals(orig.timestamp, back.timestamp);
        assertEquals(437, RadarScanMsg.ID);
        assertEquals(20, RadarScanMsg.LEN);
    }

    // ===== RADAR_TARGET (438) =====

    @Test
    void radarTargetEncodeDecode() {
        RadarTargetMsg orig = new RadarTargetMsg(42, 150.5f, 90.0f, 5.0f,
                -3.2f, 10.0f, 2, 1, 999999L);
        RadarTargetMsg back = (RadarTargetMsg) roundtrip(orig);
        assertEquals(orig.targetId, back.targetId);
        assertEquals(orig.distance, back.distance, 1e-6f);
        assertEquals(orig.azimDeg, back.azimDeg, 1e-6f);
        assertEquals(orig.elevDeg, back.elevDeg, 1e-6f);
        assertEquals(orig.radialVelocity, back.radialVelocity, 1e-6f);
        assertEquals(orig.rcs, back.rcs, 1e-6f);
        assertEquals(orig.trackState, back.trackState);
        assertEquals(orig.sysid, back.sysid);
        assertEquals(orig.timestamp, back.timestamp);
        assertEquals(438, RadarTargetMsg.ID);
        assertEquals(28, RadarTargetMsg.LEN);
    }

    // ===== ROTOR_TELEMETRY (439) =====

    @Test
    void rotorTelemetryEncodeDecode() {
        RotorTelemetryMsg orig = new RotorTelemetryMsg(0, 6000.0f, 3.68f,
                15.5f, 14.72f, 62.0f, 1);
        RotorTelemetryMsg back = (RotorTelemetryMsg) roundtrip(orig);
        assertEquals(orig.rotorIndex, back.rotorIndex);
        assertEquals(orig.rpm, back.rpm, 1e-6f);
        assertEquals(orig.thrust, back.thrust, 1e-6f);
        assertEquals(orig.power, back.power, 1e-6f);
        assertEquals(orig.totalThrust, back.totalThrust, 1e-6f);
        assertEquals(orig.totalPower, back.totalPower, 1e-6f);
        assertEquals(orig.sysid, back.sysid);
        assertEquals(439, RotorTelemetryMsg.ID);
        assertEquals(24, RotorTelemetryMsg.LEN);
    }

    // ===== LIDAR_DATA (440) =====

    @Test
    void lidarDataEncodeDecode() {
        LidarDataMsg orig = new LidarDataMsg(5.0f, 1200L, 0.8f, 0.65f, 1);
        LidarDataMsg back = (LidarDataMsg) roundtrip(orig);
        assertEquals(orig.nearestDistance, back.nearestDistance, 1e-6f);
        assertEquals(orig.pointCount, back.pointCount);
        assertEquals(orig.density, back.density, 1e-6f);
        assertEquals(orig.avgIntensity, back.avgIntensity, 1e-6f);
        assertEquals(orig.sysid, back.sysid);
        assertEquals(440, LidarDataMsg.ID);
        assertEquals(20, LidarDataMsg.LEN);
    }

    // ===== IMU_DATA (441) =====

    @Test
    void imuDataEncodeDecode() {
        ImuDataMsg orig = new ImuDataMsg(0.1f, 0.2f, 9.81f,
                0.001f, 0.002f, 0.003f,
                45.0f, 5.0f, 0.0f, 28.5f, 1);
        ImuDataMsg back = (ImuDataMsg) roundtrip(orig);
        assertEquals(orig.accelX, back.accelX, 1e-6f);
        assertEquals(orig.accelY, back.accelY, 1e-6f);
        assertEquals(orig.accelZ, back.accelZ, 1e-6f);
        assertEquals(orig.gyroX, back.gyroX, 1e-6f);
        assertEquals(orig.gyroY, back.gyroY, 1e-6f);
        assertEquals(orig.gyroZ, back.gyroZ, 1e-6f);
        assertEquals(orig.magX, back.magX, 1e-6f);
        assertEquals(orig.magY, back.magY, 1e-6f);
        assertEquals(orig.magZ, back.magZ, 1e-6f);
        assertEquals(orig.tempC, back.tempC, 1e-6f);
        assertEquals(orig.sysid, back.sysid);
        assertEquals(441, ImuDataMsg.ID);
        assertEquals(41, ImuDataMsg.LEN);
    }

    // ===== 分发注册 =====

    @Test
    void allRegisteredInDispatch() {
        // 验证 437-441 在 MavlinkMessage.decode 中有对应分支
        assertNotNull(roundtrip(new RadarScanMsg(0, 0, 0, 1000, 0, 1, 0)));
        assertNotNull(roundtrip(new RadarTargetMsg(0, 100, 0, 0, 0, 0, 0, 1, 0)));
        assertNotNull(roundtrip(new RotorTelemetryMsg(0, 0, 0, 0, 0, 0, 1)));
        assertNotNull(roundtrip(new LidarDataMsg(100, 0, 0, 0, 1)));
        assertNotNull(roundtrip(new ImuDataMsg(0, 0, 9.81f, 0, 0, 0, 0, 0, 0, 25, 1)));
    }

    @Test
    void frameSysidPreserved() {
        RadarScanMsg orig = new RadarScanMsg(0, 0, 0, 1000, 0, 3, 0);
        MavlinkFrame frame = orig.toFrame(3, 1, 0);
        assertEquals(3, frame.getSystemId());
    }
}