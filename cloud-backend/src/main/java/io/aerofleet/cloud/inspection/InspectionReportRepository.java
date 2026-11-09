package io.aerofleet.cloud.inspection;

import org.springframework.data.jpa.repository.JpaRepository;

public interface InspectionReportRepository extends JpaRepository<InspectionReportEntity, String> {
}
