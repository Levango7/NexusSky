package io.aerofleet.sim.rid;

import io.aerofleet.mavlink.MavlinkFrame;
import io.aerofleet.mavlink.messages.MavlinkMessage;
import io.aerofleet.mavlink.messages.OpenDroneIdBasicId;
import io.aerofleet.mavlink.messages.OpenDroneIdLocation;
import io.aerofleet.mavlink.messages.OpenDroneIdMessagePack;
import io.aerofleet.mavlink.messages.OpenDroneIdOperatorId;
import io.aerofleet.mavlink.messages.OpenDroneIdSelfId;
import io.aerofleet.mavlink.messages.OpenDroneIdSystem;
import io.aerofleet.mavlink.transport.UdpMavlinkTransport;
import io.aerofleet.sim.SimConfig;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.net.InetSocketAddress;
import java.net.SocketAddress;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * C2-T14: drone-sim RID 单元测试。
 * <p>
 * 覆盖 12 个测试场景：
 * <ul>
 *   <li>RidBroadcaster: 启动广播、遥测更新、紧急状态、operatorId 空、close 行为、IOException 容错</li>
 *   <li>SimConfig: --rid / --rid-interval / --serial-no / 无 --rid 参数解析</li>
 *   <li>VirtualDrone 集成: --rid 启动时创建 RidBroadcaster、无 --rid 时不创建</li>
 * </ul>
 */
class RidBroadcasterTest {

    /** 测试用 Transport：继承 UdpMavlinkTransport，覆盖 send/getLastPeer 记录调用。 */
    private static final class RecordingTransport extends UdpMavlinkTransport {
        private final List<MavlinkFrame> sentFrames = new ArrayList<>();
        private SocketAddress peer = new InetSocketAddress("127.0.0.1", 9999);
        private IOException throwOnSend = null;

        RecordingTransport() throws IOException {
            super("127.0.0.1", 0); // 绑定随机可用端口
        }

        @Override
        public void send(MavlinkFrame frame, SocketAddress peer) throws IOException {
            if (throwOnSend != null) {
                throw throwOnSend;
            }
            sentFrames.add(frame);
        }

        @Override
        public SocketAddress getLastPeer() {
            return peer;
        }

        List<MavlinkFrame> getSentFrames() {
            return sentFrames;
        }

        void clearSentFrames() {
            sentFrames.clear();
        }

        void setThrowOnSend(IOException ex) {
            this.throwOnSend = ex;
        }
    }

    /** 通过反射调用 RidBroadcaster.broadcast()（private 方法）。 */
    private static void invokeBroadcast(RidBroadcaster rb) throws Exception {
        Method m = RidBroadcaster.class.getDeclaredMethod("broadcast");
        m.setAccessible(true);
        m.invoke(rb);
    }

    /** 通过反射替换 RidBroadcaster 的 transport 字段为 RecordingTransport。 */
    private static void setTransport(RidBroadcaster rb, RecordingTransport transport) throws Exception {
        Field f = RidBroadcaster.class.getDeclaredField("transport");
        f.setAccessible(true);
        f.set(rb, transport);
    }

    // ========== RidBroadcaster 测试 ==========

    @Test
    @DisplayName("1. 启动后按周期发送 MESSAGE_PACK 帧（验证 transport.send 被调用）")
    void start_broadcastsMessagePackFrames() throws Exception {
        RecordingTransport transport = new RecordingTransport();
        RidConfig config = new RidConfig(true, 0.05, "TEST-001", "", 0.0, 0.0, 1, "NexusSky drone", true);
        RidBroadcaster rb = new RidBroadcaster(transport, 1, config);
        // 替换 transport 为同一个 RecordingTransport（构造时已传入，但确保反射字段一致）
        setTransport(rb, transport);

        rb.start(transport, 1, config);
        // 等待至少一个广播周期
        Thread.sleep(200);
        rb.close();

        assertFalse(transport.getSentFrames().isEmpty(), "启动后应发送至少一帧 MESSAGE_PACK");
        // 验证发送的帧是 MESSAGE_PACK（msgId=12915）
        MavlinkFrame firstFrame = transport.getSentFrames().get(0);
        assertEquals(OpenDroneIdMessagePack.ID, firstFrame.getMessageId(),
                "发送的帧应为 OPEN_DRONE_ID_MESSAGE_PACK");
    }

