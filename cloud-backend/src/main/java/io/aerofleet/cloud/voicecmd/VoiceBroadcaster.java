package io.aerofleet.cloud.voicecmd;

import io.aerofleet.cloud.gateway.DeviceRegistry;
import io.aerofleet.cloud.gateway.DroneSnapshot;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.UUID;

/**
 * 语音播报服务。
 * <p>
 * 生成并发送语音播报文本，包括：
 * <ul>
 *   <li>自定义文本播报：将任意文本播报给指定无人机操作员</li>
 *   <li>状态播报：根据无人机当前遥测数据自动生成状态描述</li>
 *   <li>告警播报：根据告警类型生成告警语音文本</li>
 * </ul>
 * <p>
 * 状态播报模板："无人机{sysid}当前{mode}模式，电量{battery}%，高度{alt}米，距离{distance}米"
 * <br>
 * 告警播报模板："警告：无人机{sysid}电量低，仅剩{battery}%，请立即返航"
 */
@Service
public class VoiceBroadcaster {

    private static final Logger log = LoggerFactory.getLogger(VoiceBroadcaster.class);

    /** 低电量告警阈值（%） */
    private static final int LOW_BATTERY_THRESHOLD = 20;

    private final DeviceRegistry registry;

    public VoiceBroadcaster(DeviceRegistry registry) {
        this.registry = registry;
    }

    /**
     * 发送自定义语音播报。
     *
     * @param text  播报文本
     * @param sysid 目标无人机 systemId
     * @return 播报结果
     */
    public BroadcastResult broadcast(String text, int sysid) {
        if (text == null || text.isBlank()) {
            return new BroadcastResult(
                    generateId(),
                    sysid,
                    "",
                    System.currentTimeMillis(),
                    BroadcastResult.Status.FAILED);
        }

        DroneSnapshot drone = registry.get(sysid);
        if (drone == null) {
            log.warn("Broadcast failed: sysid={} not registered", sysid);
            return new BroadcastResult(
                    generateId(),
                    sysid,
                    text,
                    System.currentTimeMillis(),
                    BroadcastResult.Status.FAILED);
        }

        BroadcastResult result = new BroadcastResult(
                generateId(),
                sysid,
                text,
                System.currentTimeMillis(),
                BroadcastResult.Status.SENT);
        log.info("Broadcast sent: sysid={} text='{}'", sysid, text);
        return result;
    }

    /**
     * 生成无人机状态播报文本。
     * <p>
     * 模板："无人机{sysid}当前{mode}模式，电量{battery}%，高度{alt}米，距离{distance}米"
     *
     * @param sysid 无人机 systemId
     * @return 状态播报文本，无人机不存在时返回告警文本
     */
    public String statusBroadcast(int sysid) {
        DroneSnapshot drone = registry.get(sysid);
        if (drone == null) {
            return "无人机" + sysid + "未连接";
        }

        String mode = drone.mode != null ? drone.mode : "UNKNOWN";
        int battery = drone.battery >= 0 ? drone.battery : 0;
        String altStr = Double.isNaN(drone.relativeAlt) ? "未知" :
                String.format("%.0f", drone.relativeAlt);
        String distanceStr = "未知"; // 距离需要计算，暂用占位

        return "无人机" + sysid + "当前" + mode + "模式，"
                + "电量" + battery + "%，"
                + "高度" + altStr + "米，"
                + "距离" + distanceStr + "米";
    }

    /**
     * 生成告警播报文本。
     * <p>
     * 支持的告警类型：
     * <ul>
     *   <li>"low_battery" — 低电量告警："警告：无人机{sysid}电量低，仅剩{battery}%，请立即返航"</li>
     *   <li>"offline" — 离线告警："警告：无人机{sysid}已失联，请检查通信链路"</li>
     *   <li>"gps_lost" — GPS丢失告警："警告：无人机{sysid}GPS信号丢失，请谨慎操作"</li>
     * </ul>
     *
     * @param alertType 告警类型
     * @param sysid     无人机 systemId
     * @return 告警播报文本
     */
    public String alertBroadcast(String alertType, int sysid) {
        DroneSnapshot drone = registry.get(sysid);

        switch (alertType) {
            case "low_battery":
                int battery = (drone != null && drone.battery >= 0) ? drone.battery : 0;
                return "警告：无人机" + sysid + "电量低，仅剩" + battery + "%，请立即返航";
            case "offline":
                return "警告：无人机" + sysid + "已失联，请检查通信链路";
            case "gps_lost":
                return "警告：无人机" + sysid + "GPS信号丢失，请谨慎操作";
            default:
                return "警告：无人机" + sysid + "发生" + alertType + "告警";
        }
    }

    /**
     * 检查无人机是否需要低电量告警。
     *
     * @param sysid 无人机 systemId
     * @return true 表示电量低于阈值需要告警
     */
    public boolean needsLowBatteryAlert(int sysid) {
        DroneSnapshot drone = registry.get(sysid);
        return drone != null && drone.battery >= 0 && drone.battery <= LOW_BATTERY_THRESHOLD;
    }

    private String generateId() {
        return "bc-" + UUID.randomUUID().toString().substring(0, 8);
    }
}