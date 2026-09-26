package io.aerofleet.cloud.security;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * UserController 用户管理 CRUD 单测（直接实例化，无 Spring 上下文）。
 * <p>
 * 参考 AuthControllerTest 风格：mock Repository / PasswordEncoder / JwtDecoder，
 * 直接实例化 Controller，使用 MockHttpServletRequest 提供 request 信息。
 */
@DisplayName("UserController 用户管理 CRUD")
class UserControllerTest {

    private UserRepository userRepository;
    private PasswordEncoder passwordEncoder;
    private JwtDecoder jwtDecoder;
    private UserController controller;

    @BeforeEach
    void setUp() {
        userRepository = mock(UserRepository.class);
        passwordEncoder = mock(PasswordEncoder.class);
        jwtDecoder = mock(JwtDecoder.class);
        controller = new UserController(userRepository, passwordEncoder, jwtDecoder);
    }

    @AfterEach
    void tearDown() {
        TenantContext.clear();
    }

    // ===== listUsers =====

    @Test
    @DisplayName("listUsers tenantId 非空时只返回该租户用户")
    void listUsers_withTenantId_returnsTenantUsers() {
        TenantContext.setTenantId(1);
        UserEntity u1 = new UserEntity(1, "user1", "hash1", "ADMIN", 1, true, Instant.now());
        UserEntity u2 = new UserEntity(2, "user2", "hash2", "OPERATOR", 1, true, Instant.now());
        when(userRepository.findByTenantId(1)).thenReturn(List.of(u1, u2));

        ResponseEntity<List<Map<String, Object>>> resp = controller.listUsers();

        assertThat(resp.getStatusCode().value()).isEqualTo(200);
        assertThat(resp.getBody()).hasSize(2);
        assertThat(resp.getBody().get(0).get("username")).isEqualTo("user1");
        assertThat(resp.getBody().get(1).get("username")).isEqualTo("user2");
    }

    @Test
    @DisplayName("listUsers tenantId 为空时返回所有用户（全局管理员）")
    void listUsers_withoutTenantId_returnsAllUsers() {
        // TenantContext 未设置，getTenantId() 返回 null
        UserEntity u1 = new UserEntity(1, "admin", "hash1", "ADMIN", null, true, Instant.now());
        UserEntity u2 = new UserEntity(2, "user2", "hash2", "OPERATOR", 1, true, Instant.now());
        when(userRepository.findAll()).thenReturn(List.of(u1, u2));

        ResponseEntity<List<Map<String, Object>>> resp = controller.listUsers();

        assertThat(resp.getStatusCode().value()).isEqualTo(200);
        assertThat(resp.getBody()).hasSize(2);
    }

    @Test
    @DisplayName("listUsers 响应中不包含 passwordHash")
    void listUsers_noPasswordHashInResponse() {
        TenantContext.setTenantId(1);
        UserEntity u1 = new UserEntity(1, "user1", "secretHash", "ADMIN", 1, true, Instant.now());
        when(userRepository.findByTenantId(1)).thenReturn(List.of(u1));

        ResponseEntity<List<Map<String, Object>>> resp = controller.listUsers();

        assertThat(resp.getBody().get(0)).doesNotContainKey("passwordHash");
    }

    // ===== getUser =====

    @Test
    @DisplayName("getUser 存在的用户返回 200 + 详情")
    void getUser_found_returns200() {
        UserEntity user = new UserEntity(1, "user1", "hash", "ADMIN", 1, true, Instant.now());
        when(userRepository.findById(1)).thenReturn(Optional.of(user));

        ResponseEntity<?> resp = controller.getUser(1);

        assertThat(resp.getStatusCode().value()).isEqualTo(200);
        @SuppressWarnings("unchecked")
        Map<String, Object> body = (Map<String, Object>) resp.getBody();
        assertThat(body.get("username")).isEqualTo("user1");
        assertThat(body).doesNotContainKey("passwordHash");
    }

    @Test
    @DisplayName("getUser 不存在的用户返回 404")
    void getUser_notFound_returns404() {
        when(userRepository.findById(999)).thenReturn(Optional.empty());

        ResponseEntity<?> resp = controller.getUser(999);

        assertThat(resp.getStatusCode().value()).isEqualTo(404);
        @SuppressWarnings("unchecked")
        Map<String, Object> body = (Map<String, Object>) resp.getBody();
        assertThat(body.get("error")).isEqualTo("user not found");
    }

    // ===== createUser =====

