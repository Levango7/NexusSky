package io.aerofleet.cloud.security;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.ResponseEntity;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.oauth2.jwt.Jwt;

import java.time.Duration;
import java.util.HashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * AuthController 认证端点单测（直接实例化，无 Spring 上下文 / MockMvc）。
 * <p>
 * 验证登录、令牌刷新的状态码与响应体，使用 MockHttpServletRequest 提供 request 信息。
 */
@DisplayName("AuthController 认证端点 (登录 / 刷新)")
class AuthControllerTest {

    private static final String SECRET = "this-is-a-test-secret-key-32bytes!";
    private static final String USERS_CONFIG = "admin:admin,operator:op123";
    private static final long EXPIRY_SECONDS = 3600L;

    private JwtTokenProvider tokenProvider;
    private PasswordEncoder passwordEncoder;
    private AuthController controller;

    @BeforeEach
    void setUp() {
        tokenProvider = new JwtTokenProvider(SECRET);
        passwordEncoder = new BCryptPasswordEncoder();
        controller = new AuthController(tokenProvider, passwordEncoder, USERS_CONFIG, EXPIRY_SECONDS, null);
    }

    /** 构造一个带 remoteAddr 的 MockHttpServletRequest。 */
    private static MockHttpServletRequest request() {
        MockHttpServletRequest req = new MockHttpServletRequest();
        req.setRemoteAddr("127.0.0.1");
        return req;
    }

    private static Map<String, String> body(String username, String password) {
        Map<String, String> b = new HashMap<>();
        if (username != null) b.put("username", username);
        if (password != null) b.put("password", password);
        return b;
    }

    // ===== /login =====

    @Test
    @DisplayName("login 正确凭据返回 200 + token + username")
    void login_success_returnsToken() {
        ResponseEntity<Map<String, Object>> resp =
                controller.login(body("admin", "admin"), request());

        assertThat(resp.getStatusCode().value()).isEqualTo(200);
        Map<String, Object> body = resp.getBody();
        assertThat(body).isNotNull();
        assertThat(body.get("token")).isNotNull();
        assertThat(body.get("token").toString()).isNotBlank();
        assertThat(body.get("expiresIn")).isEqualTo(EXPIRY_SECONDS);
        assertThat(body.get("username")).isEqualTo("admin");
    }

    @Test
    @DisplayName("login 错误密码返回 401 invalid credentials")
    void login_wrongPassword_returns401() {
        ResponseEntity<Map<String, Object>> resp =
                controller.login(body("admin", "wrong"), request());

        assertThat(resp.getStatusCode().value()).isEqualTo(401);
        assertThat(resp.getBody().get("error")).isEqualTo("invalid credentials");
    }

    @Test
    @DisplayName("login 不存在的用户返回 401 invalid credentials")
    void login_unknownUser_returns401() {
        ResponseEntity<Map<String, Object>> resp =
                controller.login(body("nobody", "whatever"), request());

        assertThat(resp.getStatusCode().value()).isEqualTo(401);
        assertThat(resp.getBody().get("error")).isEqualTo("invalid credentials");
    }

    @Test
    @DisplayName("login 缺 username/password 字段返回 400")
    void login_missingFields_returns400() {
        // 缺 password
        ResponseEntity<Map<String, Object>> resp1 =
                controller.login(body("admin", null), request());
        assertThat(resp1.getStatusCode().value()).isEqualTo(400);
        assertThat(resp1.getBody().get("error").toString()).contains("required");

        // 缺 username
        ResponseEntity<Map<String, Object>> resp2 =
                controller.login(body(null, "admin"), request());
        assertThat(resp2.getStatusCode().value()).isEqualTo(400);

        // 两者都缺
        ResponseEntity<Map<String, Object>> resp3 =
                controller.login(new HashMap<>(), request());
        assertThat(resp3.getStatusCode().value()).isEqualTo(400);
    }

    @Test
    @DisplayName("login 第二个用户 operator 正确凭据也能登录成功")
    void login_secondUser_success() {
        ResponseEntity<Map<String, Object>> resp =
                controller.login(body("operator", "op123"), request());

        assertThat(resp.getStatusCode().value()).isEqualTo(200);
        assertThat(resp.getBody().get("username")).isEqualTo("operator");
        assertThat(resp.getBody().get("token")).isNotNull();
    }

    // ===== /refresh =====

