package io.aerofleet.cloud.surveillance.onvif;

import io.aerofleet.cloud.surveillance.SurveillanceDevice;
import io.aerofleet.cloud.surveillance.VendorRtspUrl;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.w3c.dom.Document;
import org.w3c.dom.Element;

import java.net.URI;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;

/**
 * 真实 ONVIF 后端（SOAP 到设备，无第三方库）。
 *
 * <h2>实现了什么</h2>
 *
 * <ul>
 *   <li>Device {@code GetCapabilities}</li>
 *   <li>Media {@code GetProfiles} + {@code GetStreamUri}（取回设备自己的 RTSP 地址）</li>
 *   <li>PTZ {@code ContinuousMove} / {@code Stop}</li>
 *   <li>WS-Discovery（见 {@link OnvifDiscoveryClient}）</li>
 * </ul>
 *
 * <h2>失败就失败，不猜</h2>
 *
 * <p>设备没有 Media 服务、没有对应通道、SOAP 报错、连不上——都抛
 * {@link OnvifException}。**不会**退回"按厂商规范拼一个 rtsp:// 地址"：
 * 那样调用方拿到的地址看起来可用，实际不知道是谁拼的。模拟模式才走
 * {@link SimulatedOnvifBackend}，两者的输出由
 * {@code StreamUrlOrigin} 在适配器层如实区分。
 *
 * <h2>通道号 → Profile</h2>
 *
 * <p>ONVIF 没有"通道"这个概念，只有 Media Profile。这里按 {@code GetProfiles}
 * 返回顺序做 1-based 映射（第 N 个 profile = 第 N 路）；设备报的 profile 数少于
 * 请求通道时抛错并说明实际有几路，而不是悄悄回第一路。
 *
 * <h2>凭据</h2>
 *
 * <p>设备 {@code GetStreamUri} 返回的 URI <b>不带</b>用户名密码。这里按 RTSP
 * 客户端惯例把凭据注入 userinfo，编码复用 {@link VendorRtspUrl#format}
 * （RFC 3986，不是表单编码）。日志与异常里不打印带凭据的地址。
 */
public class OnvifLiveBackend implements OnvifBackend, OnvifEventSource {

    private static final Logger log = LoggerFactory.getLogger(OnvifLiveBackend.class);

    private static final int DEFAULT_RTSP_PORT = 554;

    private final OnvifSettings settings;
    private final OnvifSoapClient soap;
    private final OnvifDiscoveryClient discovery;
    private final OnvifPullPointClient pullPoint;
    /** 活跃事件订阅：句柄 → 轮询器。 */
    private final Map<String, Poller> pollers = new ConcurrentHashMap<>();

    public OnvifLiveBackend(OnvifSettings settings) {
        this(settings, new OnvifSoapClient(settings), new OnvifDiscoveryClient(settings));
    }

    OnvifLiveBackend(OnvifSettings settings, OnvifSoapClient soap, OnvifDiscoveryClient discovery) {
        this(settings, soap, discovery, new OnvifPullPointClient(soap));
    }

    OnvifLiveBackend(OnvifSettings settings, OnvifSoapClient soap,
                     OnvifDiscoveryClient discovery, OnvifPullPointClient pullPoint) {
        this.settings = settings;
        this.soap = soap;
        this.discovery = discovery;
        this.pullPoint = pullPoint;
    }

    @Override
    public boolean isLive() {
        return true;
    }

    @Override
    public List<SurveillanceDevice> discoverDevices(String subnet) {
        return discovery.discover(subnet);
    }

