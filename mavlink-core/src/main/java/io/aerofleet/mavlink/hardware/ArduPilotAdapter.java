package io.aerofleet.mavlink.hardware;

import io.aerofleet.mavlink.MavlinkFrame;
import io.aerofleet.mavlink.enums.MavEnums;
import io.aerofleet.mavlink.messages.*;
import io.aerofleet.mavlink.transport.UdpMavlinkTransport;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

/**
 * ArduPilot 飞控适配器：基于 MAVLink 协议通过 TCP/UDP/Serial 连接 ArduPilot。
 * <p>
 * ArduPilot 特有行为：
 * <ul>
 *   <li>默认 TCP 端口 5760（SITL）/ UDP 端口 14550</li>
 *   <li>心跳中 autopilot = MAV_AUTOPILOT_ARDUPILOTMEGA (3)</li>
 *   <li>模式切换通过 MAV_CMD_DO_SET_MODE，但 mode 参数编码与 PX4 不同</li>
 *   <li>支持 GUIDED 模式下的实时位置控制</li>
 *   <li>任务协议使用 MISSION_ITEM_INT（与 PX4 一致）</li>
 * </ul>
 * <p>
 * 线程安全：所有可变状态使用原子变量或并发集合。
 */
public class ArduPilotAdapter implements HardwareAdapter {

    private static final Logger log = LoggerFactory.getLogger(ArduPilotAdapter.class);

    private static final int GCS_SYSTEM_ID = 255;
    private static final int GCS_COMPONENT_ID = 190;
    private static final int TARGET_SYSTEM_ID = 1;
    private static final int TARGET_COMPONENT_ID = 1;
    private static final long HEARTBEAT_INTERVAL_MS = 1000L;

    private final AtomicBoolean connected = new AtomicBoolean(false);
    private final AtomicBoolean armed = new AtomicBoolean(false);
    private final AtomicReference<String> mode = new AtomicReference<>("UNKNOWN");
    private final AtomicReference<String> firmwareVersion = new AtomicReference<>("unknown");
    private final AtomicInteger systemId = new AtomicInteger(-1);
    private final AtomicInteger sequence = new AtomicInteger(0);

    private final CopyOnWriteArrayList<TelemetryListener> listeners = new CopyOnWriteArrayList<>();

    private volatile UdpMavlinkTransport transport;
    private ScheduledExecutorService heartbeatExecutor;
    private volatile String connectionUrl;

    @Override
    public boolean connect(String connectionUrl) {
        if (connected.get()) {
            log.warn("ArduPilotAdapter 已连接，忽略重复连接请求");
            return true;
        }
        this.connectionUrl = connectionUrl;

        try {
            // 解析连接字符串：tcp://host:port 或 udp://host:port 或 serial://port:baud
            if (connectionUrl.startsWith("udp://")) {
                String addrPart = connectionUrl.substring("udp://".length());
                String[] parts = addrPart.split(":");
                String host = parts[0];
                int port = parts.length > 1 ? Integer.parseInt(parts[1]) : 14550;

                transport = new UdpMavlinkTransport(0);
                transport.addFrameListener(this::handleFrame);

                InetSocketAddress target = new InetSocketAddress(host, port);
                transport.enablePeerDiscovery(target, () -> buildHeartbeat());

            } else if (connectionUrl.startsWith("tcp://")) {
                // TCP 连接：当前骨架使用 UDP 传输层模拟，后续替换为 TCP 传输
                String addrPart = connectionUrl.substring("tcp://".length());
                String[] parts = addrPart.split(":");
                String host = parts[0];
                int port = parts.length > 1 ? Integer.parseInt(parts[1]) : 5760;

                // 骨架阶段：ArduPilot SITL 也支持 UDP，先用 UDP 连接
                transport = new UdpMavlinkTransport(0);
                transport.addFrameListener(this::handleFrame);

                InetSocketAddress target = new InetSocketAddress(host, port);
                transport.enablePeerDiscovery(target, () -> buildHeartbeat());

            } else if (connectionUrl.startsWith("serial://")) {
                // 串口连接：需要 jSerialComm 或类似库，骨架阶段暂不支持
                log.error("串口连接暂未实现，请使用 udp:// 或 tcp:// 连接");
                return false;
            } else {
                log.error("不支持的连接格式: {}", connectionUrl);
                return false;
            }

            connected.set(true);
            systemId.set(TARGET_SYSTEM_ID);
            log.info("ArduPilotAdapter 已连接到 {}", connectionUrl);

            startHeartbeat();
            notifyStatusChange("CONNECTED");
            return true;
        } catch (IOException e) {
            log.error("ArduPilotAdapter 连接失败: {}", e.getMessage());
            connected.set(false);
            return false;
        }
    }

