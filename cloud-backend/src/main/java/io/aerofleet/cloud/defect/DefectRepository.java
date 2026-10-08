package io.aerofleet.cloud.defect;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface DefectRepository extends JpaRepository<DefectEntity, Long> {
    List<DefectEntity> findByStatusInOrderByCreatedAtDesc(List<String> statuses);
    List<DefectEntity> findByCreatedAtBetweenOrderByCreatedAtDesc(long from, long to);
    List<DefectEntity> findByKindAndStatusIn(String kind, List<String> statuses);
}
