package io.aerofleet.mavlink;

import io.aerofleet.mavlink.enums.MavEnums;
import io.aerofleet.mavlink.messages.*;
import io.aerofleet.mavlink.transport.UdpMavlinkTransport;
import org.junit.jupiter.api.Test;

import java.nio.ByteBuffer;

import static org.junit.jupiter.api.Assertions.*;

/**
 * mavlink-core 协议库单元测试。
 * CRC 采用 X.25 标准测试向量（"123456789" -> 0x906E），消息用“编码→组帧→解析→解码”回路验证。
 */
class MavlinkCoreTest {

    @Test
    void crcKnownVector() {
        // MAVLink 使用 CRC-16/MCRF4XX（init=0xFFFF，无最终异或），
        // "123456789" 的标准校验值为 0x6F91（X.25 变体带异或才是 0x906E）
        byte[] data = "123456789".getBytes();
        assertEquals(0x6F91, MavlinkCrc.compute(data, 0, data.length), "MCRF4XX 标准向量");
    }

    @Test
    void crcAccumulateMatchesCompute() {
        byte[] data = {0x11, 0x22, 0x33, 0x44};
        int split = MavlinkCrc.accumulate(MavlinkCrc.accumulate(MavlinkCrc.init(), data[0]), data, 1, 3);
        assertEquals(MavlinkCrc.compute(data, 0, 4), split, "分片累积与整块计算一致");
    }

    @Test
    void frameRoundTripHeartbeat() {
        Heartbeat hb = new Heartbeat(4, MavEnums.MAV_TYPE_QUADROTOR,
                MavEnums.MAV_AUTOPILOT_PX4,
                MavEnums.MAV_MODE_FLAG_SAFETY_ARMED | MavEnums.MAV_MODE_FLAG_AUTO_ENABLED,
                MavEnums.MAV_STATE_ACTIVE);
        MavlinkFrame frame = hb.toFrame(1, 1, 7);

        // 线上格式断言
        byte[] wire = frame.encodeV2();
        assertEquals(0xFD, wire[0] & 0xFF);
        assertEquals(9, wire[1] & 0xFF);
        assertEquals(21, wire.length); // 10头 + 9payload + 2crc

        MavlinkParser parser = new MavlinkParser();
        MavlinkParser.ParseResult result = parser.parse(ByteBuffer.wrap(wire));
        assertNotNull(result);
        assertTrue(result.isV2);
        assertEquals(7, result.frame.getSequence());
        assertEquals(1, result.frame.getSystemId());

        Heartbeat back = (Heartbeat) MavlinkMessage.decode(result.frame);
        assertNotNull(back);
        assertEquals(4, back.customMode);
        assertEquals(MavEnums.MAV_TYPE_QUADROTOR, back.type);
        assertEquals(MavEnums.MAV_AUTOPILOT_PX4, back.autopilot);
        assertEquals(MavEnums.MAV_MODE_FLAG_SAFETY_ARMED | MavEnums.MAV_MODE_FLAG_AUTO_ENABLED,
                back.baseMode);
        assertEquals(MavEnums.MAV_STATE_ACTIVE, back.systemStatus);
    }

    @Test
    void frameRoundTripMissionItemInt() {
        MissionItemInt item = new MissionItemInt(1, 1, 3,
                MavEnums.MAV_FRAME_GLOBAL_RELATIVE_ALT, MavEnums.MAV_CMD_NAV_WAYPOINT,
                0, 1, 0f, 0f, 0f, Float.NaN,
                (int) (22.531043 * 1e7), (int) (114.055953 * 1e7), 50f,
                MavEnums.MAV_MISSION_TYPE_MISSION);
        MavlinkFrame frame = item.toFrame(255, 190, 42);
        MavlinkParser.ParseResult result = new MavlinkParser().parse(ByteBuffer.wrap(frame.encodeV2()));
        assertNotNull(result);

        MissionItemInt back = (MissionItemInt) MavlinkMessage.decode(result.frame);
        assertNotNull(back);
        assertEquals(3, back.seq);
        assertEquals(MavEnums.MAV_CMD_NAV_WAYPOINT, back.command);
        assertEquals(MavEnums.MAV_FRAME_GLOBAL_RELATIVE_ALT, back.frame);
        assertEquals((int) (22.531043 * 1e7), back.x);
        assertEquals((int) (114.055953 * 1e7), back.y);
        assertEquals(50f, back.z, 1e-6);
        assertTrue(Float.isNaN(back.param4));
    }

