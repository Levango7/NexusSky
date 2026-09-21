package io.aerofleet.cloud.orch.repository;

import io.aerofleet.cloud.orch.entity.ConditionTriggerEntity;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

/**
 * 条件触发器 JPA Repository。
 *
 * 提供 ConditionTriggerEntity 的持久化 CRUD 操作及按计划 ID 查询能力。
 */
@Repository
public interface ConditionTriggerRepository extends JpaRepository<ConditionTriggerEntity, String> {

    /**
     * 按计划 ID 查询该计划下的所有条件触发器。
     *
     * @param planId 计划 ID
     * @return 属于该计划的触发器列表
     */
    List<ConditionTriggerEntity> findByPlanId(Long planId);
}