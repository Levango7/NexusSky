package io.aerofleet.cloud.surveillance;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.function.Consumer;

/**
 * ONVIF 协议客户端（模拟实现，不依赖外部 ONVIF 库）。
 * <p>
 * ONVIF（Open Network Video Interface Forum）是海康威视、大华、宇视三大安防厂商
 * 共同支持的标准协议，覆盖：
 * <ul>
 *   <li>WS-Discovery：设备发现（UDP 多播 Probe）</li>
 *   <li>Device 服务：获取能力（GetCapabilities）</li>
 *   <li>Media 服务：获取 RTSP 流 URL（GetStreamUri）</li>
 *   <li>PTZ 服务：云台控制（ContinuousMove / Stop）</li>
 *   <li>Event 服务：事件订阅（WS-BaseNotification）</li>
 * </ul>
 * <p>
 * 本实现为<b>模拟实现</b>，不发起真实 SOAP/UDP 请求，仅返回符合 ONVIF 协议语义
 * 的预设结果，便于上层业务在无真实设备环境下联调与测试。生产环境可替换为
 * 基于 onvif-java 库或 apache-cxf 的真实实现。
 */
@Component
public class OnvifClient {

    private static final Logger log = LoggerFactory.getLogger(OnvifClient.class);

    /** 默认 ONVIF 端口。 */
    public static final int DEFAULT_PORT = 80;

    /** PTZ 支持的命令集合。 */
    public static final Set<String> PTZ_COMMANDS = Collections.unmodifiableSet(
            new LinkedHashSet<>(Arrays.asList(
                    "up", "down", "left", "right", "zoomIn", "zoomOut", "stop")));

    /** 已订阅事件的设备回调表（设备地址 -> callback），用于模拟事件订阅。 */
    private final ConcurrentMap<String, Consumer<String>> eventSubscribers = new ConcurrentHashMap<>();

    /**
     * WS-Discovery 设备发现。
     * <p>
     * 真实实现通过 UDP 多播发送 Probe 消息到 239.255.255.250:3702，
     * 收集设备 ProbeMatch 应答。模拟实现根据子网返回预设设备列表。
     *
     * @param subnet 子网 CIDR，如 "192.168.1.0/24"
     * @return 发现的设备列表（模拟实现返回预设设备）
     */
    public List<SurveillanceDevice> discoverDevices(String subnet) {
        if (subnet == null || subnet.isBlank()) {
            throw new IllegalArgumentException("subnet must not be blank");
        }
        log.info("WS-Discovery probe on subnet={} (simulated)", subnet);
        // 模拟实现：根据子网第三段生成 3 个预设设备（海康/大华/宇视各一）
        String prefix = extractPrefix(subnet);
        List<SurveillanceDevice> devices = new ArrayList<>();
        devices.add(newDevice("hik-" + prefix + "-100", "海康摄像头",
                SurveillanceDevice.Vendor.HIKVISION, prefix + ".100", "admin", "hik12345"));
        devices.add(newDevice("dahua-" + prefix + "-101", "大华摄像头",
                SurveillanceDevice.Vendor.DAHUA, prefix + ".101", "admin", "dahua123"));
        devices.add(newDevice("uniview-" + prefix + "-102", "宇视摄像头",
                SurveillanceDevice.Vendor.UNIVIEW, prefix + ".102", "admin", "uniview123"));
        log.info("Discovered {} devices on {}", devices.size(), subnet);
        return devices;
    }

    /**
     * 获取设备能力（GetCapabilities）。
     * <p>
     * 真实实现通过 SOAP 调用 Device 服务 GetCapabilities，
     * 返回 Media/PTZ/Event/Device 等服务端点。模拟实现返回标准能力集合。
     *
     * @param ip   设备 IP
     * @param port ONVIF 端口
     * @param user 用户名
     * @param pass 密码
     * @return 设备能力集合（如 "Media", "PTZ", "Events", "Device"）
     */
    public Set<String> getDeviceCapabilities(String ip, int port, String user, String pass) {
        validateConnectionArgs(ip, port, user, pass);
        log.info("GetCapabilities {}:{} (simulated)", ip, port);
        // 模拟实现：所有设备都支持 Device + Media + Events，PTZ 仅在端口非 80 时缺失
        Set<String> caps = new LinkedHashSet<>();
        caps.add("Device");
        caps.add("Media");
        caps.add("Events");
        if (port == DEFAULT_PORT) {
            caps.add("PTZ");
            caps.add("Imaging");
        }
        return Collections.unmodifiableSet(caps);
    }