    @Test
    void parserRejectsCorruptedFrame() {
        Heartbeat hb = new Heartbeat(0, MavEnums.MAV_TYPE_QUADROTOR,
                MavEnums.MAV_AUTOPILOT_PX4, 0, MavEnums.MAV_STATE_STANDBY);
        byte[] wire = hb.toFrame(1, 1, 0).encodeV2();
        wire[wire.length - 1] ^= 0x55; // 破坏 CRC

        MavlinkParser parser = new MavlinkParser();
        assertNull(parser.parse(ByteBuffer.wrap(wire)));
        assertEquals(1, parser.getCrcErrors());
    }

    @Test
    void parserSkipsNoiseAndRecovers() {
        Heartbeat hb = new Heartbeat(0, MavEnums.MAV_TYPE_QUADROTOR,
                MavEnums.MAV_AUTOPILOT_PX4, 0, MavEnums.MAV_STATE_STANDBY);
        byte[] frame = hb.toFrame(1, 1, 1).encodeV2();
        byte[] noisy = new byte[frame.length + 3];
        noisy[0] = 0x00;
        noisy[1] = 0x33;
        noisy[2] = (byte) 0xAA;
        System.arraycopy(frame, 0, noisy, 3, frame.length);

        MavlinkParser parser = new MavlinkParser();
        MavlinkParser.ParseResult result = parser.parse(ByteBuffer.wrap(noisy));
        assertNotNull(result);
        assertEquals(3, parser.getNoiseBytes());
        assertNotNull(MavlinkMessage.decode(result.frame));
    }

    @Test
    void parserHandlesPartialBuffer() {
        Heartbeat hb = new Heartbeat(0, MavEnums.MAV_TYPE_QUADROTOR,
                MavEnums.MAV_AUTOPILOT_PX4, 0, MavEnums.MAV_STATE_STANDBY);
        byte[] wire = hb.toFrame(1, 1, 2).encodeV2();
        MavlinkParser parser = new MavlinkParser();

        ByteBuffer partial = ByteBuffer.wrap(wire, 0, 15);
        assertNull(parser.parse(partial), "半帧应返回 null");

        ByteBuffer rest = ByteBuffer.wrap(wire, 15, wire.length - 15);
        // 模拟 TCP 式重组：骨架内 UDP 场景直接整包到达，这里验证分片语义正确
        ByteBuffer combined = ByteBuffer.allocate(wire.length);
        combined.put(partial.slice());
        combined.put(rest);
        combined.flip();
        MavlinkParser.ParseResult result = parser.parse(combined);
        assertNotNull(result);
    }

    @Test
    void telemetryRoundTrip() {
        GlobalPositionInt gpi = new GlobalPositionInt(1000,
                (int) (22.531043 * 1e7), (int) (114.055953 * 1e7),
                55000, 45000, 10, -5, 0, 9000);
        Attitude att = new Attitude(1000, 0.05f, -0.03f, 1.2f, 0, 0, 0);
        SysStatus sys = new SysStatus(0, 0, 0, 300, 14800, 1500, 87);
        VfrHud hud = new VfrHud(5.5f, 5.2f, 45f, 1.0f, 90, 60);

        for (MavlinkMessage msg : new MavlinkMessage[]{gpi, att, sys, hud}) {
            MavlinkFrame frame = msg.toFrame(1, 1, 3);
            MavlinkParser.ParseResult r = new MavlinkParser().parse(ByteBuffer.wrap(frame.encodeV2()));
            assertNotNull(r, "round trip 失败: " + msg.getClass().getSimpleName());
            MavlinkMessage back = MavlinkMessage.decode(r.frame);
            assertNotNull(back, "解码失败: " + msg.getClass().getSimpleName());

            if (back instanceof GlobalPositionInt g) {
                assertEquals(22.531043, g.lat(), 1e-6);
                assertEquals(114.055953, g.lon(), 1e-6);
                assertEquals(45.0, g.relativeAltM(), 1e-3);
            } else if (back instanceof Attitude a) {
                assertEquals(0.05f, a.roll, 1e-6);
                assertEquals(-0.03f, a.pitch, 1e-6);
                assertEquals(1.2f, a.yaw, 1e-6);
            } else if (back instanceof SysStatus s) {
                assertEquals(87, s.batteryRemaining);
                assertEquals(14800, s.voltageBattery);
            } else if (back instanceof VfrHud h) {
                assertEquals(5.5f, h.airspeed, 1e-4);
                assertEquals(60, h.throttle);
            }
        }
    }

