package io.aerofleet.cloud.spi;

import io.aerofleet.mavlink.messages.MavlinkMessage;

import java.util.Map;

/**
 * 载荷插件 SPI 接口，支持不同载荷类型（喷洒、夹爪、相机等）的可插拔扩展。
 * <p>
 * 每种载荷类型实现此接口，标注 {@code @Component} 即可被 {@link PluginRegistry} 自动发现注册。
 * 通过 {@code getPayloadType()} 标识载荷类型，注册中心按类型建立映射。
 */
public interface PayloadPlugin extends PluginLifecycle {

    /**
     * 获取载荷类型标识。
     * <p>
     * 如 "spray"、"gripper"、"camera" 等，用于注册中心索引。
     *
     * @return 载荷类型字符串
     */
    String getPayloadType();

    /**
     * 处理上行遥测消息。
     * <p>
     * 当收到与该载荷相关的 MAVLink 遥测消息时调用，插件负责解析并处理。
     *
     * @param sysid 无人机系统 ID
     * @param msg   MAVLink 消息
     */
    void onTelemetry(int sysid, MavlinkMessage msg);

    /**
     * 构建下行控制命令。
     * <p>
     * 根据业务参数组装对应的 MAVLink 命令消息，发送给无人机执行。
     *
     * @param sysid  目标无人机系统 ID
     * @param params 命令参数 Map
     * @return 组装完成的 MAVLink 命令消息
     */
    MavlinkMessage buildCommand(int sysid, Map<String, Object> params);

    /**
     * 获取插件元数据。
     *
     * @return 插件元数据（名称、版本、作者、描述）
     */
    PluginMetadata getMetadata();
}