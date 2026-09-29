package io.aerofleet.cloud.security;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.util.Map;

import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * {@link RoleInterceptor} 单元测试（P0-2）。
 * <p>
 * 此前 RBAC 在 src/test 里零覆盖：既没有角色断言，也没有拦截器用例。
 * 本测试用 standaloneSetup 手工装配拦截器（test profile 的 dev-mode 会整体短路，
 * 故不能靠 @SpringBootTest 覆盖），逐条验证开关、注解查找顺序与角色来源。
 */
@DisplayName("RoleInterceptor RBAC 拦截 (P0-2)")
class RoleInterceptorTest {

    private static final String METHOD_OK = "ok";

    private JwtDecoder jwtDecoder;

    @AfterEach
    void clearApiKeyContext() {
        ApiKeyContext.clear();
    }

    /** 无类级注解的控制器：用于方法级与未标注端点。 */
    @RestController
    @RequestMapping("/rbac")
    static class MethodAnnotatedController {

        @GetMapping("/open")
        public String open() {
            return METHOD_OK;
        }

        @GetMapping("/operator")
        @RequireRole(Role.OPERATOR)
        public String operator() {
            return METHOD_OK;
        }

        @GetMapping("/admin")
        @RequireRole(Role.ADMIN)
        public String admin() {
            return METHOD_OK;
        }
    }

    /** 类级 ADMIN，方法级可放宽。 */
    @RestController
    @RequestMapping("/rbac/class")
    @RequireRole(Role.ADMIN)
    static class ClassAnnotatedController {

        @GetMapping("/inherited")
        public String inherited() {
            return METHOD_OK;
        }

        @GetMapping("/loosened")
        @RequireRole(Role.OBSERVER)
        public String loosened() {
            return METHOD_OK;
        }
    }

    private MockMvc mockMvc(boolean devMode, boolean rbacEnabled) {
        jwtDecoder = mock(JwtDecoder.class);
        when(jwtDecoder.decode(anyString())).thenAnswer(invocation -> {
            String token = invocation.getArgument(0);
            Map<String, Object> claims = switch (token) {
                case "tok-admin" -> Map.of("role", "ADMIN");
                case "tok-operator" -> Map.of("role", "OPERATOR");
                case "tok-observer" -> Map.of("role", "OBSERVER");
                case "tok-bogus" -> Map.of("role", "SUPERUSER");
                default -> Map.of("sub", "tester");   // 有 JWT 但无 role claim
            };
            return new Jwt(token, Instant.now(), Instant.now().plusSeconds(300), Map.of("typ", "JWT"), claims);
        });
        RoleInterceptor interceptor = new RoleInterceptor(jwtDecoder, devMode, rbacEnabled);
        return MockMvcBuilders.standaloneSetup(new MethodAnnotatedController(), new ClassAnnotatedController())
                .addInterceptors(interceptor)
                .build();
    }

    @Test
    @DisplayName("未标注注解的端点不受 RBAC 影响")
    void unannotatedEndpointPasses() throws Exception {
        mockMvc(false, true).perform(get("/rbac/open"))
                .andExpect(status().isOk())
                .andExpect(content().string(METHOD_OK));
    }

    @Test
    @DisplayName("rbac-enabled=false 时整体放行（向后兼容）")
    void disabledRbacPassesEverything() throws Exception {
        mockMvc(false, false).perform(get("/rbac/admin"))
                .andExpect(status().isOk());
    }

    @Test
    @DisplayName("dev-mode=true 时整体放行，即便 RBAC 开启")
    void devModePassesEverything() throws Exception {
        mockMvc(true, true).perform(get("/rbac/admin"))
                .andExpect(status().isOk());
    }

    @Test
    @DisplayName("无凭证访问受保护端点返回 403 与错误体")
    void missingCredentialsForbidden() throws Exception {
        mockMvc(false, true).perform(get("/rbac/operator"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error").value("forbidden: requires role OPERATOR"));
    }

    @Test
    @DisplayName("JWT role claim 满足最小角色即放行，层级向上兼容")
    void jwtRoleSatisfiesHierarchy() throws Exception {
        MockMvc mvc = mockMvc(false, true);
        mvc.perform(get("/rbac/operator").header("Authorization", "Bearer tok-operator"))
                .andExpect(status().isOk());
        // ADMIN(0) <= OPERATOR(1)：更高角色可访问更低要求
        mvc.perform(get("/rbac/operator").header("Authorization", "Bearer tok-admin"))
                .andExpect(status().isOk());
        // OBSERVER 不足 OPERATOR
        mvc.perform(get("/rbac/operator").header("Authorization", "Bearer tok-observer"))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("JWT 有但无 role claim 视为无角色，403")
    void jwtWithoutRoleClaimForbidden() throws Exception {
        mockMvc(false, true).perform(get("/rbac/operator").header("Authorization", "Bearer tok-norole"))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("非法 role claim 字符串无法解析为枚举，403")
    void invalidRoleClaimForbidden() throws Exception {
        mockMvc(false, true).perform(get("/rbac/admin").header("Authorization", "Bearer tok-bogus"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error").value("forbidden: requires role ADMIN"));
    }

    @Test
    @DisplayName("类级 @RequireRole 生效，方法级可放宽该类要求")
    void classLevelAnnotationAndMethodOverride() throws Exception {
        MockMvc mvc = mockMvc(false, true);
        mvc.perform(get("/rbac/class/inherited").header("Authorization", "Bearer tok-operator"))
                .andExpect(status().isForbidden());
        mvc.perform(get("/rbac/class/inherited").header("Authorization", "Bearer tok-admin"))
                .andExpect(status().isOk());
        // 方法级 OBSERVER 放宽类级 ADMIN
        mvc.perform(get("/rbac/class/loosened").header("Authorization", "Bearer tok-observer"))
                .andExpect(status().isOk());
    }

    @Test
    @DisplayName("X-API-Key 路径（SDK 唯一认证方式）用 Key 上的角色判定")
    void apiKeyRoleIsUsedWhenNoJwt() throws Exception {
        ApiKeyContext.set("nsk_test", 1, "[\"read\",\"write\"]", "OPERATOR");

        mockMvc(false, true).perform(get("/rbac/operator"))
                .andExpect(status().isOk());

        ApiKeyContext.clear();
        ApiKeyContext.set("nsk_test", 1, "[\"read\"]", "OBSERVER");
        mockMvc(false, true).perform(get("/rbac/admin"))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("API Key 未记录角色（历史 Key）时受保护端点拒绝，不放行")
    void apiKeyWithoutRoleFailsClosed() throws Exception {
        ApiKeyContext.set("nsk_legacy", 1, "[]", null);

        mockMvc(false, true).perform(get("/rbac/operator"))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("JWT 优先于 API Key 角色（取较高可信来源）")
    void jwtTakesPrecedenceOverApiKey() throws Exception {
        ApiKeyContext.set("nsk_legacy", 1, "[]", "OBSERVER");

        mockMvc(false, true).perform(get("/rbac/admin")
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("Authorization", "Bearer tok-admin"))
                .andExpect(status().isOk());
    }
}