    /**
     * 获取 RTSP 流 URL（GetStreamUri）。
     * <p>
     * 真实实现通过 Media 服务 GetStreamUri 获取 RTSP 地址，
     * 凭据需携带 digest 认证。模拟实现拼接标准 RTSP URL。
     *
     * @param ip      设备 IP
     * @param port    ONVIF 端口
     * @param user    用户名
     * @param pass    密码
     * @param channel 通道号（从 1 开始）
     * @return RTSP 流 URL
     */
    public String getRtspUrl(String ip, int port, String user, String pass, int channel) {
        validateConnectionArgs(ip, port, user, pass);
        if (channel < 1) {
            throw new IllegalArgumentException("channel must be >= 1");
        }
        log.info("GetStreamUri {}:{} channel={} (simulated)", ip, port, channel);
        // 模拟实现：rtsp://user:pass@ip:554/Streaming/Channels/channel
        // 凭据需 URL 编码，避免密码含 @ : / # ? 等特殊字符破坏 URL 结构
        String encodedUser = URLEncoder.encode(user, StandardCharsets.UTF_8);
        String encodedPass = URLEncoder.encode(pass, StandardCharsets.UTF_8);
        return String.format("rtsp://%s:%s@%s:554/Streaming/Channels/%d",
                encodedUser, encodedPass, ip, channel);
    }

    /**
     * PTZ 控制（ContinuousMove / Stop）。
     * <p>
     * 真实实现通过 PTZ 服务 ContinuousMove 发送速度向量，
     * 或 Stop 停止当前运动。模拟实现仅记录日志。
     *
     * @param ip   设备 IP
     * @param port ONVIF 端口
     * @param user 用户名
     * @param pass 密码
     * @param cmd  命令：up/down/left/right/zoomIn/zoomOut/stop
     * @return 命令执行结果（"ok" 或错误消息）
     */
    public String ptzControl(String ip, int port, String user, String pass, String cmd) {
        validateConnectionArgs(ip, port, user, pass);
        if (cmd == null || !PTZ_COMMANDS.contains(cmd)) {
            throw new IllegalArgumentException(
                    "unsupported ptz command: " + cmd + ", supported: " + PTZ_COMMANDS);
        }
        log.info("PTZ {} -> {}:{} (simulated)", cmd, ip, port);
        return "ok";
    }

    /**
     * 事件订阅（WS-BaseNotification Subscribe）。
     * <p>
     * 真实实现通过 Event 服务 Subscribe 注册消费端点，
     * 设备会通过 Notify 推送事件到该端点。模拟实现将 callback 注册到内存表，
     * 可通过 {@link #simulateEvent(String, String)} 触发回调。
     *
     * @param ip       设备 IP
     * @param port     ONVIF 端口
     * @param user     用户名
     * @param pass     密码
     * @param callback 事件回调（接收事件 XML/JSON 字符串）
     * @return 订阅句柄（设备地址，可用于取消订阅）
     */
    public String subscribeEvents(String ip, int port, String user, String pass,
                                  Consumer<String> callback) {
        validateConnectionArgs(ip, port, user, pass);
        if (callback == null) {
            throw new IllegalArgumentException("callback must not be null");
        }
        String addr = ip + ":" + port;
        eventSubscribers.put(addr, callback);
        log.info("Subscribed events for {} (simulated, {} subscribers total)",
                addr, eventSubscribers.size());
        return addr;
    }

    /**
     * 取消事件订阅。
     *
     * @param handle 订阅句柄（由 {@link #subscribeEvents} 返回）
     */
    public void unsubscribeEvents(String handle) {
        if (handle == null) return;
        eventSubscribers.remove(handle);
        log.info("Unsubscribed events for {}", handle);
    }

    /**
     * 模拟向指定设备推送一个事件（仅用于测试与联调）。
     *
     * @param handle      订阅句柄
     * @param eventPayload 事件载荷字符串
     * @return true 若存在订阅者并已触发回调
     */
    public boolean simulateEvent(String handle, String eventPayload) {
        Consumer<String> cb = eventSubscribers.get(handle);
        if (cb == null) {
            return false;
        }
        cb.accept(eventPayload);
        return true;
    }

    /** 当前活跃订阅数（用于监控与测试）。 */
    public int activeSubscriberCount() {
        return eventSubscribers.size();
    }

    // ------------------------------------------------------------------

    private static SurveillanceDevice newDevice(String id, String name,
                                                SurveillanceDevice.Vendor vendor,
                                                String ip, String user, String pass) {
        return new SurveillanceDevice(id, name, vendor, ip, DEFAULT_PORT, user, pass);
    }

    /** 从 CIDR 提取前三段前缀，如 "192.168.1.0/24" -> "192.168.1"。 */
    private static String extractPrefix(String subnet) {
        String addr = subnet.contains("/") ? subnet.substring(0, subnet.indexOf('/')) : subnet;
        int lastDot = addr.lastIndexOf('.');
        return lastDot > 0 ? addr.substring(0, lastDot) : addr;
    }

    private static void validateConnectionArgs(String ip, int port, String user, String pass) {
        if (ip == null || ip.isBlank()) {
            throw new IllegalArgumentException("ip must not be blank");
        }
        if (port <= 0 || port > 65535) {
            throw new IllegalArgumentException("port must be in [1, 65535]");
        }
        if (user == null || user.isBlank()) {
            throw new IllegalArgumentException("user must not be blank");
        }
        if (pass == null || pass.isEmpty()) {
            throw new IllegalArgumentException("pass must not be empty");
        }
    }
}