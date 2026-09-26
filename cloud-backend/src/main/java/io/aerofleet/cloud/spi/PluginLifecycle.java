package io.aerofleet.cloud.spi;

/**
 * 插件生命周期接口，定义插件从初始化到销毁的标准流程。
 * <p>
 * 所有 SPI 插件接口（PayloadPlugin、TransportPlugin、DecisionStrategyPlugin、
 * RoutePlannerPlugin）均继承此接口，确保统一的生命周期管理。
 * <p>
 * 生命周期顺序：
 * <ol>
 *   <li>{@link #initialize(PluginContext)} — 注册后、启用前调用，用于读取配置、初始化资源</li>
 *   <li>{@link #enable()} — 插件启用，开始接收业务调用</li>
 *   <li>{@link #disable()} — 插件禁用，停止接收业务调用但不卸载，可重新 enable</li>
 *   <li>{@link #destroy()} — 插件销毁，卸载前清理所有资源</li>
 * </ol>
 */
public interface PluginLifecycle {

    /**
     * 插件初始化（注册后、启用前调用）。
     * <p>
     * 用于读取配置、建立连接、初始化内部状态等。
     *
     * @param context 插件上下文，包含配置属性
     */
    void initialize(PluginContext context);

    /**
     * 插件启用。
     * <p>
     * 插件开始接收业务调用。initialize 之后或 disable 之后均可调用。
     */
    void enable();

    /**
     * 插件禁用（不卸载，可重新启用）。
     * <p>
     * 插件停止接收业务调用，但内部资源不释放，可通过 enable 重新激活。
     */
    void disable();

    /**
     * 插件销毁（卸载前清理资源）。
     * <p>
     * 释放所有内部资源，插件不再可用。
     */
    void destroy();
}