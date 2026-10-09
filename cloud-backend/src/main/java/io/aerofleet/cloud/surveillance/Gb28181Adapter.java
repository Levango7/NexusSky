package io.aerofleet.cloud.surveillance;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.function.Consumer;

/**
 * GB28181 政企视频网标准适配器（E5，GB/T 28181-2016）。
 * <p>
 * 与 UniviewAdapter（私有 SDK 无法落地，5 处搁置）的口径不同：GB28181 的
 * **国标形状层是真实现**——编码校验、PTZ 指令码、SIP 信令构造、Catalog 解析
 * 全部按国标落地（纯函数可逐字节断言）。**SIP 传输与 RTP/PS 媒体面属生产阶段**：
 * getStreamUrl 返回描述性 INVITE 标识并 WARN，不返回假的可播 RTSP 地址。
 */
@Component
public class Gb28181Adapter implements VendorAdapter {

    private static final Logger log = LoggerFactory.getLogger(Gb28181Adapter.class);

    private final Gb28181CatalogParser catalogParser;
    private final Gb28181Signaling signaling;

    public Gb28181Adapter(Gb28181CatalogParser catalogParser,
                          @Value("${aerofleet.gb28181.sip-domain:00000000000000000001}") String sipDomain,
                          @Value("${aerofleet.gb28181.sip-host:127.0.0.1:5060}") String sipHost) {
        this.catalogParser = catalogParser;
        this.signaling = new Gb28181Signaling(sipDomain, sipHost);
    }

    @Override
    public Vendor getVendor() {
        return Vendor.GB28181;
    }

    /**
     * 设备发现：构造国标 Catalog 查询信令（记录日志供接入方取证），
     * 从给定的 Catalog 响应 XML 解析设备清单。
     * <p>
     * 本方法语义是"给定响应 → 设备列表"（解析侧）；SIP 发包收包属传输层（生产阶段）。
     * 调用方传入响应 XML 的来源在 PoC 期是手工/对接样例。
     */
    @Override
    public List<SurveillanceDevice> discover(String catalogResponseXml) {
        Gb28181CatalogParser.CatalogResult result = catalogParser.parse(catalogResponseXml);
        List<SurveillanceDevice> out = new ArrayList<>();
        for (Gb28181CatalogParser.CatalogItem item : result.items()) {
            Gb28181DeviceId id = Gb28181DeviceId.parse(item.deviceId());
            // GB28181 设备经 SIP 域接入（无直连 ip/凭据）；port 固定 SIP 5060，
            // SurveillanceDevice 构造校验 port ∈ [1,65535]——0.0.0.0:5060 表达"SIP 域可达"
            SurveillanceDevice d = new SurveillanceDevice(
                    item.deviceId(), item.name(), SurveillanceDevice.Vendor.GB28181,
                    "0.0.0.0", 5060, "sip", "none");
            out.add(d);
            log.info("GB28181 catalog item: id={} name={} type={} status={}",
                    id.value(), item.name(), id.typeName(), item.status());
        }
        if (result.skippedItems() > 0) {
            log.warn("GB28181 catalog parse skipped {} item(s) (invalid/missing DeviceID)",
                    result.skippedItems());
        }
        return out;
    }

    /**
     * 点播：构造国标 INVITE 形状，返回描述性标识。
     * <p>
     * **不返回假的可播 RTSP 地址**——RTP/PS 流接收与转发属生产阶段（诚实边界 spec §4）。
     * 返回的 gb28181-invite:// URI 可用于上层确认信令已构造（含通道号），前端据此
     * 显示"信令就绪、媒体面待接"而非黑屏。
     */
    @Override
    public String getStreamUrl(SurveillanceDevice device, int channel) {
        validateDevice(device);
        Gb28181DeviceId id = Gb28181DeviceId.parse(device.id);
        String invite = signaling.inviteStream(id, channel, "0.0.0.0", 0, channel);
        log.warn("GB28181 stream request for {} ch={} — INVITE constructed ({} bytes), "
                        + "but RTP/PS media plane is production-stage (no fake playable URL returned)",
                id.value(), channel, invite.length());
        return "gb28181-invite://" + id.value() + "?channel=" + channel;
    }

    /** PTZ：动作名 → 国标 A50F 指令码（hex 形态回传，真实字节语义）。 */
    @Override
    public String ptzControl(SurveillanceDevice device, String cmd) {
        validateDevice(device);
        Gb28181DeviceId.parse(device.id);   // 编码合法性（非法编码在控制面就该拒）
        String code = Gb28181PtzCommand.encodeHex(cmd);
        log.info("GB28181 PTZ control device={} cmd={} -> {}", device.id, cmd, code);
        return code;
    }

    /** 报警订阅：构造国标 SUBSCRIBE 形状，返回可注销的 handle。 */
    @Override
    public String subscribeEvents(SurveillanceDevice device, Consumer<String> callback) {
        validateDevice(device);
        Gb28181DeviceId id = Gb28181DeviceId.parse(device.id);
        String sub = signaling.subscribeAlarm(id, 3600, 1);
        String handle = "gb28181-sub-" + id.value();
        log.warn("GB28181 alarm subscribe for {} — SUBSCRIBE constructed ({} bytes), "
                        + "SIP transport is production-stage; callback will not fire in PoC",
                id.value(), sub.length());
        return handle;
    }

    @Override
    public void unsubscribeEvents(String handle) {
        log.info("GB28181 unsubscribe {}", handle);
    }

    /** 能力查询：按国标类型码报告能力面。 */
    @Override
    public Set<String> getCapabilities(SurveillanceDevice device) {
        validateDevice(device);
        Gb28181DeviceId id = Gb28181DeviceId.parse(device.id);
        if (id.isCameraDevice()) {
            return Set.of("PTZ", "STREAM", "RECORD");
        }
        if (id.isAlarmDevice()) {
            return Set.of("ALARM");
        }
        return Set.of();
    }

    private static void validateDevice(SurveillanceDevice device) {
        if (device == null || device.id == null || device.id.isBlank()) {
            throw new IllegalArgumentException("device must have a non-blank id");
        }
        if (device.vendor != SurveillanceDevice.Vendor.GB28181) {
            throw new IllegalArgumentException(
                    "Gb28181Adapter only accepts GB28181 devices, got vendor=" + device.vendor);
        }
    }
}
