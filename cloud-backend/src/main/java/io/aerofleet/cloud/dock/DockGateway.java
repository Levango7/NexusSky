package io.aerofleet.cloud.dock;

import java.util.Map;

/**
 * 机巢命令下行通道抽象（spec R3，DJI Cloud API 物模型形状）。
 * <p>
 * 实现可插拔：{@link SimLoopbackGateway}（HTTP 回环到机巢模拟器，默认）与
 * 生产阶段的 MQTT 实现（thing/product/{sn}/services，见 README 边界声明）。
 * 帧形状与 DJI Cloud API 对齐：{@code {tid, bid, timestamp, method, data}}，
 * 应答 {@code {tid, bid, timestamp, data:{result}}}（result=0 成功）。
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

    /** 应答帧。result=0 成功，非 0 为机巢侧错误码。 */
    record DockReply(long tid, long bid, long timestamp, int result, String message, String rawJson) {
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
