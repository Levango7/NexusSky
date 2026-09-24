package io.aerofleet.cloud.mission.formation;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

/**
 * 编队持久化 Repository（Spring Data JPA）。
 *
 * 提供 FormationEntity 的标准 CRUD 操作，供 FormationService 在创建/解散/变换时同步写入。
 */
@Repository
public interface FormationRepository extends JpaRepository<FormationEntity, Integer> {

    /** 按租户 ID 查询编队列表（租户隔离）。 */
    List<FormationEntity> findByTenantId(Integer tenantId);
}