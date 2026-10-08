package io.aerofleet.cloud.defect;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface WorkOrderRepository extends JpaRepository<WorkOrderEntity, Long> {
    List<WorkOrderEntity> findAllByOrderByCreatedAtDesc();
    List<WorkOrderEntity> findByCreatedAtBetweenOrderByCreatedAtDesc(long from, long to);
}
