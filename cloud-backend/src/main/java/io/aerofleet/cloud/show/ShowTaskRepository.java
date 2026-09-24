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
}