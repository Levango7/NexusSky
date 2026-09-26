package io.aerofleet.mavlink.hardware;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

/**
 * SimulatedHardwareAdapter 单元测试。
 * <p>
 * 覆盖场景：
 * <ul>
 *   <li>连接/断开</li>
 *   <li>ARM/DISARM</li>
 *   <li>起飞/RTL</li>
 *   <li>任务上传/下载/清除</li>
 *   <li>遥测订阅</li>
 *   <li>适配器信息</li>
 *   <li>Waypoint 范围验证</li>
 * </ul>
 */
class SimulatedHardwareAdapterTest {

    private SimulatedHardwareAdapter adapter;

    @BeforeEach
    void setUp() {
        adapter = new SimulatedHardwareAdapter();
    }

    @AfterEach
    void tearDown() {
        if (adapter.isConnected()) {
            adapter.disconnect();
        }
    }

    // ====== 连接/断开测试 ======

    @Test
    @DisplayName("连接后状态应为 connected=true")
    void connectShouldSetConnected() {
        assertTrue(adapter.connect("sim://test"));
        assertTrue(adapter.isConnected());
        assertEquals("STABILIZE", adapter.getState().getMode());
        assertEquals(1, adapter.getSystemId());
    }

    @Test
    @DisplayName("断开后状态应为 connected=false")
    void disconnectShouldClearConnected() {
        adapter.connect("sim://test");
        assertTrue(adapter.disconnect());
        assertFalse(adapter.isConnected());
        assertEquals("UNKNOWN", adapter.getState().getMode());
        assertEquals(-1, adapter.getSystemId());
    }

    @Test
    @DisplayName("重复连接应返回 true 且不报错")
    void duplicateConnectShouldSucceed() {
        assertTrue(adapter.connect("sim://test"));
        assertTrue(adapter.connect("sim://test"));
        assertTrue(adapter.isConnected());
    }

    @Test
    @DisplayName("未连接时断开应返回 true")
    void disconnectWhenNotConnectedShouldSucceed() {
        assertTrue(adapter.disconnect());
    }

    // ====== ARM/DISARM 测试 ======

    @Test
    @DisplayName("连接后 ARM 应成功，状态应为 armed=true")
    void armShouldSetArmed() {
        adapter.connect("sim://test");
        assertTrue(adapter.arm());
        assertTrue(adapter.getState().isArmed());
    }

    @Test
    @DisplayName("DISARM 应成功，状态应为 armed=false")
    void disarmShouldClearArmed() {
        adapter.connect("sim://test");
        adapter.arm();
        assertTrue(adapter.disarm());
        assertFalse(adapter.getState().isArmed());
    }

    @Test
    @DisplayName("未连接时 ARM 应失败")
    void armWhenNotConnectedShouldFail() {
        assertFalse(adapter.arm());
    }

    @Test
    @DisplayName("未连接时 DISARM 应失败")
    void disarmWhenNotConnectedShouldFail() {
        assertFalse(adapter.disarm());
    }

    // ====== 起飞/RTL 测试 ======

    @Test
    @DisplayName("ARM 后起飞应成功，模式应为 GUIDED")
    void takeoffShouldSetGuidedMode() {
        adapter.connect("sim://test");
        adapter.arm();
        assertTrue(adapter.takeoff(10.0));
        assertEquals("GUIDED", adapter.getState().getMode());
    }

    @Test
    @DisplayName("未 ARM 时起飞应失败")
    void takeoffWithoutArmShouldFail() {
        adapter.connect("sim://test");
        assertFalse(adapter.takeoff(10.0));
    }

    @Test
    @DisplayName("未连接时起飞应失败")
    void takeoffWhenNotConnectedShouldFail() {
        assertFalse(adapter.takeoff(10.0));
    }

    @Test
    @DisplayName("RTL 应成功，模式应为 RTL")
    void rtlShouldSetRtlMode() {
        adapter.connect("sim://test");
        adapter.arm();
        adapter.takeoff(10.0);
        assertTrue(adapter.rtl());
        assertEquals("RTL", adapter.getState().getMode());
    }

    @Test
    @DisplayName("未连接时 RTL 应失败")
    void rtlWhenNotConnectedShouldFail() {
        assertFalse(adapter.rtl());
    }

    // ====== 模式切换测试 ======

    @Test
    @DisplayName("setMode 应切换模式")
    void setModeShouldChangeMode() {
        adapter.connect("sim://test");
        assertTrue(adapter.setMode("AUTO"));
        assertEquals("AUTO", adapter.getState().getMode());
    }

    @Test
    @DisplayName("未连接时 setMode 应失败")
    void setModeWhenNotConnectedShouldFail() {
        assertFalse(adapter.setMode("AUTO"));
    }

    // ====== 任务管理测试 ======

