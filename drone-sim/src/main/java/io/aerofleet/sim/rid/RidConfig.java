package io.aerofleet.sim.rid;

/**
 * Remote ID (RID) 广播配置 record。
 * <p>
 * 对应 ASTM F3411 / ASD-STAN EN 4709-002 远程识别标准，
 * 定义无人机 RID 广播的行为参数。字段与 design.md RidConfig 定义一致。
 * </p>
 *
 * @param enabled           RID 是否启用
 * @param broadcastInterval 广播周期（秒），默认 1.0
 * @param serialNo          无人机序列号，默认 "UNKNOWN-<sysid>"
 * @param operatorId        操作者注册号，默认空字符串
 * @param operatorLat       操作者纬度，默认 0.0
 * @param operatorLon       操作者经度，默认 0.0
 * @param uaType            无人机类型（1=飞机），默认 1
 * @param selfIdDesc        自描述文本，默认 "NexusSky drone"
 * @param useMessagePack    是否使用 MESSAGE_PACK 合并发送，默认 true
 */
public record RidConfig(
        boolean enabled,
        double broadcastInterval,
        String serialNo,
        String operatorId,
        double operatorLat,
        double operatorLon,
        int uaType,
        String selfIdDesc,
        boolean useMessagePack
) {
}