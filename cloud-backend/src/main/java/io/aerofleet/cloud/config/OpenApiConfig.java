package io.aerofleet.cloud.config;

import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.info.Contact;
import io.swagger.v3.oas.models.info.License;
import io.swagger.v3.oas.models.security.SecurityScheme;
import io.swagger.v3.oas.models.security.SecurityRequirement;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * SpringDoc OpenAPI 配置：定义 NexusSky Cloud Backend 的 API 文档元数据
 * （标题、描述、版本、联系方式、许可证）与全局安全方案。
 * <p>
 * 安全方案：
 * <ul>
 *   <li><b>BearerAuth</b> — JWT Bearer Token（用户登录后获取）</li>
 *   <li><b>ApiKeyAuth</b> — API Key via {@code X-API-Key} Header（用于 SDK / 程序化访问）</li>
 * </ul>
 * <p>
 * 访问地址：
 * <ul>
 *   <li>OpenAPI JSON：{@code /v3/api-docs}</li>
 *   <li>Swagger UI：{@code /swagger-ui.html}</li>
 *   <li>Spec 导出（需认证）：{@code /api/v1/openapi/json}、{@code /api/v1/openapi/yaml}</li>
 * </ul>
 * Controller 由 SpringDoc 自动扫描 {@code @RestController}，无需在此显式注册。
 */
@Configuration
public class OpenApiConfig {
    @Bean
    public OpenAPI aerofleetOpenAPI() {
        return new OpenAPI()
            .info(new Info()
                .title("NexusSky Cloud Backend API")
                .description("无人机智能飞控系统云端管理平台 REST API")
                .version("0.1.0")
                .contact(new Contact().name("AeroFleet").url("https://github.com/Levango7/NexusSky"))
                .license(new License().name("MIT")))
            .addSecurityItem(new SecurityRequirement().addList("BearerAuth"))
            .addSecurityItem(new SecurityRequirement().addList("ApiKeyAuth"))
            .components(new io.swagger.v3.oas.models.Components()
                .addSecuritySchemes("BearerAuth", new SecurityScheme()
                    .type(SecurityScheme.Type.HTTP)
                    .scheme("bearer")
                    .bearerFormat("JWT"))
                .addSecuritySchemes("ApiKeyAuth", new SecurityScheme()
                    .type(SecurityScheme.Type.APIKEY)
                    .in(SecurityScheme.In.HEADER)
                    .name("X-API-Key")
                    .description("API Key for programmatic access (format: nsk_<hex>)")));
    }
}