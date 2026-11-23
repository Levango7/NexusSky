package io.aerofleet.cloud.regulator;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * P1 钉测：NoopReportSink 的 profile 门（合规假成功治理）。
 *
 * 此前 NoopReportSink 是「配置键缺失或为 none 时激活、全成功」——prod 未显式配置
 * sink-type 时也装它，返回「实名已验证/激活成功/注销成功/遥测成功」，与 GB 46750/46761
 * 合规直接冲突（监管数据造假）。
 *
 * 修复：NoopReportSink 加 @Profile({"dev","test","default"})，只在 dev/test 装配；
 * prod/staging 未显式选择时**启动失败（fail-closed）**——因为 RegulatorReportSink 在
 * TelemetryReportService 与 RegulatorController 都是必需的构造器注入，无 bean 即失败。
 *
 * 先红（修复前 prod 也装 Noop 假成功）后绿（修复后 prod 不装、无 bean 即失败）。
 */
class NoopReportSinkProfileTest {

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withUserConfiguration(NoopReportSink.class, SimReportSink.class, UomReportSink.class, RegulatorConfig.class)
            .withBean(com.fasterxml.jackson.databind.ObjectMapper.class);

    @Test
    void noopAssemblesInDevTestWhenUnconfigured() {
        runner.withPropertyValues("spring.profiles.active=dev").run(context -> {
            assertTrue(context.containsBean("noopReportSink"));
            assertEquals(1, context.getBeanNamesForType(RegulatorReportSink.class).length);
        });
    }

    @Test
    void prodFailsClosedWhenUnconfigured() {
        runner.withPropertyValues("spring.profiles.active=prod").run(context -> {
            // 修复前：prod 也装 NoopReportSink（假成功）⇒ assertFalse 失败（先红）。
            // 修复后：prod 不装 NoopReportSink，且无 RegulatorReportSink bean（fail-closed）。
            assertFalse(context.containsBean("noopReportSink"));
            assertEquals(0, context.getBeanNamesForType(RegulatorReportSink.class).length);
        });
    }

    @Test
    void prodStagingAlsoFailsClosedWhenUnconfigured() {
        runner.withPropertyValues("spring.profiles.active=staging").run(context -> {
            assertFalse(context.containsBean("noopReportSink"));
            assertEquals(0, context.getBeanNamesForType(RegulatorReportSink.class).length);
        });
    }

    @Test
    void prodSimTakesOver() {
        runner.withPropertyValues("spring.profiles.active=prod")
                .withPropertyValues("aerofleet.regulator.sink-type=sim")
                .run(context -> {
                    assertFalse(context.containsBean("noopReportSink"));
                    assertTrue(context.containsBean("simReportSink"));
                });
    }

    @Test
    void prodUomTakesOver() {
        runner.withPropertyValues("spring.profiles.active=prod")
                .withPropertyValues("aerofleet.regulator.sink-type=uom")
                .run(context -> {
                    assertFalse(context.containsBean("noopReportSink"));
                    assertTrue(context.containsBean("uomReportSink"));
                });
    }
}
