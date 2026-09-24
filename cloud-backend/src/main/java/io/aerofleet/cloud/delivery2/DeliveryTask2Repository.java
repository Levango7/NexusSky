package io.aerofleet.cloud.delivery2;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface DeliveryTask2Repository extends JpaRepository<DeliveryTask2, String> {

    List<DeliveryTask2> findByTenantId(Integer tenantId);
}