package io.aerofleet.mavlink.hardware;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

/**
 * 模拟硬件适配器：纯内存实现，无需真实飞控硬件。
 * <p>
 * 用于开发测试和 SDK 集成测试，模拟基本的飞行状态变化：
 * <ul>
 *   <li>连接/断开：纯内存状态切换</li>
 *   <li>ARM/DISARM：状态切换 + 通知</li>
 *   <li>起飞：模拟高度逐渐上升至目标高度</li>
 *   <li>RTL：模拟返回起飞点</li>
 *   <li>模式切换：状态切换</li>
 *   <li>任务上传/下载：内存存储航点列表</li>
 *   <li>遥测：定时模拟状态更新（位置、电池、速度等）</li>
 * </ul>
 * <p>
 * 线程安全：所有可变状态使用原子变量或并发集合，遥测模拟在独立线程中运行。
 */
public class SimulatedHardwareAdapter implements HardwareAdapter {

    private static final Logger log = LoggerFactory.getLogger(SimulatedHardwareAdapter.class);

    private static final long TELEMETRY_INTERVAL_MS = 200L;
    private static final double TAKEOFF_RATE = 2.0;     // 起飞爬升率（m/s）
    private static final double RTL_RATE = 5.0;         // RTL 水平速度（m/s）
    private static final double DESCENT_RATE = 1.0;     // 下降率（m/s）

    private final AtomicBoolean connected = new AtomicBoolean(false);
    private final AtomicBoolean armed = new AtomicBoolean(false);
    private final AtomicReference<String> mode = new AtomicReference<>("UNKNOWN");
    private final AtomicInteger systemId = new AtomicInteger(-1);

    // 模拟飞行状态
    private final AtomicReference<Double> lat = new AtomicReference<>(22.531043);
    private final AtomicReference<Double> lon = new AtomicReference<>(114.055953);
    private final AtomicReference<Double> alt = new AtomicReference<>(0.0);
    private final AtomicReference<Double> battery = new AtomicReference<>(100.0);
    private final AtomicReference<Double> heading = new AtomicReference<>(0.0);
    private final AtomicReference<Double> airspeed = new AtomicReference<>(0.0);
    private final AtomicReference<Double> groundspeed = new AtomicReference<>(0.0);

    // 起飞点（用于 RTL）
    private double homeLat = 22.531043;
    private double homeLon = 114.055953;

    // 目标高度（起飞用）
    private final AtomicReference<Double> targetAlt = new AtomicReference<>(0.0);

    // 任务航点存储
    private final CopyOnWriteArrayList<Waypoint> missionWaypoints = new CopyOnWriteArrayList<>();

    // 遥测监听器
    private final CopyOnWriteArrayList<TelemetryListener> listeners = new CopyOnWriteArrayList<>();

    // 遥测模拟线程
    private ScheduledExecutorService telemetryExecutor;
    private ScheduledExecutorService heartbeatExecutor;

    // 模拟飞行行为
    private enum SimPhase { IDLE, TAKEOFF, CRUISE, RTL, LANDING }
    private final AtomicReference<SimPhase> phase = new AtomicReference<>(SimPhase.IDLE);

    @Override
    public boolean connect(String connectionUrl) {
        if (connected.get()) {
            log.warn("SimulatedHardwareAdapter 已连接，忽略重复连接请求");
            return true;
        }
        connected.set(true);
        armed.set(false);
        mode.set("STABILIZE");
        systemId.set(1);
        alt.set(0.0);
        battery.set(100.0);
        phase.set(SimPhase.IDLE);
        log.info("SimulatedHardwareAdapter 已连接（模拟模式）");

        startHeartbeat();
        startTelemetrySimulation();
        notifyStatusChange("CONNECTED");
        return true;
    }

