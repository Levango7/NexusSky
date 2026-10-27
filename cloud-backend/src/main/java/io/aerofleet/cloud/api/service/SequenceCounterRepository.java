package io.aerofleet.cloud.api.service;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

/** 单调序列计数器仓库（V35）。 */
@Repository
public interface SequenceCounterRepository extends JpaRepository<SequenceCounterEntity, String> {
}
