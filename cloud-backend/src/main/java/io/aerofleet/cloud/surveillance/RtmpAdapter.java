package io.aerofleet.cloud.surveillance;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;

/**
 * RTMP 推流/拉流适配器（E5，Adobe RTMP 规范公开）。
 * <p>
 * 与 GB28181 口径对齐：
 * <ul>
 *   <li>getStreamUrl 返回标准 {@code rtmp://} 可播地址（无信令依赖）</li>
 *   <li>streamUrlOrigin() = {@link StreamUrlOrigin#VENDOR_STANDARD_FORMAT}（playable=true, deviceVerified=false）</li>
 *   <li>PTZ：RTMP 规范无 PTZ 定义，抛 {@code UnsupportedOperationException}，与 ONVIF/厂商私有 fallback 一致</li>
 *   <li>事件订阅：不支持，{@code supportsEventSubscription()=false}</li>
 * </ul>
 * <p>
 * 配置项（application.yml）：
 * <ul>
 *   <li>{@code aerofleet.rtmp.server-host} — RTMP 服务器地址（默认 127.0.0.1）</li>
 *   <li>{@code aerofleet.rtmp.server-port} — RTMP 服务器端口（默认 1935）</li>
 *   <li>{@code aerofleet.rtmp.app-name} — RTMP 应用名（默认 live）</li>
 * </ul>
 */
@Component
public class RtmpAdapter implements VendorAdapter {

    private static final Logger log = LoggerFactory.getLogger(RtmpAdapter.class);

    private final String serverHost;
    private final int serverPort;
    private final String appName;

    public RtmpAdapter(
            @Value("${aerofleet.rtmp.server-host:127.0.0.1}") String serverHost,
            @Value("${aerofleet.rtmp.server-port:1935}") int serverPort,
            @Value("${aerofleet.rtmp.app-name:live}") String appName) {
        this.serverHost = serverHost;
        this.serverPort = serverPort;
        this.appName = appName;
        log.info("RTMP 接入配置: server={}:{} app={}", serverHost, serverPort, appName);
    }

    @Override
    public Vendor getVendor() {
        return Vendor.RTMP;
    }

    /**
     * 设备发现：RTMP 无标准发现协议，返回空列表。
     * <p>
     * 如需发现，建议配合 ONVIF WS-Discovery 或厂商私有接口。
     */
    @Override
    public List<SurveillanceDevice> discover(String ignored) {
        log.debug("RTMP discover: no standard discovery protocol, returning empty list");
        return Collections.emptyList();
    }

    /**
     * 取流：构造标准 RTMP 播放地址 {@code rtmp://user:pass@host:port/app/stream}。
     * <p>
     * <b>可直接播放</b>（无信令/协商，deviceVerified=false）。
     * streamKey 语义由上层约定：通常为 {@code deviceId_channel} 或调用方自定义。
     * 凭据按 RFC 3986 编码（复用 {@link VendorRtspUrl#encodeUserInfo}），空格→%20 而非 +。
     */
    @Override
    public String getStreamUrl(SurveillanceDevice device, int channel) {
        validateDevice(device);
        String streamKey = device.id + "_" + channel;
        String url = buildRtmpUrl(device.username, device.password, streamKey);
        log.info("RTMP getStreamUrl device={} ch={} -> {}", device.id, channel, VendorRtspUrl.maskCredentials(url));
        return url;
    }

    /**
     * 推流：构造标准 RTMP 推流地址 {@code rtmp://user:pass@host:port/app/stream}。
     * <p>
     * 推流端通常需要鉴权（RTMP 规范无标准鉴权，依赖服务器侧实现如 token、IP 白名单、自定义握手）。
     * 此处仅返回推流地址；实际推流由客户端（FFmpeg/OBS/设备端 SDK）发起。
     */
    public String getPublishUrl(String username, String password, String streamKey) {
        if (streamKey == null || streamKey.isBlank()) {
            throw new IllegalArgumentException("streamKey must not be blank");
        }
        String url = buildRtmpUrl(username, password, streamKey);
        log.info("RTMP getPublishUrl streamKey={} -> {}", streamKey, VendorRtspUrl.maskCredentials(url));
        return url;
    }

    private String buildRtmpUrl(String username, String password, String streamKey) {
        StringBuilder sb = new StringBuilder("rtmp://");
        sb.append(VendorRtspUrl.encodeUserInfo(username)).append(':')
                .append(VendorRtspUrl.encodeUserInfo(password)).append('@');
        sb.append(serverHost);
        if (serverPort != 1935) {
            sb.append(':').append(serverPort);
        }
        sb.append('/').append(appName).append('/').append(streamKey);
        return sb.toString();
    }

    /**
     * RTMP 规范无 PTZ 定义。
     * <p>
     * 抛 {@code UnsupportedOperationException}，上层适配器链会落到 ONVIF/厂商私有 fallback。
     */
    @Override
    public String ptzControl(SurveillanceDevice device, String cmd) {
        validateDevice(device);
        throw new UnsupportedOperationException("RTMP specification does not define PTZ control; "
                + "use ONVIF or vendor-specific adapter for PTZ (fallback chain handles this)");
    }

    /**
     * RTMP 可直接播放（标准 rtmp://），但设备端未验证（无握手/鉴权标准）。
     * <p>
     * {@code playable=true, deviceVerified=false} —— 前端可尝试播放，失败时提示"需确认设备端推流状态"。
     */
    @Override
    public StreamUrlOrigin streamUrlOrigin() {
        return StreamUrlOrigin.VENDOR_STANDARD_FORMAT;
    }

    /** RTMP 无标准事件订阅。 */
    @Override
    public boolean supportsEventSubscription() {
        return false;
    }

    @Override
    public String subscribeEvents(SurveillanceDevice device, Consumer<String> callback) {
        throw new UnsupportedOperationException("RTMP specification does not define event subscription");
    }

    @Override
    public void unsubscribeEvents(String handle) {
        log.debug("RTMP unsubscribe {} (no-op)", handle);
    }

    /** RTMP 能力：仅流媒体。 */
    @Override
    public Set<String> getCapabilities(SurveillanceDevice device) {
        validateDevice(device);
        return Set.of("STREAM");
    }

    private static void validateDevice(SurveillanceDevice device) {
        if (device == null || device.id == null || device.id.isBlank()) {
            throw new IllegalArgumentException("device must have a non-blank id");
        }
        if (device.vendor != SurveillanceDevice.Vendor.RTMP) {
            throw new IllegalArgumentException(
                    "RtmpAdapter only accepts RTMP devices, got vendor=" + device.vendor);
        }
    }
}