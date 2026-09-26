package io.aerofleet.cloud.alarm;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

/**
 * 报警事件 JPA Repository（M10 报警联动编排，FR-31）。
 * <p>
 * 提供 AlarmEvent 的持久化 CRUD 操作，替代原内存存储方案。
 *
 * @see AlarmEvent
 * @see AlarmEventStore
 */
@Repository
public interface AlarmEventRepository extends JpaRepository<AlarmEvent, String> {

    /**
     * 按租户 ID 查询报警事件。
     *
     * @param tenantId 租户 ID
     * @return 该租户下的所有报警事件列表
     */
    List<AlarmEvent> findByTenantId(Integer tenantId);
}