package io.aerofleet.cloud.orch.repository;

import io.aerofleet.cloud.orch.entity.TaskStepEntity;
import io.aerofleet.cloud.orch.enums.StepStatus;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

/**
 * 任务步骤 JPA Repository。
 *
 * 提供 TaskStepEntity 的持久化 CRUD 操作及按计划 ID、状态查询能力。
 */
@Repository
public interface TaskStepRepository extends JpaRepository<TaskStepEntity, Long> {

    /**
     * 按计划 ID 查询该计划下的所有步骤。
     *
     * @param planId 计划 ID
     * @return 属于该计划的步骤列表
     */
    List<TaskStepEntity> findByPlanId(Long planId);

    /**
     * 按计划 ID 和步骤状态查询步骤列表。
     *
     * @param planId 计划 ID
     * @param status 步骤状态
     * @return 匹配条件的步骤列表
     */
    List<TaskStepEntity> findByPlanIdAndStatus(Long planId, StepStatus status);

    /**
     * 按租户 ID 查询任务步骤列表。
     *
     * @param tenantId 租户 ID
     * @return 属于该租户的步骤列表
     */
    List<TaskStepEntity> findByTenantId(Integer tenantId);
}