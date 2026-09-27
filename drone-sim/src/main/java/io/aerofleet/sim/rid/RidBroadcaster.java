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
import io.aerofleet.sim.SimLog;

import java.io.IOException;
import java.net.SocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Remote ID (RID) 广播器：按 ASTM F3411 标准周期性广播 OPEN_DRONE_ID_* 消息。
 * <p>
 * 使用 {@link ScheduledExecutorService} 定时执行 {@link #broadcast()}，
 * 通过 volatile 字段接收 {@link #updateTelemetry} 的遥测更新，
 * 构造 BASIC_ID / LOCATION / SYSTEM / SELF_ID / OPERATOR_ID 消息，
 * 可选使用 MESSAGE_PACK 合并发送或逐条发送。
 * </p>
 * <p>
 * IOException 在 broadcast 中仅记录 WARN 日志，不抛出，不影响下次广播。
 * </p>
 */
public final class RidBroadcaster implements AutoCloseable {

    /** MAVLink 组件 ID（与 VirtualDrone.COMPONENT_ID 一致）。 */
    private static final int COMPONENT_ID = 1;

    private final UdpMavlinkTransport transport;
    private final int sysid;
    private final RidConfig config;
    private final ScheduledExecutorService scheduler;
    private final AtomicInteger frameSeq = new AtomicInteger(0);

    // ---- volatile 遥测字段，由 updateTelemetry 写、broadcast 读 ----
    private volatile double lat = 0.0;
    private volatile double lon = 0.0;
    private volatile double altBaro = 0.0;
    private volatile double altGeo = 0.0;
    private volatile double height = 0.0;
    private volatile double heading = 0.0;
    private volatile double speedH = 0.0;
    private volatile double speedV = 0.0;
    private volatile boolean airborne = false;
    private volatile boolean emergency = false;

    /**
     * 初始化并启动定时广播任务。
     *
     * @param transport MAVLink UDP 传输层
     * @param sysid     系统 ID
     * @param config    RID 配置
     */
    public void start(UdpMavlinkTransport transport, int sysid, RidConfig config) {
        // 字段已在构造器中赋值，此处启动定时任务
        this.scheduler.scheduleAtFixedRate(this::broadcastSafe,
                (long) (config.broadcastInterval() * 1000),
                (long) (config.broadcastInterval() * 1000),
                TimeUnit.MILLISECONDS);
    }

    /**
     * 构造 RidBroadcaster（不启动定时任务，需调 {@link #start} 启动）。
     *
     * @param transport MAVLink UDP 传输层
     * @param sysid     系统 ID
     * @param config    RID 配置
     */
    public RidBroadcaster(UdpMavlinkTransport transport, int sysid, RidConfig config) {
        this.transport = transport;
        this.sysid = sysid;
        this.config = config;
        this.scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "rid-broadcast-" + sysid);
            t.setDaemon(true);
            return t;
        });
    }

    /**
     * 更新遥测数据（由 tick 循环调用，volatile 保证可见性）。
     *
     * @param lat      纬度（度）
     * @param lon      经度（度）
     * @param altBaro  气压高度（m）
     * @param altGeo   大地高度（m）
     * @param height   离地高度（m）
     * @param heading  航向角（度）
     * @param speedH   水平速度（m/s）
     * @param speedV   垂直速度（m/s，上升为正）
     * @param airborne 是否在飞行
     * @param emergency 是否处于紧急状态
     */
    public void updateTelemetry(double lat, double lon, double altBaro, double altGeo,
                                 double height, double heading, double speedH, double speedV,
                                 boolean airborne, boolean emergency) {
        this.lat = lat;
        this.lon = lon;
        this.altBaro = altBaro;
        this.altGeo = altGeo;
        this.height = height;
        this.heading = heading;
        this.speedH = speedH;
        this.speedV = speedV;
        this.airborne = airborne;
        this.emergency = emergency;
    }

    /**
     * broadcast 的安全包装：捕获所有异常，仅记录 WARN 日志，不影响下次广播。
     */
    private void broadcastSafe() {
        try {
            broadcast();
        } catch (Throwable t) {
            SimLog.warn("RID broadcast failed: " + t.getMessage());
        }
    }

    /**
     * 构造并发送 OPEN_DRONE_ID_* 消息。
     * <p>
     * 构造顺序：BASIC_ID → LOCATION → SYSTEM → SELF_ID → OPERATOR_ID（可选）。
     * 当 {@link RidConfig#useMessagePack()} 为 true 时使用 MESSAGE_PACK 合并发送，
     * 否则逐条发送。IOException 仅记录 WARN 日志，不抛出。
     * </p>
     */
    private void broadcast() {
        // 读取 volatile 快照，保证一次广播内数据一致
        double curLat = this.lat;
        double curLon = this.lon;
        double curAltBaro = this.altBaro;
        double curAltGeo = this.altGeo;
        double curHeight = this.height;
        double curHeading = this.heading;
        double curSpeedH = this.speedH;
        double curSpeedV = this.speedV;
        boolean curAirborne = this.airborne;
        boolean curEmergency = this.emergency;

        List<MavlinkMessage> messages = new ArrayList<>(5);

        // 1. BASIC_ID：id_type=1（序列号），ua_type=config.uaType()
        byte[] uasIdBytes = padAscii(config.serialNo(), 20);
        messages.add(new OpenDroneIdBasicId(1, config.uaType(), uasIdBytes));

        // 2. LOCATION：status=airborne?2:1，direction=heading*100，speed=speedH*100
        int status = curAirborne ? 2 : 1;
        int direction = (int) Math.round(curHeading * 100) & 0xFFFF;
        int speedHorizontal = (int) Math.round(curSpeedH * 100) & 0xFFFF;
        int speedVertical = (int) Math.round(curSpeedV * 100);
        int latitude = (int) Math.round(curLat * 1e7);
        int longitude = (int) Math.round(curLon * 1e7);
        float timestamp = (System.currentTimeMillis() % 86400000L) / 1000.0f;
        messages.add(new OpenDroneIdLocation(
                status, direction, speedHorizontal, speedVertical,
                latitude, longitude,
                (float) curAltBaro, (float) curAltGeo,
                0,  // heightReference：0=地面
                0,  // horizontalAccuracy：未知
                0,  // verticalAccuracy：未知
                0,  // barometerAccuracy：未知
                0,  // speedAccuracy：未知
                timestamp));

        // 3. SYSTEM：operator_location_type=1，area_count=1
        int operatorLat = (int) Math.round(config.operatorLat() * 1e7);
        int operatorLon = (int) Math.round(config.operatorLon() * 1e7);
        messages.add(new OpenDroneIdSystem(
                0,  // flags
                1,  // operatorLocationType：1=操作者纬度/经度
                0,  // classificationType：0=未分类
                operatorLat, operatorLon,
                1,  // areaCount
                0,  // areaRadius
                0f, // areaCeiling
                0f  // areaFloor
        ));

        // 4. SELF_ID：description_type=emergency?1:0
        int descType = curEmergency ? 1 : 0;
        String descText = curEmergency ? "EMERGENCY" : config.selfIdDesc();
        byte[] descBytes = padAscii(descText, 23);
        messages.add(new OpenDroneIdSelfId(descType, descBytes));

        // 5. OPERATOR_ID：仅当 config.operatorId() 非空时
        if (!config.operatorId().isEmpty()) {
            byte[] opIdBytes = padAscii(config.operatorId(), 20);
            messages.add(new OpenDroneIdOperatorId(0, opIdBytes));
        }

        try {
            SocketAddress peer = transport.getLastPeer();
            if (peer == null) {
                // 无已知对端，跳过本次广播（不报错）
                return;
            }

            if (config.useMessagePack()) {
                // 使用 MESSAGE_PACK 合并发送
                OpenDroneIdMessagePack pack = OpenDroneIdMessagePack.pack(messages);
                sendFrame(pack, peer);
            } else {
                // 逐条发送
                for (MavlinkMessage msg : messages) {
                    sendFrame(msg, peer);
                }
            }
        } catch (IOException e) {
            SimLog.warn("RID broadcast IOException: " + e.getMessage());
        }
    }

    /**
     * 组装 MAVLink 帧并发送。
     */
    private void sendFrame(MavlinkMessage msg, SocketAddress peer) throws IOException {
        MavlinkFrame frame = msg.toFrame(sysid, COMPONENT_ID,
                frameSeq.getAndIncrement() & 0xFF);
        transport.send(frame, peer);
    }

    /**
     * 将字符串填充/截断为指定长度的 ASCII 字节数组。
     *
     * @param s      原始字符串
     * @param length 目标字节长度
     * @return length 字节的 ASCII 数组，不足补 0
     */
    private static byte[] padAscii(String s, int length) {
        byte[] result = new byte[length];
        byte[] src = s.getBytes(StandardCharsets.US_ASCII);
        int copyLen = Math.min(src.length, length);
        System.arraycopy(src, 0, result, 0, copyLen);
        return result;
    }

    @Override
    public void close() {
        scheduler.shutdownNow();
        try {
            // 等待 in-flight 广播任务结束，保证 close() 返回后不再发送任何帧
            if (!scheduler.awaitTermination(2, TimeUnit.SECONDS)) {
                SimLog.warn("RID scheduler did not terminate in time");
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}