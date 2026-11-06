package io.aerofleet.cloud.dock;

import java.util.Map;

/**
 * 机巢命令下行通道抽象（spec R3，DJI Cloud API 物模型形状）。
 * <p>
 * 实现可插拔：{@link SimLoopbackGateway}（HTTP 回环到机巢模拟器，默认）与
 * {@link MqttDockGateway}（thing/product/{sn}/services，transport=mqtt）。
 * 帧形状按 DJI Cloud API 官方文档逐字段对齐（{@code dji-sdk/Cloud-API-Doc} v1.18
 * {@code 60.api-reference/20.dock-to-cloud/00.mqtt/00.topic-definition.md}）：
 * 下行 {@code {tid, bid, timestamp, gateway, method, data}}，
 * 应答 {@code {tid, bid, timestamp, gateway, method, data:{result, output}}}。
 * 其中 <b>tid/bid 是 UUID 文本</b>（公共字段表：事务uuid/业务uuid，均为 text——不是 long）、
 * <b>gateway 是网关设备（机巢）序列号</b>、<b>设备消息内容在 data.output</b>（struct，
 * 无 data.message 字段位）。
 */
public interface DockGateway {

    /**
     * 下发一条服务命令。
     *
     * @param sn      机巢序列号（物模型 topic 的 {sn} 段）
     * @param command 命令
     * @param data    附参（可空）
     * @return 应答帧；通道故障抛 {@link DockGatewayException}
     */
    DockReply sendCommand(String sn, DockCommand command, Map<String, Object> data);

    /** 当前实现的传输名（sim/mqtt），用于状态展示与测试断言。 */
    String transportName();

    /** 下行帧（物模型形状）。 */
    record DockCommandFrame(long tid, long bid, long timestamp, String method, Map<String, Object> data) {
    }

    /** 应答帧。tid/bid 为 UUID 文本（DJI 口径）；result=0 成功，非 0 为机巢侧错误码；
     *  message 为应答 data.output 的文本形式（DJI 无 data.message 字段，output 是 struct）。 */
    record DockReply(String tid, String bid, long timestamp, int result, String message, String rawJson) {
        public boolean ok() {
            return result == 0;
        }
    }

    /** 通道故障（网络不可达/超时/非 2xx）。 */
    class DockGatewayException extends RuntimeException {
        public DockGatewayException(String message, Throwable cause) {
            super(message, cause);
        }

        public DockGatewayException(String message) {
            super(message);
        }
    }
}
