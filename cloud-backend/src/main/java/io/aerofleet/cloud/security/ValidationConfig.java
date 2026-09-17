package io.aerofleet.cloud.security;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.validation.beanvalidation.MethodValidationPostProcessor;

/**
 * JSR303 Bean Validation 配置。
 * <p>
 * 注册 {@link MethodValidationPostProcessor}，使方法参数/返回值上的约束注解
 * （{@code @Min}、{@code @Max}、{@code @NotNull} 等）生效，触发
 * {@link jakarta.validation.ConstraintViolationException}。
 * <p>
 * 配合控制器方法签名上的 {@code @Valid} 注解，对请求体 DTO 字段进行自动校验，
 * 校验失败由 {@code ApiExceptionHandler} 统一转换为 400 响应。
 */
@Configuration
public class ValidationConfig {

    /**
     * 方法级校验后置处理器：对 Bean 方法参数和返回值执行约束校验。
     *
     * @return MethodValidationPostProcessor 实例
     */
    @Bean
    public MethodValidationPostProcessor methodValidationPostProcessor() {
        return new MethodValidationPostProcessor();
    }
}