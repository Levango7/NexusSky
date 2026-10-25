package io.aerofleet.cloud.security;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link ValidationConfig} 的最小契约（P1 #7 变异审计收口）。
 *
 * <p>这个 {@code @Bean} 方法此前没有任何覆盖测试——返回 null 的变异体会让
 * Spring 上下文直接启动失败，而"上下文没启动就等于全红"在本仓测试结构下不成立：
 * 单测都是无上下文直调的，谁都不会撞上这个坑。PIT 视角下就是"改坏了没人发现"。
 */
@DisplayName("ValidationConfig 方法校验 Bean 契约")
class ValidationConfigContractTest {

    @Test
    @DisplayName("methodValidationPostProcessor 必须返回非 null（@Bean 返 null = 上下文启动失败）")
    void methodValidationPostProcessorIsNotNull() {
        assertThat(new ValidationConfig().methodValidationPostProcessor())
                .as("@Bean 返回 null 会让上下文启动即失败（ConstraintViolationException 无处落地）")
                .isNotNull();
    }
}
