package io.aerofleet.cloud;

import io.aerofleet.cloud.api.DroneController;
import io.aerofleet.cloud.api.TelemetryPusher;
import io.aerofleet.cloud.gateway.DeviceRegistry;
import io.aerofleet.cloud.gateway.TelemetryIngestService;
import io.aerofleet.cloud.gateway.UdpGateway;
import io.aerofleet.cloud.mission.DroneCommandService;
import io.aerofleet.cloud.telemetry.PendingAcks;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.springframework.test.context.TestPropertySource;

import static org.junit.jupiter.api.Assertions.assertNotNull;

/**
 * Scaffold-level smoke test: context loads and the core service beans exist.
 * End-to-end checks (drone-sim <-> backend <-> gcs-web) are run separately.
 * UDP port is randomized so tests can run while a real backend holds 14550.
 */
@SpringBootTest
@TestPropertySource(properties = {
        "aerofleet.udp-port=0",
        "aerofleet.drone-port=14549",
})
class CloudBackendApplicationTests {

    @Autowired
    private ApplicationContext context;

    @Autowired
    private DeviceRegistry registry;

    @Autowired
    private TelemetryIngestService ingest;

    @Autowired
    private DroneCommandService commands;

    @Autowired
    private DroneController controller;

    @Autowired
    private TelemetryPusher pusher;

    @Autowired
    private PendingAcks pendings;

    @Test
    void contextLoads() {
        assertNotNull(context);
        UdpGateway gw = context.getBean(UdpGateway.class);
        assertNotNull(gw);
    }

    @Test
    void coreBeansPresent() {
        assertNotNull(registry);
        assertNotNull(ingest);
        assertNotNull(commands);
        assertNotNull(controller);
        assertNotNull(pusher);
        assertNotNull(pendings);
    }
}
