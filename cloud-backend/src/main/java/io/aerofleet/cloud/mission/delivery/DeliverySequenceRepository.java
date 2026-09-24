package io.aerofleet.cloud.mission.delivery;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

/**
 * DeliverySequence 的 JPA Repository。
 * <p>
 * 支持按租户 ID 和 sysid+status 查询，用于租户隔离和任务筛选。
 */
@Repository
public interface DeliverySequenceRepository extends JpaRepository<DeliverySequenceEntity, Integer> {

    /** 按租户 ID 查询所有配送任务。 */
    List<DeliverySequenceEntity> findByTenantId(Integer tenantId);

    /** 按 sysid 和状态查询配送任务。 */
    List<DeliverySequenceEntity> findBySysidAndStatus(int sysid, String status);
}