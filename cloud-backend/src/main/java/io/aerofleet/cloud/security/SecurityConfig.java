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
     *   <li><b>开发模式</b>（{@code aerofleet.security.dev-mode=true}，仅 dev/test profile 开启；代码内默认 false）：
 *       禁用 CSRF，允许所有请求，不破坏现有测试。</li>
 *   <li><b>生产模式</b>（{@code aerofleet.security.dev-mode=false}）：
 *      无状态 JWT + API Key 认证。仅 /api/v1/auth/login 与 /api/v1/auth/refresh
 *       匿名开放；health 与 prometheus/metrics 公开
 *       （指标端点本身不带凭据，其安全性依赖入口隔离，见下方注解），
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
    // CSRF 关闭的前提：认证凭据只来自 Authorization / X-API-Key 请求头，会话策略
    // STATELESS，全站不下发也不读取 Cookie —— 浏览器不会自动附带凭据，跨站请求
    // 因此拿不到身份。该前提由 HttpAuthChainTest.csrfPremise_noAmbientCookieCredential
    // 机器复核：将来任何一处改成 cookie/会话型凭据，那条测试先红，这里就必须改回开启。
    @SuppressWarnings("java/spring-disabled-csrf-protection")
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
                            // 指标端点必须匿名可达：Prometheus 抓取请求不带 JWT / API Key，
                            // 这里放行否则抓取 401、监控全空。前提是 Ingress 不暴露 /actuator，
                            // 并由集群内网 / NetworkPolicy 保证仅监控侧可达。若将来要求指标公网可达，
                            // 必须改走独立 management 端口 + 独立认证，不能把这里的 permitAll 当授权手段。
                            .requestMatchers("/actuator/prometheus", "/actuator/metrics").permitAll()
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