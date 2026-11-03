package io.aerofleet.cloud.mapping;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

/**
 * MappingTask JPA Repository，提供测绘任务的持久化操作。
 */
@Repository
public interface MappingTaskRepository extends JpaRepository<MappingTask, String> {

    List<MappingTask> findByTenantId(Integer tenantId);

    /**
     * 按租户 + 状态下推查询。
     * <p>
     * {@code MappingController#listTasks} 此前是 {@code findAll()} 后逐行
     * {@code if (tenantId != null && !tenantId.equals(...)) continue;}——租户与状态
     * 两张皮都在内存里过滤。测绘任务只增不减，表会随运行时长增长，那意味着每次列表
     * 请求都把整表拖进堆再逐行跳过。此方法让 DB 直接按两列过滤。
     * <p>
     * 注意 {@code tenantId} 传 null 时 Spring Data 会解析成 {@code = null} 而非
     * 「不过滤」，所以调用方仍需按「全局管理员/未归属」分支自己选方法（见 controller）。
     */
    List<MappingTask> findByTenantIdAndStatus(Integer tenantId, MappingTask.Status status);

    /** 全局管理员视角：只按状态下推（无租户条件）。 */
    List<MappingTask> findByStatus(MappingTask.Status status);
}