    @Test
    @DisplayName("上传任务应成功")
    void uploadMissionShouldSucceed() {
        adapter.connect("sim://test");
        List<Waypoint> waypoints = Arrays.asList(
                new Waypoint(22.531, 114.055, 10, 0, 16),
                new Waypoint(22.532, 114.056, 20, 5, 16),
                new Waypoint(22.533, 114.057, 30, 0, 20)
        );
        assertTrue(adapter.uploadMission(waypoints));
    }

    @Test
    @DisplayName("下载任务应返回上传的航点")
    void downloadMissionShouldReturnUploadedWaypoints() {
        adapter.connect("sim://test");
        List<Waypoint> waypoints = Arrays.asList(
                new Waypoint(22.531, 114.055, 10, 0, 16),
                new Waypoint(22.532, 114.056, 20, 5, 16)
        );
        adapter.uploadMission(waypoints);

        List<Waypoint> downloaded = adapter.downloadMission();
        assertEquals(2, downloaded.size());
        assertEquals(waypoints.get(0), downloaded.get(0));
        assertEquals(waypoints.get(1), downloaded.get(1));
    }

    @Test
    @DisplayName("清除任务后下载应返回空列表")
    void clearMissionShouldEmptyDownload() {
        adapter.connect("sim://test");
        adapter.uploadMission(Arrays.asList(
                new Waypoint(22.531, 114.055, 10, 0, 16)
        ));
        assertTrue(adapter.clearMission());
        assertTrue(adapter.downloadMission().isEmpty());
    }

    @Test
    @DisplayName("启动任务应成功（有航点时）")
    void startMissionShouldSucceedWithWaypoints() {
        adapter.connect("sim://test");
        adapter.uploadMission(Arrays.asList(
                new Waypoint(22.531, 114.055, 10, 0, 16)
        ));
        assertTrue(adapter.startMission());
        assertEquals("AUTO", adapter.getState().getMode());
    }

    @Test
    @DisplayName("启动任务应失败（无航点时）")
    void startMissionShouldFailWithoutWaypoints() {
        adapter.connect("sim://test");
        assertFalse(adapter.startMission());
    }

    @Test
    @DisplayName("未连接时上传任务应失败")
    void uploadMissionWhenNotConnectedShouldFail() {
        assertFalse(adapter.uploadMission(Arrays.asList(
                new Waypoint(22.531, 114.055, 10, 0, 16)
        )));
    }

    // ====== 遥测订阅测试 ======

    @Test
    @DisplayName("遥测监听器应收到状态变化通知")
    void telemetryListenerShouldReceiveStatusChange() throws InterruptedException {
        LinkedBlockingQueue<String> statusQueue = new LinkedBlockingQueue<>();
        adapter.subscribeTelemetry(new TelemetryListener() {
            @Override
            public void onTelemetry(HardwareState state) {
                // 不在此测试中验证遥测数据
            }

            @Override
            public void onStatusChange(String status) {
                statusQueue.add(status);
            }
        });

        adapter.connect("sim://test");
        // 应收到 CONNECTED 状态
        String connectedStatus = statusQueue.poll(2, TimeUnit.SECONDS);
        assertNotNull(connectedStatus);
        assertEquals("CONNECTED", connectedStatus);

        adapter.arm();
        String armedStatus = statusQueue.poll(2, TimeUnit.SECONDS);
        assertNotNull(armedStatus);
        assertEquals("ARMED", armedStatus);

        adapter.disarm();
        String disarmedStatus = statusQueue.poll(2, TimeUnit.SECONDS);
        assertNotNull(disarmedStatus);
        assertEquals("DISARMED", disarmedStatus);
    }

    @Test
    @DisplayName("遥测监听器应收到遥测数据更新")
    void telemetryListenerShouldReceiveTelemetryData() throws InterruptedException {
        LinkedBlockingQueue<HardwareState> telemetryQueue = new LinkedBlockingQueue<>();
        adapter.subscribeTelemetry(new TelemetryListener() {
            @Override
            public void onTelemetry(HardwareState state) {
                telemetryQueue.add(state);
            }

            @Override
            public void onStatusChange(String status) {
                // 不在此测试中验证状态变化
            }
        });

        adapter.connect("sim://test");
        // 遥测模拟线程启动后应定期推送状态
        HardwareState state = telemetryQueue.poll(2, TimeUnit.SECONDS);
        assertNotNull(state);
        assertTrue(state.isConnected());
    }

    // ====== 适配器信息测试 ======

    @Test
    @DisplayName("getAdapterType 应返回 Simulated")
    void getAdapterTypeShouldReturnSimulated() {
        assertEquals("Simulated", adapter.getAdapterType());
    }

    @Test
    @DisplayName("getFirmwareVersion 应返回模拟版本号")
    void getFirmwareVersionShouldReturnSimVersion() {
        assertEquals("sim-1.0.0", adapter.getFirmwareVersion());
    }

    @Test
    @DisplayName("未连接时 getSystemId 应返回 -1")
    void getSystemIdWhenNotConnectedShouldReturnMinusOne() {
        assertEquals(-1, adapter.getSystemId());
    }

