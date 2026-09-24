package io.aerofleet.cloud.mission.spray;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

/**
 * SprayTask 的 JPA Repository。
 * <p>
 * 支持按租户 ID 和 sysid+status 查询，用于租户隔离和任务筛选。
 */
@Repository
public interface SprayTaskRepository extends JpaRepository<SprayTaskEntity, Integer> {

    /** 按租户 ID 查询所有喷洒任务。 */
    List<SprayTaskEntity> findByTenantId(Integer tenantId);

    /** 按 sysid 和状态查询喷洒任务。 */
    List<SprayTaskEntity> findBySysidAndStatus(int sysid, String status);
}