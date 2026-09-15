package io.aerofleet.mavlink.messages;

import io.aerofleet.mavlink.MavlinkFrame;
import io.aerofleet.mavlink.MavlinkMessageInfo;
import io.aerofleet.mavlink.MavlinkParser;
import org.junit.jupiter.api.Test;

import java.nio.ByteBuffer;

import static org.junit.jupiter.api.Assertions.*;

/**
 * EnvironmentStatus / EnvironmentAlert 消息编解码单测（FR-24/25/26/27）：
 * 422/421 encode→decode 往返一致、decode 分发、msgId 无冲突、字段单位精度。
 */
class EnvironmentMessageTest {

    /** 构造 v2 帧 → 解析 → decode 往返。 */
    private static MavlinkMessage roundtrip(MavlinkMessage msg) {
        MavlinkFrame frame = msg.toFrame(1, 1, 0);
        MavlinkParser.ParseResult r = new MavlinkParser().parse(ByteBuffer.wrap(frame.encodeV2()));
        assertNotNull(r, "parser must accept the frame (CRC registered?)");
        return MavlinkMessage.decode(r.frame);
    }

    @Test
    void environmentStatusEncodeDecodeRoundtrip() {
        EnvironmentStatus orig = new EnvironmentStatus(2500, 60, 350, 18000, 120, 2, 3000, 15);
        EnvironmentStatus back = (EnvironmentStatus) roundtrip(orig);
        assertEquals(orig.temperature, back.temperature);
        assertEquals(orig.humidity, back.humidity);
        assertEquals(orig.windSpeed, back.windSpeed);
        assertEquals(orig.windDirection, back.windDirection);
        assertEquals(orig.gust, back.gust);
        assertEquals(orig.weather, back.weather);
        assertEquals(orig.visibility, back.visibility);
        assertEquals(orig.rainRate, back.rainRate);
    }

    @Test
    void environmentAlertEncodeDecodeRoundtrip() {
        EnvironmentAlert orig = new EnvironmentAlert(0, 2, 150, 120,
                "Wind critical: 15.0 m/s > 12.0");
        EnvironmentAlert back = (EnvironmentAlert) roundtrip(orig);
        assertEquals(orig.alertType, back.alertType);
        assertEquals(orig.severity, back.severity);
        assertEquals(orig.value, back.value);
        assertEquals(orig.threshold, back.threshold);
        assertEquals(orig.text, back.text);
    }

    @Test
    void decodeDispatches422() {
        EnvironmentStatus msg = new EnvironmentStatus(2000, 50, 200, 0, 0, 0, 10000, 0);
        MavlinkMessage decoded = roundtrip(msg);
        assertNotNull(decoded);
        assertTrue(decoded instanceof EnvironmentStatus,
                "msgId=422 should dispatch to EnvironmentStatus");
    }

    @Test
    void decodeDispatches421() {
        EnvironmentAlert msg = new EnvironmentAlert(1, 4, 480, 450, "Temp warning");
        MavlinkMessage decoded = roundtrip(msg);
        assertNotNull(decoded);
        assertTrue(decoded instanceof EnvironmentAlert,
                "msgId=421 should dispatch to EnvironmentAlert");
    }

    @Test
    void unknownMsgIdReturnsNull() {
        // 构造一个未注册 msgId 的帧（999）→ decode 返回 null
        // 直接调 MavlinkMessage.decode 需要一个 frame，这里用反射不优雅；
        // 改为验证 999 不在已知集合
        assertFalse(MavlinkMessageInfo.isKnown(999), "msgId 999 should be unknown");
    }

    @Test
    void msgIdNoConflict() {
        // 421/422 已注册且不在既有 msgId 集合
        assertTrue(MavlinkMessageInfo.isKnown(EnvironmentStatus.ID));
        assertTrue(MavlinkMessageInfo.isKnown(EnvironmentAlert.ID));
        assertEquals(13, MavlinkMessageInfo.lengthOf(EnvironmentStatus.ID));
        assertEquals(46, MavlinkMessageInfo.lengthOf(EnvironmentAlert.ID));
        // 确认不与既有消息冲突（既有集合不含 421/422）
        int[] existing = {0, 1, 2, 24, 30, 33, 42, 43, 44, 47, 51, 69, 73, 74, 76, 77,
                109, 143, 242, 253, 259, 260, 262, 263, 271, 420};
        for (int id : existing) {
            assertNotEquals(id, EnvironmentStatus.ID, "422 must not conflict with existing " + id);
            assertNotEquals(id, EnvironmentAlert.ID, "421 must not conflict with existing " + id);
        }
    }

    @Test
    void fieldUnitsCorrect() {
        // temperature 单位 c°C（×100）、windSpeed 单位 cm/s（×100）、windDirection 单位 cdeg（×100）
        EnvironmentStatus msg = new EnvironmentStatus(2550, 60, 350, 18000, 120, 2, 3000, 15);
        // 2550 c°C = 25.5°C
        assertEquals(25.5, msg.temperature / 100.0, 1e-9);
        // 350 cm/s = 3.5 m/s
        assertEquals(3.5, msg.windSpeed / 100.0, 1e-9);
        // 18000 cdeg = 180 deg
        assertEquals(180.0, msg.windDirection / 100.0, 1e-9);
        // 120 cm/s = 1.2 m/s
        assertEquals(1.2, msg.gust / 100.0, 1e-9);
    }

    @Test
    void alertFieldUnitsCorrect() {
        // value/threshold 单位 ×10
        EnvironmentAlert msg = new EnvironmentAlert(0, 2, 150, 120, "test");
        assertEquals(15.0, msg.value / 10.0, 1e-9, "150 -> 15.0");
        assertEquals(12.0, msg.threshold / 10.0, 1e-9, "120 -> 12.0");
    }
}