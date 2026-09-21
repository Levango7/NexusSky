package io.aerofleet.cloud.security;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

/**
 * {@link UserEntity} 的 JPA Repository。
 * <p>
 * 提供按用户名查找、按租户查找、用户名存在性检查等查询方法。
 */
public interface UserRepository extends JpaRepository<UserEntity, Integer> {

    /**
     * 按用户名查找用户。
     *
     * @param username 用户名
     * @return 用户实体（可能为空）
     */
    Optional<UserEntity> findByUsername(String username);

    /**
     * 查找指定租户下的所有用户。
     *
     * @param tenantId 租户 ID
     * @return 用户实体列表
     */
    List<UserEntity> findByTenantId(Integer tenantId);

    /**
     * 检查用户名是否已存在。
     *
     * @param username 用户名
     * @return true 表示已存在
     */
    boolean existsByUsername(String username);
}