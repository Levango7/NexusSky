package io.aerofleet.cloud;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * AeroFleet cloud backend entry point: MAVLink device gateway + REST API + WebSocket push.
 * Modular monolith on purpose (no microservices at scaffold stage).
 */
@SpringBootApplication
@EnableScheduling
public class CloudBackendApplication {

    public static void main(String[] args) {
        SpringApplication.run(CloudBackendApplication.class, args);
    }
}
