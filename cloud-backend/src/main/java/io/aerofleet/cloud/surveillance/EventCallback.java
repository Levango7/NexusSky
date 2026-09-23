package io.aerofleet.cloud.surveillance;

/**
 * 厂商适配层事件回调接口。
 * <p>
 * 由 {@link VendorAdapter#subscribeEvents} 注册，当设备发生移动侦测、入侵报警等事件时触发。
 * 替代 {@code Consumer<String>}，提供更语义化的事件回调抽象。
 * <p>
 * 各厂商适配器在接收到厂商私有协议事件后，将其转换为 {@link SurveillanceEvent} 并通过此回调推送：
 * <ul>
 *   <li>海康 ISAPI：解析 ISAPI Event XML，转换为 SurveillanceEvent</li>
 *   <li>大华 DHSDK：解析 DHSDK Event 结构，转换为 SurveillanceEvent</li>
 *   <li>宇视 SDK：解析 UNIVIEW SDK Event，转换为 SurveillanceEvent</li>
 *   <li>ONVIF：解析 WS-BaseNotification Notify，转换为 SurveillanceEvent</li>
 * </ul>
 */
@FunctionalInterface
public interface EventCallback {

    /**
     * 当设备事件发生时调用。
     *
     * @param event 设备事件（已转换为统一的 SurveillanceEvent）
     */
    void onEvent(SurveillanceEvent event);
}