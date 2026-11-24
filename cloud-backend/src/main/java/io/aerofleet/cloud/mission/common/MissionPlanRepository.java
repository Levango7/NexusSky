package io.aerofleet.cloud.mission.common;

import org.springframework.data.jpa.repository.JpaRepository;

public interface MissionPlanRepository extends JpaRepository<MissionPlanEntity, Integer> {
}