    @Override
    public Set<String> getDeviceCapabilities(String ip, int port, String user, String pass) {
        OnvifValidation.requireConnectionArgs(ip, port, user, pass);
        Endpoints endpoints = endpoints(ip, port, user, pass);

        // 能力按设备在 GetCapabilities 里声明的服务如实上报：
        // Device 一定存在（否则这次调用不会成功），其余看 XAddr 有没有。
        Set<String> caps = new LinkedHashSet<>();
        caps.add("Device");
        if (endpoints.media() != null) {
            caps.add("Media");
        }
        if (endpoints.ptz() != null) {
            caps.add("PTZ");
        }
        if (endpoints.events() != null) {
            caps.add("Events");
        }
        if (endpoints.imaging() != null) {
            caps.add("Imaging");
        }
        log.info("GetCapabilities {}:{} -> {}", ip, port, caps);
        return java.util.Collections.unmodifiableSet(caps);
    }

    @Override
    public String getRtspUrl(String ip, int port, String user, String pass, int channel) {
        OnvifValidation.requireConnectionArgs(ip, port, user, pass);
        OnvifValidation.requireChannel(channel);

        Endpoints endpoints = endpoints(ip, port, user, pass);
        if (endpoints.media() == null) {
            throw new OnvifException("device " + ip + ":" + port
                    + " does not expose a Media service (GetCapabilities has no Media/XAddr)");
        }
        List<Profile> profiles = profiles(endpoints.media(), user, pass);
        if (profiles.isEmpty()) {
            throw new OnvifException("device " + ip + ":" + port
                    + " returned no media profiles (GetProfiles is empty)");
        }
        if (channel > profiles.size()) {
            throw new IllegalArgumentException("channel " + channel + " is not available: device "
                    + ip + ":" + port + " reports " + profiles.size() + " media profile(s)");
        }
        Profile profile = profiles.get(channel - 1);

        String body = "<trt:GetStreamUri>"
                + "<trt:StreamSetup>"
                + "<tt:Stream>RTP-Unicast</tt:Stream>"
                + "<tt:Transport><tt:Protocol>RTSP</tt:Protocol></tt:Transport>"
                + "</trt:StreamSetup>"
                + "<trt:ProfileToken>" + OnvifXml.escape(profile.token) + "</trt:ProfileToken>"
                + "</trt:GetStreamUri>";
        Document response = soap.call(endpoints.media(), "GetStreamUri", body, user, pass);

        Element uriElement = OnvifXml.firstDescendant(response.getDocumentElement(), "Uri");
        String uri = OnvifXml.text(uriElement);
        if (uri == null) {
            throw new OnvifException("GetStreamUri for profile '" + profile.token
                    + "' returned no Uri element");
        }
        if (!uri.toLowerCase(Locale.ROOT).startsWith("rtsp://")) {
            // 非 RTSP 的 MediaUri（例如设备把快照地址塞进来）不能当可播 RTSP 流返回
            throw new OnvifException("GetStreamUri returned a non-RTSP URI for profile '"
                    + profile.token + "': " + VendorRtspUrl.maskCredentials(uri));
        }
        String withCredentials = injectCredentials(uri, user, pass);
        log.info("GetStreamUri {}:{} channel={} profile='{}' -> {} (device-verified)",
                ip, port, channel, profile.token, VendorRtspUrl.maskCredentials(withCredentials));
        return withCredentials;
    }