    @Test
    @DisplayName("refresh 有效 Bearer token 返回 200 + 新 token")
    void refresh_validToken_returnsNewToken() {
        String token = tokenProvider.generateToken("admin", Duration.ofSeconds(EXPIRY_SECONDS));
        ResponseEntity<Map<String, Object>> resp = controller.refresh("Bearer " + token);

        assertThat(resp.getStatusCode().value()).isEqualTo(200);
        Map<String, Object> body = resp.getBody();
        assertThat(body).isNotNull();
        assertThat(body.get("token")).isNotNull();
        assertThat(body.get("token").toString()).isNotBlank();
        assertThat(body.get("expiresIn")).isEqualTo(EXPIRY_SECONDS);
        // 新令牌应可验证
        assertThat(tokenProvider.validateToken(body.get("token").toString())).isTrue();
    }

    @Test
    @DisplayName("refresh 缺少 Authorization header 返回 401")
    void refresh_missingHeader_returns401() {
        ResponseEntity<Map<String, Object>> resp = controller.refresh(null);
        assertThat(resp.getStatusCode().value()).isEqualTo(401);
        assertThat(resp.getBody().get("error")).isNotNull();
    }

    @Test
    @DisplayName("refresh 无效 token 返回 401")
    void refresh_invalidToken_returns401() {
        ResponseEntity<Map<String, Object>> resp = controller.refresh("Bearer invalid.token.value");
        assertThat(resp.getStatusCode().value()).isEqualTo(401);
        assertThat(resp.getBody().get("error")).isNotNull();
    }

    @Test
    @DisplayName("refresh 非 Bearer 前缀的 header 返回 401")
    void refresh_nonBearerHeader_returns401() {
        ResponseEntity<Map<String, Object>> resp = controller.refresh("Basic sometoken");
        assertThat(resp.getStatusCode().value()).isEqualTo(401);
    }

    // ===== 内存用户角色（P0-2：RBAC 可用的前提）=====

    @Test
    @DisplayName("内存用户缺省角色 OPERATOR 并写入 token 的 role claim")
    void login_memoryUserDefaultsToOperator() {
        ResponseEntity<Map<String, Object>> resp = controller.login(body("operator", "op123"), request());
        assertThat(resp.getStatusCode().value()).isEqualTo(200);

        Jwt jwt = tokenProvider.getDecoder().decode(resp.getBody().get("token").toString());
        assertThat(jwt.getClaimAsString("role")).isEqualTo("OPERATOR");
    }

    @Test
    @DisplayName("users 配置第三段可显式指定 ADMIN 角色")
    void login_explicitRoleFromConfig() {
        AuthController rootController = new AuthController(tokenProvider, passwordEncoder,
                "root:rootpw:ADMIN", EXPIRY_SECONDS, null);

        ResponseEntity<Map<String, Object>> resp = rootController.login(body("root", "rootpw"), request());
        assertThat(resp.getStatusCode().value()).isEqualTo(200);

        Jwt jwt = tokenProvider.getDecoder().decode(resp.getBody().get("token").toString());
        assertThat(jwt.getClaimAsString("role")).isEqualTo("ADMIN");
    }

    @Test
    @DisplayName("口令含冒号且末段不是角色名时整段仍视为口令（不被当角色）")
    void login_passwordWithColonIsNotMistakenForRole() {
        AuthController colonController = new AuthController(tokenProvider, passwordEncoder,
                "svc:a:b", EXPIRY_SECONDS, null);

        assertThat(colonController.login(body("svc", "a:b"), request()).getStatusCode().value()).isEqualTo(200);
        assertThat(colonController.login(body("svc", "a"), request()).getStatusCode().value()).isEqualTo(401);
    }

    @Test
    @DisplayName("refresh 时用户已不在内存配置中返回 401（与 DB 模式行为一致）")
    void refresh_removedMemoryUser_returns401() {
        AuthController soloController = new AuthController(tokenProvider, passwordEncoder,
                "solo:solo:ADMIN", EXPIRY_SECONDS, null);
        String token = soloController.login(body("solo", "solo"), request())
                .getBody().get("token").toString();
        assertThat(soloController.refresh("Bearer " + token).getStatusCode().value()).isEqualTo(200);

        AuthController withoutSolo = new AuthController(tokenProvider, passwordEncoder,
                "other:other", EXPIRY_SECONDS, null);
        assertThat(withoutSolo.refresh("Bearer " + token).getStatusCode().value()).isEqualTo(401);
    }
}