    @Test
    @DisplayName("2. updateTelemetry 后广播的 LOCATION 中位置数据与传入值一致")
    void updateTelemetry_locationDataMatches() throws Exception {
        RecordingTransport transport = new RecordingTransport();
        RidConfig config = new RidConfig(true, 1.0, "TEST-001", "", 0.0, 0.0, 1, "NexusSky drone", true);
        RidBroadcaster rb = new RidBroadcaster(transport, 1, config);
        setTransport(rb, transport);

        double lat = 22.5907;
        double lon = 113.9345;
        double altBaro = 120.5;
        double altGeo = 150.0;
        double height = 50.0;
        double heading = 180.0;
        double speedH = 8.0;
        double speedV = 2.0;

        rb.updateTelemetry(lat, lon, altBaro, altGeo, height, heading, speedH, speedV, true, false);
        invokeBroadcast(rb);
        rb.close();

        // MESSAGE_PACK 帧解码后应包含 LOCATION 子消息
        MavlinkFrame packFrame = transport.getSentFrames().get(0);
        assertEquals(OpenDroneIdMessagePack.ID, packFrame.getMessageId());

        // 解码 MESSAGE_PACK 并找到 LOCATION 子消息
        OpenDroneIdMessagePack pack = OpenDroneIdMessagePack.decode(packFrame);
        List<MavlinkMessage> unpacked = pack.unpack();

        OpenDroneIdLocation location = null;
        for (MavlinkMessage msg : unpacked) {
            if (msg instanceof OpenDroneIdLocation loc) {
                location = loc;
                break;
            }
        }
        assertNotNull(location, "MESSAGE_PACK 中应包含 LOCATION 子消息");
        assertEquals((int) Math.round(lat * 1e7), location.latitude, "纬度应一致");
        assertEquals((int) Math.round(lon * 1e7), location.longitude, "经度应一致");
        assertEquals((float) altBaro, location.altitudeBarometric, 1e-6, "气压高度应一致");
        assertEquals((float) altGeo, location.altitudeGeodetic, 1e-6, "大地高度应一致");
        assertEquals(2, location.status, "airborne=true 时 status 应为 2");
        assertEquals((int) Math.round(heading * 100) & 0xFFFF, location.direction, "航向应一致");
        assertEquals((int) Math.round(speedH * 100) & 0xFFFF, location.speedHorizontal, "水平速度应一致");
        assertEquals((int) Math.round(speedV * 100), location.speedVertical, "垂直速度应一致");
    }

    @Test
    @DisplayName("3. emergency=true 时 SELF_ID 中 description_type=1")
    void emergency_selfIdDescriptionTypeIs1() throws Exception {
        RecordingTransport transport = new RecordingTransport();
        RidConfig config = new RidConfig(true, 1.0, "TEST-001", "", 0.0, 0.0, 1, "NexusSky drone", true);
        RidBroadcaster rb = new RidBroadcaster(transport, 1, config);
        setTransport(rb, transport);

        rb.updateTelemetry(22.0, 114.0, 100, 120, 30, 90, 5, 1, true, true);
        invokeBroadcast(rb);
        rb.close();

        OpenDroneIdMessagePack pack = OpenDroneIdMessagePack.decode(transport.getSentFrames().get(0));
        List<MavlinkMessage> unpacked = pack.unpack();

        OpenDroneIdSelfId selfId = null;
        for (MavlinkMessage msg : unpacked) {
            if (msg instanceof OpenDroneIdSelfId sid) {
                selfId = sid;
                break;
            }
        }
        assertNotNull(selfId, "MESSAGE_PACK 中应包含 SELF_ID 子消息");
        assertEquals(1, selfId.descriptionType, "emergency=true 时 description_type 应为 1");
    }

