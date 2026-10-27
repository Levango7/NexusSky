package io.aerofleet.cloud.surveillance.onvif;

import org.w3c.dom.Document;
import org.w3c.dom.Element;

import java.util.ArrayList;
import java.util.List;

/**
 * ONVIF 事件订阅的客户端面（WS-BaseNotification PullPoint）。
 *
 * <h2>为什么是 PullPoint，不是 Push</h2>
 *
 * <p>WS-BaseNotification 有 Push（设备主动 POST 到我们）与 Pull（我们轮询订阅地址）
 * 两种模式。Push 要求设备能连回本服务，现场摄像头通常在独立视频网段、且服务多在
 * NAT 后面——Push 地址设备根本到不了。PullPoint 只要求「本服务能连设备」，
 * 与取流同向，是本仓唯一能保证可用的模式。
 *
 * <h2>只做三件事</h2>
 *
 * <ol>
 *   <li>{@code CreatePullPointSubscription}：拿订阅地址（设备侧有状态对象）；</li>
 *   <li>{@code PullMessages}：按 Timeout/MessageLimit 取通知；</li>
 *   <li>把 {@code NotificationMessage} 原样序列化交出——厂商私有字段在
 *       {@code Message} 里的结构千差万别，强类型化只会把不认识的部分丢掉。</li>
 * </ol>
 *
 * <p>订阅到期（{@code TerminationTime}）后设备会对 PullMessages 报 Fault；这里不自动
 * 续期——调用方（{@link OnvifLiveBackend} 的轮询器）负责重建订阅并如实记账，避免
 * 在协议层做一个「悄悄续命」的黑箱。
 */
public class OnvifPullPointClient {

    /** 订阅有效期：1 小时。够长到不用频繁重建，又不会在设备侧永久占资源。 */
    static final String INITIAL_TERMINATION_TIME = "PT1H";
    /** 单次 PullMessages 的服务端等待上限（设备无事件时最多压这么久）。 */
    static final String PULL_TIMEOUT = "PT1S";
    /** 单次最多取多少条通知（防止一次性把设备侧积压全拉进内存）。 */
    static final int MESSAGE_LIMIT = 16;

    private final OnvifSoapClient soap;

    public OnvifPullPointClient(OnvifSettings settings) {
        this(new OnvifSoapClient(settings));
    }

    OnvifPullPointClient(OnvifSoapClient soap) {
        this.soap = soap;
    }

    /**
     * 创建拉取点订阅。
     *
     * @param eventsServiceUrl 设备的 Events 服务 XAddr（来自 GetCapabilities）
     * @param user             用户名
     * @param pass             密码
     * @return 订阅（含真实订阅地址）
     * @throws OnvifException 设备拒绝、或响应里没有订阅地址
     */
    public Subscription subscribe(String eventsServiceUrl, String user, String pass) {
        String body = "<tev:CreatePullPointSubscription>"
                + "<tev:InitialTerminationTime>" + INITIAL_TERMINATION_TIME + "</tev:InitialTerminationTime>"
                + "</tev:CreatePullPointSubscription>";
        Document document = soap.call(eventsServiceUrl, "CreatePullPointSubscription", body, user, pass);

        Element reference = OnvifXml.firstDescendant(
                document.getDocumentElement(), "SubscriptionReference");
        String address = reference == null ? null : OnvifXml.childText(reference, "Address");
        if (address == null || address.isBlank()) {
            // 没有地址就没有可轮询的对象；这时返回一个假句柄等于制造"订阅成功"的假象
            throw new OnvifException("CreatePullPointSubscription response has no "
                    + "SubscriptionReference/Address");
        }
        String termination = OnvifXml.text(
                OnvifXml.firstDescendant(document.getDocumentElement(), "TerminationTime"));
        return new Subscription(address, termination);
    }

    /**
     * 拉取一批通知。
     *
     * @param subscriptionAddress {@link #subscribe} 返回的地址
     * @param user                用户名
     * @param pass                密码
     * @return 序列化后的 {@code NotificationMessage} 列表；无事件时为空列表
     */
    public List<String> pull(String subscriptionAddress, String user, String pass) {
        String body = "<tev:PullMessages>"
                + "<tev:Timeout>" + PULL_TIMEOUT + "</tev:Timeout>"
                + "<tev:MessageLimit>" + MESSAGE_LIMIT + "</tev:MessageLimit>"
                + "</tev:PullMessages>";
        Document document = soap.call(subscriptionAddress, "PullMessages", body, user, pass);

        List<String> events = new ArrayList<>();
        for (Element message : OnvifXml.descendants(
                document.getDocumentElement(), "NotificationMessage")) {
            events.add(OnvifXml.serialize(message));
        }
        return events;
    }

    /** 设备侧的订阅（地址 + 到期时间）。 */
    public record Subscription(String address, String terminationTime) {
    }
}
