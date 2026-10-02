package io.aerofleet.cloud.license;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.aerofleet.cloud.api.controller.DroneController;
import io.aerofleet.cloud.security.ApiKeyController;
import io.aerofleet.cloud.security.AuthController;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.annotation.AnnotatedElementUtils;
import org.springframework.mock.web.MockServletContext;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.context.support.AnnotationConfigWebApplicationContext;
import org.springframework.web.servlet.config.annotation.EnableWebMvc;

import java.lang.reflect.Method;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * LicenseConfig 注册行为测试：真实 Spring MVC 拦截器链（{@code @EnableWebMvc}
 * 最小切片 + webAppContextSetup），被测路径全部取自真实 Controller 的
 * {@code @RequestMapping} 注解——路径再漂移时此处判红，而不是等商用部署锁死登录。
 * <p>
 * 2026-10-03 第七轮全仓路径对齐发现：排除路径曾停留在 v1 迁移前的
 * {@code /api/auth/**}、{@code /api/license/**}（死模式，匹配不到任何真实端点）。
 * 后果：{@code aerofleet.license.enabled=true}（prod/staging 默认）且 License
 * 运行期失效时，登录/刷新与 License 自管理端点被 LicenseInterceptor 一并 403，
 * 部署无法经 API 自救（鸡生蛋）。本测试把「认证/License/API Key 端点在 License
 * 失效时仍可达、业务端点仍被拦截」钉成行为断言。
 */
@DisplayName("LicenseConfig：License 失效时认证与 License 端点必须可达")
class LicenseConfigTest {

    private AnnotationConfigWebApplicationContext context;
    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
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
    }

    @Test
    @DisplayName("登录/刷新（路径取自 AuthController 注解）不被 License 拦截")
    void authEndpointsReachableWhenLicenseInvalid() throws Exception {
        mockMvc.perform(post(postPath(AuthController.class, "/login"))).andExpect(status().isOk());
        mockMvc.perform(post(postPath(AuthController.class, "/refresh"))).andExpect(status().isOk());
    }

    @Test
    @DisplayName("License 自管理端点（info/activate）不被拦截（可经 API 自救）")
    void licenseEndpointsReachableWhenLicenseInvalid() throws Exception {
        mockMvc.perform(get(getPath(LicenseController.class, "/info"))).andExpect(status().isOk());
        mockMvc.perform(post(postPath(LicenseController.class, "/activate"))).andExpect(status().isOk());
    }

    @Test
    @DisplayName("API Key 管理端点不被拦截（运维凭据可轮换）")
    void apiKeyEndpointsReachableWhenLicenseInvalid() throws Exception {
        mockMvc.perform(get(basePath(ApiKeyController.class))).andExpect(status().isOk());
        mockMvc.perform(post(basePath(ApiKeyController.class))).andExpect(status().isOk());
    }

    @Test
    @DisplayName("业务端点仍被 License 拦截（403 License Invalid，覆盖面不变）")
    void businessEndpointsBlockedWhenLicenseInvalid() throws Exception {
        String businessPath = basePath(DroneController.class) + "/1/telemetry";
        MvcResult result = mockMvc.perform(get(businessPath))
                .andExpect(status().isForbidden())
                .andReturn();
        assertThat(result.getResponse().getContentAsString()).contains("License Invalid");
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

    private static String getPath(Class<?> controller, String leaf) {
        String base = basePath(controller);
        for (Method m : controller.getDeclaredMethods()) {
            GetMapping a = AnnotatedElementUtils.findMergedAnnotation(m, GetMapping.class);
            if (a != null) {
                for (String p : a.value()) {
                    if (leaf.equals(p)) {
                        return base + p;
                    }
                }
            }
        }
        throw new AssertionError("未在 " + controller.getSimpleName() + " 找到 GET " + leaf);
    }

    // ---- 最小 MVC 切片 ----

    /**
     * 最小 MVC 切片：失效 License（validateLicense 恒 false）+ 真实
     * LicenseConfig/LicenseInterceptor 注册链 + 承接所有 /api/** 的探针端点。
     */
    @EnableWebMvc
    @Configuration
    static class Slice {

        @Bean
        LicenseService invalidLicenseService() {
            LicenseService svc = mock(LicenseService.class);
            when(svc.getLicenseInfo()).thenReturn(null);
            when(svc.validateLicense(any(LicenseInfo.class))).thenReturn(false);
            return svc;
        }

        @Bean
        LicenseInterceptor licenseInterceptor(LicenseService licenseService) {
            return new LicenseInterceptor(licenseService, true, false, new ObjectMapper());
        }

        @Bean
        LicenseConfig licenseConfig(LicenseInterceptor licenseInterceptor) {
            return new LicenseConfig(licenseInterceptor);
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