    @Test
    @DisplayName("连接后 getSystemId 应返回 1")
    void getSystemIdWhenConnectedShouldReturnOne() {
        adapter.connect("sim://test");
        assertEquals(1, adapter.getSystemId());
    }

    // ====== Waypoint 范围验证测试 ======

    @Test
    @DisplayName("Waypoint 纬度超出范围应抛异常")
    void waypointLatOutOfRangeShouldThrow() {
        assertThrows(IllegalArgumentException.class, () -> new Waypoint(91, 114, 10, 0, 16));
        assertThrows(IllegalArgumentException.class, () -> new Waypoint(-91, 114, 10, 0, 16));
    }

    @Test
    @DisplayName("Waypoint 经度超出范围应抛异常")
    void waypointLonOutOfRangeShouldThrow() {
        assertThrows(IllegalArgumentException.class, () -> new Waypoint(22, 181, 10, 0, 16));
        assertThrows(IllegalArgumentException.class, () -> new Waypoint(22, -181, 10, 0, 16));
    }

    @Test
    @DisplayName("Waypoint 高度为负应抛异常")
    void waypointNegativeAltShouldThrow() {
        assertThrows(IllegalArgumentException.class, () -> new Waypoint(22, 114, -1, 0, 16));
    }

    @Test
    @DisplayName("Waypoint 悬停时间为负应抛异常")
    void waypointNegativeHoldTimeShouldThrow() {
        assertThrows(IllegalArgumentException.class, () -> new Waypoint(22, 114, 10, -1, 16));
    }

    // ====== HardwareAdapterFactory 测试 ======

    @Test
    @DisplayName("工厂应根据 sim:// 前缀创建 SimulatedHardwareAdapter")
    void factoryShouldCreateSimulatedForSimPrefix() {
        HardwareAdapter a = HardwareAdapterFactory.create("sim://test");
        assertEquals("Simulated", a.getAdapterType());
    }

    @Test
    @DisplayName("工厂应根据 udp:// 前缀创建 Px4Adapter")
    void factoryShouldCreatePx4ForUdpPrefix() {
        HardwareAdapter a = HardwareAdapterFactory.create("udp://127.0.0.1:14540");
        assertEquals("PX4", a.getAdapterType());
    }

    @Test
    @DisplayName("工厂应根据 tcp:// 前缀创建 ArduPilotAdapter")
    void factoryShouldCreateArduPilotForTcpPrefix() {
        HardwareAdapter a = HardwareAdapterFactory.create("tcp://127.0.0.1:5760");
        assertEquals("ArduPilot", a.getAdapterType());
    }

    @Test
    @DisplayName("工厂应根据 serial:// 前缀创建 ArduPilotAdapter")
    void factoryShouldCreateArduPilotForSerialPrefix() {
        HardwareAdapter a = HardwareAdapterFactory.create("serial://COM3:57600");
        assertEquals("ArduPilot", a.getAdapterType());
    }

    @Test
    @DisplayName("工厂对不支持的前缀应抛异常")
    void factoryShouldThrowForUnsupportedPrefix() {
        assertThrows(IllegalArgumentException.class, () -> HardwareAdapterFactory.create("foo://bar"));
    }

    @Test
    @DisplayName("工厂显式指定类型应创建对应适配器")
    void factoryExplicitTypeShouldCreateCorrectAdapter() {
        assertEquals("PX4", HardwareAdapterFactory.create("PX4", "udp://x").getAdapterType());
        assertEquals("ArduPilot", HardwareAdapterFactory.create("ArduPilot", "tcp://x").getAdapterType());
        assertEquals("Simulated", HardwareAdapterFactory.create("Simulated", "sim://x").getAdapterType());
    }

    // ====== HardwareState 测试 ======

    @Test
    @DisplayName("HardwareState.disconnected 应返回未连接状态")
    void disconnectedStateShouldBeCorrect() {
        HardwareState state = HardwareState.disconnected();
        assertFalse(state.isConnected());
        assertFalse(state.isArmed());
        assertEquals("UNKNOWN", state.getMode());
    }

    @Test
    @DisplayName("HardwareState Builder 应正确设置所有字段")
    void stateBuilderShouldSetAllFields() {
        HardwareState state = new HardwareState.Builder()
                .connected(true)
                .armed(true)
                .mode("AUTO")
                .lat(22.5)
                .lon(114.0)
                .alt(50.0)
                .battery(80.0)
                .heading(180.0)
                .airspeed(5.0)
                .groundspeed(4.5)
                .build();
        assertTrue(state.isConnected());
        assertTrue(state.isArmed());
        assertEquals("AUTO", state.getMode());
        assertEquals(22.5, state.getLat());
        assertEquals(114.0, state.getLon());
        assertEquals(50.0, state.getAlt());
        assertEquals(80.0, state.getBattery());
        assertEquals(180.0, state.getHeading());
        assertEquals(5.0, state.getAirspeed());
        assertEquals(4.5, state.getGroundspeed());
    }
}