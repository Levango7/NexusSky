package io.aerofleet.mavlink.hardware;

import java.util.List;

/**
 * 硬件适配层接口：统一抽象 PX4、ArduPilot 等真实飞控硬件的差异。
 * <p>
 * 设计目标：
 * <ul>
 *   <li>上层 SDK 和业务逻辑通过此接口操作无人机，无需关心底层协议差异</li>
 *   <li>每个实现类封装特定飞控固件的 MAVLink 消息差异</li>
 *   <li>线程安全：所有方法可在多线程环境下调用，实现类需保证内部状态一致性</li>
 * </ul>
 * <p>
 * 连接字符串格式约定：
 * <ul>
 *   <li>udp://127.0.0.1:14540 — UDP 连接（PX4 SITL 默认端口）</li>
 *   <li>tcp://127.0.0.1:5760 — TCP 连接（ArduPilot SITL 默认端口）</li>
 *   <li>serial://COM3:57600 — 串口连接（Windows）</li>
 *   <li>serial:///dev/ttyUSB0:57600 — 串口连接（Linux）</li>
 *   <li>sim:// — 模拟连接（无需真实硬件）</li>
 * </ul>
 */
public interface HardwareAdapter {

    // ====== 连接管理 ======

    /**
     * 连接到飞控硬件。
     *
     * @param connectionUrl 连接字符串，格式见类注释
     * @return true 表示连接成功
     */
    boolean connect(String connectionUrl);

    /**
     * 断开与飞控硬件的连接。
     *
     * @return true 表示断开成功
     */
    boolean disconnect();

    /**
     * 查询当前是否已连接。
     *
     * @return true 表示已连接且心跳正常
     */
    boolean isConnected();

    // ====== 心跳与状态 ======

    /**
     * 启动心跳发送。连接后应自动调用，也可手动启动。
     */
    void startHeartbeat();

    /**
     * 停止心跳发送。
     */
    void stopHeartbeat();

    /**
     * 获取当前硬件状态快照。
     *
     * @return 硬件状态数据，未连接时返回 disconnected 状态
     */
    HardwareState getState();

    // ====== 飞行控制 ======

    /**
     * 解锁（ARM）无人机，准备起飞。
     *
     * @return true 表示解锁成功
     */
    boolean arm();

    /**
     * 上锁（DISARM）无人机。
     *
     * @return true 表示上锁成功
     */
    boolean disarm();

    /**
     * 起飞到指定高度。
     *
     * @param altitude 目标高度（米，相对起飞点）
     * @return true 表示起飞指令已接受
     */
    boolean takeoff(double altitude);

    /**
     * 返回起飞点（Return To Launch）。
     *
     * @return true 表示 RTL 指令已接受
     */
    boolean rtl();

    /**
     * 设置飞行模式。
     *
     * @param mode 模式名称（如 "AUTO", "GUIDED", "STABILIZE", "LAND"）
     * @return true 表示模式切换成功
     */
    boolean setMode(String mode);

    // ====== 任务管理 ======

    /**
     * 上传航点任务到飞控。
     *
     * @param waypoints 航点列表
     * @return true 表示任务上传成功
     */
    boolean uploadMission(List<Waypoint> waypoints);

    /**
     * 从飞控下载当前航点任务。
     *
     * @return 航点列表，无任务时返回空列表
     */
    List<Waypoint> downloadMission();

    /**
     * 开始执行已上传的任务。
     *
     * @return true 表示任务开始执行
     */
    boolean startMission();

    /**
     * 清除飞控上的所有任务。
     *
     * @return true 表示任务清除成功
     */
    boolean clearMission();

    // ====== 遥测 ======

    /**
     * 订阅遥测数据更新。每次状态变化时回调监听器。
     *
     * @param listener 遥测监听器
     */
    void subscribeTelemetry(TelemetryListener listener);

    // ====== 适配器信息 ======

    /**
     * 获取适配器类型标识。
     *
     * @return 适配器类型（如 "PX4", "ArduPilot", "Simulated"）
     */
    String getAdapterType();

    /**
     * 获取飞控固件版本。
     *
     * @return 固件版本字符串，未连接时返回 "unknown"
     */
    String getFirmwareVersion();

    /**
     * 获取飞控系统 ID。
     *
     * @return 系统 ID（MAVLink sysId），未连接时返回 -1
     */
    int getSystemId();
}