    @Test
    @DisplayName("createUser 正常创建返回 201")
    void createUser_success_returns201() {
        UserRequest request = new UserRequest();
        request.setUsername("newuser");
        request.setPassword("password123");
        request.setRole("ADMIN");
        request.setTenantId(1);
        request.setEnabled(true);

        when(userRepository.existsByUsername("newuser")).thenReturn(false);
        when(passwordEncoder.encode("password123")).thenReturn("encodedHash");
        when(userRepository.save(any(UserEntity.class))).thenAnswer(invocation -> {
            UserEntity entity = invocation.getArgument(0);
            entity.setId(10);
            return entity;
        });

        ResponseEntity<?> resp = controller.createUser(request);

        assertThat(resp.getStatusCode().value()).isEqualTo(201);
        @SuppressWarnings("unchecked")
        Map<String, Object> body = (Map<String, Object>) resp.getBody();
        assertThat(body.get("username")).isEqualTo("newuser");
        assertThat(body.get("role")).isEqualTo("ADMIN");
        assertThat(body.get("id")).isEqualTo(10);
        assertThat(body).doesNotContainKey("passwordHash");
    }

    @Test
    @DisplayName("createUser username 重复返回 400")
    void createUser_duplicateUsername_returns400() {
        UserRequest request = new UserRequest();
        request.setUsername("existinguser");
        request.setPassword("password123");
        request.setRole("ADMIN");

        when(userRepository.existsByUsername("existinguser")).thenReturn(true);

        ResponseEntity<?> resp = controller.createUser(request);

        assertThat(resp.getStatusCode().value()).isEqualTo(400);
        @SuppressWarnings("unchecked")
        Map<String, Object> body = (Map<String, Object>) resp.getBody();
        assertThat(body.get("error")).isEqualTo("username already exists");
    }

    @Test
    @DisplayName("createUser 非法 role 返回 400")
    void createUser_invalidRole_returns400() {
        UserRequest request = new UserRequest();
        request.setUsername("newuser");
        request.setPassword("password123");
        request.setRole("SUPERUSER");

        when(userRepository.existsByUsername("newuser")).thenReturn(false);

        ResponseEntity<?> resp = controller.createUser(request);

        assertThat(resp.getStatusCode().value()).isEqualTo(400);
        @SuppressWarnings("unchecked")
        Map<String, Object> body = (Map<String, Object>) resp.getBody();
        assertThat(body.get("error").toString()).contains("invalid role");
    }

    @Test
    @DisplayName("createUser enabled 为 null 时默认为 true")
    void createUser_enabledNull_defaultsTrue() {
        UserRequest request = new UserRequest();
        request.setUsername("newuser");
        request.setPassword("password123");
        request.setRole("OPERATOR");
        request.setEnabled(null);

        when(userRepository.existsByUsername("newuser")).thenReturn(false);
        when(passwordEncoder.encode("password123")).thenReturn("encodedHash");
        when(userRepository.save(any(UserEntity.class))).thenAnswer(invocation -> {
            UserEntity entity = invocation.getArgument(0);
            entity.setId(10);
            return entity;
        });

        ResponseEntity<?> resp = controller.createUser(request);

        assertThat(resp.getStatusCode().value()).isEqualTo(201);
        @SuppressWarnings("unchecked")
        Map<String, Object> body = (Map<String, Object>) resp.getBody();
        assertThat(body.get("enabled")).isEqualTo(true);
    }

    // ===== updateUser =====

    @Test
    @DisplayName("updateUser 正常更新返回 200")
    void updateUser_success_returns200() {
        UserEntity existing = new UserEntity(1, "user1", "oldHash", "ADMIN", 1, true, Instant.now());
        UserRequest request = new UserRequest();
        request.setRole("OPERATOR");
        request.setEnabled(false);

        when(userRepository.findById(1)).thenReturn(Optional.of(existing));
        when(userRepository.save(any(UserEntity.class))).thenAnswer(invocation -> invocation.getArgument(0));

        ResponseEntity<?> resp = controller.updateUser(1, request);

        assertThat(resp.getStatusCode().value()).isEqualTo(200);
        @SuppressWarnings("unchecked")
        Map<String, Object> body = (Map<String, Object>) resp.getBody();
        assertThat(body.get("role")).isEqualTo("OPERATOR");
        assertThat(body.get("enabled")).isEqualTo(false);
    }

    @Test
    @DisplayName("updateUser 不存在的用户返回 404")
    void updateUser_notFound_returns404() {
        UserRequest request = new UserRequest();
        request.setRole("OPERATOR");

        when(userRepository.findById(999)).thenReturn(Optional.empty());

        ResponseEntity<?> resp = controller.updateUser(999, request);

        assertThat(resp.getStatusCode().value()).isEqualTo(404);
    }