    @Override
    public String ptzControl(String ip, int port, String user, String pass, String cmd) {
        OnvifValidation.requireConnectionArgs(ip, port, user, pass);
        OnvifValidation.requirePtzCommand(cmd);

        Endpoints endpoints = endpoints(ip, port, user, pass);
        if (endpoints.ptz() == null) {
            throw new OnvifException("device " + ip + ":" + port
                    + " does not expose a PTZ service (GetCapabilities has no PTZ/XAddr)");
        }
        List<Profile> profiles = profiles(endpoints.media(), user, pass);
        if (profiles.isEmpty()) {
            throw new OnvifException("device " + ip + ":" + port
                    + " returned no media profiles; PTZ needs a profile token");
        }
        String token = profiles.get(0).token;

        String body;
        if ("stop".equals(cmd)) {
            body = "<tptz:Stop>"
                    + "<tptz:ProfileToken>" + OnvifXml.escape(token) + "</tptz:ProfileToken>"
                    + "<tptz:PanTilt>true</tptz:PanTilt>"
                    + "<tptz:Zoom>true</tptz:Zoom>"
                    + "</tptz:Stop>";
        } else {
            double pan = 0;
            double tilt = 0;
            double zoom = 0;
            switch (cmd) {
                case "up" -> tilt = 0.5;
                case "down" -> tilt = -0.5;
                case "left" -> pan = -0.5;
                case "right" -> pan = 0.5;
                case "zoomIn" -> zoom = 0.5;
                case "zoomOut" -> zoom = -0.5;
                default -> throw new IllegalArgumentException("unsupported ptz command: " + cmd);
            }
            body = "<tptz:ContinuousMove>"
                    + "<tptz:ProfileToken>" + OnvifXml.escape(token) + "</tptz:ProfileToken>"
                    + "<tptz:Velocity>"
                    + "<tt:PanTilt x=\"" + pan + "\" y=\"" + tilt + "\"/>"
                    + "<tt:Zoom x=\"" + zoom + "\"/>"
                    + "</tptz:Velocity>"
                    + "</tptz:ContinuousMove>";
        }
        soap.call(endpoints.ptz(), "PTZ " + cmd, body, user, pass);
        log.info("PTZ {} -> {}:{} profile='{}' (device-verified)", cmd, ip, port, token);
        return "ok";
    }

    // ------------------------------------------------------------------
    // 事件订阅（WS-BaseNotification PullPoint）

    @Override
    public String subscribeEvents(String ip, int port, String user, String pass,
                                  Consumer<String> callback) {
        OnvifValidation.requireConnectionArgs(ip, port, user, pass);
        if (callback == null) {
            throw new IllegalArgumentException("callback must not be null");
        }
        Endpoints endpoints = endpoints(ip, port, user, pass);
        if (endpoints.events() == null) {
            // 设备没声明 Events 服务：订阅是不可能成功的，这里直接说清楚，
            // 而不是返回一个永远不会有事件的句柄
            throw new OnvifException("device " + ip + ":" + port
                    + " does not expose an Events service (GetCapabilities has no Events/XAddr)");
        }
        OnvifPullPointClient.Subscription subscription =
                pullPoint.subscribe(endpoints.events(), user, pass);
        String handle = "onvif-" + ip + "-" + port + "-" + UUID.randomUUID();
        Poller poller = new Poller(handle, subscription.address(), user, pass, callback);
        pollers.put(handle, poller);
        poller.start();
        log.info("Subscribed events {}:{} handle={} termination={}",
                ip, port, handle, subscription.terminationTime());
        return handle;
    }

    @Override
    public void unsubscribeEvents(String handle) {
        if (handle == null) {
            return;
        }
        Poller poller = pollers.remove(handle);
        if (poller == null) {
            return;
        }
        poller.stop();
        log.info("Unsubscribed events {}", handle);
    }

    @Override
    public int activeSubscriptions() {
        return pollers.size();
    }

    // ------------------------------------------------------------------

    private Endpoints endpoints(String ip, int port, String user, String pass) {
        String deviceUrl = deviceServiceUrl(ip, port);
        Document document = soap.call(deviceUrl, "GetCapabilities",
                "<tds:GetCapabilities/>", user, pass);
        Element capabilities = OnvifXml.firstDescendant(
                document.getDocumentElement(), "Capabilities");
        if (capabilities == null) {
            throw new OnvifException("GetCapabilities response has no Capabilities element");
        }
        // Device 的 XAddr 不需要记录：本次 GetCapabilities 就是发到它的，
        // 后续调用只消费 Media/PTZ（Events/Imaging 暂只用于能力上报）。
        return new Endpoints(
                xaddr(capabilities, "Media"),
                xaddr(capabilities, "PTZ"),
                xaddr(capabilities, "Events"),
                xaddr(capabilities, "Imaging"));
    }

