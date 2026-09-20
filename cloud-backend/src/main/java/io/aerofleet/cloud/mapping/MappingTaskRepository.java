package io.aerofleet.cloud.mapping;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

/**
 * MappingTask JPA Repository，提供测绘任务的持久化操作。
 */
@Repository
public interface MappingTaskRepository extends JpaRepository<MappingTask, String> {
}