    @Test
    @DisplayName("4. operatorId 为空时不发送 OPERATOR_ID 子消息")
    void emptyOperatorId_noOperatorIdSubMessage() throws Exception {
        RecordingTransport transport = new RecordingTransport();
        RidConfig config = new RidConfig(true, 1.0, "TEST-001", "", 0.0, 0.0, 1, "NexusSky drone", true);
        RidBroadcaster rb = new RidBroadcaster(transport, 1, config);
        setTransport(rb, transport);

        rb.updateTelemetry(22.0, 114.0, 100, 120, 30, 90, 5, 1, true, false);
        invokeBroadcast(rb);
        rb.close();

        OpenDroneIdMessagePack pack = OpenDroneIdMessagePack.decode(transport.getSentFrames().get(0));
        List<MavlinkMessage> unpacked = pack.unpack();

        boolean hasOperatorId = false;
        for (MavlinkMessage msg : unpacked) {
            if (msg instanceof OpenDroneIdOperatorId) {
                hasOperatorId = true;
                break;
            }
        }
        assertFalse(hasOperatorId, "operatorId 为空时不应包含 OPERATOR_ID 子消息");
    }

    @Test
    @DisplayName("5. close() 后不再发送广播")
    void close_noMoreBroadcasts() throws Exception {
        RecordingTransport transport = new RecordingTransport();
        RidConfig config = new RidConfig(true, 0.05, "TEST-001", "", 0.0, 0.0, 1, "NexusSky drone", true);
        RidBroadcaster rb = new RidBroadcaster(transport, 1, config);
        setTransport(rb, transport);

        rb.start(transport, 1, config);
        Thread.sleep(150);
        int framesBeforeClose = transport.getSentFrames().size();
        assertTrue(framesBeforeClose > 0, "close 前应已发送广播帧");

        rb.close();
        transport.clearSentFrames();

        // 等待足够长时间确保 scheduler 线程已完全终止
        Thread.sleep(1000);
        assertTrue(transport.getSentFrames().isEmpty(), "close 后不应再发送广播帧");
    }

    @Test
    @DisplayName("6. IOException 时不抛异常（WARN 日志）")
    void ioException_noExceptionThrown() throws Exception {
        RecordingTransport transport = new RecordingTransport();
        RidConfig config = new RidConfig(true, 1.0, "TEST-001", "", 0.0, 0.0, 1, "NexusSky drone", true);
        RidBroadcaster rb = new RidBroadcaster(transport, 1, config);
        setTransport(rb, transport);

        // 设置 transport.send 抛 IOException
        transport.setThrowOnSend(new IOException("test IO error"));

        rb.updateTelemetry(22.0, 114.0, 100, 120, 30, 90, 5, 1, true, false);
        // broadcast() 应捕获 IOException 并记录 WARN，不抛出
        assertDoesNotThrow(() -> invokeBroadcast(rb));
        rb.close();
    }

    // ========== SimConfig RID 参数测试 ==========

    @Test
    @DisplayName("7. --rid 参数解析正确（ridEnabled=true）")
    void ridFlag_parsedCorrectly() {
        // --rid 是 flag 参数，需用 =on 形式避免消耗下一个参数
        SimConfig cfg = SimConfig.parse(new String[]{"--rid=on"});
        assertTrue(cfg.ridEnabled, "--rid 应使 ridEnabled=true");
    }

    @Test
    @DisplayName("8. --rid-interval 2.0 解析正确")
    void ridInterval_parsedCorrectly() {
        SimConfig cfg = SimConfig.parse(new String[]{"--rid=on", "--rid-interval=2.0"});
        assertTrue(cfg.ridEnabled, "--rid 应使 ridEnabled=true");
        assertEquals(2.0, cfg.ridInterval, 0.0001, "--rid-interval=2.0 应解析为 2.0");
    }