    private List<Profile> profiles(String mediaUrl, String user, String pass) {
        if (mediaUrl == null) {
            return List.of();
        }
        Document document = soap.call(mediaUrl, "GetProfiles", "<trt:GetProfiles/>", user, pass);
        List<Profile> profiles = new ArrayList<>();
        for (Element element : OnvifXml.descendants(document.getDocumentElement(), "Profiles")) {
            String token = element.getAttribute("token");
            if (token == null || token.isBlank()) {
                continue;
            }
            String name = OnvifXml.childText(element, "Name");
            profiles.add(new Profile(token, name));
        }
        return profiles;
    }

    private static String xaddr(Element capabilities, String serviceName) {
        Element service = OnvifXml.firstChild(capabilities, serviceName);
        if (service == null) {
            return null;
        }
        String value = OnvifXml.childText(service, "XAddr");
        return value == null || value.isBlank() ? null : value;
    }

    private String deviceServiceUrl(String ip, int port) {
        String host = ip;
        if (host.contains(":") && !host.startsWith("[")) {
            host = "[" + host + "]";
        }
        String path = settings.getDeviceServicePath();
        if (path == null || path.isBlank()) {
            path = "/onvif/device_service";
        }
        if (!path.startsWith("/")) {
            path = "/" + path;
        }
        return "http://" + host + ":" + port + path;
    }

    /**
     * 把凭据注入设备返回的 RTSP URI。
     *
     * <p>设备给的地址通常形如 {@code rtsp://192.168.1.64:554/Streaming/Channels/101}。
     * 用 {@link VendorRtspUrl#format} 重新拼 authority，复用仓内唯一的
     * RFC 3986 凭据编码实现；路径/查询串保持设备原样（不做二次编码）。
     */
    static String injectCredentials(String rtspUri, String user, String pass) {
        URI uri;
        try {
            uri = URI.create(rtspUri);
        } catch (IllegalArgumentException e) {
            throw new OnvifException("GetStreamUri returned an invalid URI: "
                    + VendorRtspUrl.maskCredentials(rtspUri), e);
        }
        String host = uri.getHost();
        if (host == null || host.isBlank()) {
            throw new OnvifException("GetStreamUri URI has no host: "
                    + VendorRtspUrl.maskCredentials(rtspUri));
        }
        int port = uri.getPort() > 0 ? uri.getPort() : DEFAULT_RTSP_PORT;
        String path = uri.getRawPath() == null ? "" : uri.getRawPath();
        String query = uri.getRawQuery() == null ? "" : "?" + uri.getRawQuery();
        return VendorRtspUrl.format(user, pass, host, port, path + query);
    }

    private record Endpoints(String media, String ptz, String events, String imaging) {
    }

    private record Profile(String token, String name) {
    }

    /**
     * 单个订阅的轮询线程（决策逻辑在 {@link OnvifEventPoller}，可同步测试）。
     *
     * <p>停止语义：{@link #stop()} 只置标志 + 打断阻塞，线程在下一轮退出；
     * 不在设备侧做显式 Unsubscribe（订阅会随 TerminationTime 过期）。
     */
    private final class Poller implements Runnable {

        private final String handle;
        private final OnvifEventPoller poller;
        private volatile boolean stopped;
        private Thread thread;

        private Poller(String handle, String address, String user, String pass,
                       Consumer<String> callback) {
            this.handle = handle;
            this.poller = new OnvifEventPoller(handle,
                    () -> pullPoint.pull(address, user, pass), callback);
        }

        private void start() {
            thread = new Thread(this, "onvif-events-" + handle);
            thread.setDaemon(true);
            thread.start();
        }

        private void stop() {
            stopped = true;
            if (thread != null) {
                thread.interrupt();
            }
        }

        @Override
        public void run() {
            try {
                while (!stopped) {
                    if (!poller.pollOnce()) {
                        return;
                    }
                }
            } finally {
                pollers.remove(handle);
            }
        }
    }
}
