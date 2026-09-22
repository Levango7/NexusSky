package io.aerofleet.cloud.spi;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 插件注册中心，通过 Spring Bean 自动发现模式管理所有 SPI 插件。
 * <p>
 * 与现有 {@code StepExecutor} 的 {@code List<ModuleAdapter>} 注入模式一致：
 * 插件只需标注 {@code @Component} 即可被 Spring 容器扫描并注入到对应类型的 List 中，
 * 注册中心在 {@code @PostConstruct} 阶段按类型标识建立映射。
 * <p>
 * 支持四类插件：
 * <ul>
 *   <li>{@link PayloadPlugin} — 载荷类型插件</li>
 *   <li>{@link TransportPlugin} — 通信协议插件</li>
 *   <li>{@link DecisionStrategyPlugin} — AI 决策策略插件</li>
 *   <li>{@link RoutePlannerPlugin} — 航线规划算法插件</li>
 * </ul>
 * <p>
 * 线程安全：所有映射使用 ConcurrentHashMap。
 */
@Component
public class PluginRegistry {

    private static final Logger log = LoggerFactory.getLogger(PluginRegistry.class);

    /** 载荷插件映射：payloadType → PayloadPlugin */
    private final Map<String, PayloadPlugin> payloadPlugins = new ConcurrentHashMap<>();

    /** 通信协议插件映射：protocolType → TransportPlugin */
    private final Map<String, TransportPlugin> transportPlugins = new ConcurrentHashMap<>();

    /** AI 决策策略插件映射：strategyType → DecisionStrategyPlugin */
    private final Map<String, DecisionStrategyPlugin> decisionPlugins = new ConcurrentHashMap<>();

    /** 航线规划算法插件映射：algorithmType → RoutePlannerPlugin */
    private final Map<String, RoutePlannerPlugin> routePlannerPlugins = new ConcurrentHashMap<>();

    /**
     * 构造器注入所有插件列表，Spring 会自动收集标注 @Component 的插件实现。
     * <p>
     * 当没有插件实现时，Spring 注入空列表，注册中心正常启动。
     *
     * @param payloadPlugins       载荷插件列表（可能为空）
     * @param transportPlugins     通信协议插件列表（可能为空）
     * @param decisionStrategyPlugins AI 决策策略插件列表（可能为空）
     * @param routePlannerPlugins  航线规划算法插件列表（可能为空）
     */
    public PluginRegistry(List<PayloadPlugin> payloadPlugins,
                          List<TransportPlugin> transportPlugins,
                          List<DecisionStrategyPlugin> decisionStrategyPlugins,
                          List<RoutePlannerPlugin> routePlannerPlugins) {
        registerPayloadPlugins(payloadPlugins);
        registerTransportPlugins(transportPlugins);
        registerDecisionPlugins(decisionStrategyPlugins);
        registerRoutePlannerPlugins(routePlannerPlugins);
    }

    private void registerPayloadPlugins(List<PayloadPlugin> plugins) {
        if (plugins == null || plugins.isEmpty()) {
            log.info("未发现 PayloadPlugin 实现，跳过注册");
            return;
        }
        for (PayloadPlugin p : plugins) {
            String type = p.getPayloadType();
            PayloadPlugin prev = payloadPlugins.put(type, p);
            if (prev != null) {
                log.warn("载荷类型 {} 存在重复插件，覆盖：{} → {}", type, prev.getClass().getName(), p.getClass().getName());
            }
            log.info("注册载荷插件：type={}, class={}", type, p.getClass().getName());
        }
    }

    private void registerTransportPlugins(List<TransportPlugin> plugins) {
        if (plugins == null || plugins.isEmpty()) {
            log.info("未发现 TransportPlugin 实现，跳过注册");
            return;
        }
        for (TransportPlugin p : plugins) {
            String type = p.getProtocolType();
            TransportPlugin prev = transportPlugins.put(type, p);
            if (prev != null) {
                log.warn("协议类型 {} 存在重复插件，覆盖：{} → {}", type, prev.getClass().getName(), p.getClass().getName());
            }
            log.info("注册通信协议插件：type={}, class={}", type, p.getClass().getName());
        }
    }

