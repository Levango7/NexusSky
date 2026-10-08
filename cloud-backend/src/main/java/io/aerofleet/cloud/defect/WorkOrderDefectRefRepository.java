package io.aerofleet.cloud.defect;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface WorkOrderDefectRefRepository extends JpaRepository<WorkOrderDefectRefEntity, Long> {
    List<WorkOrderDefectRefEntity> findByWorkOrderId(Long workOrderId);
    List<WorkOrderDefectRefEntity> findByDefectId(Long defectId);
    void deleteByWorkOrderId(Long workOrderId);
}
