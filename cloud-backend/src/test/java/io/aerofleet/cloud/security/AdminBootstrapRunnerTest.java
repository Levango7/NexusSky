package io.aerofleet.cloud.security;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * {@link AdminBootstrapRunner} 单元测试（P0-2 首次管理员引导）。
 * <p>
 * 关注点：未配置即完全不介入（CI/dev 不受影响）、配置后写对角色与启用位、
 * 已存在用户走重置而非新建、弱口令拒绝。
 */
@DisplayName("AdminBootstrapRunner 管理员引导 (P0-2)")
class AdminBootstrapRunnerTest {

    private final PasswordEncoder encoder = new BCryptPasswordEncoder();

    private AdminBootstrapRunner runner(UserRepository repository, String password) {
        return new AdminBootstrapRunner(repository, encoder, password, "admin", 1);
    }

    @Test
    @DisplayName("未配置口令时不查库不写库")
    void skippedWhenPasswordAbsent() {
        UserRepository repository = mock(UserRepository.class);

        runner(repository, "").run(null);

        verify(repository, never()).findByUsername(org.mockito.ArgumentMatchers.anyString());
        verify(repository, never()).save(any());
    }

    @Test
    @DisplayName("无 UserRepository（内存模式）时跳过而不抛错")
    void skippedWithoutRepository() {
        runner(null, "bootstrap-pw").run(null);
    }

    @Test
    @DisplayName("配置口令后创建 ADMIN 且启用，哈希可被校验")
    void createsAdminWhenConfigured() {
        UserRepository repository = mock(UserRepository.class);
        when(repository.findByUsername("admin")).thenReturn(Optional.empty());
        org.mockito.ArgumentCaptor<UserEntity> captor = org.mockito.ArgumentCaptor.forClass(UserEntity.class);

        runner(repository, "bootstrap-pw").run(null);

        verify(repository).save(captor.capture());
        UserEntity created = captor.getValue();
        assertThat(created.getUsername()).isEqualTo("admin");
        assertThat(created.getRole()).isEqualTo("ADMIN");
        assertThat(created.getTenantId()).isEqualTo(1);
        assertThat(created.isEnabled()).isTrue();
        assertThat(created.getCreatedAt()).isNotNull();
        assertThat(encoder.matches("bootstrap-pw", created.getPasswordHash())).isTrue();
    }

    @Test
    @DisplayName("已存在同名用户时重置口令/角色/启用位，不新建第二行")
    void resetsExistingUser() {
        UserRepository repository = mock(UserRepository.class);
        UserEntity existing = new UserEntity();
        existing.setId(5);
        existing.setUsername("admin");
        existing.setPasswordHash("old-hash");
        existing.setRole(Role.OBSERVER.name());
        existing.setEnabled(false);
        when(repository.findByUsername("admin")).thenReturn(Optional.of(existing));

        runner(repository, "bootstrap-pw").run(null);

        assertThat(existing.getRole()).isEqualTo("ADMIN");
        assertThat(existing.isEnabled()).isTrue();
        assertThat(encoder.matches("bootstrap-pw", existing.getPasswordHash())).isTrue();
        verify(repository).save(existing);
    }

    @Test
    @DisplayName("口令短于 8 位时拒绝引导")
    void rejectsWeakPassword() {
        UserRepository repository = mock(UserRepository.class);

        runner(repository, "short").run(null);

        verify(repository, never()).save(any());
    }
}