    @Test
    void commandAckRoundTrip() {
        CommandLong arm = new CommandLong(1, 1, MavEnums.MAV_CMD_COMPONENT_ARM_DISARM, 0,
                1f, 0f, 0f, 0f, 0f, 0f, 0f);
        MavlinkFrame frame = arm.toFrame(255, 190, 0);
        MavlinkParser.ParseResult r = new MavlinkParser().parse(ByteBuffer.wrap(frame.encodeV2()));
        assertNotNull(r);
        CommandLong back = (CommandLong) MavlinkMessage.decode(r.frame);
        assertEquals(MavEnums.MAV_CMD_COMPONENT_ARM_DISARM, back.command);
        assertEquals(1f, back.param1, 1e-6);

        CommandAck ack = new CommandAck(MavEnums.MAV_CMD_COMPONENT_ARM_DISARM,
                MavEnums.MAV_RESULT_ACCEPTED, -1, 0, 255, 0);
        MavlinkFrame ackFrame = ack.toFrame(1, 1, 1);
        MavlinkParser.ParseResult r2 = new MavlinkParser().parse(ByteBuffer.wrap(ackFrame.encodeV2()));
        assertNotNull(r2);
        CommandAck ackBack = (CommandAck) MavlinkMessage.decode(r2.frame);
        assertEquals(MavEnums.MAV_RESULT_ACCEPTED, ackBack.result);
        assertEquals(MavEnums.MAV_CMD_COMPONENT_ARM_DISARM, ackBack.command);
    }

    @Test
    void statustextRoundTrip() {
        Statustext st = new Statustext(MavEnums.MAV_SEVERITY_WARNING, "Low battery: 25%", 5, 0);
        MavlinkFrame frame = st.toFrame(1, 1, 9);
        MavlinkParser.ParseResult r = new MavlinkParser().parse(ByteBuffer.wrap(frame.encodeV2()));
        assertNotNull(r);
        Statustext back = (Statustext) MavlinkMessage.decode(r.frame);
        assertEquals("Low battery: 25%", back.text);
        assertEquals(MavEnums.MAV_SEVERITY_WARNING, back.severity);
    }

    @Test
    void missionProtocolRoundTrip() {
        MissionCountMsg count = new MissionCountMsg(3, 1, 1, MavEnums.MAV_MISSION_TYPE_MISSION, 0);
        MissionRequestInt req = new MissionRequestInt(2, 255, 190, MavEnums.MAV_MISSION_TYPE_MISSION);
        MissionAckMsg ack = new MissionAckMsg(255, 190, MavEnums.MAV_MISSION_ACCEPTED,
                MavEnums.MAV_MISSION_TYPE_MISSION, 0);
        MissionCurrent cur = new MissionCurrent(1, 3, MavEnums.MISSION_STATE_ACTIVE, 0, 0, 0, 0);

        for (MavlinkMessage msg : new MavlinkMessage[]{count, req, ack, cur}) {
            MavlinkFrame frame = msg.toFrame(1, 1, 5);
            MavlinkParser.ParseResult r = new MavlinkParser().parse(ByteBuffer.wrap(frame.encodeV2()));
            assertNotNull(r, "round trip 失败: " + msg.getClass().getSimpleName());
            assertNotNull(MavlinkMessage.decode(r.frame), "解码失败: " + msg.getClass().getSimpleName());
        }
    }