    @Override
    public boolean disconnect() {
        if (!connected.get()) {
            log.warn("ArduPilotAdapter 未连接，忽略断开请求");
            return true;
        }
        stopHeartbeat();
        if (transport != null) {
            transport.close();
            transport = null;
        }
        connected.set(false);
        armed.set(false);
        mode.set("UNKNOWN");
        systemId.set(-1);
        log.info("ArduPilotAdapter 已断开");
        notifyStatusChange("DISCONNECTED");
        return true;
    }

    @Override
    public boolean isConnected() {
        return connected.get();
    }

    @Override
    public void startHeartbeat() {
        if (heartbeatExecutor != null && !heartbeatExecutor.isShutdown()) {
            log.warn("心跳已在运行");
            return;
        }
        heartbeatExecutor = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "ardupilot-heartbeat");
            t.setDaemon(true);
            return t;
        });
        heartbeatExecutor.scheduleAtFixedRate(() -> {
            if (connected.get() && transport != null) {
                try {
                    MavlinkFrame hb = buildHeartbeat();
                    transport.sendToLastPeer(hb);
                } catch (IOException e) {
                    log.warn("心跳发送失败: {}", e.getMessage());
                }
            }
        }, 0, HEARTBEAT_INTERVAL_MS, TimeUnit.MILLISECONDS);
        log.info("ArduPilotAdapter 心跳已启动");
    }

    @Override
    public void stopHeartbeat() {
        if (heartbeatExecutor != null) {
            heartbeatExecutor.shutdownNow();
            heartbeatExecutor = null;
            log.info("ArduPilotAdapter 心跳已停止");
        }
    }

    @Override
    public HardwareState getState() {
        return new HardwareState.Builder()
                .connected(connected.get())
                .armed(armed.get())
                .mode(mode.get())
                .build();
    }

    @Override
    public boolean arm() {
        // ArduPilot ARM 需要 MAV_CMD_COMPONENT_ARM_DISARM，param1=1
        return sendCommand(MavEnums.MAV_CMD_COMPONENT_ARM_DISARM, 1f, 0f);
    }

    @Override
    public boolean disarm() {
        return sendCommand(MavEnums.MAV_CMD_COMPONENT_ARM_DISARM, 0f, 0f);
    }

    @Override
    public boolean takeoff(double altitude) {
        // ArduPilot 起飞：先切 GUIDED 模式，再发送 MAV_CMD_NAV_TAKEOFF
        setMode("GUIDED");
        return sendCommand(MavEnums.MAV_CMD_NAV_TAKEOFF, 0f, 0f, 0f, 0f,
                (float) altitude, 0f, 0f);
    }

    @Override
    public boolean rtl() {
        return setMode("RTL");
    }

    @Override
    public boolean setMode(String modeName) {
        // ArduPilot 模式切换：MAV_CMD_DO_SET_MODE，param1=1（custom mode），param2=mode_id
        int modeId = arduPilotModeToId(modeName);
        return sendCommand(MavEnums.MAV_CMD_DO_SET_MODE, 1f, (float) modeId);
    }

    @Override
    public boolean uploadMission(List<Waypoint> waypoints) {
        if (!connected.get() || waypoints == null || waypoints.isEmpty()) {
            return false;
        }
        int count = waypoints.size();
        MissionCountMsg countMsg = new MissionCountMsg(count, TARGET_SYSTEM_ID, TARGET_COMPONENT_ID,
                MavEnums.MAV_MISSION_TYPE_MISSION, 0);
        sendFrame(countMsg);
        log.info("已发送任务数量 {} 给 ArduPilot", count);
        // 完整实现需处理 MISSION_REQUEST_INT → MISSION_ITEM_INT → MISSION_ACK 流程
        return true;
    }

    @Override
    public List<Waypoint> downloadMission() {
        if (!connected.get()) {
            return List.of();
        }
        MissionRequestList reqList = new MissionRequestList(TARGET_SYSTEM_ID, TARGET_COMPONENT_ID,
                MavEnums.MAV_MISSION_TYPE_MISSION);
        sendFrame(reqList);
        log.info("已请求 ArduPilot 任务列表");
        // 完整实现需处理 MISSION_COUNT → MISSION_REQUEST_INT → MISSION_ITEM_INT 流程
        return List.of();
    }

    @Override
    public boolean startMission() {
        // ArduPilot 通过设置 AUTO 模式启动任务
        return setMode("AUTO");
    }

    @Override
    public boolean clearMission() {
        MissionCountMsg emptyCount = new MissionCountMsg(0, TARGET_SYSTEM_ID, TARGET_COMPONENT_ID,
                MavEnums.MAV_MISSION_TYPE_MISSION, 0);
        sendFrame(emptyCount);
        log.info("已发送清除任务指令给 ArduPilot");
        return true;
    }

    @Override
    public void subscribeTelemetry(TelemetryListener listener) {
        listeners.add(listener);
    }

    @Override
    public String getAdapterType() {
        return "ArduPilot";
    }

    @Override
    public String getFirmwareVersion() {
        return firmwareVersion.get();
    }

    @Override
    public int getSystemId() {
        return systemId.get();
    }

    // ====== 内部方法 ======

    private MavlinkFrame buildHeartbeat() {
        int baseMode = MavEnums.MAV_MODE_FLAG_CUSTOM_MODE_ENABLED;
        if (armed.get()) {
            baseMode |= MavEnums.MAV_MODE_FLAG_SAFETY_ARMED;
        }
        Heartbeat hb = new Heartbeat(0, MavEnums.MAV_TYPE_QUADROTOR,
                MavEnums.MAV_AUTOPILOT_ARDUPILOTMEGA, baseMode, MavEnums.MAV_STATE_ACTIVE);
        return hb.toFrame(GCS_SYSTEM_ID, GCS_COMPONENT_ID, sequence.getAndIncrement());
    }

    private void sendFrame(MavlinkMessage msg) {
        if (transport == null) return;
        try {
            MavlinkFrame frame = msg.toFrame(GCS_SYSTEM_ID, GCS_COMPONENT_ID, sequence.getAndIncrement());
            transport.sendToLastPeer(frame);
        } catch (IOException e) {
            log.warn("发送帧失败: {}", e.getMessage());
        }
    }

    private boolean sendCommand(int command, float... params) {
        if (!connected.get()) return false;
        float p1 = params.length > 0 ? params[0] : 0f;
        float p2 = params.length > 1 ? params[1] : 0f;
        float p3 = params.length > 2 ? params[2] : 0f;
        float p4 = params.length > 3 ? params[3] : 0f;
        float p5 = params.length > 4 ? params[4] : 0f;
        float p6 = params.length > 5 ? params[5] : 0f;
        float p7 = params.length > 6 ? params[6] : 0f;
        CommandLong cmd = new CommandLong(TARGET_SYSTEM_ID, TARGET_COMPONENT_ID,
                command, 0, p1, p2, p3, p4, p5, p6, p7);
        sendFrame(cmd);
        log.info("已发送命令 {} 给 ArduPilot", command);
        return true;
    }

    private void handleFrame(MavlinkFrame frame) {
        MavlinkMessage msg = MavlinkMessage.decode(frame);
        if (msg == null) return;

        if (msg instanceof Heartbeat hb) {
            armed.set((hb.baseMode & MavEnums.MAV_MODE_FLAG_SAFETY_ARMED) != 0);
            systemId.set(frame.getSystemId());
            mode.set(arduPilotCustomModeToName(hb.customMode));
            notifyTelemetry();
        } else if (msg instanceof GlobalPositionInt gpi) {
            notifyTelemetryWithPosition(gpi.lat(), gpi.lon(), gpi.relativeAltM());
        } else if (msg instanceof SysStatus sys) {
            notifyTelemetryWithBattery(sys.batteryRemaining);
        } else if (msg instanceof VfrHud hud) {
            notifyTelemetryWithSpeed(hud.airspeed, hud.groundspeed, hud.heading);
        } else if (msg instanceof CommandAck ack) {
            handleCommandAck(ack);
        } else if (msg instanceof Statustext st) {
            log.info("ArduPilot 状态文本: {}", st.text);
        }
    }

    private void handleCommandAck(CommandAck ack) {
        if (ack.result == MavEnums.MAV_RESULT_ACCEPTED) {
            log.info("命令 {} 被 ArduPilot 接受", ack.command);
            if (ack.command == MavEnums.MAV_CMD_COMPONENT_ARM_DISARM) {
                notifyStatusChange(armed.get() ? "ARMED" : "DISARMED");
            }
        } else {
            log.warn("命令 {} 被 ArduPilot 拒绝 (result={})", ack.command, ack.result);
        }
    }

    private void notifyTelemetry() {
        HardwareState state = getState();
        for (TelemetryListener l : listeners) {
            try {
                l.onTelemetry(state);
            } catch (RuntimeException e) {
                log.warn("遥测监听器异常: {}", e.getMessage());
            }
        }
    }

    private void notifyTelemetryWithPosition(double lat, double lon, double alt) {
        HardwareState state = new HardwareState.Builder()
                .connected(connected.get())
                .armed(armed.get())
                .mode(mode.get())
                .lat(lat)
                .lon(lon)
                .alt(alt)
                .build();
        for (TelemetryListener l : listeners) {
            try {
                l.onTelemetry(state);
            } catch (RuntimeException e) {
                log.warn("遥测监听器异常: {}", e.getMessage());
            }
        }
    }

    private void notifyTelemetryWithBattery(int batteryRemaining) {
        HardwareState state = new HardwareState.Builder()
                .connected(connected.get())
                .armed(armed.get())
                .mode(mode.get())
                .battery(batteryRemaining)
                .build();
        for (TelemetryListener l : listeners) {
            try {
                l.onTelemetry(state);
            } catch (RuntimeException e) {
                log.warn("遥测监听器异常: {}", e.getMessage());
            }
        }
    }

    private void notifyTelemetryWithSpeed(float airspeed, float groundspeed, int heading) {
        HardwareState state = new HardwareState.Builder()
                .connected(connected.get())
                .armed(armed.get())
                .mode(mode.get())
                .airspeed(airspeed)
                .groundspeed(groundspeed)
                .heading(heading)
                .build();
        for (TelemetryListener l : listeners) {
            try {
                l.onTelemetry(state);
            } catch (RuntimeException e) {
                log.warn("遥测监听器异常: {}", e.getMessage());
            }
        }
    }

    private void notifyStatusChange(String status) {
        for (TelemetryListener l : listeners) {
            try {
                l.onStatusChange(status);
            } catch (RuntimeException e) {
                log.warn("状态监听器异常: {}", e.getMessage());
            }
        }
    }

    /**
     * ArduPilot custom_mode（mode_id）转模式名称。
     * ArduPilot 的 mode_id 与 PX4 完全不同。
     */
    private static String arduPilotCustomModeToName(int customMode) {
        // ArduCopter mode_id
        return switch (customMode) {
            case 0 -> "STABILIZE";
            case 1 -> "ACRO";
            case 2 -> "ALT_HOLD";
            case 3 -> "AUTO";
            case 4 -> "GUIDED";
            case 5 -> "LOITER";
            case 6 -> "RTL";
            case 7 -> "CIRCLE";
            case 8 -> "POSITION";
            case 9 -> "LAND";
            case 10 -> "OF_LOITER";
            case 11 -> "DRIFT";
            case 13 -> "SPORT";
            case 14 -> "FLIP";
            case 15 -> "AUTOTUNE";
            case 16 -> "POSHOLD";
            case 17 -> "BRAKE";
            case 18 -> "THROW";
            case 19 -> "AVOID_ADSB";
            case 20 -> "GUIDED_NOGPS";
            case 21 -> "SMART_RTL";
            case 22 -> "FLOWHOLD";
            case 23 -> "FOLLOW";
            case 24 -> "ZIGZAG";
            default -> "UNKNOWN(" + customMode + ")";
        };
    }

    /**
     * 模式名称转 ArduPilot mode_id。
     */
    private static int arduPilotModeToId(String modeName) {
        return switch (modeName) {
            case "STABILIZE" -> 0;
            case "ACRO" -> 1;
            case "ALT_HOLD" -> 2;
            case "AUTO" -> 3;
            case "GUIDED" -> 4;
            case "LOITER" -> 5;
            case "RTL" -> 6;
            case "CIRCLE" -> 7;
            case "POSITION" -> 8;
            case "LAND" -> 9;
            case "POSHOLD" -> 16;
            case "BRAKE" -> 17;
            case "SMART_RTL" -> 21;
            case "FOLLOW" -> 23;
            default -> 0;
        };
    }
}