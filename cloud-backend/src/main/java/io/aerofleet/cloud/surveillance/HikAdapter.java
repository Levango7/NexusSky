package io.aerofleet.cloud.surveillance;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.function.Consumer;

/**
 * 海康威视厂商适配器（模拟实现）。
 * <p>
 * 海康威视设备支持两种协议路径：
 * <ul>
 *   <li>ISAPI（IP Surveillance API）：基于 HTTP REST 的设备管理接口，用于能力查询/事件订阅</li>
 *   <li>HCNetSDK：C 语言原生 SDK，用于设备发现/PTZ 控制/流媒体推送</li>
 * </ul>
 * <p>
 * 本实现为<b>模拟实现</b>，不发起真实 ISAPI/SDK 调用，仅返回符合海康协议语义的预设结果。
 * 生产环境接入真实 SDK 时，需替换以下 TODO 标注的方法实现：
 * <ul>
 *   <li>TODO: 接入 HCNetSDK NET_DVR_FindDevice_V40 实现真实设备发现</li>
 *   <li>TODO: 接入 HCNetSDK NET_DVR_RealPlay_V40 实现真实流媒体获取</li>
 *   <li>TODO: 接入 HCNetSDK NET_DVR_PTZControl_Other 实现真实 PTZ 控制</li>
 *   <li>TODO: 接入 ISAPI /ISAPI/Event/notification/alertStream 实现真实事件订阅</li>
 * </ul>
 */
@Component
public final class HikAdapter implements VendorAdapter {

    private static final Logger log = LoggerFactory.getLogger(HikAdapter.class);

    /** 海康 ISAPI 默认端口。 */
    private static final int ISAPI_PORT = 80;

    /** 海康 RTSP 默认端口。 */
    private static final int RTSP_PORT = 554;

    /** PTZ 支持的命令集合。 */
    private static final Set<String> PTZ_COMMANDS = Set.of(
            "up", "down", "left", "right", "zoomIn", "zoomOut", "stop");

    /** 已订阅事件的设备回调表（设备地址 -> callback）。 */
    private final ConcurrentMap<String, Consumer<String>> eventSubscribers = new ConcurrentHashMap<>();

    @Override
    public Vendor getVendor() {
        return Vendor.HIKVISION;
    }

    @Override
    public List<SurveillanceDevice> discover(String subnet) {
        if (subnet == null || subnet.isBlank()) {
            throw new IllegalArgumentException("subnet must not be blank");
        }
        log.info("海康设备发现 subnet={} (模拟实现)", subnet);
        // TODO: 接入 HCNetSDK NET_DVR_FindDevice_V40 实现真实设备发现
        String prefix = extractPrefix(subnet);
        List<SurveillanceDevice> devices = new ArrayList<>();
        devices.add(new SurveillanceDevice(
                "hik-" + prefix + "-100", "海康摄像头-前端",
                SurveillanceDevice.Vendor.HIKVISION, prefix + ".100", ISAPI_PORT,
                "admin", "hik12345"));
        devices.add(new SurveillanceDevice(
                "hik-" + prefix + "-101", "海康摄像头-后端",
                SurveillanceDevice.Vendor.HIKVISION, prefix + ".101", ISAPI_PORT,
                "admin", "hik12345"));
        log.info("海康设备发现完成: 发现 {} 台设备", devices.size());
        return Collections.unmodifiableList(devices);
    }

    @Override
    public String getStreamUrl(SurveillanceDevice device, int channel) {
        validateDevice(device);
        if (channel < 1) {
            throw new IllegalArgumentException("channel must be >= 1");
        }
        log.info("海康获取流 URL device={} channel={} (模拟实现)", device.id, channel);
        // TODO: 接入 HCNetSDK NET_DVR_RealPlay_V40 实现真实流媒体获取
        // 海康 RTSP URL 格式: rtsp://user:pass@ip:554/Streaming/Channels/channel
        String encodedUser = URLEncoder.encode(device.username, StandardCharsets.UTF_8);
        String encodedPass = URLEncoder.encode(device.password, StandardCharsets.UTF_8);
        return String.format("rtsp://%s:%s@%s:%d/Streaming/Channels/%d",
                encodedUser, encodedPass, device.ip, RTSP_PORT, channel);
    }

    @Override
    public String ptzControl(SurveillanceDevice device, String cmd) {
        validateDevice(device);
        if (cmd == null || !PTZ_COMMANDS.contains(cmd)) {
            throw new IllegalArgumentException(
                    "unsupported ptz command: " + cmd + ", supported: " + PTZ_COMMANDS);
        }
        log.info("海康 PTZ 控制 device={} cmd={} (模拟实现)", device.id, cmd);
        // TODO: 接入 HCNetSDK NET_DVR_PTZControl_Other 实现真实 PTZ 控制
        return "ok";
    }

    @Override
    public String subscribeEvents(SurveillanceDevice device, Consumer<String> callback) {
        validateDevice(device);
        if (callback == null) {
            throw new IllegalArgumentException("callback must not be null");
        }
        String handle = "hik-" + device.id;
        eventSubscribers.put(handle, callback);
        log.info("海康事件订阅 device={} handle={} (模拟实现, 共 {} 个订阅)",
                device.id, handle, eventSubscribers.size());
        // TODO: 接入 ISAPI /ISAPI/Event/notification/alertStream 实现真实事件订阅
        return handle;
    }

    @Override
    public void unsubscribeEvents(String handle) {
        if (handle == null) return;
        eventSubscribers.remove(handle);
        log.info("海康取消事件订阅 handle={}", handle);
    }

    @Override
    public Set<String> getCapabilities(SurveillanceDevice device) {
        validateDevice(device);
        log.info("海康能力查询 device={} (模拟实现)", device.id);
        // TODO: 接入 ISAPI /ISAPI/System/deviceInfo 实现真实能力查询
        Set<String> caps = new LinkedHashSet<>();
        caps.add("Device");
        caps.add("Media");
        caps.add("Events");
        caps.add("PTZ");
        caps.add("Imaging");
        return Collections.unmodifiableSet(caps);
    }

    // ===== 内部工具方法 =====

    private static void validateDevice(SurveillanceDevice device) {
        if (device == null) {
            throw new IllegalArgumentException("device must not be null");
        }
        if (device.ip == null || device.ip.isBlank()) {
            throw new IllegalArgumentException("device ip must not be blank");
        }
    }

    /** 从 CIDR 提取前三段前缀，如 "192.168.1.0/24" -> "192.168.1"。 */
    private static String extractPrefix(String subnet) {
        String addr = subnet.contains("/") ? subnet.substring(0, subnet.indexOf('/')) : subnet;
        int lastDot = addr.lastIndexOf('.');
        return lastDot > 0 ? addr.substring(0, lastDot) : addr;
    }
}