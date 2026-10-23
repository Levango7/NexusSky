package io.aerofleet.cloud.security;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link SecurityConfig} 的 Bean 工厂最小契约（P1 #7 变异审计收口）。
 *
 * <p>只覆盖**不依赖 Spring 上下文就能观测**的那部分：{@code @Bean} 方法自身要返回
 * 非 null——返回 null 的变异体会让上下文启动即失败，而本仓控制器/门禁类单测都是
 * "直接 new"绕开上下文的，谁都不会撞上这个坑（PIT 视角下"改坏了没人发现"）。
 * dev/prod 两个分支的链路装配需要上下文级用例，留给端到端层。
 */
@DisplayName("SecurityConfig Bean 工厂契约")
class SecurityConfigContractTest {

    private static final String SECRET = "this-is-a-test-secret-key-32bytes!";

    private SecurityConfig config() {
        return new SecurityConfig(new JwtTokenProvider(SECRET));
    }

    @Test
    @DisplayName("jwtDecoder() 返回非 null（Bean 工厂返回 null 会让上下文启动即失败）")
    void jwtDecoderBeanIsNotNull() {
        assertThat(config().jwtDecoder()).isNotNull();
    }

    @Test
    @DisplayName("passwordEncoder() 返回 BCryptPasswordEncoder（Bean 工厂返回 null 同样致命）")
    void passwordEncoderBeanIsNotNull() {
        assertThat(config().passwordEncoder()).isInstanceOf(org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder.class);
    }
}
