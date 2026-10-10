package io.aerofleet.cloud.surveillance;

import io.aerofleet.cloud.api.ws.TelemetryWebSocketHandler;
import io.aerofleet.cloud.gateway.MavlinkMessageEvent;
import io.aerofleet.mavlink.messages.SurveillanceStatusMsg;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 安防设备状态 WS 推送器（WS_TYPE_MAP 收口 2026-10-05 边界清零）。
 * <p>
 * SurveillanceStatusMsg(30059) 的生产者：1s 周期枚举注册表内全部安防设备，
 * 逐设备发布状态帧（设备注册后 1s 内可见，注册无需即时推送）。
 * 无 WS 客户端时跳过（仿 CellTowerPusher 范式）。
 * <p>
 * 字段映射口径（协议 vs 设备模型的诚实边界）：
 * <ul>
 *   <li>deviceId：String 设备 ID 的 u16 稳定哈希（{@code hashCode() & 0xFFFF}，
 *       碰撞可能，REST 面以字符串 ID 为准）</li>
 *   <li>deviceType：{@link SurveillanceDevice.Vendor#ordinal()}——HIKVISION=0/
 *       DAHUA=1/UNIVIEW=2/ONVIF=3，与协议 0=海康/1=大华/2=宇视/3=其他 一致</li>
 *   <li>status：{@link SurveillanceDevice.Status#ordinal()} 直接映射——四态与
 *       协议值域一致（0=在线/1=离线/2=故障/3=维护）</li>
 *   <li>onlineCameras/totalCameras：totalCameras 取设备模型（注册时指定，默认 1）；
 *       onlineCameras 按状态派生（ONLINE → 全部在线，否则 0）。
 *       **部分通道离线**（4 路中 2 路在线）属生产阶段——需通道级状态上报</li>
 *   <li>uptimeSec：进程内首次注册以来的时长（重启重新计数）</li>
 *   <li>lastEventMs：最近心跳时间戳（u32 ms 截断）</li>
 * </ul>
 * <p>
 * 显式租户：帧携带设备自身 tenantId（null = 未归属 → 全局域）；
 * 帧 sysid 为设备 ID 哈希（非无人机 sysid），路由不经 tenantOf 反查。
 */
@Component
public class SurveillanceStatusPusher {
    private static final Logger log = LoggerFactory.getLogger(SurveillanceStatusPusher.class);

    private final SurveillanceDeviceRegistry registry;
    private final ApplicationEventPublisher eventPublisher;
    private final TelemetryWebSocketHandler wsHandler;

    public SurveillanceStatusPusher(SurveillanceDeviceRegistry registry,
                                    ApplicationEventPublisher eventPublisher,
                                    TelemetryWebSocketHandler wsHandler) {
        this.registry = registry;
        this.eventPublisher = eventPublisher;
        this.wsHandler = wsHandler;
    }

    /**
     * 1s 周期推送：逐设备发布 30059 帧。无 WS 客户端时跳过。
     */
    @Scheduled(fixedDelay = 1000)
    public void pushOnce() {
        if (wsHandler.connectionCount() == 0) {
            return;
        }
        try {
            // @Scheduled 线程无租户上下文 → listDevices 返回全部设备，
            // 每设备帧携带自身显式租户，互不串扰。
            List<SurveillanceDevice> devices = registry.listDevices();
            for (SurveillanceDevice d : devices) {
                publishDeviceStatus(d);
            }
        } catch (Exception e) {
            log.warn("surveillance status push failed: {}", e.getMessage());
        }
    }

    /** 发布单设备 30059 帧；失败仅记日志（推送是旁路关注点）。 */
    private void publishDeviceStatus(SurveillanceDevice d) {
        try {
            long now = System.currentTimeMillis();
            SurveillanceStatusMsg msg = new SurveillanceStatusMsg(
                    d.lastHeartbeatMs,
                    Math.max(0, (now - d.firstSeenMs) / 1000),
                    deviceIdToU16(d.id),
                    d.vendor.ordinal(),
                    d.status.ordinal(),   // 0=在线/1=离线/2=故障/3=维护（与协议值域一致）
                    d.status == SurveillanceDevice.Status.ONLINE ? d.totalCameras : 0,
                    d.totalCameras);
            eventPublisher.publishEvent(new MavlinkMessageEvent(
                    this, deviceIdToU16(d.id), SurveillanceStatusMsg.ID, msg, now,
                    d.tenantId));
        } catch (Exception e) {
            log.warn("surveillance status frame publish failed: device={}: {}", d.id, e.getMessage());
        }
    }

    /** 设备 ID → u16 稳定哈希。 */
    static int deviceIdToU16(String deviceId) {
        return deviceId == null ? 0 : deviceId.hashCode() & 0xFFFF;
    }
}
