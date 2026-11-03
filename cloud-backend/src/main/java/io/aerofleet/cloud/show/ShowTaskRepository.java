package io.aerofleet.cloud.show;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

/**
 * 表演任务 JPA Repository。
 */
@Repository
public interface ShowTaskRepository extends JpaRepository<ShowTask, String> {

    List<ShowTask> findByTenantId(Integer tenantId);

    /**
     * 按状态下推查询。
     * <p>
     * {@code ShowTaskService#listTasks} 此前 {@code findAll()} 后
     * {@code stream().filter(t -> t.getStatus() == status)}；表演任务只增不减，
     * 每次列表请求都把整表拖进堆再逐行筛。
     */
    List<ShowTask> findByStatus(ShowStatus status);
}