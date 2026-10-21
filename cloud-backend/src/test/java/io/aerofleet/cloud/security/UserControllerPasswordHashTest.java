package io.aerofleet.cloud.security;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.oauth2.jwt.JwtDecoder;

import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 建号这条路的**口令落库形态**测试。
 *
 * <p><b>为什么单独一个类</b>：2026-10-21 用 PIT 变异测试审 security 包时，
 * {@code UserController.createUser} 里这一行
 * {@code user.setPasswordHash(passwordEncoder.encode(request.getPassword()));}
 * 被变异成"不调用"之后<b>存活</b> —— 也就是说：如果哪天这行被误删、
 * 或者编码器被换成一个什么都不做的东西，**明文口令会被直接写进 users 表**，
 * 而现有测试一条都不会红（它们只查状态码和响应体）。
 *
 * <p>这里用**真的** {@link BCryptPasswordEncoder}（不是 mock）—— 用 mock 的话
 * "encode 被调用了"只能证明调用发生，证明不了"存下来的是哈希不是明文"。
 */
@DisplayName("UserController 建号：口令必须哈希后落库")
class UserControllerPasswordHashTest {

    private static final String PLAIN = "sup3r-secret-pass";

    private UserRepository userRepository;
    private PasswordEncoder passwordEncoder;
    private UserController controller;

    @BeforeEach
    void setUp() {
        userRepository = mock(UserRepository.class);
        passwordEncoder = new BCryptPasswordEncoder();
        controller = new UserController(userRepository, passwordEncoder, mock(JwtDecoder.class));
        when(userRepository.existsByUsername(any())).thenReturn(false);
        when(userRepository.save(any(UserEntity.class))).thenAnswer(inv -> inv.getArgument(0));
    }

    @AfterEach
    void tearDown() {
        TenantContext.clear();
    }

    private static UserRequest request() {
        UserRequest req = new UserRequest();
        req.setUsername("newuser");
        req.setPassword(PLAIN);
        req.setRole("ADMIN");
        return req;
    }

    @Test
    @DisplayName("显式 enabled=false 时，落库必须是禁用态")
    void createUser_explicitlyDisabled_storesDisabled() {
        UserRequest req = request();
        req.setEnabled(false);

        ResponseEntity<?> resp = controller.createUser(req);
        assertThat(resp.getStatusCode().value()).isEqualTo(201);

        ArgumentCaptor<UserEntity> captor = ArgumentCaptor.forClass(UserEntity.class);
        verify(userRepository).save(captor.capture());

        assertThat(captor.getValue().isEnabled())
                .as("显式禁用必须落库成禁用（删掉 setEnabled 调用就会变成默认值）")
                .isFalse();
    }

    @Test
    @DisplayName("落库的口令是哈希，不是明文")
    void createUser_storesHashNotPlaintext() {
        ResponseEntity<?> resp = controller.createUser(request());
        assertThat(resp.getStatusCode().value()).isEqualTo(201);

        ArgumentCaptor<UserEntity> captor = ArgumentCaptor.forClass(UserEntity.class);
        verify(userRepository).save(captor.capture());
        UserEntity saved = captor.getValue();
        String stored = saved.getPasswordHash();

        assertThat(stored)
                .as("口令必须编码后落库（删掉 encode 调用就会存明文）")
                .isNotNull()
                .isNotBlank()
                .isNotEqualTo(PLAIN)
                .doesNotContain(PLAIN);
        assertThat(passwordEncoder.matches(PLAIN, stored))
                .as("存下来的必须能被同一个编码器验回明文")
                .isTrue();
    }

    @Test
    @DisplayName("建号时其余字段也要落库（租户/启用状态/创建时间）")
    void createUser_persistsAllFields() {
        UserRequest req = request();
        req.setTenantId(42);

        ResponseEntity<?> resp = controller.createUser(req);
        assertThat(resp.getStatusCode().value()).isEqualTo(201);

        ArgumentCaptor<UserEntity> captor = ArgumentCaptor.forClass(UserEntity.class);
        verify(userRepository).save(captor.capture());
        UserEntity saved = captor.getValue();

        assertThat(saved.getTenantId())
                .as("租户必须落库（删掉 setTenantId 就会丢归属）")
                .isEqualTo(42);
        assertThat(saved.isEnabled())
                .as("未显式指定时默认启用")
                .isTrue();
        assertThat(saved.getCreatedAt())
                .as("创建时间必须落库")
                .isNotNull();
        assertThat(saved.getRole()).isEqualTo("ADMIN");
    }

    @Test
    @DisplayName("响应体不含口令，也不含口令哈希")
    void createUser_responseDoesNotLeakPasswordOrHash() {
        ResponseEntity<?> resp = controller.createUser(request());
        assertThat(resp.getStatusCode().value()).isEqualTo(201);

        String body = String.valueOf(resp.getBody());
        assertThat(body).doesNotContain(PLAIN);
        assertThat(body.toLowerCase()).doesNotContain("passwordhash");
        assertThat(body).doesNotContain("$2a$").doesNotContain("$2b$").doesNotContain("$2y$");
    }

    @Test
    @DisplayName("口令哈希每次建号都不同（同一个口令不产生同一行哈希）")
    void createUser_hashIsSaltedPerUser() {
        controller.createUser(request());
        ArgumentCaptor<UserEntity> first = ArgumentCaptor.forClass(UserEntity.class);
        verify(userRepository).save(first.capture());

        UserRequest second = request();
        second.setUsername("another");
        controller.createUser(second);
        ArgumentCaptor<UserEntity> secondCaptor = ArgumentCaptor.forClass(UserEntity.class);
        verify(userRepository, org.mockito.Mockito.times(2)).save(secondCaptor.capture());

        assertThat(first.getValue().getPasswordHash())
                .as("BCrypt 带盐：同口令两次建号的哈希必须不同")
                .isNotEqualTo(secondCaptor.getValue().getPasswordHash());
    }

    @Test
    @DisplayName("重复用户名被拒（不落库）")
    void createUser_duplicateUsername_rejected() {
        when(userRepository.existsByUsername("newuser")).thenReturn(true);

        ResponseEntity<?> resp = controller.createUser(request());

        assertThat(resp.getStatusCode().value()).isEqualTo(400);
        verify(userRepository, org.mockito.Mockito.never()).save(any(UserEntity.class));
        assertThat(Optional.ofNullable(resp.getBody()).map(Object::toString).orElse(""))
                .contains("username already exists");
    }
}