    @Override
    public boolean disconnect() {
        if (!connected.get()) {
            log.warn("SimulatedHardwareAdapter 未连接，忽略断开请求");
            return true;
        }
        stopHeartbeat();
        stopTelemetrySimulation();
        connected.set(false);
        armed.set(false);
        mode.set("UNKNOWN");
        systemId.set(-1);
        phase.set(SimPhase.IDLE);
        log.info("SimulatedHardwareAdapter 已断开");
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
            return;
        }
        heartbeatExecutor = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "sim-heartbeat");
            t.setDaemon(true);
            return t;
        });
        heartbeatExecutor.scheduleAtFixedRate(() -> {
            // 模拟心跳：电池缓慢消耗
            if (connected.get() && armed.get()) {
                double currentBattery = battery.get();
                if (currentBattery > 0) {
                    battery.set(Math.max(0, currentBattery - 0.01));
                }
            }
        }, 0, 1000, TimeUnit.MILLISECONDS);
        log.info("SimulatedHardwareAdapter 心跳已启动");
    }

    @Override
    public void stopHeartbeat() {
        if (heartbeatExecutor != null) {
            heartbeatExecutor.shutdownNow();
            heartbeatExecutor = null;
        }
    }

    @Override
    public HardwareState getState() {
        return new HardwareState.Builder()
                .connected(connected.get())
                .armed(armed.get())
                .mode(mode.get())
                .lat(lat.get())
                .lon(lon.get())
                .alt(alt.get())
                .battery(battery.get())
                .heading(heading.get())
                .airspeed(airspeed.get())
                .groundspeed(groundspeed.get())
                .build();
    }

    @Override
    public boolean arm() {
        if (!connected.get()) {
            log.warn("未连接，无法 ARM");
            return false;
        }
        armed.set(true);
        log.info("模拟适配器已 ARM");
        notifyStatusChange("ARMED");
        return true;
    }

    @Override
    public boolean disarm() {
        if (!connected.get()) {
            log.warn("未连接，无法 DISARM");
            return false;
        }
        armed.set(false);
        phase.set(SimPhase.IDLE);
        alt.set(0.0);
        airspeed.set(0.0);
        groundspeed.set(0.0);
        log.info("模拟适配器已 DISARM");
        notifyStatusChange("DISARMED");
        return true;
    }

    @Override
    public boolean takeoff(double altitude) {
        if (!connected.get()) {
            log.warn("未连接，无法起飞");
            return false;
        }
        if (!armed.get()) {
            log.warn("未 ARM，无法起飞");
            return false;
        }
        targetAlt.set(altitude);
        phase.set(SimPhase.TAKEOFF);
        mode.set("GUIDED");
        log.info("模拟起飞至 {} 米", altitude);
        notifyStatusChange("TAKEOFF:" + altitude);
        return true;
    }

    @Override
    public boolean rtl() {
        if (!connected.get()) {
            log.warn("未连接，无法 RTL");
            return false;
        }
        phase.set(SimPhase.RTL);
        mode.set("RTL");
        log.info("模拟 RTL");
        notifyStatusChange("RTL");
        return true;
    }

    @Override
    public boolean setMode(String newMode) {
        if (!connected.get()) {
            log.warn("未连接，无法切换模式");
            return false;
        }
        String oldMode = mode.get();
        mode.set(newMode);
        log.info("模拟模式切换: {} → {}", oldMode, newMode);
        notifyStatusChange("MODE_CHANGED:" + newMode);
        return true;
    }

    @Override
    public boolean uploadMission(List<Waypoint> waypoints) {
        if (!connected.get()) {
            log.warn("未连接，无法上传任务");
            return false;
        }
        missionWaypoints.clear();
        missionWaypoints.addAll(waypoints);
        log.info("模拟任务上传完成，共 {} 个航点", waypoints.size());
        notifyStatusChange("MISSION_UPLOADED:" + waypoints.size());
        return true;
    }

    @Override
    public List<Waypoint> downloadMission() {
        if (!connected.get()) {
            log.warn("未连接，无法下载任务");
            return List.of();
        }
        log.info("模拟任务下载，共 {} 个航点", missionWaypoints.size());
        return new ArrayList<>(missionWaypoints);
    }

    @Override
    public boolean startMission() {
        if (!connected.get()) {
            log.warn("未连接，无法启动任务");
            return false;
        }
        if (missionWaypoints.isEmpty()) {
            log.warn("无任务航点，无法启动");
            return false;
        }
        mode.set("AUTO");
        phase.set(SimPhase.CRUISE);
        log.info("模拟任务启动");
        notifyStatusChange("MISSION_STARTED");
        return true;
    }

    @Override
    public boolean clearMission() {
        missionWaypoints.clear();
        log.info("模拟任务已清除");
        notifyStatusChange("MISSION_CLEARED");
        return true;
    }

    @Override
    public void subscribeTelemetry(TelemetryListener listener) {
        listeners.add(listener);
    }

    @Override
    public String getAdapterType() {
        return "Simulated";
    }

    @Override
    public String getFirmwareVersion() {
        return "sim-1.0.0";
    }

    @Override
    public int getSystemId() {
        return systemId.get();
    }

    // ====== 内部方法 ======

    private void startTelemetrySimulation() {
        if (telemetryExecutor != null && !telemetryExecutor.isShutdown()) {
            return;
        }
        telemetryExecutor = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "sim-telemetry");
            t.setDaemon(true);
            return t;
        });
        telemetryExecutor.scheduleAtFixedRate(this::simulateStep, 0, TELEMETRY_INTERVAL_MS, TimeUnit.MILLISECONDS);
        log.info("模拟遥测已启动");
    }

    private void stopTelemetrySimulation() {
        if (telemetryExecutor != null) {
            telemetryExecutor.shutdownNow();
            telemetryExecutor = null;
        }
    }

    /**
     * 模拟一步飞行状态变化。
     */
    private void simulateStep() {
        if (!connected.get()) return;

        SimPhase currentPhase = phase.get();
        double dt = TELEMETRY_INTERVAL_MS / 1000.0; // 时间步长（秒）

        switch (currentPhase) {
            case TAKEOFF -> simulateTakeoff(dt);
            case CRUISE -> simulateCruise(dt);
            case RTL -> simulateRtl(dt);
            case LANDING -> simulateLanding(dt);
            case IDLE -> {
                // 空闲状态：仅消耗电池
            }
        }

        // 通知遥测监听器
        notifyTelemetry();
    }

    private void simulateTakeoff(double dt) {
        double currentAlt = alt.get();
        double target = targetAlt.get();
        double newAlt = Math.min(target, currentAlt + TAKEOFF_RATE * dt);
        alt.set(newAlt);
        airspeed.set(0.0);
        groundspeed.set(0.0);

        if (newAlt >= target) {
            phase.set(SimPhase.CRUISE);
            mode.set("GUIDED");
            log.info("模拟起飞完成，高度 {} 米", newAlt);
            notifyStatusChange("TAKEOFF_COMPLETE:" + newAlt);
        }
    }

    private void simulateCruise(double dt) {
        // 巡航：模拟水平移动和速度
        airspeed.set(5.0);
        groundspeed.set(5.0);
        heading.set((heading.get() + 0.5) % 360);

        // 简单模拟：纬度轻微变化
        double currentLat = lat.get();
        lat.set(currentLat + 0.000001 * groundspeed.get());
    }

    private void simulateRtl(double dt) {
        double currentLat = lat.get();
        double currentLon = lon.get();
        double currentAlt = alt.get();

        // 水平移动向起飞点
        double dLat = homeLat - currentLat;
        double dLon = homeLon - currentLon;
        double dist = Math.sqrt(dLat * dLat + dLon * dLon);

        if (dist > 0.0001) {
            double step = Math.min(dist, RTL_RATE * dt * 0.00001);
            lat.set(currentLat + dLat / dist * step);
            lon.set(currentLon + dLon / dist * step);
            groundspeed.set(RTL_RATE);
            airspeed.set(RTL_RATE);
            heading.set(Math.toDegrees(Math.atan2(dLon, dLat)));
        } else {
            // 已到达起飞点上方，开始下降
            if (currentAlt > 0.1) {
                alt.set(Math.max(0, currentAlt - DESCENT_RATE * dt));
                groundspeed.set(0.0);
                airspeed.set(0.0);
            } else {
                alt.set(0.0);
                phase.set(SimPhase.IDLE);
                mode.set("LAND");
                groundspeed.set(0.0);
                airspeed.set(0.0);
                log.info("模拟 RTL 完成，已着陆");
                notifyStatusChange("RTL_COMPLETE");
            }
        }
    }

    private void simulateLanding(double dt) {
        double currentAlt = alt.get();
        if (currentAlt > 0.1) {
            alt.set(Math.max(0, currentAlt - DESCENT_RATE * dt));
        } else {
            alt.set(0.0);
            phase.set(SimPhase.IDLE);
            log.info("模拟着陆完成");
            notifyStatusChange("LANDING_COMPLETE");
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

    private void notifyStatusChange(String status) {
        for (TelemetryListener l : listeners) {
            try {
                l.onStatusChange(status);
            } catch (RuntimeException e) {
                log.warn("状态监听器异常: {}", e.getMessage());
            }
        }
    }
}