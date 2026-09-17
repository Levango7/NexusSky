package io.aerofleet.cloud.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * API 版本管理配置。
 * <p>
 * 通过 {@code aerofleet.api.version}（默认 {@code v1}）配置当前 API 版本，
 * 并暴露一个 {@code apiVersionPrefix} Bean（形如 {@code /api/v1}）供需要版本前缀的组件使用。
 * <p>
 * OpenAPI 文档的路径匹配通过 {@code springdoc.paths-to-match=/api/v1/**} 在
 * {@code application.properties} 中统一配置，确保 Swagger 仅暴露当前版本的端点。
 * <p>
 * Controller 的 {@code @RequestMapping} 应使用此前缀，例如：
 * <pre>
 * &#64;RequestMapping("/api/v1/drones")
 * public class DroneController { ... }
 * </pre>
 * 新增 Controller 应遵循 {@code /api/${aerofleet.api.version}/<resource>} 约定。
 */
@Configuration
public class ApiVersionConfig {

    /** API 版本号，例如 v1、v2。通过 application.properties 的 aerofleet.api.version 配置。 */
    @Value("${aerofleet.api.version:v1}")
    private String apiVersion;

    /**
     * API 版本前缀 Bean，形如 {@code /api/v1}。
     * <p>
     * 可在其他组件中注入使用：
     * <pre>
     * &#64;Qualifier("apiVersionPrefix")
     * &#64;Autowired
     * private String apiVersionPrefix;
     * </pre>
     *
     * @return API 版本前缀，例如 {@code /api/v1}
     */
    @Bean("apiVersionPrefix")
    public String apiVersionPrefix() {
        return "/api/" + apiVersion;
    }

    /**
     * @return 当前配置的 API 版本号，例如 {@code v1}
     */
    @Bean("apiVersion")
    public String apiVersion() {
        return apiVersion;
    }
}