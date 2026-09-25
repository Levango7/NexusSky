package io.aerofleet.mavlink.hardware;

import io.aerofleet.mavlink.MavlinkFrame;
import io.aerofleet.mavlink.MavlinkParser;
import io.aerofleet.mavlink.enums.MavEnums;
import io.aerofleet.mavlink.messages.*;
import io.aerofleet.mavlink.transport.UdpMavlinkTransport;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

/**
 * PX4 飞控适配器：基于 MAVLink 协议通过 UDP 连接 PX4 SITL 或真机。
 * <p>
 * PX4 特有行为：
 * <ul>
 *   <li>默认 UDP 端口 14540（SITL）/ 14550（真机 + GCS）</li>
 *   <li>心跳中 autopilot = MAV_AUTOPILOT_PX4 (12)</li>
 *   <li>自定义模式编码与 ArduPilot 不同</li>
 *   <li>MISSION_ITEM_INT 使用 MAV_FRAME_GLOBAL_RELATIVE_ALT</li>
 * </ul>
 * <p>
 * 线程安全：所有可变状态使用原子变量或并发集合，心跳和遥测在独立线程中运行。
 */
public class Px4Adapter implements HardwareAdapter {

    private static final Logger log = LoggerFactory.getLogger(Px4Adapter.class);

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
    private final ConcurrentHashMap<String, Object> modeMap = new ConcurrentHashMap<>();

    private volatile UdpMavlinkTransport transport;
    private ScheduledExecutorService heartbeatExecutor;
    private ScheduledExecutorService telemetryExecutor;
    private volatile String connectionUrl;

    // PX4 自定义模式映射（部分常用模式）
    static {
        // PX4 custom_mode 值 → 模式名称
    }

    @Override
    public boolean connect(String connectionUrl) {
        if (connected.get()) {
            log.warn("Px4Adapter 已连接，忽略重复连接请求");
            return true;
        }
        this.connectionUrl = connectionUrl;

        try {
            // 解析连接字符串：udp://host:port
            if (!connectionUrl.startsWith("udp://")) {
                log.error("Px4Adapter 仅支持 udp:// 连接，收到: {}", connectionUrl);
                return false;
            }
            String addrPart = connectionUrl.substring("udp://".length());
            String[] parts = addrPart.split(":");
            String host = parts[0];
            int port = parts.length > 1 ? Integer.parseInt(parts[1]) : 14540;

            // 绑定本地端口接收，向飞控端口发送
            transport = new UdpMavlinkTransport(0);
            transport.addFrameListener(this::handleFrame);

            // 启动对端发现：向飞控发送心跳直到收到回包
            InetSocketAddress target = new InetSocketAddress(host, port);
            transport.enablePeerDiscovery(target, () -> buildHeartbeat());

            connected.set(true);
            systemId.set(TARGET_SYSTEM_ID);
            log.info("Px4Adapter 已连接到 {}", connectionUrl);

            // 自动启动心跳
            startHeartbeat();

            notifyStatusChange("CONNECTED");
            return true;
        } catch (IOException e) {
            log.error("Px4Adapter 连接失败: {}", e.getMessage());
            connected.set(false);
            return false;
        }
    }

