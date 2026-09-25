package io.aerofleet.mavlink.hardware;

/**
 * 遥测监听器接口：订阅飞控遥测数据更新和状态变化通知。
 * <p>
 * 实现类注册到 {@link HardwareAdapter#subscribeTelemetry(TelemetryListener)} 后，
 * 适配器在每次遥测数据更新或飞控状态变化时回调对应方法。
 * <p>
 * 回调在适配器的接收线程中执行，实现类应避免耗时操作，
 * 如需处理耗时逻辑请在回调中转发到独立线程。
 */
public interface TelemetryListener {

    /**
     * 遥测数据更新回调。每次收到新的遥测消息（GPS、姿态、电池等）时触发。
     *
     * @param state 当前硬件状态快照
     */
    void onTelemetry(HardwareState state);

    /**
     * 飞控状态变化回调。如连接/断开、ARM/DISARM、模式切换等。
     *
     * @param status 状态描述字符串（如 "CONNECTED", "DISCONNECTED", "ARMED", "MODE_CHANGED:AUTO"）
     */
    void onStatusChange(String status);
}