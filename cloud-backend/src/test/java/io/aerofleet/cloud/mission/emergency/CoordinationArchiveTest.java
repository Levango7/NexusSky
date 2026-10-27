package io.aerofleet.cloud.mission.emergency;

import io.aerofleet.cloud.mission.emergency.AirGroundCoordinationService.CoordinationRecord;
import io.aerofleet.cloud.mission.emergency.AirGroundCoordinationService.CoordinationReport;
import io.aerofleet.cloud.mission.emergency.AirGroundCoordinationService.DeploymentInfo;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 空地协同归档单测（V33；审计 §2.1 第 6 条）。
 * <p>
 * 核心两组断言：① 复合对象（deployment/evaluation/report）经实体往返后**逐字段保真**
 * ——显式映射表是这里唯一被证明有效的机制；② **归档读穿修掉 404**——
 * 内存未命中（总结后清理/重启）时 getCoordinationReport 从库重建返回。
 * <p>
 * 实体层用真对象直接测（不经 mock），服务层写穿/读穿用 mock 仓库钉调用面。
 */
@DisplayName("空地协同归档")
class CoordinationArchiveTest {

    private CoordinationRecord recordWithReport() {
        CoordinationRecord r = new CoordinationRecord("AGC-1", "alarm-1", "cmd-1");
        r.setPhase(EmergencyCommandPhase.EXECUTING);
        r.restorePhaseTimestamps(100L, 200L, 300L);
        r.setPlanId(77);
        r.setAssessmentResult("威胁 2 级，资源充足");
        DeploymentInfo d = new DeploymentInfo();
        d.setScenarioType(3);
        d.setCenterLat(399000000);
        d.setCenterLon(1163000000);
        d.setRadius(500);
        d.setDroneIds(List.of(1, 2, 3));
        d.setGroundDeployAvailable(true);
        d.setGroundDeploySummary("两组地面警力已出发");
        d.setPriority("HIGH");
        r.setDeploymentInfo(d);
        r.setEvaluation(new AirGroundCoordinationService.CoordinationEvaluation(
                80, 60, 95,
                new AirGroundCoordinationService.ResponseTiming(5, 10, 15, 30),
                new AirGroundCoordinationService.ResourceConsumption(3, 2, 76, 4)));
        r.setReport(new CoordinationReport("AGC-1", "alarm-1", "cmd-1", 77,
                "总览：警情一，已闭环", "覆盖 80%/设备 60%/连通 95%", "协同完成，用时 30s",
                List.of("建议加强夜间巡逻"), List.of("接报: 0ms", "研判: 100ms"), 123456L));
        return r;
    }

    @Test
    @DisplayName("实体往返逐字段保真：deployment / evaluation / report / 时间戳全等")
    void entityRoundTripIsFaithful() {
        CoordinationRecord original = recordWithReport();
        CoordinationRecordEntity entity = CoordinationRecordEntity.fromRecord(original);
        CoordinationRecord restored = entity.toRecord();

        assertThat(restored.getCoordinationId()).isEqualTo("AGC-1");
        assertThat(restored.getAlarmEventId()).isEqualTo("alarm-1");
        assertThat(restored.getPlanId()).isEqualTo(77);
        assertThat(restored.getAssessmentResult()).isEqualTo("威胁 2 级，资源充足");
        // setPhase 会把时间戳写成 now——恢复必须用 restorePhaseTimestamps，此处即证明
        assertThat(restored.getAssessTimeMs()).isEqualTo(100L);
        assertThat(restored.getDeployTimeMs()).isEqualTo(200L);
        assertThat(restored.getExecuteTimeMs()).isEqualTo(300L);

        DeploymentInfo d = restored.getDeploymentInfo();
        assertThat(d.getScenarioType()).isEqualTo(3);
        assertThat(d.getDroneIds()).containsExactly(1, 2, 3);
        assertThat(d.getGroundDeploySummary()).isEqualTo("两组地面警力已出发");
        assertThat(d.getPriority()).isEqualTo("HIGH");

        assertThat(restored.getEvaluation().coverageRate).isEqualTo(80);
        assertThat(restored.getEvaluation().timing.totalResponseSec).isEqualTo(30);
        assertThat(restored.getEvaluation().consumption.meshNodes).isEqualTo(4);

        CoordinationReport report = restored.getReport();
        assertThat(report.getSummary()).isEqualTo("协同完成，用时 30s");
        assertThat(report.getRecommendations()).containsExactly("建议加强夜间巡逻");
        assertThat(report.getPhaseTimeline()).containsExactly("接报: 0ms", "研判: 100ms");
        assertThat(report.getGeneratedAtMs()).isEqualTo(123456L);
    }

    @Test
    @DisplayName("归档读穿修掉 404：内存未命中时从库重建指挥报告")
    void reportReadsThroughArchive() {
        AirGroundCoordinationService service = new AirGroundCoordinationService(null, null,
                null, null, null, null, null, null, null);
        CoordinationRecordRepository repo = mock(CoordinationRecordRepository.class);
        when(repo.findById("AGC-1")).thenReturn(Optional.of(
                CoordinationRecordEntity.fromRecord(recordWithReport())));
        ReflectionTestUtils.setField(service, "recordRepository", repo);

        CoordinationReport report = service.getCoordinationReport("AGC-1");

        assertThat(report).isNotNull();
        assertThat(report.getCoordinationId()).isEqualTo("AGC-1");
        assertThat(report.getOverview()).contains("已闭环");
    }

    @Test
    @DisplayName("无仓库（纯单测构造）：读穿路径返回 null，零仓库交互（行为如旧）")
    void memoryModeUnchanged() {
        AirGroundCoordinationService service = new AirGroundCoordinationService(null, null,
                null, null, null, null, null, null, null);
        CoordinationRecordRepository repo = mock(CoordinationRecordRepository.class);
        ReflectionTestUtils.setField(service, "recordRepository", null);

        assertThat(service.getCoordinationReport("AGC-404")).isNull();
        assertThat(service.getCoordinationRecord("AGC-404")).isNull();
        verify(repo, never()).findById(any());
    }
}
