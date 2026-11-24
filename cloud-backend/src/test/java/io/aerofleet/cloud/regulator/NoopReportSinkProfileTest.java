package io.aerofleet.cloud.regulator;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class NoopReportSinkProfileTest {

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withUserConfiguration(NoopReportSink.class, SimReportSink.class, UomReportSink.class,
                    RegulatorConfig.class, ComplianceStateManager.class, TelemetryReportService.class,
                    RegulatorController.class)
            .withBean(com.fasterxml.jackson.databind.ObjectMapper.class);

    @Test
    void noopSatisfiesConsumersInDevWhenUnconfigured() {
        runner.withPropertyValues("spring.profiles.active=dev").run(context -> {
            assertNull(context.getStartupFailure());
            assertTrue(context.containsBean("noopReportSink"));
            assertEquals(1, context.getBeanNamesForType(RegulatorReportSink.class).length);
        });
    }

    @Test
    void prodFailsClosedWhenConsumerRequiresSink() {
        runner.withPropertyValues("spring.profiles.active=prod").run(context -> {
            Throwable failure = context.getStartupFailure();
            assertNotNull(failure);
            assertTrue(failure.toString().contains("RegulatorReportSink"));
        });
    }

    @Test
    void stagingFailsClosedWhenConsumerRequiresSink() {
        runner.withPropertyValues("spring.profiles.active=staging").run(context -> {
            Throwable failure = context.getStartupFailure();
            assertNotNull(failure);
            assertTrue(failure.toString().contains("RegulatorReportSink"));
        });
    }

    @Test
    void prodSimSatisfiesConsumers() {
        runner.withPropertyValues("spring.profiles.active=prod")
                .withPropertyValues("aerofleet.regulator.sink-type=sim")
                .run(context -> {
                    assertNull(context.getStartupFailure());
                    assertFalse(context.containsBean("noopReportSink"));
                    assertTrue(context.containsBean("simReportSink"));
                });
    }

    @Test
    void prodUomSatisfiesConsumers() {
        runner.withPropertyValues("spring.profiles.active=prod")
                .withPropertyValues("aerofleet.regulator.sink-type=uom")
                .run(context -> {
                    assertNull(context.getStartupFailure());
                    assertFalse(context.containsBean("noopReportSink"));
                    assertTrue(context.containsBean("uomReportSink"));
                });
    }
}
