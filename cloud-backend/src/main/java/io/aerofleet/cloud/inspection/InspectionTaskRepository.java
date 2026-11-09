package io.aerofleet.cloud.inspection;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface InspectionTaskRepository extends JpaRepository<InspectionTaskEntity, String> {
    List<InspectionTaskEntity> findByTemplateId(String templateId);
}
