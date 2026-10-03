package io.aerofleet.cloud.security;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;

/**
 * Spring Security 配置。
 * <p>
 * 支持两种模式：
 * <ul>
 *   <li><b>开发模式</b>（{@code aerofleet.security.dev-mode=true}，默认）：
 *       禁用 CSRF，允许所有请求，不破坏现有测试。</li>
 *   <li><b>生产模式</b>（{@code aerofleet.security.dev-mode=false}）：
 *       无状态 JWT + API Key 认证。仅 /api/v1/auth/login 与 /api/v1/auth/refresh
 *       匿名开放，/actuator/health* 公开，
 *       /ws/** 公开（WebSocket handshake 在 {@code TelemetryWebSocketHandler} 中认证），
 *       其余 /api/** 需要 JWT 或 API Key 认证。
 *       API Key 通过 {@code X-API-Key} Header 传递，由 {@link ApiKeyFilter} 处理。</li>
 * </ul>
 */
@Configuration
@EnableWebSecurity
public class SecurityConfig {

    @Value("${aerofleet.security.dev-mode:false}")
    private boolean devMode;

    private final JwtTokenProvider jwtTokenProvider;

    @Autowired(required = false)
    private ApiKeyRepository apiKeyRepository;

    @Autowired(required = false)
    private ApiKeyCache apiKeyCache;

    @Autowired(required = false)
    private ApiKeyLastUsedTracker apiKeyLastUsedTracker;

    @Autowired(required = false)
    private StreamTokenService streamTokenService;

    public SecurityConfig(JwtTokenProvider jwtTokenProvider) {
        this.jwtTokenProvider = jwtTokenProvider;
    }

    @Bean
    public SecurityFilterChain securityFilterChain(HttpSecurity http) throws Exception {
        if (devMode) {
            // 开发模式：允许所有请求，不破坏现有测试和本地开发
            http
                    .csrf(csrf -> csrf.disable())
                    .authorizeHttpRequests(auth -> auth.anyRequest().permitAll());
        } else {
            // 生产模式：JWT + API Key 认证
            // ApiKeyFilter 在 oauth2ResourceServer 之前执行，
            // 从 X-API-Key Header 验证 API Key 并设置上下文
            ApiKeyFilter apiKeyFilter = new ApiKeyFilter(devMode);
            // 通过 @Autowired(required=false) 注入 apiKeyRepository
            // 由于 Filter 是手动 new 的，需要通过 setter 注入
            if (apiKeyRepository != null) {
                apiKeyFilter.setApiKeyRepository(apiKeyRepository);
            }
            if (apiKeyCache != null) {
                apiKeyFilter.setApiKeyCache(apiKeyCache);
            }
            if (apiKeyLastUsedTracker != null) {
                apiKeyFilter.setLastUsedTracker(apiKeyLastUsedTracker);
            }

            http
                    .csrf(csrf -> csrf.disable())
                    .sessionManagement(session ->
                            session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                    .authorizeHttpRequests(auth -> auth
                            // 仅登录/刷新匿名开放；/api/v1/auth/** 其余端点（API Key 铸造/撤销/列表）需认证
                            .requestMatchers("/api/v1/auth/login", "/api/v1/auth/refresh").permitAll()
                            .requestMatchers("/actuator/health", "/actuator/health/liveness", "/actuator/health/readiness").permitAll()
                            // 浏览器 WS 握手带不了 Authorization 头：链上放行，
                            // 由 TelemetryWebSocketHandler 在握手后校验 query token 并拒绝匿名连接
                            .requestMatchers("/ws/**").permitAll()
                            .anyRequest().authenticated())
                    .oauth2ResourceServer(oauth2 ->
                            oauth2.jwt(Customizer.withDefaults()))
                    // ApiKeyFilter 在 oauth2ResourceServer（JWT 认证）之前执行
                    .addFilterBefore(
                            apiKeyFilter,
                            UsernamePasswordAuthenticationFilter.class)
                    // StreamTokenFilter 紧随 ApiKeyFilter：只处理两个 SSE 端点的
                    // ?streamToken= 短令牌（EventSource 带不了认证头），其余直通
                    .addFilterBefore(
                            new StreamTokenFilter(devMode, streamTokenService),
                            UsernamePasswordAuthenticationFilter.class)
                    // TenantFilter 在 oauth2ResourceServer 之后执行，
                    // 从 JWT 提取 tenant_id 设置到 TenantContext
                    .addFilterAfter(
                            new TenantFilter(jwtTokenProvider.getDecoder(), devMode),
                            UsernamePasswordAuthenticationFilter.class);
        }
        return http.build();
    }

    @Bean
    public JwtDecoder jwtDecoder() {
        return jwtTokenProvider.getDecoder();
    }

    @Bean
    public PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }
}