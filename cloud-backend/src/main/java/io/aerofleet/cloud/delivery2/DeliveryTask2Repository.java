package io.aerofleet.cloud.delivery2;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface DeliveryTask2Repository extends JpaRepository<DeliveryTask2, String> {
}