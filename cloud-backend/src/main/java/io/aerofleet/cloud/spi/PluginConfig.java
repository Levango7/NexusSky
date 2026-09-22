package io.aerofleet.cloud.spi;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.Map;

/**
 * 插件配置管理，基于 application.properties 中的 {@code aerofleet.plugins.*} 配置项
 * 控制各插件的启用/禁用状态。
 * <p>
 * 配置格式：{@code aerofleet.plugins.{category}.{type}.enabled=true/false}
 * <ul>
 *   <li>category: payload / transport / ai / route</li>
 *   <li>type: 具体插件类型标识（如 spray、gripper、mavlink-udp、rtl、astar 等）</li>
 * </ul>
 * <p>
 * 默认行为：未配置的插件视为启用（enabled=true），仅显式配置为 false 时才禁用。
 */
@Component
public class PluginConfig {

    private static final Logger log = LoggerFactory.getLogger(PluginConfig.class);

    /** 配置前缀 */
    private static final String CONFIG_PREFIX = "aerofleet.plugins.";

    /** 配置后缀 */
    private static final String ENABLED_SUFFIX = ".enabled";

    private final Environment environment;

    private final PluginRegistry pluginRegistry;

    @Autowired
    public PluginConfig(Environment environment, PluginRegistry pluginRegistry) {
        this.environment = environment;
        this.pluginRegistry = pluginRegistry;
    }

    /**
     * 判断指定载荷插件是否启用。
     *
     * @param payloadType 载荷类型标识
     * @return 启用时返回 true；未配置时默认启用
     */
    public boolean isPayloadEnabled(String payloadType) {
        return isEnabled("payload", payloadType);
    }

    /**
     * 判断指定通信协议插件是否启用。
     *
     * @param protocolType 协议类型标识
     * @return 启用时返回 true；未配置时默认启用
     */
    public boolean isTransportEnabled(String protocolType) {
        return isEnabled("transport", protocolType);
    }

    /**
     * 判断指定 AI 决策策略插件是否启用。
     *
     * @param strategyType 策略类型标识
     * @return 启用时返回 true；未配置时默认启用
     */
    public boolean isDecisionStrategyEnabled(String strategyType) {
        return isEnabled("ai", strategyType);
    }

    /**
     * 判断指定航线规划算法插件是否启用。
     *
     * @param algorithmType 算法类型标识
     * @return 启用时返回 true；未配置时默认启用
     */
    public boolean isRoutePlannerEnabled(String algorithmType) {
        return isEnabled("route", algorithmType);
    }

    /**
     * 通用启用判断逻辑。
     * <p>
     * 配置键格式：{@code aerofleet.plugins.{category}.{type}.enabled}
     * 未配置时默认返回 true（启用）。
     *
     * @param category 插件类别（payload/transport/ai/route）
     * @param type     插件类型标识
     * @return 启用时返回 true
     */
    private boolean isEnabled(String category, String type) {
        String key = CONFIG_PREFIX + category + "." + type + ENABLED_SUFFIX;
        String value = environment.getProperty(key);
        if (value == null) {
            return true; // 未配置时默认启用
        }
        return Boolean.parseBoolean(value);
    }

    /**
     * 构建指定插件的 PluginContext，包含配置属性。
     * <p>
     * 从 Environment 中提取以 {@code aerofleet.plugins.{category}.{type}.} 为前缀的所有配置项，
     * 去除前缀后放入属性 Map，供插件初始化使用。
     *
     * @param category 插件类别
     * @param type     插件类型标识
     * @return 插件上下文
     */
    public PluginContext buildPluginContext(String category, String type) {
        String prefix = CONFIG_PREFIX + category + "." + type + ".";
        Map<String, Object> properties = new HashMap<>();

        // 从 Environment 中提取该插件的所有配置项
        // Spring Boot 3.x 中 Environment 不直接暴露遍历接口，
        // 此处通过已知配置键模式获取常见配置项
        String enabledKey = prefix + "enabled";
        String enabledValue = environment.getProperty(enabledKey);
        if (enabledValue != null) {
            properties.put("enabled", Boolean.parseBoolean(enabledValue));
        }

        // 通用属性提取：尝试获取 priority、timeout 等常见配置
        String priorityKey = prefix + "priority";
        String priorityValue = environment.getProperty(priorityKey);
        if (priorityValue != null) {
            try {
                properties.put("priority", Integer.parseInt(priorityValue));
            } catch (NumberFormatException e) {
                log.warn("插件配置 {} 值无效：{}", priorityKey, priorityValue);
            }
        }

        String timeoutKey = prefix + "timeout-ms";
        String timeoutValue = environment.getProperty(timeoutKey);
        if (timeoutValue != null) {
            try {
                properties.put("timeout-ms", Long.parseLong(timeoutValue));
            } catch (NumberFormatException e) {
                log.warn("插件配置 {} 值无效：{}", timeoutKey, timeoutValue);
            }
        }

        return new PluginContext(properties);
    }

    /**
     * 获取所有已注册但被配置禁用的插件类型列表。
     * <p>
     * 用于启动日志展示哪些插件被配置禁用。
     *
     * @return 禁用插件类型列表（格式：category:type）
     */
    public java.util.List<String> getDisabledPlugins() {
        java.util.List<String> disabled = new java.util.ArrayList<>();

        for (String type : pluginRegistry.getAllPayloadTypes()) {
            if (!isPayloadEnabled(type)) {
                disabled.add("payload:" + type);
            }
        }
        for (String type : pluginRegistry.getAllProtocolTypes()) {
            if (!isTransportEnabled(type)) {
                disabled.add("transport:" + type);
            }
        }
        for (String type : pluginRegistry.getAllStrategyTypes()) {
            if (!isDecisionStrategyEnabled(type)) {
                disabled.add("ai:" + type);
            }
        }
        for (String type : pluginRegistry.getAllAlgorithmTypes()) {
            if (!isRoutePlannerEnabled(type)) {
                disabled.add("route:" + type);
            }
        }

        return disabled;
    }
}