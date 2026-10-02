package io.aerofleet.cloud.tenant;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.aerofleet.cloud.api.controller.DroneController;
import io.aerofleet.cloud.security.ApiKeyContext;
import io.aerofleet.cloud.security.AuthController;
import io.aerofleet.cloud.security.TenantContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.annotation.AnnotatedElementUtils;
import org.springframework.mock.web.MockServletContext;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.context.support.AnnotationConfigWebApplicationContext;
import org.springframework.web.servlet.config.annotation.EnableWebMvc;

import java.lang.reflect.Method;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * TenantConfig 注册行为测试：真实 Spring MVC 拦截器链（{@code @EnableWebMvc}
 * 最小切片 + webAppContextSetup），认证路径取自 AuthController 的注解。
 * <p>
 * 2026-10-03 第七轮全仓路径对齐发现：排除路径曾停留在 v1 迁移前的
 * {@code /api/auth/**}（死模式）。后果：登录/刷新落进共享 IP 限流桶（默认
 * 100/分钟），与同 IP 的其他匿名流量互相挤占，而登录本应只受 AuthController
 * 自带的每 IP 每分钟 10 次独立限制。本测试把「认证端点不进限流桶、业务端点
 * 仍被限流」钉成行为断言。
 */
@DisplayName("TenantConfig：认证端点必须被限流排除，业务端点仍被限流")
class TenantConfigTest {

    private AnnotationConfigWebApplicationContext context;
    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        // 每个用例全新上下文：限流窗口在拦截器实例内，用例间必须隔离
        context = new AnnotationConfigWebApplicationContext();
        context.register(Slice.class);
        context.setServletContext(new MockServletContext());
        context.refresh();
        mockMvc = MockMvcBuilders.webAppContextSetup(context).build();
    }

    @AfterEach
    void tearDown() {
        if (context != null) {
            context.close();
        }
        TenantContext.clear();
        ApiKeyContext.clear();
    }

    @Test
    @DisplayName("业务端点超限返回 429（限流拦截器在本切片内生效）")
    void businessEndpointsRateLimited() throws Exception {
        String business = basePath(DroneController.class) + "/1/telemetry";
        mockMvc.perform(get(business)).andExpect(status().isOk());
        mockMvc.perform(get(business)).andExpect(status().isOk());
        mockMvc.perform(get(business)).andExpect(status().isTooManyRequests());
    }

    @Test
    @DisplayName("登录/刷新在 IP 桶耗尽后仍可达（被限流排除；登录限频由 AuthController 负责）")
    void authEndpointsExcludedFromRateLimit() throws Exception {
        String business = basePath(DroneController.class) + "/1/telemetry";
        String login = postPath(AuthController.class, "/login");
        String refresh = postPath(AuthController.class, "/refresh");
        // 先用业务端点把共享 IP 桶打满（本切片限流 2/分钟）
        mockMvc.perform(get(business)).andExpect(status().isOk());
        mockMvc.perform(get(business)).andExpect(status().isOk());
        // 认证端点不进该桶：桶满后仍应全部可达
        for (int i = 0; i < 5; i++) {
            mockMvc.perform(post(login)).andExpect(status().isOk());
        }
        mockMvc.perform(post(refresh)).andExpect(status().isOk());
        // 对照：业务端点在桶满后确实 429
        mockMvc.perform(get(business)).andExpect(status().isTooManyRequests());
    }

    // ---- 路径推导：直接读真实 Controller 的注解，路径漂移即测试失败 ----

    private static String basePath(Class<?> controller) {
        RequestMapping rm = AnnotatedElementUtils.findMergedAnnotation(controller, RequestMapping.class);
        assertThat(rm).as("%s 需有类级 @RequestMapping", controller.getSimpleName()).isNotNull();
        assertThat(rm.path()).isNotEmpty();
        return rm.path()[0];
    }

    private static String postPath(Class<?> controller, String leaf) {
        String base = basePath(controller);
        for (Method m : controller.getDeclaredMethods()) {
            PostMapping a = AnnotatedElementUtils.findMergedAnnotation(m, PostMapping.class);
            if (a != null) {
                for (String p : a.value()) {
                    if (leaf.equals(p)) {
                        return base + p;
                    }
                }
            }
        }
        throw new AssertionError("未在 " + controller.getSimpleName() + " 找到 POST " + leaf);
    }

    // ---- 最小 MVC 切片 ----

    /**
     * 最小 MVC 切片：限流 2 次/分钟的 TenantInterceptor + 真实 TenantConfig
     * 注册链 + 承接所有 /api/** 的探针端点。
     */
    @EnableWebMvc
    @Configuration
    static class Slice {

        @Bean
        TenantInterceptor tenantInterceptor() {
            return new TenantInterceptor(2, new ObjectMapper());
        }

        @Bean
        TenantConfig tenantConfig(TenantInterceptor tenantInterceptor) {
            return new TenantConfig(tenantInterceptor);
        }

        @Bean
        Endpoints endpoints() {
            return new Endpoints();
        }
    }

    /** 承接所有 /api/** 请求的探针端点：未拦截一律 200，让拦截器行为独占断言面。 */
    @RestController
    static class Endpoints {
        @RequestMapping("/api/**")
        Map<String, Object> probe() {
            return Map.of("ok", true);
        }
    }
}
