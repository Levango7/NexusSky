package io.aerofleet.cloud.surveillance;

import io.aerofleet.cloud.surveillance.onvif.OnvifBackend;
import io.aerofleet.cloud.surveillance.onvif.OnvifLiveBackend;
import io.aerofleet.cloud.surveillance.onvif.OnvifPtzCommands;
import io.aerofleet.cloud.surveillance.onvif.OnvifSettings;
import io.aerofleet.cloud.surveillance.onvif.OnvifUnsupportedException;
import io.aerofleet.cloud.surveillance.onvif.OnvifValidation;
import io.aerofleet.cloud.surveillance.onvif.SimulatedOnvifBackend;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.function.Consumer;

/**
 * ONVIF 协议客户端（对外门面）。
 *
 * <h2>两种后端</h2>
 *
 * <ul>
 *   <li><b>simulated</b>（默认，{@code aerofleet.onvif.mode=simulated}）：
 *       离线预设结果，demo 与测试不依赖任何摄像头；流地址是<b>按规范推导</b>的，
 *       对应 {@link StreamUrlOrigin#VENDOR_STANDARD_FORMAT}。</li>
 *   <li><b>live</b>（{@code aerofleet.onvif.mode=live}）：真实 SOAP
 *       （Device/Media/PTZ）+ WS-Discovery；流地址由设备 {@code GetStreamUri}
 *       返回，对应 {@link StreamUrlOrigin#ONVIF_GETSTREAM_URI}，失败即报错。</li>
 * </ul>
 *
 * <h2>事件订阅</h2>
 *
 * <p>事件面的真实实现（WS-BaseNotification / PullPoint）<b>尚未接入</b>。
 * 模拟模式下沿用内存回调表（{@link #simulateEvent} 可触发）；live 模式下
 * {@link #subscribeEvents} 抛 {@link OnvifUnsupportedException}，
 * 由 REST 层映射 501 —— 不返回一个"订阅成功但永远不会来事件"的句柄。
 *
 * <h2>线程安全</h2>
 *
 * <p>后端实现无状态（SOAP 客户端持有线程安全的 {@code HttpClient}）；
 * 事件回调表使用 {@link ConcurrentHashMap}。
 */
@Component
public class OnvifClient {

    private static final Logger log = LoggerFactory.getLogger(OnvifClient.class);

    /** 默认 ONVIF 端口。 */
    public static final int DEFAULT_PORT = 80;

    /** PTZ 支持的命令集合（单一事实源见 {@link OnvifPtzCommands}）。 */
    public static final Set<String> PTZ_COMMANDS = OnvifPtzCommands.ALL;

    private final OnvifBackend backend;

    /** 已订阅事件的设备回调表（设备地址 -> callback），仅模拟模式使用。 */
    private final ConcurrentMap<String, Consumer<String>> eventSubscribers = new ConcurrentHashMap<>();

    /**
     * 模拟客户端（测试与离线场景）。
     *
     * <p>Spring 容器不会用这个构造器：它注入了 {@link OnvifSettings} 的那个
     * 构造器带 {@code @Autowired}。保留它是为了让 {@code new OnvifClient()}
     * 继续等价于"默认配置的模拟后端"（既有单测即按此构造）。
     */
    public OnvifClient() {
        this(new OnvifSettings());
    }

    @Autowired
    public OnvifClient(OnvifSettings settings) {
        this.backend = settings.isLive()
                ? new OnvifLiveBackend(settings)
                : new SimulatedOnvifBackend();
        log.info("ONVIF client mode={}", settings.getMode());
    }

    /** 当前是否为真实协议后端。 */
    public boolean isLive() {
        return backend.isLive();
    }

    /**
     * WS-Discovery 设备发现。
     *
     * <p>simulated：按子网返回预设设备；live：向 {@code 239.255.255.250:3702}
     * 与配置的单播目标发 Probe，解析 ProbeMatch（详见
     * {@code OnvifDiscoveryClient}）。
     *
     * @param subnet 子网 CIDR，如 "192.168.1.0/24"
     * @return 发现的设备列表
     */
    public List<SurveillanceDevice> discoverDevices(String subnet) {
        return backend.discoverDevices(subnet);
    }

    /**
     * 获取设备能力（GetCapabilities）。
     *
     * @param ip   设备 IP
     * @param port ONVIF 端口
     * @param user 用户名
     * @param pass 密码
     * @return 设备能力集合（如 "Media", "PTZ", "Events", "Device"）
     */
    public Set<String> getDeviceCapabilities(String ip, int port, String user, String pass) {
        return backend.getDeviceCapabilities(ip, port, user, pass);
    }

    /**
     * 获取 RTSP 流 URL（GetProfiles + GetStreamUri）。
     *
     * <p>live 模式下地址由设备返回并注入凭据（凭据编码见
     * {@link VendorRtspUrl}）；失败抛异常，<b>不</b>退回猜测的地址。
     *
     * @param ip      设备 IP
     * @param port    ONVIF 端口
     * @param user    用户名
     * @param pass    密码
     * @param channel 通道号（从 1 开始，按 GetProfiles 顺序映射）
     * @return RTSP 流 URL
     */
    public String getRtspUrl(String ip, int port, String user, String pass, int channel) {
        return backend.getRtspUrl(ip, port, user, pass, channel);
    }

    /**
     * PTZ 控制（ContinuousMove / Stop）。
     *
     * @param ip   设备 IP
     * @param port ONVIF 端口
     * @param user 用户名
     * @param pass 密码
     * @param cmd  命令：up/down/left/right/zoomIn/zoomOut/stop
     * @return 命令执行结果（"ok" 或错误消息）
     */
    public String ptzControl(String ip, int port, String user, String pass, String cmd) {
        return backend.ptzControl(ip, port, user, pass, cmd);
    }

    /**
     * 事件订阅（WS-BaseNotification Subscribe）。
     *
     * <p>simulated 下将 callback 注册到内存表（可用 {@link #simulateEvent} 触发）；
     * live 下尚未实现，抛 {@link OnvifUnsupportedException}。
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
        OnvifValidation.requireConnectionArgs(ip, port, user, pass);
        if (callback == null) {
            throw new IllegalArgumentException("callback must not be null");
        }
        if (backend.isLive()) {
            throw new OnvifUnsupportedException(
                    "ONVIF event subscription is not implemented in live mode "
                            + "(WS-BaseNotification/PullPoint not wired); refusing to fake a subscription");
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
        if (handle == null) {
            return;
        }
        if (backend.isLive()) {
            // live 模式从不产生句柄；这里保持幂等，避免 SSE 关闭路径二次报错
            log.debug("Ignoring unsubscribe in live mode (no live subscription was created): {}", handle);
            return;
        }
        eventSubscribers.remove(handle);
        log.info("Unsubscribed events for {}", handle);
    }

    /**
     * 模拟向指定设备推送一个事件（仅用于测试与联调；live 模式下无订阅表）。
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
}