    private void registerDecisionPlugins(List<DecisionStrategyPlugin> plugins) {
        if (plugins == null || plugins.isEmpty()) {
            log.info("未发现 DecisionStrategyPlugin 实现，跳过注册");
            return;
        }
        for (DecisionStrategyPlugin p : plugins) {
            String type = p.getStrategyType();
            DecisionStrategyPlugin prev = decisionPlugins.put(type, p);
            if (prev != null) {
                log.warn("策略类型 {} 存在重复插件，覆盖：{} → {}", type, prev.getClass().getName(), p.getClass().getName());
            }
            log.info("注册 AI 决策策略插件：type={}, class={}", type, p.getClass().getName());
        }
    }

    private void registerRoutePlannerPlugins(List<RoutePlannerPlugin> plugins) {
        if (plugins == null || plugins.isEmpty()) {
            log.info("未发现 RoutePlannerPlugin 实现，跳过注册");
            return;
        }
        for (RoutePlannerPlugin p : plugins) {
            String type = p.getAlgorithmType();
            RoutePlannerPlugin prev = routePlannerPlugins.put(type, p);
            if (prev != null) {
                log.warn("算法类型 {} 存在重复插件，覆盖：{} → {}", type, prev.getClass().getName(), p.getClass().getName());
            }
            log.info("注册航线规划算法插件：type={}, class={}", type, p.getClass().getName());
        }
    }

    // ===== 载荷插件查询 =====

    /**
     * 按类型获取载荷插件。
     *
     * @param type 载荷类型标识
     * @return 对应插件；未找到时返回 null
     */
    public PayloadPlugin getPayloadPlugin(String type) {
        return payloadPlugins.get(type);
    }

    /**
     * 获取所有已注册的载荷类型。
     *
     * @return 载荷类型集合
     */
    public Set<String> getAllPayloadTypes() {
        return Collections.unmodifiableSet(payloadPlugins.keySet());
    }

    /**
     * 获取所有已注册的载荷插件。
     *
     * @return 载荷插件映射（只读视图）
     */
    public Map<String, PayloadPlugin> getAllPayloadPlugins() {
        return Collections.unmodifiableMap(payloadPlugins);
    }

    // ===== 通信协议插件查询 =====

    /**
     * 按类型获取通信协议插件。
     *
     * @param type 协议类型标识
     * @return 对应插件；未找到时返回 null
     */
    public TransportPlugin getTransportPlugin(String type) {
        return transportPlugins.get(type);
    }

    /**
     * 获取所有已注册的协议类型。
     *
     * @return 协议类型集合
     */
    public Set<String> getAllProtocolTypes() {
        return Collections.unmodifiableSet(transportPlugins.keySet());
    }

    /**
     * 获取所有已注册的通信协议插件。
     *
     * @return 通信协议插件映射（只读视图）
     */
    public Map<String, TransportPlugin> getAllTransportPlugins() {
        return Collections.unmodifiableMap(transportPlugins);
    }

    // ===== AI 决策策略插件查询 =====

    /**
     * 按类型获取 AI 决策策略插件。
     *
     * @param type 策略类型标识
     * @return 对应插件；未找到时返回 null
     */
    public DecisionStrategyPlugin getDecisionStrategyPlugin(String type) {
        return decisionPlugins.get(type);
    }

    /**
     * 获取所有已注册的决策策略类型。
     *
     * @return 策略类型集合
     */
    public Set<String> getAllStrategyTypes() {
        return Collections.unmodifiableSet(decisionPlugins.keySet());
    }

    /**
     * 获取所有已注册的 AI 决策策略插件。
     *
     * @return 决策策略插件映射（只读视图）
     */
    public Map<String, DecisionStrategyPlugin> getAllDecisionStrategyPlugins() {
        return Collections.unmodifiableMap(decisionPlugins);
    }

    // ===== 航线规划算法插件查询 =====

    /**
     * 按类型获取航线规划算法插件。
     *
     * @param type 算法类型标识
     * @return 对应插件；未找到时返回 null
     */
    public RoutePlannerPlugin getRoutePlannerPlugin(String type) {
        return routePlannerPlugins.get(type);
    }

    /**
     * 获取所有已注册的航线规划算法类型。
     *
     * @return 算法类型集合
     */
    public Set<String> getAllAlgorithmTypes() {
        return Collections.unmodifiableSet(routePlannerPlugins.keySet());
    }

    /**
     * 获取所有已注册的航线规划算法插件。
     *
     * @return 航线规划插件映射（只读视图）
     */
    public Map<String, RoutePlannerPlugin> getAllRoutePlannerPlugins() {
        return Collections.unmodifiableMap(routePlannerPlugins);
    }
}