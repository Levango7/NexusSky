package io.aerofleet.cloud.inspection;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.TestPropertySource;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * InspectionTaskService 持久化集成测试（V40）。
 * <p>
 * 两层断言：
 * 1) 写透——创建/启动/真实完成/失败各迁移点都落库，报告与严重度/建议快照同行可查；
 * 2) 重启还原——new 一个新 Service 实例（同库）即"重启后"，任务状态/计数/航线
 *    从库还原，报告不丢；照片/异常明细不落库是已声明取舍，还原后返回空列表。
 */
@SpringBootTest
@TestPropertySource(properties = {
        "spring.datasource.url=jdbc:h2:mem:inspection-test;MODE=PostgreSQL;DB_CLOSE_DELAY=-1;DATABASE_TO_LOWER=TRUE",
        "spring.datasource.driver-class-name=org.h2.Driver",
        "spring.datasource.username=sa",
        "spring.datasource.password=",
        "spring.jpa.hibernate.ddl-auto=create-drop",
        "spring.flyway.enabled=false",
        "aerofleet.flightlog.persist-to-db=true",
        "aerofleet.flightlog.dir=./flight-logs-test",
        "aerofleet.flightlog.track-interval-ms=0",
        "aerofleet.device-registry.persist=false",
        "spring.cache.type=none",
        "aerofleet.udp-port=0",
        "aerofleet.drone-port=14549",
        "spring.autoconfigure.exclude=org.springframework.boot.autoconfigure.data.redis.RedisAutoConfiguration,org.springframework.boot.autoconfigure.data.redis.RedisRepositoriesAutoConfiguration",
})
@DisplayName("InspectionTaskService 持久化（V40）")
class InspectionPersistenceTest {

    @Autowired
    private InspectionTaskService service;

    @Autowired
    private InspectionTaskRepository taskRepo;

    @Autowired
    private InspectionReportRepository reportRepo;

    @Autowired
    private RoutePlannerService routePlanner;

    @Autowired
    private AnomalyDetectionService anomalyDetector;

    @Autowired
    private InspectionPresetFactory presetFactory;

    @BeforeEach
    void cleanUp() {
        reportRepo.deleteAll();
        taskRepo.deleteAll();
    }

    @Test
    @DisplayName("全生命周期落库 + 重启还原：状态/计数/航线/报告都在")
    void lifecycleSurvivesRestart() {
        InspectionTask created = service.createTask("preset-power-line", 9, 39.90, 116.40, null);
        service.startTask(created.id());

        InspectionReport report = service.completeFromRun(
                created.id(), 4.2, 0, 0, "相机未接入：本轮只记录飞行执行结果");

        // 库内事实：任务行 + 报告行
        InspectionTaskEntity taskRow = taskRepo.findById(created.id()).orElseThrow();
        assertThat(taskRow.status).isEqualTo("COMPLETED");
        assertThat(taskRow.startTime).isNotNull();
        assertThat(taskRow.endTime).isNotNull();
        assertThat(taskRow.progressPct).isEqualTo(100.0);
        assertThat(taskRow.waypoints).isNotBlank();

        InspectionReportEntity reportRow = reportRepo.findById(report.id()).orElseThrow();
        assertThat(reportRow.taskId).isEqualTo(created.id());
        assertThat(reportRow.summary).contains("相机未接入");
        assertThat(reportRow.bySeverityJson).contains("HIGH").contains("MEDIUM").contains("LOW");
        assertThat(reportRow.recommendations).contains("30 天");

        // 重启还原：同库新实例即"重启后的服务"
        InspectionTaskService restarted = new InspectionTaskService(
                routePlanner, anomalyDetector, presetFactory, taskRepo, reportRepo, new ObjectMapper());

        InspectionTask restored = restarted.getTask(created.id());
        assertThat(restored.status()).isEqualTo(InspectionTask.Status.COMPLETED);
        assertThat(restored.photosCaptured()).isZero();
        assertThat(restored.anomaliesFound()).isZero();
        assertThat(restored.progressPct()).isEqualTo(100.0);

        List<Waypoint> restoredWps = restarted.getWaypoints(created.id());
        assertThat(restoredWps).hasSameSizeAs(service.getWaypoints(created.id()));
        assertThat(restoredWps.get(0).lat).isEqualTo(service.getWaypoints(created.id()).get(0).lat);
        assertThat(restoredWps.get(0).action).isEqualTo(service.getWaypoints(created.id()).get(0).action);

        InspectionReport restoredReport = restarted.getReport(created.id());
        assertThat(restoredReport.id()).isEqualTo(report.id());
        assertThat(restoredReport.summary()).contains("相机未接入");
        assertThat(restoredReport.anomaliesBySeverity()).containsKeys(
                Anomaly.Severity.HIGH, Anomaly.Severity.MEDIUM, Anomaly.Severity.LOW);
        assertThat(restoredReport.recommendations()).contains("建议下次巡检间隔不超过 30 天");

        // 已声明取舍：明细列表不落库，还原后返回空（不是 500，不是旧数据）
        assertThat(restarted.getPhotos(created.id())).isEmpty();
        assertThat(restarted.getAnomalies(created.id())).isEmpty();
    }

    @Test
    @DisplayName("模拟完成同样落库：照片计数与异常计数写进任务行")
    void simulateCompletionPersists() {
        InspectionTask created = service.createTask("preset-power-line", 9, 39.90, 116.40, null);
        service.startTask(created.id());

        InspectionReport report = service.simulateCompletion(created.id());

        InspectionTaskEntity taskRow = taskRepo.findById(created.id()).orElseThrow();
        assertThat(taskRow.status).isEqualTo("COMPLETED");
        assertThat(taskRow.photosCaptured).isEqualTo(report.totalPhotos());
        assertThat(taskRow.anomaliesFound).isEqualTo(report.totalAnomalies());
        assertThat(taskRow.photosCaptured).isGreaterThan(0);
    }

    @Test
    @DisplayName("失败与中止也落库：终态不被重启吞掉")
    void failAndAbortPersistTerminalState() {
        InspectionTask a = service.createTask("preset-power-line", 9, 39.90, 116.40, null);
        service.startTask(a.id());
        service.failTask(a.id(), "flight timeout");

        InspectionTask b = service.createTask("preset-power-line", 9, 39.90, 116.40, null);
        service.abortTask(b.id());

        InspectionTaskService restarted = new InspectionTaskService(
                routePlanner, anomalyDetector, presetFactory, taskRepo, reportRepo, new ObjectMapper());
        assertThat(restarted.getTask(a.id()).status()).isEqualTo(InspectionTask.Status.FAILED);
        assertThat(restarted.getTask(b.id()).status()).isEqualTo(InspectionTask.Status.ABORTED);
    }
}
