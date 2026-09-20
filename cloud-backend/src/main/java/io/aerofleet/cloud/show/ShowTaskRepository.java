package io.aerofleet.cloud.show;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

/**
 * 表演任务 JPA Repository。
 */
@Repository
public interface ShowTaskRepository extends JpaRepository<ShowTask, String> {
}