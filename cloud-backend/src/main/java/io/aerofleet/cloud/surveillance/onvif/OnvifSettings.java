package io.aerofleet.cloud.surveillance.onvif;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * ONVIF 接入配置（{@code aerofleet.onvif.*}）。
 *
 * <h2>为什么默认是 {@code simulated}</h2>
 *
 * <p>本仓的演示链路（drone-sim / 机巢 sim / 摄像机适配器）默认都不依赖外部硬件，
 * {@code mvn test} 与离线 demo 必须能在没有任何摄像头的机器上跑通。因此默认值保持
 * {@code simulated}，<b>行为与升级前逐字节一致</b>；要用真实设备时显式设
 * {@code aerofleet.onvif.mode=live}。
 *
 * <p>这与"假实现"不是一回事：{@code live} 路径是真的 SOAP/UDP，失败就报错，
 * <b>不会</b>退回猜出来的地址（见 {@link OnvifLiveBackend}）。
 *
 * <h2>live 模式需要的最小配置</h2>
 *
 * <pre>
 *   aerofleet.onvif.mode=live
 *   aerofleet.onvif.discovery-username=admin
 *   aerofleet.onvif.discovery-password=******
 * </pre>
 *
 * <p>发现到的设备之所以要带一套凭据：WS-Discovery 的 {@code ProbeMatch} 里
 * <b>没有任何凭据字段</b>（那是协议设计），而设备注册模型
 * （{@code SurveillanceDevice}）要求用户名非空。没有配置凭据时发现会直接报错，
 * 而不是拿一个占位账号把设备登记成"看起来能用"。
 */
@Component
@ConfigurationProperties(prefix = "aerofleet.onvif")
public class OnvifSettings {

    /** 接入模式。 */
    public enum Mode {
        /** 纯模拟（默认）：不发任何网络请求。 */
        SIMULATED,
        /** 真实 ONVIF：SOAP + WS-Discovery。 */
        LIVE
    }

    /** 接入模式，默认 {@link Mode#SIMULATED}。 */
    private Mode mode = Mode.SIMULATED;

    /** TCP 建连超时（ms）。 */
    private int connectTimeoutMs = 3000;

    /** 单次 SOAP 请求的读超时（ms）。 */
    private int readTimeoutMs = 5000;

    /** WS-Discovery 收集应答的窗口（ms）。 */
    private int discoveryTimeoutMs = 1500;

    /** 单个 SOAP 响应体上限（字节）。防止异常设备/中间人用超大包打爆内存。 */
    private int maxResponseBytes = 2 * 1024 * 1024;

    /** 设备服务路径；ONVIF 规范默认 {@code /onvif/device_service}。 */
    private String deviceServicePath = "/onvif/device_service";

    /** 发现到的设备登记用的用户名（ProbeMatch 不含凭据，必须显式配置）。 */
    private String discoveryUsername = "";

    /** 发现到的设备登记用的密码。 */
    private String discoveryPassword = "";

    /**
     * 额外的单播发现目标（{@code host} 或 {@code host:port}）。
     *
     * <p>为什么需要：WS-Discovery 靠 {@code 239.255.255.250:3702} 多播，而不少
     * 现场交换机会丢多播/IGMP snooping 配置不当时探针根本到不了设备。单播目标
     * 让运维直接把已知设备 IP 指过来，仍然走同一套 Probe/ProbeMatch 协议。
     */
    private List<String> discoveryUnicastTargets = new ArrayList<>();

    /**
     * 是否发送多播探针（默认 true）。
     *
     * <p>关掉它只留单播目标，用于两类环境：多播被策略禁止的机房，
     * 以及测试（避免向真实局域网发探针）。
     */
    private boolean discoveryMulticastEnabled = true;

    /** 是否 live 模式。 */
    public boolean isLive() {
        return mode == Mode.LIVE;
    }

    public Mode getMode() {
        return mode;
    }

    public void setMode(Mode mode) {
        this.mode = mode == null ? Mode.SIMULATED : mode;
    }

    public int getConnectTimeoutMs() {
        return connectTimeoutMs;
    }

    public void setConnectTimeoutMs(int connectTimeoutMs) {
        this.connectTimeoutMs = connectTimeoutMs;
    }

    public int getReadTimeoutMs() {
        return readTimeoutMs;
    }

    public void setReadTimeoutMs(int readTimeoutMs) {
        this.readTimeoutMs = readTimeoutMs;
    }

    public int getDiscoveryTimeoutMs() {
        return discoveryTimeoutMs;
    }

    public void setDiscoveryTimeoutMs(int discoveryTimeoutMs) {
        this.discoveryTimeoutMs = discoveryTimeoutMs;
    }

    public int getMaxResponseBytes() {
        return maxResponseBytes;
    }

    public void setMaxResponseBytes(int maxResponseBytes) {
        this.maxResponseBytes = maxResponseBytes;
    }

    public String getDeviceServicePath() {
        return deviceServicePath;
    }

    public void setDeviceServicePath(String deviceServicePath) {
        this.deviceServicePath = deviceServicePath;
    }

    public String getDiscoveryUsername() {
        return discoveryUsername;
    }

    public void setDiscoveryUsername(String discoveryUsername) {
        this.discoveryUsername = discoveryUsername;
    }

    public String getDiscoveryPassword() {
        return discoveryPassword;
    }

    public void setDiscoveryPassword(String discoveryPassword) {
        this.discoveryPassword = discoveryPassword;
    }

    public List<String> getDiscoveryUnicastTargets() {
        return discoveryUnicastTargets;
    }

    public void setDiscoveryUnicastTargets(List<String> discoveryUnicastTargets) {
        this.discoveryUnicastTargets = discoveryUnicastTargets == null
                ? new ArrayList<>()
                : discoveryUnicastTargets;
    }

    public boolean isDiscoveryMulticastEnabled() {
        return discoveryMulticastEnabled;
    }

    public void setDiscoveryMulticastEnabled(boolean discoveryMulticastEnabled) {
        this.discoveryMulticastEnabled = discoveryMulticastEnabled;
    }
}
