package io.aerofleet.cloud.inspection;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

/**
 * 真实执行完成路径：报告只能来自调用方传入的实际采集结果，不得凭空生成照片/异常。
 * <p>
 * 这是黄金路径硬化的核心断言：{@code simulateCompletion} 可以继续用于演示，
 * 但机巢无人值守任务走的是 {@code completeFromRun}。
 */
@DisplayName("InspectionTaskService — 真实执行报告")
class InspectionRunReportTest {

    private InspectionTaskService service;

    @BeforeEach
    void setUp() {
        service = new InspectionTaskService(
                new RoutePlannerService(),
                new AnomalyDetectionService(),
                new InspectionPresetFactory(),
                mock(InspectionTaskRepository.class),
                mock(InspectionReportRepository.class),
                new ObjectMapper());
    }

    @Test
    @DisplayName("相机未接入：0 照片 / 0 异常，报告注明原因")
    void completeFromRunDoesNotFabricateEvidence() {
        InspectionTask task = service.createTask("preset-power-line", 9, 39.90, 116.40, null);
        service.startTask(task.id());

        InspectionReport report = service.completeFromRun(
                task.id(), 3.5, 0, 0, "相机未接入：本轮只记录飞行执行结果，未生成照片/视觉检测");

        assertThat(service.getTask(task.id()).status())
                .isEqualTo(InspectionTask.Status.COMPLETED);
        assertThat(report.totalPhotos()).isZero();
        assertThat(report.totalAnomalies()).isZero();
        assertThat(report.actualDurationMin()).isEqualTo(3.5);
        assertThat(report.distanceFlownKm()).isZero();
        assertThat(report.summary()).contains("相机未接入");
        assertThat(service.getPhotos(task.id())).isEmpty();
        assertThat(service.getAnomalies(task.id())).isEmpty();
    }

    @Test
    @DisplayName("执行失败：任务置 FAILED")
    void failTaskMarksFailed() {
        InspectionTask task = service.createTask("preset-power-line", 9, 39.90, 116.40, null);
        service.startTask(task.id());

        service.failTask(task.id(), "flight timeout");

        assertThat(service.getTask(task.id()).status())
                .isEqualTo(InspectionTask.Status.FAILED);
    }
}
