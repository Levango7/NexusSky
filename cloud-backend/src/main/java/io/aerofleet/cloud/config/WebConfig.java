package io.aerofleet.cloud.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.CorsRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * 全局 CORS 配置：通过 {@code aerofleet.security.allowed-origins} 配置允许的源列表，
 * 逗号分隔，默认仅允许 Vite dev server（{@code http://localhost:5173}）。
 * <p>
 * 生产环境应配置为实际前端域名，例如：
 * <pre>
 * aerofleet.security.allowed-origins=https://gcs.aerofleet.io,https://admin.aerofleet.io
 * </pre>
 */
@Configuration
public class WebConfig {

    @Value("${aerofleet.security.allowed-origins:http://localhost:5173}")
    private String allowedOrigins;

    @Bean
    public WebMvcConfigurer corsConfigurer() {
        return new WebMvcConfigurer() {
            @Override
            public void addCorsMappings(CorsRegistry registry) {
                registry.addMapping("/api/**")
                        .allowedOrigins(allowedOrigins.split(","))
                        .allowedMethods("GET", "POST", "PUT", "DELETE", "OPTIONS")
                        .allowedHeaders("*")
                        .allowCredentials(true)
                        .maxAge(3600);
            }
        };
    }
}
