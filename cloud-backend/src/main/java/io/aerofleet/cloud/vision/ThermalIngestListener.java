package io.aerofleet.cloud.vision;

import io.aerofleet.cloud.gateway.DeviceRegistry;
import io.aerofleet.cloud.gateway.DroneSnapshot;
import io.aerofleet.cloud.gateway.MavlinkMessageEvent;
import io.aerofleet.mavlink.messages.ThermalDataMsg;
import io.aerofleet.mavlink.messages.ThermalHotspotMsg;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * M3 热成像遥测 ingest（P2 #19，2026-10-24 接线；2026-10-26 条件③收官）。
 *
 * <p>此前 THERMAL_DATA(30012) 在 cloud-backend 零引用：drone-sim 每秒下传机下视
 * 温度场分析，网关解得出来、事件也发布了，就是没有任何监听器接——热成像任务
 * 永远停在 PENDING，前端面板只能吃 404。本监听器补上最后一环：
 *
 * <ol>
 *   <li>把每帧分析写进 {@link DroneSnapshot}（机队最新热读数，GET /thermal/status 读它）；</li>
 *   <li>收账该 sysid 最老的活跃热成像任务（PENDING → COMPLETED，语义见
 *       {@link ThermalTaskService#completeOldestActive}）。</li>
 *   <li>【条件③】把 THERMAL_HOTSPOT(30064) 的逐点像素坐标反投影成 lat/lon
 *       （见 {@link #onThermalHotspot}）——只有聚合量时"热源在地面哪个位置"
 *       这个问题在链路层就丢了。</li>
 * </ol>
 *
 * <p>姿势对齐 {@code TelemetrySnapshotListener}（@EventListener + msgId 条件）；
 * 事件已按 sysid 广播，这里不做租户过滤（快照写入是全量事实，读侧按租户口径过滤）。
 */
@Component
public class ThermalIngestListener {

    /**
     * 热成像场景分辨率——必须与 drone-sim
     * {@code VirtualDrone#synthesizeThermalScene} 的 32×24 一致。
     * <p>像素 u/v 只有配上传感器尺寸才有物理意义：反投影要做
     * {@code f = (W/2)/tan(hFov/2)}，拿错 W 会让所有地面坐标成比例缩放。
     */
    static final int THERMAL_IMAGE_W = 32;
    static final int THERMAL_IMAGE_H = 24;

    /**
     * 热像仪水平视场角（度）。
     * <p><b>这是假设值，不是测量值</b>：合成场景没有真实相机的 FOV 可言。
     * 取值 55°（常见热成像机芯量级）。它对结果的影响是单调的——FOV 取大，
     * 同一像素对应的地面偏移同比变大。若日后接入真实载荷，改成按机型配置即可。
     */
    static final double THERMAL_HFOV_DEG = 55.0;

    /**
     * 云台零点假设：正下视（nadir）。
     * <p>30064 不带云台姿态，遥测里也没有独立的云台消息可查；本仓合成场景
     * 定义即"机下视"（见 {@code synthesizeThermalScene} 注释），故取 0/0。
     * 若日后挂可俯仰云台，需要在消息里带上 gimbalPitch/Yaw，现在的常量会
     * 让斜视场景的解算结果错——宁可留一个显式假设，也不要一个查不到来源的数字。
     */
    static final double ASSUMED_GIMBAL_PITCH_DEG = 0.0;
    static final double ASSUMED_GIMBAL_YAW_DEG = 0.0;

    private final DeviceRegistry registry;
    private final ThermalTaskService tasks;
    /** 可为 null（老装配/单测未注入）：此时逐点坐标仍下发，lat/lon 记 null（明示"未解算"）。 */
    private final GeolocationSolver solver;

    public ThermalIngestListener(DeviceRegistry registry, ThermalTaskService tasks) {
        this(registry, tasks, null);
    }

    /**
     * 正式装配构造器。
     * <p><b>必须显式 @Autowired</b>：两个公开构造器并存时 Spring 无法自己选
     * （"No default constructor found" / 歧义报错），整个 CloudBackendApplication
     * 上下文起不来——连带所有 @SpringBootTest 一起红（实测踩到：EdgeCoordination /
     * FlightLog 两批用例齐刷刷 ApplicationContext failure）。
     */
    @Autowired
    public ThermalIngestListener(DeviceRegistry registry, ThermalTaskService tasks,
                                 GeolocationSolver solver) {
        this.registry = registry;
        this.tasks = tasks;
        this.solver = solver;
    }

    @EventListener(condition = "#event.msgId == T(io.aerofleet.mavlink.messages.ThermalDataMsg).ID")
    public void onThermalData(MavlinkMessageEvent event) {
        ThermalDataMsg msg = (ThermalDataMsg) event.getMessage();
        DroneSnapshot s = registry.registerIfAbsent(event.getSysid());
        s.thermalTempMean = msg.tempMean;
        s.thermalTempMin = msg.tempMin;
        s.thermalTempMax = msg.tempMax;
        s.thermalTempStdDev = msg.tempStdDev;
        s.thermalHotspotCount = msg.hotspotCount;
        s.thermalUpdatedAtMs = System.currentTimeMillis();
        tasks.completeOldestActive(event.getSysid(), msg.tempMean, msg.tempMin,
                msg.tempMax, msg.tempStdDev, msg.hotspotCount);
    }

    /**
     * P2 #19 条件③：逐点像素坐标 → 地面经纬度。
     *
     * <p>输入哪里来：{@link GeolocationSolver} 要的是「相机位姿」，而 30064 帧本身
     * 只有 (u, v, 峰值温度)。相机位姿的快照源就是 {@link DroneSnapshot}：
     * GPS（lat/lon）即相机本地坐标原点、relativeAlt 即相机离地高度、
     * roll/pitch/yaw 即无人机姿态（ATTITUDE 遥测写入），云台按正下视假设取 0/0。
     *
     * <p><b>时效口径</b>：姿态与位置是"最新已知值"，不是这一帧热像拍摄时刻的值——
     * 30012/30064 都不带位姿，这是遥测设计的固有限制。10Hz 姿态 + 1Hz 热像下
     * 最坏 100ms 偏移，机场景内（相对位置慢变）可接受；高速机动场景会放大误差。
     * 这一点如实写进注释与面板文案，不假装没有。
     *
     * <p>解不出来时不猜：缺 GPS/高度/姿态、或射线朝天（{@code solve} 返回 null），
     * 该点 lat/lon 记 null，由面板明说"无法解算"，而不是填 0/NaN 冒充坐标。
     */
    @EventListener(condition = "#event.msgId == T(io.aerofleet.mavlink.messages.ThermalHotspotMsg).ID")
    public void onThermalHotspot(MavlinkMessageEvent event) {
        ThermalHotspotMsg msg = (ThermalHotspotMsg) event.getMessage();
        DroneSnapshot s = registry.registerIfAbsent(event.getSysid());
        s.thermalHotspots = geolocate(s, msg);
        s.thermalHotspotsTruncated = msg.truncated;
    }

    private List<DroneSnapshot.ThermalHotspot> geolocate(DroneSnapshot s, ThermalHotspotMsg msg) {
        boolean poseReady = hasPose(s);
        List<DroneSnapshot.ThermalHotspot> out = new ArrayList<>(msg.count);
        for (int[] h : msg.hotspots) {
            Double lat = null;
            Double lon = null;
            if (poseReady && solver != null) {
                GeolocationSolver.Result r = solver.solveOrNull(
                        h[0], h[1], THERMAL_IMAGE_W, THERMAL_IMAGE_H, THERMAL_HFOV_DEG,
                        s.lat, s.lon, 0.0, 0.0, s.relativeAlt,
                        s.roll, s.pitch, s.yaw,
                        ASSUMED_GIMBAL_PITCH_DEG, ASSUMED_GIMBAL_YAW_DEG);
                if (r != null && !Double.isNaN(r.lat) && !Double.isNaN(r.lon)) {
                    lat = r.lat;
                    lon = r.lon;
                }
            }
            out.add(new DroneSnapshot.ThermalHotspot(h[0], h[1], h[2] / 10.0, lat, lon));
        }
        return out;
    }

    /** 反投影所需的最小输入集：位置 + 离地高度 + 三轴姿态（任一缺失即无法解算）。 */
    static boolean hasPose(DroneSnapshot s) {
        return !Double.isNaN(s.lat) && !Double.isNaN(s.lon)
                && !Double.isNaN(s.relativeAlt) && s.relativeAlt > 0.5
                && !Double.isNaN(s.roll) && !Double.isNaN(s.pitch) && !Double.isNaN(s.yaw);
    }
}
