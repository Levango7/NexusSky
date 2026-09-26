package io.aerofleet.cloud.surveillance;

import java.util.List;
import java.util.Set;
import java.util.function.Consumer;

/**
 * 厂商私有协议适配器接口。
 * <p>
 * 为海康威视、大华、宇视三大安防厂商提供统一适配接口，屏蔽各厂商私有协议差异。
 * 上层业务通过 {@link VendorAdapterRegistry} 获取对应厂商的适配器实例，
 * 调用统一接口完成设备发现、视频流获取、云台控制、事件订阅和能力查询。
 * <p>
 * 所有适配器当前为模拟实现，接口设计已预留真实 SDK 接入点：
 * <ul>
 *   <li>{@link #discover} — 设备发现（厂商私有发现协议或 ONVIF WS-Discovery）</li>
 *   <li>{@link #getStreamUrl} — 获取 RTSP/私有流 URL</li>
 *   <li>{@link #ptzControl} — 云台控制（方向/缩放/停止）</li>
 *   <li>{@link #subscribeEvents} — 事件订阅（移动侦测/入侵报警等）</li>
 *   <li>{@link #getCapabilities} — 设备能力查询</li>
 * </ul>
 */
public interface VendorAdapter {

    /**
     * 获取本适配器对应的厂商类型。
     *
     * @return 厂商枚举值
     */
    Vendor getVendor();

    /**
     * 设备发现。
     * <p>
     * 通过厂商私有发现协议或 ONVIF WS-Discovery 在指定子网内搜索设备。
     *
     * @param subnet 子网 CIDR，如 "192.168.1.0/24"
     * @return 发现的设备列表
     */
    List<SurveillanceDevice> discover(String subnet);

    /**
     * 获取视频流 URL。
     * <p>
     * 返回可直接用于播放的 RTSP 或厂商私有流协议 URL。
     *
     * @param device  目标设备
     * @param channel 通道号（从 1 开始）
     * @return 视频流 URL
     */
    String getStreamUrl(SurveillanceDevice device, int channel);

    /**
     * 云台控制。
     * <p>
     * 支持的命令：up/down/left/right/zoomIn/zoomOut/stop。
     *
     * @param device 目标设备
     * @param cmd    PTZ 命令
     * @return 命令执行结果（"ok" 表示成功）
     */
    String ptzControl(SurveillanceDevice device, String cmd);

    /**
     * 事件订阅。
     * <p>
     * 注册事件回调，当设备发生移动侦测、入侵报警等事件时触发。
     *
     * @param device   目标设备
     * @param callback 事件回调（接收事件载荷字符串）
     * @return 订阅句柄（可用于取消订阅）
     */
    String subscribeEvents(SurveillanceDevice device, Consumer<String> callback);

    /**
     * 取消事件订阅。
     *
     * @param handle 订阅句柄（由 {@link #subscribeEvents} 返回）
     */
    void unsubscribeEvents(String handle);

    /**
     * 获取设备能力集合。
     * <p>
     * 返回设备支持的服务类型，如 "Device"、"Media"、"PTZ"、"Events"、"Imaging" 等。
     *
     * @param device 目标设备
     * @return 设备能力集合
     */
    Set<String> getCapabilities(SurveillanceDevice device);
}