    @Test
    @DisplayName("updateUser 包含 password 时更新密码哈希")
    void updateUser_withPassword_updatesHash() {
        UserEntity existing = new UserEntity(1, "user1", "oldHash", "ADMIN", 1, true, Instant.now());
        UserRequest request = new UserRequest();
        request.setPassword("newPassword");

        when(userRepository.findById(1)).thenReturn(Optional.of(existing));
        when(passwordEncoder.encode("newPassword")).thenReturn("newEncodedHash");
        when(userRepository.save(any(UserEntity.class))).thenAnswer(invocation -> invocation.getArgument(0));

        ResponseEntity<?> resp = controller.updateUser(1, request);

        assertThat(resp.getStatusCode().value()).isEqualTo(200);
        verify(passwordEncoder).encode("newPassword");
    }

    @Test
    @DisplayName("updateUser 不包含 password 时不修改密码")
    void updateUser_withoutPassword_keepsHash() {
        UserEntity existing = new UserEntity(1, "user1", "oldHash", "ADMIN", 1, true, Instant.now());
        UserRequest request = new UserRequest();
        request.setRole("OPERATOR");

        when(userRepository.findById(1)).thenReturn(Optional.of(existing));
        when(userRepository.save(any(UserEntity.class))).thenAnswer(invocation -> invocation.getArgument(0));

        controller.updateUser(1, request);

        verify(passwordEncoder, never()).encode(any());
    }

    @Test
    @DisplayName("updateUser 非法 role 返回 400")
    void updateUser_invalidRole_returns400() {
        UserEntity existing = new UserEntity(1, "user1", "oldHash", "ADMIN", 1, true, Instant.now());
        UserRequest request = new UserRequest();
        request.setRole("SUPERUSER");

        when(userRepository.findById(1)).thenReturn(Optional.of(existing));

        ResponseEntity<?> resp = controller.updateUser(1, request);

        assertThat(resp.getStatusCode().value()).isEqualTo(400);
        @SuppressWarnings("unchecked")
        Map<String, Object> body = (Map<String, Object>) resp.getBody();
        assertThat(body.get("error").toString()).contains("invalid role");
    }

    // ===== deleteUser =====

    @Test
    @DisplayName("deleteUser 正常删除返回 204")
    void deleteUser_success_returns204() {
        UserEntity user = new UserEntity(1, "user1", "hash", "ADMIN", 1, true, Instant.now());
        when(userRepository.findById(1)).thenReturn(Optional.of(user));

        MockHttpServletRequest request = new MockHttpServletRequest();
        // 无 Authorization header，extractUsername 返回 null，不会阻止删除

        ResponseEntity<?> resp = controller.deleteUser(1, request);

        assertThat(resp.getStatusCode().value()).isEqualTo(204);
        verify(userRepository).delete(user);
    }

    @Test
    @DisplayName("deleteUser 不存在的用户返回 404")
    void deleteUser_notFound_returns404() {
        when(userRepository.findById(999)).thenReturn(Optional.empty());

        ResponseEntity<?> resp = controller.deleteUser(999, new MockHttpServletRequest());

        assertThat(resp.getStatusCode().value()).isEqualTo(404);
    }

    @Test
    @DisplayName("deleteUser 删除自己拒绝返回 400")
    void deleteUser_self_returns400() {
        UserEntity user = new UserEntity(1, "currentUser", "hash", "ADMIN", 1, true, Instant.now());
        when(userRepository.findById(1)).thenReturn(Optional.of(user));

        // 构造带 Bearer token 的请求，JwtDecoder 返回 subject = "currentUser"
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader("Authorization", "Bearer fake.token");

        Jwt jwt = mock(Jwt.class);
        when(jwt.getSubject()).thenReturn("currentUser");
        when(jwtDecoder.decode("fake.token")).thenReturn(jwt);

        ResponseEntity<?> resp = controller.deleteUser(1, request);

        assertThat(resp.getStatusCode().value()).isEqualTo(400);
        @SuppressWarnings("unchecked")
        Map<String, Object> body = (Map<String, Object>) resp.getBody();
        assertThat(body.get("error")).isEqualTo("cannot delete yourself");
    }

    @Test
    @DisplayName("deleteUser 删除其他用户正常返回 204")
    void deleteUser_otherUser_returns204() {
        UserEntity user = new UserEntity(2, "otherUser", "hash", "OPERATOR", 1, true, Instant.now());
        when(userRepository.findById(2)).thenReturn(Optional.of(user));

        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader("Authorization", "Bearer fake.token");

        Jwt jwt = mock(Jwt.class);
        when(jwt.getSubject()).thenReturn("currentUser");
        when(jwtDecoder.decode("fake.token")).thenReturn(jwt);

        ResponseEntity<?> resp = controller.deleteUser(2, request);

        assertThat(resp.getStatusCode().value()).isEqualTo(204);
    }
}