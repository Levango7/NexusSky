package io.aerofleet.cloud.twin;

import io.aerofleet.cloud.gateway.MavlinkMessageEvent;
import io.aerofleet.mavlink.messages.SensorFusionDataMsg;
import io.aerofleet.mavlink.messages.SysStatus;
import io.aerofleet.mavlink.messages.TwinStateSyncMsg;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Service;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * M13 数字孪生实时同步生产链路（2026-10-04 接线）。
 * <p>
 * 第六轮审查曾核实：{@link DigitalTwinService#syncTwin} 唯一写入入口只有测试调用，
 * 真实运行中孪生状态恒为空，predict/compare 只会返回空/零值。本监听器补齐该链路：
 * <ul>
 *   <li>SYS_STATUS(1) → 记录每 sysid 最新电量百分比；</li>
 *   <li>SENSOR_FUSION_DATA(30054) → 换算融合态喂 {@code syncTwin}
 *       （lat/lon 1E7、alt mm/1000、heading cdeg/100、velocity m/s 原值）。
 *       喂入源选 M12 的 EKF 融合态而非 GLOBAL_POSITION_INT 原始 GPS——
 *       ROADMAP 明写「M13 依赖 M12（边缘传感器融合数据源）」，融合态是比原始
 *       观测更好的物理态估计；电量未见 SYS_STATUS 时 battery=-1（REST 侧未知）；</li>
 *   <li>每次同步后按 1Hz 节拍（每 sysid 独立）发布 {@link TwinStateSyncMsg}(30055)
 *       ——该消息此前全仓零生产者；经 TelemetryWebSocketHandler 的 WS_TYPE_MAP 以
 *       "twin-state-sync" 帧广播给 GCS（租户可见性与其他遥测帧同规则）。
 *       gcs-web 前端尚未消费该帧，属已知边界。</li>
 * </ul>
 * 监听器在 UDP 接收线程上同步执行，任何异常就地吞掉——Spring 事件组播中一个监听器
 * 抛异常会中断同帧其余监听器，孪生故障不应影响 WS 转发与 regulator 上报。
 * <p>
 * 语义边界：{@code syncTwin} 的 driftMeters 是「本次同步位置与上一孪生态的位移」，
 * 孪生与物理态在云内同源，并非与独立实测的偏差；compare REST 的近似口径见
 * {@link TwinController}。未运行机载边缘栈（EdgeInferenceRunner）的设备不发 30054，
 * 因而不进孪生——GLOBAL_POSITION_INT 兜底链路明确未做。
 */
@Service
public class TwinSyncListener {

    private static final Logger log = LoggerFactory.getLogger(TwinSyncListener.class);

    /** TWIN_STATE_SYNC 对外发布节拍（每 sysid 独立计时）。 */
    static final long PUBLISH_INTERVAL_MS = 1000;

    /** battery u8 的未知哨兵（沿用 MAVLink battery_remaining 的 255=未知惯例）。 */
    static final int BATTERY_UNKNOWN_U8 = 255;

    private final DigitalTwinService twinService;
    private final ApplicationEventPublisher eventPublisher;

    /** sysid → 最近一次 SYS_STATUS 的电量百分比。 */
    private final Map<Integer, Integer> latestBattery = new ConcurrentHashMap<>();

    /** sysid → 上次发布 TWIN_STATE_SYNC 的消息时间戳（节流用）。 */
    private final Map<Integer, Long> lastPublishMs = new ConcurrentHashMap<>();

    public TwinSyncListener(DigitalTwinService twinService, ApplicationEventPublisher eventPublisher) {
        this.twinService = twinService;
        this.eventPublisher = eventPublisher;
    }

    @EventListener(condition = "#event.msgId == T(io.aerofleet.mavlink.messages.SysStatus).ID")
    public void onSysStatus(MavlinkMessageEvent event) {
        try {
            SysStatus s = (SysStatus) event.getMessage();
            latestBattery.put(event.getSysid(), s.batteryRemaining);
        } catch (Exception e) {
            log.warn("twin battery update failed: sysid={}: {}", event.getSysid(), e.getMessage());
        }
    }

    @EventListener(condition = "#event.msgId == T(io.aerofleet.mavlink.messages.SensorFusionDataMsg).ID")
    public void onSensorFusionData(MavlinkMessageEvent event) {
        try {
            SensorFusionDataMsg f = (SensorFusionDataMsg) event.getMessage();
            int sysid = event.getSysid();
            double battery = latestBattery.getOrDefault(sysid, -1);
            twinService.syncTwin(sysid,
                    f.fusedLat / 1e7,
                    f.fusedLon / 1e7,
                    f.fusedAlt / 1000.0,
                    f.fusedHeading / 100.0,
                    f.fusedVelocity,
                    battery);
            maybePublishTwinState(sysid, event.getMsgTimestamp());
        } catch (Exception e) {
            log.warn("twin sync failed: sysid={}: {}", event.getSysid(), e.getMessage());
        }
    }

    /**
     * 1Hz 节流后把最新孪生态打包成 30055 发布。节流与发布使用同一时间基准
     * （消息到达时间戳），发布事件的时间戳取孪生态自身的 syncTimestamp。
     */
    void maybePublishTwinState(int sysid, long nowMs) {
        Long last = lastPublishMs.get(sysid);
        if (last != null && nowMs - last < PUBLISH_INTERVAL_MS) {
            return;
        }
        lastPublishMs.put(sysid, nowMs);
        TwinState s = twinService.getTwin(sysid);
        if (s == null) {
            return;
        }
        int headingCdeg = (int) Math.round(((s.heading % 360) + 360) % 360 * 100);
        int batteryU8 = s.battery < 0
                ? BATTERY_UNKNOWN_U8
                : (int) Math.min(100, Math.max(0, Math.round(s.battery)));
        TwinStateSyncMsg msg = new TwinStateSyncMsg(
                (int) Math.round(s.lat * 1e7),
                (int) Math.round(s.lon * 1e7),
                (int) Math.round(s.alt * 1000.0),
                s.syncTimestamp,
                (float) s.velocity,
                (float) s.driftMeters,
                headingCdeg,
                s.sysid,
                batteryU8);
        eventPublisher.publishEvent(
                new MavlinkMessageEvent(this, s.sysid, TwinStateSyncMsg.ID, msg, s.syncTimestamp));
    }
}
