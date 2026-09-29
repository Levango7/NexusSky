package io.aerofleet.cloud.security;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;

import java.time.Instant;

/**
 * 首次管理员引导（bootstrap）。
 * <p>
 * 背景：V6 迁移种子的 admin 口令哈希经 bcrypt 复算无法被任何常见口令命中（注释却写「密码 admin」），
 * 而创建用户的 {@code POST /api/v1/users} 本身要求 ADMIN（{@code UserController:114}）——
 * 一旦 RBAC 真正启用，系统里没有任何账号能获得第一个管理员。
 * <p>
 * 因此不在仓库/迁移里内置默认口令：仅当部署方显式提供
 * {@code aerofleet.security.bootstrap-admin-password} 时，才在启动阶段创建或重置该 ADMIN 账号。
 * 用完应立刻清除该环境变量并改密。未配置时本组件完全不介入（含 CI 的 dev profile）。
 */
@Component
public class AdminBootstrapRunner implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(AdminBootstrapRunner.class);

    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;
    private final String password;
    private final String username;
    private final Integer tenantId;

    public AdminBootstrapRunner(
            @Autowired(required = false) UserRepository userRepository,
            PasswordEncoder passwordEncoder,
            @Value("${aerofleet.security.bootstrap-admin-password:}") String password,
            @Value("${aerofleet.security.bootstrap-admin-username:admin}") String username,
            @Value("${aerofleet.security.bootstrap-admin-tenant-id:1}") Integer tenantId) {
        this.userRepository = userRepository;
        this.passwordEncoder = passwordEncoder;
        this.password = password;
        this.username = username;
        this.tenantId = tenantId;
    }

    @Override
    public void run(ApplicationArguments args) {
        if (password == null || password.isBlank()) {
            return;
        }
        if (userRepository == null) {
            log.warn("已配置 bootstrap-admin-password 但 UserRepository 不可用（无数据源？），跳过管理员引导");
            return;
        }
        if (password.length() < 8) {
            log.warn("bootstrap-admin-password 长度不足 8，拒绝引导 ADMIN（避免弱口令出厂）");
            return;
        }

        UserEntity user = userRepository.findByUsername(username).orElseGet(UserEntity::new);
        user.setUsername(username);
        user.setPasswordHash(passwordEncoder.encode(password));
        user.setRole(Role.ADMIN.name());
        user.setTenantId(tenantId);
        user.setEnabled(true);
        if (user.getCreatedAt() == null) {
            user.setCreatedAt(Instant.now());
        }
        userRepository.save(user);

        log.warn("已通过 bootstrap-admin-password 引导 ADMIN 用户: username={} tenantId={}；"
                + "请登录改密后立刻移除该环境变量", username, tenantId);
    }
}
