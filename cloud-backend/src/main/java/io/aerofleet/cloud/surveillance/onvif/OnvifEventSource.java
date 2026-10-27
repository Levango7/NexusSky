package io.aerofleet.cloud.surveillance.onvif;

import java.util.function.Consumer;

/**
 * ONVIF 事件源（live 后端真实实现；模拟后端不使用本接口）。
 *
 * <p>为什么单独一个接口而不是塞进 {@link OnvifBackend}：模拟模式的事件表是
 * {@code OnvifClient} 的测试/联调设施（{@code simulateEvent} 直接回放字符串），
 * 与真实 WS-BaseNotification 的 PullPoint 轮询没有任何共享语义。用独立接口表达
 * 「这个后端能真的从设备取事件」，门面据此分派，避免让模拟实现假装实现协议。
 */
public interface OnvifEventSource {

    /**
     * 订阅设备事件。
     *
     * <p>真实实现会先向设备的 Events 服务发 {@code CreatePullPointSubscription}，
     * 拿到订阅地址后才返回；拿不到就抛 {@link OnvifException}——**不返回一个
     * 永远不会有事件的句柄**。
     *
     * @param ip       设备 IP
     * @param port     ONVIF 端口
     * @param user     用户名
     * @param pass     密码
     * @param callback 事件回调（收到的是序列化后的 NotificationMessage XML）
     * @return 订阅句柄，供 {@link #unsubscribeEvents(String)} 使用
     */
    String subscribeEvents(String ip, int port, String user, String pass, Consumer<String> callback);

    /**
     * 取消订阅（幂等：未知句柄不报错）。
     *
     * @param handle {@link #subscribeEvents} 返回的句柄
     */
    void unsubscribeEvents(String handle);

    /** 当前活跃订阅数（用于观测与测试，不要用它推断设备侧状态）。 */
    int activeSubscriptions();
}
