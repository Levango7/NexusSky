package io.aerofleet.cloud.api.service;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

/** 应急编排计划仓库（V37）。 */
@Repository
public interface EmergencyPlanRepository extends JpaRepository<EmergencyPlanEntity, Long> {
}
