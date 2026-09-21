package io.aerofleet.cloud.security;

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
 *       无状态 JWT 认证。/api/auth/** 和 /actuator/** 公开，
 *       /ws/** 公开（WebSocket handshake 在 {@code TelemetryWebSocketHandler} 中认证），
 *       其余 /api/** 需要 JWT 认证。</li>
 * </ul>
 */
@Configuration
@EnableWebSecurity
public class SecurityConfig {

    @Value("${aerofleet.security.dev-mode:false}")
    private boolean devMode;

    private final JwtTokenProvider jwtTokenProvider;

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
            // 生产模式：JWT 认证
            http
                    .csrf(csrf -> csrf.disable())
                    .sessionManagement(session ->
                            session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                    .authorizeHttpRequests(auth -> auth
                            .requestMatchers("/api/auth/**").permitAll()
                            .requestMatchers("/actuator/**").permitAll()
                            .requestMatchers("/ws/**").permitAll()
                            .anyRequest().authenticated())
                    .oauth2ResourceServer(oauth2 ->
                            oauth2.jwt(Customizer.withDefaults()))
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