    @Test
    void twoFramesInOneDatagram() {
        Heartbeat hb = new Heartbeat(0, MavEnums.MAV_TYPE_QUADROTOR,
                MavEnums.MAV_AUTOPILOT_PX4, 0, MavEnums.MAV_STATE_STANDBY);
        Statustext st = new Statustext(MavEnums.MAV_SEVERITY_INFO, "sim ready", 0, 0);
        byte[] wire1 = hb.toFrame(1, 1, 10).encodeV2();
        byte[] wire2 = st.toFrame(1, 1, 11).encodeV2();
        byte[] combined = new byte[wire1.length + wire2.length];
        System.arraycopy(wire1, 0, combined, 0, wire1.length);
        System.arraycopy(wire2, 0, combined, wire1.length, wire2.length);

        MavlinkParser parser = new MavlinkParser();
        ByteBuffer buf = ByteBuffer.wrap(combined);
        MavlinkParser.ParseResult r1 = parser.parse(buf);
        MavlinkParser.ParseResult r2 = parser.parse(buf);
        assertNotNull(r1);
        assertNotNull(r2);
        assertEquals(Heartbeat.ID, r1.frame.getMessageId());
        assertEquals(Statustext.ID, r2.frame.getMessageId());
    }

    @Test
    void unknownMessageIdFailsCrcCheck() {
        // msgId=600 不在常量表：查表侧必须抛异常（防止发送未登记 CRC_EXTRA 的消息）
        assertThrows(MavlinkException.class, () -> MavlinkMessageInfo.crcExtraOf(600));
        assertFalse(MavlinkMessageInfo.isKnown(600));
    }

    @Test
    void heartbeatV1StyleParse() {
        // 构造 v1 帧：STX=0xFE | len | seq | sys | comp | msgid=0 | payload9 | crc
        // v1 CRC 覆盖 len+seq+sys+comp+msgid+payload+crcExtra（无兼容字节）
        byte[] payload = new Heartbeat(4, 2, 12, 209, 3).encode();
        int crc = MavlinkCrc.init();
        crc = MavlinkCrc.accumulate(crc, payload.length);
        crc = MavlinkCrc.accumulate(crc, 5);      // seq
        crc = MavlinkCrc.accumulate(crc, 1);       // sysid
        crc = MavlinkCrc.accumulate(crc, 1);       // compid
        crc = MavlinkCrc.accumulate(crc, 0);       // msgid
        crc = MavlinkCrc.accumulate(crc, payload, 0, payload.length);
        crc = MavlinkCrc.accumulate(crc, 50);      // HEARTBEAT CRC_EXTRA

        byte[] wire = new byte[8 + payload.length];
        wire[0] = (byte) 0xFE;
        wire[1] = (byte) payload.length;
        wire[2] = 5;
        wire[3] = 1;
        wire[4] = 1;
        wire[5] = 0;
        System.arraycopy(payload, 0, wire, 6, payload.length);
        wire[6 + payload.length] = (byte) (crc & 0xFF);
        wire[7 + payload.length] = (byte) ((crc >> 8) & 0xFF);

        MavlinkParser parser = new MavlinkParser();
        MavlinkParser.ParseResult r = parser.parse(ByteBuffer.wrap(wire));
        assertNotNull(r, "v1 帧应可解析");
        assertFalse(r.isV2);
        assertEquals(5, r.frame.getSequence());
        Heartbeat hb = (Heartbeat) MavlinkMessage.decode(r.frame);
        assertEquals(209, hb.baseMode);
    }

    @Test
    void transportLoopback() throws Exception {
        try (UdpMavlinkTransport a = new UdpMavlinkTransport(0);
             UdpMavlinkTransport b = new UdpMavlinkTransport(0)) {
            java.util.concurrent.LinkedBlockingQueue<MavlinkFrame> received =
                    new java.util.concurrent.LinkedBlockingQueue<>();
            b.addFrameListener(received::add);

            Heartbeat hb = new Heartbeat(9, 2, 12, 128, 4);
            a.send(hb.toFrame(1, 1, 0), new java.net.InetSocketAddress("127.0.0.1", b.getLocalPort()));

            MavlinkFrame got = received.poll(5, java.util.concurrent.TimeUnit.SECONDS);
            assertNotNull(got, "UDP 回环应收到帧");
            assertEquals(Heartbeat.ID, got.getMessageId());
            assertEquals(9, ((Heartbeat) MavlinkMessage.decode(got)).customMode);
        }
    }
}