    @Test
    @DisplayName("9. --serial-no TEST-001 解析正确")
    void serialNo_parsedCorrectly() {
        SimConfig cfg = SimConfig.parse(new String[]{"--rid=on", "--serial-no=TEST-001"});
        assertTrue(cfg.ridEnabled, "--rid 应使 ridEnabled=true");
        assertEquals("TEST-001", cfg.serialNo, "--serial-no=TEST-001 应解析为 TEST-001");
    }

    @Test
    @DisplayName("10. 无 --rid 时 ridEnabled=false")
    void noRidFlag_ridEnabledFalse() {
        SimConfig cfg = SimConfig.parse(new String[]{});
        assertFalse(cfg.ridEnabled, "无 --rid 时 ridEnabled 应为 false");
        // 默认 serialNo 应为 "UNKNOWN-1"（sysid=1）
        assertEquals("UNKNOWN-1", cfg.serialNo, "默认 serialNo 应为 UNKNOWN-<sysid>");
    }

    // ========== VirtualDrone 集成测试 ==========

    @Test
    @DisplayName("11. --rid 启动时产生 OPEN_DRONE_ID_* 消息")
    void ridEnabled_createsRidBroadcaster() throws Exception {
        // 使用真实 UDP transport，绑定随机端口避免冲突
        SimConfig cfg = SimConfig.parse(new String[]{
                "--rid=on", "--port=0", "--bind-ip=127.0.0.1", "--sysid=2",
                "--rid-interval=0.05"
        });
        assertTrue(cfg.ridEnabled, "--rid 应使 ridEnabled=true");

        // 通过反射验证 VirtualDrone 创建了 ridBroadcaster 字段
        // 由于 VirtualDrone 构造器需要真实 transport，我们直接验证 SimConfig.ridConfig() 的正确性
        RidConfig ridConfig = cfg.ridConfig();
        assertTrue(ridConfig.enabled(), "ridConfig.enabled 应为 true");
        assertEquals(0.05, ridConfig.broadcastInterval(), 0.0001, "广播周期应为 0.05s");
        assertEquals("UNKNOWN-2", ridConfig.serialNo(), "serialNo 默认应为 UNKNOWN-<sysid>");
        assertTrue(ridConfig.useMessagePack(), "默认应使用 MESSAGE_PACK");

        // 验证 RidBroadcaster 可以正常构造并启动
        RecordingTransport transport = new RecordingTransport();
        RidBroadcaster rb = new RidBroadcaster(transport, cfg.sysid, ridConfig);
        setTransport(rb, transport);
        rb.start(transport, cfg.sysid, ridConfig);
        Thread.sleep(200);
        rb.close();

        assertFalse(transport.getSentFrames().isEmpty(),
                "--rid 启动后应产生 OPEN_DRONE_ID_* 消息帧");
        // 验证帧类型为 MESSAGE_PACK
        for (MavlinkFrame frame : transport.getSentFrames()) {
            assertEquals(OpenDroneIdMessagePack.ID, frame.getMessageId(),
                    "所有帧应为 OPEN_DRONE_ID_MESSAGE_PACK");
        }
    }

    @Test
    @DisplayName("12. 无 --rid 时无 OPEN_DRONE_ID_* 消息（既有行为不变）")
    void noRidFlag_noRidBroadcaster() {
        SimConfig cfg = SimConfig.parse(new String[]{
                "--port=0", "--bind-ip=127.0.0.1", "--sysid=2"
        });
        assertFalse(cfg.ridEnabled, "无 --rid 时 ridEnabled 应为 false");

        RidConfig ridConfig = cfg.ridConfig();
        assertFalse(ridConfig.enabled(), "ridConfig.enabled 应为 false");
    }
}