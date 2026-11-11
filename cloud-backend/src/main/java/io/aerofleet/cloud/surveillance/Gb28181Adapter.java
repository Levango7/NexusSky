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
 * GB28181 政企视频网标准适配器（E5，GB/T 28181-2016/2022）。
 * <p>
 * 与 UniviewAdapter（私有 SDK 无法落地，5 处搁置）的口径不同：GB28181 的
 * **国标形状层是真实现**——编码校验、PTZ 指令码、SIP 信令构造、Catalog 解析
 * 全部按国标落地（纯函数可逐字节断言）。**SIP-over-UDP 传输层已实现**
 * （{@link Gb28181SipTransport}）、**RTP/PS 媒体面已实现**（{@link Gb28181RtpPacket} +
 * {@link Gb28181PsDemuxer} + {@link Gb28181MediaSession}，37 例含真 UDP 回环）。
 *
 * <p><b>本适配器把这三层接起来了</b>（此前是"能力都在、没人串"）：
 * <ul>
 *   <li>{@link #getStreamUrl} 现在按 {@link Gb28181MediaSessionRegistry} 开好的真实
 *       收流端口构造 SDP，返回 {@code gb28181-rtp://} 摄入端点，并把
 *       {@link StreamUrlOrigin} 如实标成 {@code MEDIA_PLANE_LOCAL}——
 *       本地媒体面可用、设备侧未验证，两件事都不瞒；</li>
 *   <li>{@link #subscribeEvents} 经 {@link Gb28181SipTransport} 真发 SUBSCRIBE，
 *       NOTIFY 到达时真回调；{@link #supportsEventSubscription} 因此为
 *       {@code true}（此前是 false + 只造形状）。</li>
 * </ul>
 *
 * <p><b>仍未接的（不假装）</b>：真实设备联调（需真机 + 设备经 REGISTER 上报地址，
 * 见 {@link #learnContact}）；SSL/证书；多路并发点播的端口分配策略（当前每通道一会话）。
 */
@Component
public class Gb28181Adapter implements VendorAdapter {

    private static final Logger log = LoggerFactory.getLogger(Gb28181Adapter.class);

    private final Gb28181CatalogParser catalogParser;
    private final Gb28181Signaling signaling;
    /** 媒体面：按「设备+通道」懒开收流会话（拿到真实端口才能写 SDP）。 */
    private final Gb28181MediaSessionRegistry mediaSessions = new Gb28181MediaSessionRegistry();
    /** 信令传输：懒开（构造时不该占端口——适配器是 Spring 单例，建 Bean 就绑端口太早）。 */
    private volatile Gb28181SipTransport transport;
    /** 设备经 REGISTER 上报的联系地址（GB28181 里平台是 SIP 服务端，设备地址是学来的）。 */
    private final java.util.Map<String, java.net.InetSocketAddress> contacts =
            new java.util.concurrent.ConcurrentHashMap<>();
    /** 报警订阅：handle → 设备 ID（退订时按设备找 SUBSCRIBE）。 */
    private final java.util.Map<String, String> subscriptions =
            new java.util.concurrent.ConcurrentHashMap<>();
    /** 报警订阅：handle → 回调（NOTIFY 到达时分发）。 */
    private final java.util.Map<String, java.util.function.Consumer<String>> subscribers =
            new java.util.concurrent.ConcurrentHashMap<>();
    /**
     * NOTIFY → handle 的路由表：Call-ID → handle。
     *
     * <p>RFC 3265：同一订阅的 NOTIFY 与 SUBSCRIBE **共用同一个 Call-ID**，
     * 而 NOTIFY 的 CSeq 是独立的（且没有 SUBSCRIBE 那样的 transaction key）。
     * 因此只能按 Call-ID 认领，不能靠 transact 的事务键。
     */
    private final java.util.Map<String, String> callIdToHandle =
            new java.util.concurrent.ConcurrentHashMap<>();

    public Gb28181Adapter(Gb28181CatalogParser catalogParser,
                          @Value("${aerofleet.gb28181.sip-domain:00000000000000000001}") String sipDomain,
                          @Value("${aerofleet.gb28181.sip-host:127.0.0.1:5060}") String sipHost) {
        this.catalogParser = catalogParser;
        this.signaling = new Gb28181Signaling(sipDomain, sipHost);
        log.info("GB28181 接入配置: sip-domain={}, sip-host={}（改这两项后需重启；连不上先看这行）",
                sipDomain, sipHost);
    }

    /**
     * 登记设备的联系地址（SIP 服务端在收到设备 REGISTER 后调用）。
     *
     * <p><b>为什么必须有这一步</b>：GB/T 28181 里平台侧是 SIP 服务端，设备的
     * IP:port 由它自己 REGISTER 时上报，平台无法从 {@code SurveillanceDevice}
     * （PoC 期固定 {@code 0.0.0.0:5060}）推断。此前适配器把 INVITE 的 SDP 写成
     * {@code 0.0.0.0:0}，等于"设备你看着推"——那不是委婉，是根本发不出去。
     *
     * <p>没有登记就点播/订阅会抛 {@link Gb28181DeviceContactUnknownException}，
     * 而不是把一条注定失败的信令发去 0.0.0.0。
     */
    public void learnContact(String deviceId, java.net.InetSocketAddress contact) {
        if (deviceId == null || deviceId.isBlank()) {
            throw new IllegalArgumentException("deviceId must not be blank");
        }
        java.net.InetSocketAddress prev = contacts.put(deviceId, contact);
        log.info("GB28181 device contact learned: {} -> {}{}", deviceId, contact,
                prev == null ? "" : " (was " + prev + ")");
    }

    /** 该设备是否已登记联系地址（上层据此决定是 501 还是"先注册"）。 */
    public boolean knowsContact(String deviceId) {
        return contacts.containsKey(deviceId);
    }

    /** 传输层（懒开 + 注册 NOTIFY 分发）；拿不到端口时响亮失败。 */
    private synchronized Gb28181SipTransport transport() {
        Gb28181SipTransport cur = transport;
        if (cur != null) {
            return cur;
        }
        try {
            Gb28181SipTransport t = new Gb28181SipTransport(0);
            // 每条到达的消息都过一遍订阅分发（NOTIFY 的 transaction key 与 SUBSCRIBE 不同，
            // 不能靠 transact 拿，必须自己认领）
            t.onMessage(() -> dispatchNotify(t));
            t.start();
            this.transport = t;
            log.info("GB28181 sip transport started on {} (lazily, first use)", t.localAddress());
            return t;
        } catch (java.net.SocketException e) {
            throw new IllegalStateException("gb28181 sip transport open failed", e);
        }
    }

    /**
     * 把收到的 NOTIFY 分发给对应订阅者（按 Call-ID 认领，RFC 3265）。
     *
     * <p>只处理**新增**的 NOTIFY：用一个已分发的 Call-ID 游标避免重复触发
     * （transport.received() 是累积列表，每来一条消息都会被 onMessage 回调一次）。
     */
    private void dispatchNotify(Gb28181SipTransport t) {
        java.util.List<Gb28181SipMessage> all = t.received();
        for (int i = dispatched.get(); i < all.size(); i++) {
            Gb28181SipMessage m = all.get(i);
            if (!"NOTIFY".equals(m.methodOfCseq())) {
                continue;
            }
            dispatched.incrementAndGet();
            String handle = callIdToHandle.get(m.firstHeader("Call-ID"));
            java.util.function.Consumer<String> cb =
                    handle == null ? null : subscribers.get(handle);
            if (cb == null) {
                log.debug("gb28181 NOTIFY without local subscription: call-id={}",
                    m.firstHeader("Call-ID"));
                continue;
            }
            try {
                cb.accept(m.bodyText());
            } catch (RuntimeException e) {
                // 订阅者异常不许杀传输读线程（与媒体面观察者隔离同一条纪律）
                log.warn("gb28181 event subscriber failed for {}", handle, e);
            }
        }
    }

    /** 已分发过的 transport.received() 下标游标（避免同一 NOTIFY 重复触发）。 */
    private final java.util.concurrent.atomic.AtomicInteger dispatched =
            new java.util.concurrent.atomic.AtomicInteger();

    /** Spring 容器销毁：关传输 + 全部媒体会话（不泄漏端口）。 */
    @jakarta.annotation.PreDestroy
    public synchronized void close() {
        Gb28181SipTransport t = transport;
        if (t != null) {
            t.close();
            this.transport = null;
        }
        mediaSessions.closeAll();
        subscribers.clear();
        subscriptions.clear();
        contacts.clear();
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
     * 点播：开真实收流端口 → 按国标构造带真实 SDP 的 INVITE → 返回摄入端点 URI。
     *
     * <p><b>此前这里有一个真缺陷</b>：SDP 的 {@code c=} 行与 {@code m=} 行被写死成
     * {@code 0.0.0.0} / {@code 0}。GB/T 28181-2022 附录 C.1 要求这里写**真实的**
     * 媒体接收端地址，否则设备不知道该把 RTP 发到哪——INVITE 造得出来，
     * 但发出去也没有任何意义（"信令就绪、媒体面待接"其实是"信令也没真发"）。
     *
     * <p>现在：先从 {@link Gb28181MediaSessionRegistry} 拿（必要时开）该通道的收流
     * 会话，把**真实本地端口**写进 SDP，再构造 INVITE。返回的 URI 形如
     * {@code gb28181-rtp://<deviceId>?channel=N&port=P&ssrc=S}，是平台自己的
     * RTP 摄入端点（不是 rtsp://，标准播放器连不上）。
     *
     * <p>设备未登记联系地址时抛 {@link Gb28181DeviceContactUnknownException}——
     * 不是发一条注定失败的信令去 0.0.0.0。
     */
    @Override
    public String getStreamUrl(SurveillanceDevice device, int channel) {
        validateDevice(device);
        if (channel < 1) {
            throw new IllegalArgumentException("channel must be >= 1");
        }
        Gb28181DeviceId id = Gb28181DeviceId.parse(device.id);
        java.net.InetSocketAddress contact = requireContact(id.value());

        // ① 真实收流端口（媒体面会话：懒开，重复点播同通道复用同一端口）
        String key = id.value() + "/" + channel;
        Gb28181MediaSession session = mediaSessions.acquire(key);
        int ssrc = ssrcOf(id, channel);

        // ② 按国标构造带真实 SDP 的 INVITE（c=/m=/y= 全是真值）。
        //    注意 inviteStream 第 5 参是 **ssrc** 而非 channel——早先误传 channel，
        //    结果 SDP 的 y= 行写 1 而 URI 写真实 ssrc，两侧对不上（设备侧 y= 与
        //    RTP 头的 SSRC 必须一致否则丢帧）。真回环测试 inviteCarriesRealSdp 抓出。
        String host = session.address().getAddress().getHostAddress();
        int port = session.address().getPort();
        Gb28181SipMessage invite = signaling.inviteStream(id, channel, host, port, ssrc);
        log.info("GB28181 invite ready for {} ch={} — SDP c={} m=video/{} y={}, sending to {}",
                id.value(), channel, host, port, ssrc, contact);

        // ③ 真发（设备已登记联系地址；收不到响应不阻塞点播——BYE/超时属传输层重试策略）
        try {
            transport().send(contact, invite);
        } catch (java.io.IOException e) {
            // 发不出去要让调用方知道：流不会来，URI 标得再对也没用
            throw new IllegalStateException("gb28181 invite send failed to " + contact, e);
        }

        return "gb28181-rtp://" + id.value()
                + "?channel=" + channel
                + "&port=" + port
                + "&ssrc=" + ssrc;
    }

    /**
     * {@inheritDoc}
     *
     * <p>如实标成 {@link StreamUrlOrigin#MEDIA_PLANE_LOCAL}：本地媒体面已接好
     * （RTP 收流 + PS 解复用可用，有真 UDP 回环测试），但**对通用播放器不可播**、
     * 且**设备侧从未验证过**。前端据此显示"平台可解、需接设备"，而不是挂一个
     * 必然黑屏的播放器。
     */
    @Override
    public StreamUrlOrigin streamUrlOrigin() {
        return StreamUrlOrigin.MEDIA_PLANE_LOCAL;
    }

    /** SSRC：由设备 ID 与通道号确定性导出（同一点播重复拿到同一值，便于对账）。 */
    private static int ssrcOf(Gb28181DeviceId id, int channel) {
        int h = 0x811C9DC5;
        for (int i = 0; i < id.value().length(); i++) {
            h ^= id.value().charAt(i);
            h *= 0x01000193;
        }
        return (h ^ (channel * 0x9E3779B1)) & 0x7FFFFFFF;
    }

    /** 取设备联系地址；未登记则响亮失败（不发去 0.0.0.0）。 */
    private java.net.InetSocketAddress requireContact(String deviceId) {
        java.net.InetSocketAddress a = contacts.get(deviceId);
        if (a == null) {
            throw new Gb28181DeviceContactUnknownException(deviceId);
        }
        return a;
    }

    /**
     * REST 契约口径 → 国标内部口径的翻译表。
     * <p>
     * 对外 API 用 camelCase（{@code zoomIn}/{@code zoomOut}，与 {@code OnvifClient.PTZ_COMMANDS}
     * 的历史契约一致）；国标内部用 snake_case（{@link Gb28181PtzCommand#ZOOM_IN}）。
     * 方向词两套写法一致，只有缩放不同。**适配器的职责就是做这层翻译**——
     * 上层不应感知厂商口径差异（否则 GB28181 设备经 REST 调 PTZ 必然失败）。
     */
    private static final java.util.Map<String, String> CMD_ALIASES = java.util.Map.of(
            "zoomIn", Gb28181PtzCommand.ZOOM_IN,
            "zoomOut", Gb28181PtzCommand.ZOOM_OUT);

    /** PTZ：动作名 → 国标 A50F 指令码（hex 形态回传，真实字节语义）。 */
    @Override
    public String ptzControl(SurveillanceDevice device, String cmd) {
        validateDevice(device);
        Gb28181DeviceId.parse(device.id);   // 编码合法性（非法编码在控制面就该拒）
        String normalized = CMD_ALIASES.getOrDefault(cmd, cmd);
        String code = Gb28181PtzCommand.encodeHex(normalized);
        log.info("GB28181 PTZ control device={} cmd={}{} -> {}", device.id, cmd,
                normalized.equals(cmd) ? "" : " (-> " + normalized + ")", code);
        return code;
    }

    /**
     * 报警订阅：**真发 SUBSCRIBE 并经传输层收 NOTIFY**（RFC 3265 事件框架）。
     *
     * <p>此前这里只构造信令形状、回调永不触发；现在经 {@link Gb28181SipTransport}
     * 真发，并按 Call-ID 把 NOTIFY 分发给本 handle 的回调（同一订阅的 NOTIFY 与
     * SUBSCRIBE 共用 Call-ID，见 RFC 3265 §3.1）。
     *
     * <p><b>为什么这里是 true 而不是继续 501</b>：SSE 端点改为"先确认能真接、
     * 再挂流"——订阅建立失败（设备未登记 / 发不出去）会抛，由 REST 层变成 5xx，
     * 而不是先回 200 再给一条永远没有事件的流。
     */
    @Override
    public boolean supportsEventSubscription() {
        return true;
    }

    /** 报警订阅：真发 SUBSCRIBE，返回可注销 handle。 */
    @Override
    public String subscribeEvents(SurveillanceDevice device, Consumer<String> callback) {
        validateDevice(device);
        java.util.Objects.requireNonNull(callback, "callback");
        Gb28181DeviceId id = Gb28181DeviceId.parse(device.id);
        java.net.InetSocketAddress contact = requireContact(id.value());

        String handle = "gb28181-sub-" + id.value();
        // 同一设备重复订阅：换 handle 会让旧回调收不到 NOTIFY，也可能双投
        if (subscribers.containsKey(handle)) {
            log.info("GB28181 alarm subscribe for {} — already subscribed, reusing {}",
                    id.value(), handle);
            return handle;
        }

        Gb28181SipTransport t = transport();
        Gb28181SipMessage sub = signaling.subscribeAlarm(id, 3600, 1);
        callIdToHandle.put(sub.firstHeader("Call-ID"), handle);
        subscribers.put(handle, callback);
        subscriptions.put(handle, id.value());
        try {
            t.send(contact, sub);
            log.info("GB28181 alarm subscribe sent for {} (handle={}, call-id={}, {} bytes)",
                    id.value(), handle, sub.firstHeader("Call-ID"), sub.bodyBytes());
        } catch (java.io.IOException e) {
            // 发不出去要回滚，别留一个"订阅成功但永远没事件"的空壳
            callIdToHandle.remove(sub.firstHeader("Call-ID"));
            subscribers.remove(handle);
            subscriptions.remove(handle);
            throw new IllegalStateException(
                    "gb28181 subscribe send failed to " + contact, e);
        }
        return handle;
    }

    @Override
    public void unsubscribeEvents(String handle) {
        String deviceId = subscriptions.remove(handle);
        subscribers.remove(handle);
        if (deviceId == null) {
            log.info("GB28181 unsubscribe {} — unknown handle (nothing to do)", handle);
            return;
        }
        java.net.InetSocketAddress contact = contacts.get(deviceId);
        if (contact == null) {
            log.info("GB28181 unsubscribe {} — device {} contact unknown, local state cleared",
                    handle, deviceId);
            return;
        }
        // RFC 3265：Expires=0 的 SUBSCRIBE 即退订
        Gb28181DeviceId id = Gb28181DeviceId.parse(deviceId);
        Gb28181SipMessage un = signaling.subscribeAlarm(id, 0, 2);
        try {
            transport().send(contact, un);
            log.info("GB28181 unsubscribe sent for {} (handle={})", deviceId, handle);
        } catch (java.io.IOException e) {
            log.warn("GB28181 unsubscribe send failed for {} (local state already cleared)",
                    deviceId, e);
        }
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

    /**
     * 设备联系地址未登记（没收到过它的 REGISTER，或已被清理）。
     *
     * <p>这是一类**明确的用户/部署问题**，不是瞬时故障：要么设备还没注册上来，
     * 要么 {@link #learnContact} 没被 SIP 服务端调到。抛它而不是把信令发去
     * 0.0.0.0，调用方（REST 层）可以据此回"设备未注册"而不是含糊的 500。
     */
    // ------------------------------------------------------------------
    // 诊断面 / 接缝（测试与运维观测用；不改业务行为）
    // ------------------------------------------------------------------

    /** 当前开着的媒体会话数（运维观测 + 测试断言"失败不留副作用"）。 */
    public int mediaSessionCount() {
        return mediaSessions.size();
    }

    /** 该设备通道的收流端口（未开返回 -1；点播成功后 > 0）。 */
    public int mediaSessionPort(String deviceId, int channel) {
        java.net.InetSocketAddress a = mediaSessions.addressOf(deviceId + "/" + channel);
        return a == null ? -1 : a.getPort();
    }

    /** 当前全部媒体会话的快照（close 前取，close 后可断言其 isClosed）。 */
    public java.util.List<Gb28181MediaSession> mediaSessionsSnapshot() {
        return mediaSessions.snapshot();
    }

    /** 该设备通道已解出的媒体帧数（端到端断言用）。 */
    public int mediaFrameCount(String deviceId, int channel) {
        Gb28181MediaSession s = mediaSessions.sessionOf(deviceId + "/" + channel);
        return s == null ? 0 : s.framesBuilt();
    }

    /** 该设备通道第 index 个解出的帧（越界返回 null，不抛）。 */
    public Gb28181MediaFrame mediaFrame(String deviceId, int channel, int index) {
        Gb28181MediaSession s = mediaSessions.sessionOf(deviceId + "/" + channel);
        if (s == null) {
            return null;
        }
        List<Gb28181MediaFrame> fs = s.frames();
        return index >= 0 && index < fs.size() ? fs.get(index) : null;
    }

    /**
     * 传输层 → 适配器的 NOTIFY 递交点（收流线程对每条到达消息调用本方法）。
     *
     * <p>抽成公开方法是**为了可测**：测试可以不依赖真实 socket 时序，直接把一条
     * 按 RFC 3265 构造的 NOTIFY 递给这里，验证 Call-ID 路由与回调分发。
     * 生产路径上由 {@link #transport()} 注册的 {@code onMessage} 回调驱动。
     */
    public void deliverNotify(Gb28181SipMessage msg) {
        java.util.Objects.requireNonNull(msg, "msg");
        String handle = callIdToHandle.get(msg.firstHeader("Call-ID"));
        java.util.function.Consumer<String> cb =
                handle == null ? null : subscribers.get(handle);
        if (cb == null) {
            log.debug("gb28181 NOTIFY without local subscription: call-id={}",
                    msg.firstHeader("Call-ID"));
            return;
        }
        try {
            cb.accept(msg.bodyText());
        } catch (RuntimeException e) {
            // 订阅者异常不许杀传输读线程（与媒体面观察者隔离同一条纪律）
            log.warn("gb28181 event subscriber failed for {}", handle, e);
        }
    }

    public static final class Gb28181DeviceContactUnknownException extends RuntimeException {
        private final String deviceId;

        public Gb28181DeviceContactUnknownException(String deviceId) {
            super("gb28181 device " + deviceId + " has no learned contact address "
                    + "(register it first: adapter.learnContact)");
            this.deviceId = deviceId;
        }

        public String deviceId() {
            return deviceId;
        }
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
