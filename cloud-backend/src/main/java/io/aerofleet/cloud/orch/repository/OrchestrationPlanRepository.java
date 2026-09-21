package io.aerofleet.cloud.orch.repository;

import io.aerofleet.cloud.orch.entity.OrchestrationPlanEntity;
import io.aerofleet.cloud.orch.enums.PlanStatus;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

/**
 * 编排计划 JPA Repository。
 *
 * 提供 OrchestrationPlanEntity 的持久化 CRUD 操作及按状态查询能力。
 */
@Repository
public interface OrchestrationPlanRepository extends JpaRepository<OrchestrationPlanEntity, Long> {

    /**
     * 按计划状态查询编排计划列表。
     *
     * @param status 计划状态
     * @return 匹配状态的编排计划列表
     */
    List<OrchestrationPlanEntity> findByStatus(PlanStatus status);
}