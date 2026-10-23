package io.aerofleet.cloud.security;

import jakarta.servlet.http.HttpServletResponse;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.method.HandlerMethod;

import java.time.Instant;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
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
 * 故不能靠 @SpringBootTest 覆盖），逐条验证开关、注解查找顺序、白名单与角色来源。
 * <p>
 * 2026-10-01 起拦截器改为默认拒绝，其中一条用例（"未标注注解的端点不受 RBAC 影响"）
 * 断言方向被翻转——旧用例钉的正是那个"漏写注解即静默无鉴权"的缺陷本身。
 */
@DisplayName("RoleInterceptor RBAC 拦截 (P0-2)")
class RoleInterceptorTest {

    private static final String METHOD_OK = "ok";

    private JwtDecoder jwtDecoder;

    @AfterEach
    void clearApiKeyContext() {
        ApiKeyContext.clear();
        SecurityContextHolder.clearContext();
        TenantContext.clear();
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

        /** 显式白名单：已认证的任意角色可用，无需角色匹配。 */
        @GetMapping("/public")
        @PermitAll
        public String permitAll() {
            return METHOD_OK;
        }

        /** 同一元素上两种声明并存：按"收紧优先"，@RequireRole 应胜出。 */
        @GetMapping("/both")
        @PermitAll
        @RequireRole(Role.ADMIN)
        public String bothDeclarations() {
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

        /** 方法级 @PermitAll 覆盖类级 @RequireRole（登录类端点在 ADMIN 控制器里的形状）。 */
        @GetMapping("/opened")
        @PermitAll
        public String opened() {
            return METHOD_OK;
        }
    }

    /** 整个控制器都在白名单里：类级 @PermitAll 覆盖所有未声明方法。 */
    @RestController
    @RequestMapping("/rbac/anonymous")
    @PermitAll
    static class PublicController {

        @GetMapping("/anything")
        public String anything() {
            return METHOD_OK;
        }

        /** 类级白名单里的单个方法仍可收紧。 */
        @GetMapping("/guarded")
        @RequireRole(Role.OPERATOR)
        public String guarded() {
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
        return MockMvcBuilders.standaloneSetup(new MethodAnnotatedController(), new ClassAnnotatedController(),
                        new PublicController())
                .addInterceptors(interceptor)
                .build();
    }

    @Test
    @DisplayName("未标注声明的端点默认拒绝（fail-closed）")
    void unannotatedEndpointIsRejected() throws Exception {
        // 这条断言在 2026-10-01 翻了方向：此前"无注解=放行"，等于漏写注解就静默无鉴权。
        mockMvc(false, true).perform(get("/rbac/open"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error").value("forbidden: endpoint has no role declaration"));
    }

    @Test
    @DisplayName("@PermitAll 端点无需角色即可访问")
    void permitAllEndpointPassesWithoutRole() throws Exception {
        MockMvc mvc = mockMvc(false, true);
        mvc.perform(get("/rbac/public"))
                .andExpect(status().isOk())
                .andExpect(content().string(METHOD_OK));
        // 带低角色也照样放行：白名单只看"是否声明公开"，不看角色
        mvc.perform(get("/rbac/public").header("Authorization", "Bearer tok-observer"))
                .andExpect(status().isOk());
    }

    @Test
    @DisplayName("类级 @PermitAll 覆盖整个控制器，方法级仍可单独收紧")
    void classLevelPermitAllWithMethodTightening() throws Exception {
        MockMvc mvc = mockMvc(false, true);
        mvc.perform(get("/rbac/anonymous/anything"))
                .andExpect(status().isOk());
        mvc.perform(get("/rbac/anonymous/guarded"))
                .andExpect(status().isForbidden());
        mvc.perform(get("/rbac/anonymous/guarded").header("Authorization", "Bearer tok-operator"))
                .andExpect(status().isOk());
    }

    @Test
    @DisplayName("方法级 @PermitAll 覆盖类级 @RequireRole")
    void methodLevelPermitAllOverridesClassRole() throws Exception {
        mockMvc(false, true).perform(get("/rbac/class/opened"))
                .andExpect(status().isOk());
        // 同一控制器里未放宽的方法仍受类级 ADMIN 约束（证明覆盖是逐方法的）
        mockMvc(false, true).perform(get("/rbac/class/inherited"))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("同一元素两种声明并存时 @RequireRole 胜出（收紧优先）")
    void requireRoleWinsOverPermitAllOnSameMethod() throws Exception {
        mockMvc(false, true).perform(get("/rbac/both").header("Authorization", "Bearer tok-operator"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error").value("forbidden: requires role ADMIN"));
        mockMvc(false, true).perform(get("/rbac/both").header("Authorization", "Bearer tok-admin"))
                .andExpect(status().isOk());
    }

    @Test
    @DisplayName("fail-closed 只在 RBAC 开启时生效：rbac-enabled=false 与 dev-mode 仍整体放行")
    void unannotatedStillPassesWhenRbacOffOrDevMode() throws Exception {
        // 补 content 断言：拦截器 return false 中断链时 MockMvc 状态码仍是默认 200，
        // 只有"处理器真的跑了"（回到响应体）才能从 HTTP 面分辨放行与中断。
        mockMvc(false, false).perform(get("/rbac/open"))
                .andExpect(status().isOk())
                .andExpect(content().string(METHOD_OK));
        mockMvc(true, true).perform(get("/rbac/open"))
                .andExpect(status().isOk())
                .andExpect(content().string(METHOD_OK));
    }

    @Test
    @DisplayName("rbac-enabled=false 时整体放行（向后兼容）")
    void disabledRbacPassesEverything() throws Exception {
        mockMvc(false, false).perform(get("/rbac/admin"))
                .andExpect(status().isOk())
                .andExpect(content().string(METHOD_OK));
    }

    @Test
    @DisplayName("dev-mode=true 时整体放行，即便 RBAC 开启")
    void devModePassesEverything() throws Exception {
        mockMvc(true, true).perform(get("/rbac/admin"))
                .andExpect(status().isOk())
                .andExpect(content().string(METHOD_OK));
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

    // ===== 第三角色来源：SSE 流令牌（StreamTokenAuthenticationToken in SecurityContext）=====

    @Test
    @DisplayName("流令牌角色在 JWT / API Key 皆缺席时生效（SSE 端点的真实形态）")
    void streamTokenRoleIsUsedWhenNoOtherSource() throws Exception {
        SecurityContextHolder.getContext()
                .setAuthentication(new StreamTokenAuthenticationToken("op-1", "OPERATOR", 7));

        MockMvc mvc = mockMvc(false, true);
        mvc.perform(get("/rbac/operator"))
                .andExpect(status().isOk());
        mvc.perform(get("/rbac/admin"))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("流令牌绑定的角色不放大：OBSERVER 令牌进不了 OPERATOR 端点")
    void streamTokenDoesNotEscalateBoundRole() throws Exception {
        SecurityContextHolder.getContext()
                .setAuthentication(new StreamTokenAuthenticationToken("obs-1", "OBSERVER", null));

        mockMvc(false, true).perform(get("/rbac/operator"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error").value("forbidden: requires role OPERATOR"));
    }

    @Test
    @DisplayName("JWT 仍优先于流令牌角色（头凭证在场时流令牌不参与判定）")
    void jwtTakesPrecedenceOverStreamToken() throws Exception {
        SecurityContextHolder.getContext()
                .setAuthentication(new StreamTokenAuthenticationToken("obs-1", "OBSERVER", null));

        mockMvc(false, true).perform(get("/rbac/admin")
                        .header("Authorization", "Bearer tok-admin"))
                .andExpect(status().isOk());
    }

    @Test
    @DisplayName("API Key 角色优先于流令牌角色（来源顺序：JWT → API Key → 流令牌）")
    void apiKeyTakesPrecedenceOverStreamToken() throws Exception {
        SecurityContextHolder.getContext()
                .setAuthentication(new StreamTokenAuthenticationToken("obs-1", "OBSERVER", null));
        ApiKeyContext.set("nsk_test", 1, "[\"read\"]", "OPERATOR");

        mockMvc(false, true).perform(get("/rbac/operator"))
                .andExpect(status().isOk());
    }

    @Test
    @DisplayName("流令牌未绑定角色（null）时受保护端点 fail-closed")
    void streamTokenWithoutRoleFailsClosed() throws Exception {
        SecurityContextHolder.getContext()
                .setAuthentication(new StreamTokenAuthenticationToken("legacy-1", null, null));

        mockMvc(false, true).perform(get("/rbac/operator"))
                .andExpect(status().isForbidden());
    }

    // ===== 变异收口：preHandle 的**返回值**本身（MockMvc 面看不见，必须直调） =====
    //
    // 2026-10-23 A/B 实测（mutate-verify.py，RoleInterceptorTest 全体）：
    // dev-mode 跳过分支 `return true` 翻成 false、默认拒绝/无角色分支
    // `return false` 翻成 true——**三条全部存活**。机理：拦截器拒绝时
    // writeForbidden 先把 403+错误体写定（响应随即 committed），MockMvc 最终
    // 拿到的响应与"放行后处理器改写"无法区分，HTTP 面断言对 boolean 翻转全部失明。
    // 唯一可靠观测面 = 直调 preHandle 读返回值与响应状态/头。

    private MockHttpServletRequest freshRequest() {
        return new MockHttpServletRequest("GET", "/rbac/open");
    }

    private static HandlerMethod handler(String method) throws Exception {
        return new HandlerMethod(new MethodAnnotatedController(),
                MethodAnnotatedController.class.getMethod(method));
    }

    @Test
    @DisplayName("直调：dev-mode=true 时 preHandle 返回 true（返回值本身，不是状态码）")
    void devModeReturnsTrueFromPreHandle() throws Exception {
        RoleInterceptor interceptor = new RoleInterceptor(mock(JwtDecoder.class), true, true);

        assertThat(interceptor.preHandle(freshRequest(), new MockHttpServletResponse(), handler("admin")))
                .as("dev-mode 跳过 RBAC：preHandle 必须返回 true（MockMvc 只验 200，翻成 false 它照样绿）")
                .isTrue();
    }

    @Test
    @DisplayName("直调：未标注端点默认拒绝——preHandle 返回 false 且写 403（fail-closed 不许翻成放行）")
    void unannotatedEndpointReturnsFalseFromPreHandle() throws Exception {
        RoleInterceptor interceptor = new RoleInterceptor(mock(JwtDecoder.class), false, true);
        MockHttpServletResponse response = new MockHttpServletResponse();

        assertThat(interceptor.preHandle(freshRequest(), response, handler("open")))
                .as("漏声明端点必须拒绝：return false 翻成 true 时 MockMvc 全绿（响应已被 403 写定），直调才能抓住")
                .isFalse();
        assertThat(response.getStatus()).isEqualTo(HttpServletResponse.SC_FORBIDDEN);
        assertThat(response.getContentAsString())
                .contains("forbidden: endpoint has no role declaration");
    }

    @Test
    @DisplayName("直调：无角色凭据访问受护端点——返回 false 写 403 且响应头是 JSON（翻成 true = 匿名通行证）")
    void missingRoleReturnsFalseFromPreHandle() throws Exception {
        RoleInterceptor interceptor = new RoleInterceptor(mock(JwtDecoder.class), false, true);
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/rbac/operator");
        // 无 Authorization 头、无 API Key 上下文、无流令牌 ⇒ 三种角色来源全空
        MockHttpServletResponse response = new MockHttpServletResponse();

        assertThat(interceptor.preHandle(request, response, handler("operator")))
                .as("无任何角色来源的调用方必须被拒绝：return false 翻成 true = 匿名获得角色通行证")
                .isFalse();
        assertThat(response.getStatus()).isEqualTo(HttpServletResponse.SC_FORBIDDEN);
        assertThat(response.getContentType())
                .as("拒绝响应必须声明 JSON content-type（setContentType 删掉后客户端按 text/html 解析错误体）")
                .isEqualTo("application/json;charset=UTF-8");
        assertThat(response.getContentAsString()).contains("forbidden: requires role OPERATOR");
    }

    @Test
    @DisplayName("直调：角色满足时 preHandle 返回 true（对照组，证明上面三个 false 不是恒 false）")
    void satisfyingRoleReturnsTrueFromPreHandle() throws Exception {
        JwtDecoder decoder = mock(JwtDecoder.class);
        when(decoder.decode("tok-admin"))
                .thenReturn(new Jwt("tok-admin", Instant.now(), Instant.now().plusSeconds(300),
                        Map.of("typ", "JWT"), Map.of("role", "ADMIN")));
        RoleInterceptor interceptor = new RoleInterceptor(decoder, false, true);
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/rbac/admin");
        request.addHeader("Authorization", "Bearer tok-admin");

        assertThat(interceptor.preHandle(request, new MockHttpServletResponse(), handler("admin")))
                .isTrue();
    }
}
