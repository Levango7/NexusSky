package io.aerofleet.cloud.spi;

import java.io.IOException;
import java.util.Map;

/**
 * 通信协议插件 SPI 接口，支持不同通信协议（MAVLink-UDP、MQTT、HTTP-REST 等）的可插拔扩展。
 * <p>
 * 每种通信协议实现此接口，标注 {@code @Component} 即可被 {@link PluginRegistry} 自动发现注册。
 * 通过 {@code getProtocolType()} 标识协议类型，注册中心按类型建立映射。
 */
public interface TransportPlugin extends PluginLifecycle {

    /**
     * 获取协议类型标识。
     * <p>
     * 如 "mavlink-udp"、"mqtt"、"http-rest" 等，用于注册中心索引。
     *
     * @return 协议类型字符串
     */
    String getProtocolType();

    /**
     * 启动传输层监听。
     * <p>
     * 根据配置启动对应协议的监听服务，接收设备消息后通过 handler 回调处理。
     *
     * @param config  传输层配置
     * @param handler 消息处理回调
     */
    void start(TransportConfig config, MessageHandler handler);

    /**
     * 停止传输层。
     * <p>
     * 关闭监听服务，释放网络资源。
     */
    void stop();

    /**
     * 发送消息到设备。
     *
     * @param sysid   目标设备系统 ID
     * @param message 待发送消息对象
     * @throws IOException 发送失败时抛出
     */
    void send(int sysid, Object message) throws IOException;

    /**
     * 传输层配置接口，各协议插件按需实现具体的配置类。
     */
    interface TransportConfig {

        /**
         * 获取配置属性 Map。
         *
         * @return 配置属性映射
         */
        Map<String, Object> getProperties();

        /**
         * 获取指定配置项。
         *
         * @param key 配置键
         * @return 配置值；不存在时返回 null
         */
        default Object getProperty(String key) {
            return getProperties().get(key);
        }
    }

    /**
     * 消息处理回调接口，传输层收到设备消息后通过此接口回调上层处理。
     */
    interface MessageHandler {

        /**
         * 处理收到的消息。
         *
         * @param sysid   来源设备系统 ID
         * @param message 收到的消息对象
         */
        void onMessage(int sysid, Object message);
    }
}