    @Override
    public boolean disconnect() {
        if (!connected.get()) {
            log.warn("Px4Adapter 未连接，忽略断开请求");
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
        log.info("Px4Adapter 已断开");
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
            Thread t = new Thread(r, "px4-heartbeat");
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
        log.info("Px4Adapter 心跳已启动");
    }

    @Override
    public void stopHeartbeat() {
        if (heartbeatExecutor != null) {
            heartbeatExecutor.shutdownNow();
            heartbeatExecutor = null;
            log.info("Px4Adapter 心跳已停止");
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
        return sendCommand(MavEnums.MAV_CMD_COMPONENT_ARM_DISARM, 1f, 0f);
    }

    @Override
    public boolean disarm() {
        return sendCommand(MavEnums.MAV_CMD_COMPONENT_ARM_DISARM, 0f, 0f);
    }

    @Override
    public boolean takeoff(double altitude) {
        return sendCommand(MavEnums.MAV_CMD_NAV_TAKEOFF, 0f, 0f, 0f, 0f,
                (float) altitude, 0f, 0f);
    }

    @Override
    public boolean rtl() {
        return setMode("AUTO.RTL") || sendCommand(MavEnums.MAV_CMD_NAV_RETURN_TO_LAUNCH);
    }

    @Override
    public boolean setMode(String modeName) {
        // PX4 模式切换通过 MAV_CMD_DO_SET_MODE
        int customMode = px4ModeToCustomMode(modeName);
        return sendCommand(MavEnums.MAV_CMD_DO_SET_MODE, 1f, (float) customMode);
    }

    @Override
    public boolean uploadMission(List<Waypoint> waypoints) {
        if (!connected.get() || waypoints == null || waypoints.isEmpty()) {
            return false;
        }
        // 发送 MISSION_COUNT，等待飞控逐项请求 MISSION_REQUEST_INT
        int count = waypoints.size();
        MissionCountMsg countMsg = new MissionCountMsg(count, TARGET_SYSTEM_ID, TARGET_COMPONENT_ID,
                MavEnums.MAV_MISSION_TYPE_MISSION, 0);
        sendFrame(countMsg);
        log.info("已发送任务数量 {} 给 PX4", count);

        // 实际任务上传需要完整的 MISSION 协议握手，此处为骨架实现
        // 完整实现需处理 MISSION_REQUEST_INT → MISSION_ITEM_INT → MISSION_ACK 流程
        return true;
    }

    @Override
    public List<Waypoint> downloadMission() {
        if (!connected.get()) {
            return List.of();
        }
        // 发送 MISSION_REQUEST_LIST，等待飞控回复 MISSION_COUNT
        MissionRequestList reqList = new MissionRequestList(TARGET_SYSTEM_ID, TARGET_COMPONENT_ID,
                MavEnums.MAV_MISSION_TYPE_MISSION);
        sendFrame(reqList);
        log.info("已请求 PX4 任务列表");

        // 实际任务下载需要完整的 MISSION 协议握手，此处为骨架实现
        return List.of();
    }

    @Override
    public boolean startMission() {
        return sendCommand(MavEnums.MAV_CMD_MISSION_START, 0f, 0f);
    }

    @Override
    public boolean clearMission() {
        // 通过发送空 MISSION_COUNT 实现清除
        MissionCountMsg emptyCount = new MissionCountMsg(0, TARGET_SYSTEM_ID, TARGET_COMPONENT_ID,
                MavEnums.MAV_MISSION_TYPE_MISSION, 0);
        sendFrame(emptyCount);
        log.info("已发送清除任务指令给 PX4");
        return true;
    }

    @Override
    public void subscribeTelemetry(TelemetryListener listener) {
        listeners.add(listener);
    }

    @Override
    public String getAdapterType() {
        return "PX4";
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
                MavEnums.MAV_AUTOPILOT_PX4, baseMode, MavEnums.MAV_STATE_ACTIVE);
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
        log.info("已发送命令 {} 给 PX4", command);
        return true;
    }

    /**
     * 处理收到的 MAVLink 帧，转换为 HardwareState 并通知监听器。
     */
    private void handleFrame(MavlinkFrame frame) {
        MavlinkMessage msg = MavlinkMessage.decode(frame);
        if (msg == null) return;

        if (msg instanceof Heartbeat hb) {
            armed.set((hb.baseMode & MavEnums.MAV_MODE_FLAG_SAFETY_ARMED) != 0);
            systemId.set(frame.getSystemId());
            // PX4 自定义模式解析
            mode.set(px4CustomModeToName(hb.customMode));
            notifyTelemetry();
        } else if (msg instanceof GlobalPositionInt gpi) {
            notifyTelemetryWithPosition(gpi.lat(), gpi.lon(), gpi.relativeAltM());
        } else if (msg instanceof SysStatus sys) {
            notifyTelemetryWithBattery(sys.batteryRemaining);
        } else if (msg instanceof VfrHud hud) {
            notifyTelemetryWithSpeed(hud.airspeed, hud.groundspeed, hud.heading);
        } else if (msg instanceof Attitude att) {
            // 姿态数据可用于扩展状态
        } else if (msg instanceof CommandAck ack) {
            handleCommandAck(ack);
        } else if (msg instanceof Statustext st) {
            log.info("PX4 状态文本: {}", st.text);
        }
    }

    private void handleCommandAck(CommandAck ack) {
        if (ack.result == MavEnums.MAV_RESULT_ACCEPTED) {
            log.info("命令 {} 被 PX4 接受", ack.command);
        } else {
            log.warn("命令 {} 被 PX4 拒绝 (result={})", ack.command, ack.result);
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
     * PX4 custom_mode 值转模式名称（部分常用模式）。
     */
    private static String px4CustomModeToName(int customMode) {
        return switch (customMode) {
            case 1 -> "MANUAL";
            case 2 -> "ALTCTL";
            case 3 -> "POSCTL";
            case 4 -> "AUTO.MISSION";
            case 5 -> "AUTO.LOITER";
            case 6 -> "AUTO.RTL";
            case 7 -> "AUTO.LAND";
            case 8 -> "OFFBOARD";
            case 9 -> "STABILIZED";
            case 10 -> "RATTITUDE";
            case 11 -> "AUTO.TAKEOFF";
            default -> "UNKNOWN(" + customMode + ")";
        };
    }

    /**
     * 模式名称转 PX4 custom_mode 值（部分常用模式）。
     */
    private static int px4ModeToCustomMode(String modeName) {
        return switch (modeName) {
            case "MANUAL" -> 1;
            case "ALTCTL" -> 2;
            case "POSCTL" -> 3;
            case "AUTO.MISSION", "AUTO" -> 4;
            case "AUTO.LOITER", "LOITER" -> 5;
            case "AUTO.RTL", "RTL" -> 6;
            case "AUTO.LAND", "LAND" -> 7;
            case "OFFBOARD" -> 8;
            case "STABILIZED" -> 9;
            case "AUTO.TAKEOFF" -> 11;
            default -> 0;
        };
    }
}