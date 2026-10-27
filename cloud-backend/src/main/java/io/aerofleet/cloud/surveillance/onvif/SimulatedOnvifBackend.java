package io.aerofleet.cloud.surveillance.onvif;

import io.aerofleet.cloud.surveillance.OnvifClient;
import io.aerofleet.cloud.surveillance.SurveillanceDevice;
import io.aerofleet.cloud.surveillance.VendorRtspUrl;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * 模拟后端：升级前的全部行为，逐字保留。
 *
 * <p>默认模式（{@code aerofleet.onvif.mode=simulated}）走这里，保证离线 demo 与
 * {@code OnvifClientTest} 的行为不发生任何变化。它返回的 RTSP 地址是
 * <b>按规范推导</b>的，对应的来源标注是
 * {@code StreamUrlOrigin.VENDOR_STANDARD_FORMAT}（{@code deviceVerified=false}）——
 * 这一点由 {@code OnvifVendorAdapter} 如实上报，模拟实现本身不冒充已验证。
 */
public final class SimulatedOnvifBackend implements OnvifBackend {

    private static final Logger log = LoggerFactory.getLogger(SimulatedOnvifBackend.class);

    @Override
    public boolean isLive() {
        return false;
    }

    @Override
    public List<SurveillanceDevice> discoverDevices(String subnet) {
        OnvifValidation.requireSubnet(subnet);
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

    @Override
    public Set<String> getDeviceCapabilities(String ip, int port, String user, String pass) {
        OnvifValidation.requireConnectionArgs(ip, port, user, pass);
        log.info("GetCapabilities {}:{} (simulated)", ip, port);
        // 模拟实现：统一返回标准能力集合。
        //
        // 此前这里写的是"PTZ / Imaging 仅在 port==80 时才有"—— 端口号和云台能力
        // 之间没有任何真实关联，那是随手写的开关。但它有实际后果：能力位现在真的会
        // 门控操作（见 SurveillanceController 的 PTZ / 取流端点），于是**注册在
        // 非 80 端口的真实摄像头会被判成"不支持云台"并拒绝操作** —— 一个编造的
        // 规则变成了真实的功能损失。
        //
        // 正确的做法是：模拟实现就声明它正在模拟的那套能力（PTZ 控制确实有模拟
        // 路径），把"没有向设备确认"这件事记在别处（流地址那边已经用
        // StreamUrlOrigin 如实标注了 deviceVerified=false），而不是让一个假的
        // 否定项去拦真实用户操作。
        Set<String> caps = new LinkedHashSet<>();
        caps.add("Device");
        caps.add("Media");
        caps.add("Events");
        caps.add("PTZ");
        caps.add("Imaging");
        return Collections.unmodifiableSet(caps);
    }

    @Override
    public String getRtspUrl(String ip, int port, String user, String pass, int channel) {
        OnvifValidation.requireConnectionArgs(ip, port, user, pass);
        OnvifValidation.requireChannel(channel);
        log.info("GetStreamUri {}:{} channel={} (simulated)", ip, port, channel);
        // 模拟实现：rtsp://user:pass@ip:554/Streaming/Channels/channel
        // 凭据需 URL 编码，避免密码含 @ : / # ? 等特殊字符破坏 URL 结构
        return VendorRtspUrl.format(user, pass, ip,
                VendorRtspUrl.RTSP_PORT,
                "/Streaming/Channels/" + channel);
    }

    @Override
    public String ptzControl(String ip, int port, String user, String pass, String cmd) {
        OnvifValidation.requireConnectionArgs(ip, port, user, pass);
        OnvifValidation.requirePtzCommand(cmd);
        log.info("PTZ {} -> {}:{} (simulated)", cmd, ip, port);
        return "ok";
    }

    // ------------------------------------------------------------------

    private static SurveillanceDevice newDevice(String id, String name,
                                                SurveillanceDevice.Vendor vendor,
                                                String ip, String user, String pass) {
        return new SurveillanceDevice(id, name, vendor, ip, OnvifClient.DEFAULT_PORT, user, pass);
    }

    /** 从 CIDR 提取前三段前缀，如 "192.168.1.0/24" -> "192.168.1"。 */
    private static String extractPrefix(String subnet) {
        String addr = subnet.contains("/") ? subnet.substring(0, subnet.indexOf('/')) : subnet;
        int lastDot = addr.lastIndexOf('.');
        return lastDot > 0 ? addr.substring(0, lastDot) : addr;
    }
}
