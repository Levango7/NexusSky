package io.aerofleet.cloud.surveillance.onvif;

/**
 * 该能力在 live 模式下<b>尚未实现</b>。
 *
 * <p>唯一来源是 ONVIF 事件订阅：模拟实现里的"订阅"只是把回调塞进内存表，
 * 永远不会被真实设备触发。live 模式下若照旧返回一个句柄，调用方（SSE 端点）
 * 会一直挂着一个"已连接但永不来事件"的流——这就是假成功。
 *
 * <p>所以这里显式失败，由 REST 层映射成 <b>501 Not Implemented</b>：
 * "服务器没实现这个方法"是事实，"订阅成功"不是。
 */
public class OnvifUnsupportedException extends OnvifException {

    public OnvifUnsupportedException(String message) {
        super(message);
    }
}
