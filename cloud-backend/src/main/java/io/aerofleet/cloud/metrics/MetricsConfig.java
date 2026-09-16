package io.aerofleet.cloud.metrics;

import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.boot.actuate.autoconfigure.metrics.MeterRegistryCustomizer;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Micrometer 指标全局配置。
 *
 * <p>为所有指标添加通用标签（{@code app}、{@code instance}），便于在 Prometheus 中
 * 按应用实例聚合与过滤。
 *
 * <p>Actuator 端点暴露路径、Prometheus 抓取配置等由 application.properties 统一管理
 * （见任务 68），此处只负责 MeterRegistry 定制。
 */
@Configuration
public class MetricsConfig {

    /**
     * 为所有指标添加通用标签。
     *
     * <ul>
     *   <li>{@code app} — 应用名（默认 aerofleet-cloud，取自 spring.application.name）</li>
     *   <li>{@code instance} — 实例标识（默认 unknown，可由环境变量覆盖）</li>
     * </ul>
     */
    @Bean
    MeterRegistryCustomizer<MeterRegistry> commonTagsCustomizer(
            @Value("${spring.application.name:aerofleet-cloud}") String appName,
            @Value("${aerofleet.instance-id:unknown}") String instanceId) {
        return registry -> registry.config().commonTags("app", appName, "instance", instanceId);
    }
}