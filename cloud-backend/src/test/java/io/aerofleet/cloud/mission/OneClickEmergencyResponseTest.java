package io.aerofleet.cloud.mission;

import io.aerofleet.cloud.api.EmergencyOrchService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.context.ApplicationEventPublisher;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link OneClickEmergencyResponse} 一键应急响应单测。
 * <p>
 * 覆盖不同事件类型的预设研判、自动部署、完整一键流程、编排启动等。
 */
@DisplayName("OneClickEmergencyResponse 一键应急响应")
class OneClickEmergencyResponseTest {

    private EmergencyCommandWorkflow workflow;
    private EmergencyOrchService orchService;
    private OneClickEmergencyResponse oneClick;
    private final ApplicationEventPublisher noopPublisher = event -> { };

    @BeforeEach
    void setUp() {
        workflow = new EmergencyCommandWorkflow();
        orchService = new EmergencyOrchService(null, noopPublisher);
        oneClick = new OneClickEmergencyResponse(workflow, orchService);
    }

    private EmergencyCommand createCommand(EmergencyCommand.IncidentType type) {
        EmergencyCommand cmd = new EmergencyCommand(
                null, type, EmergencyCommand.Severity.CRITICAL,
                new EmergencyCommand.Location(39.9, 116.3, 50.0),
                "test", "reporter", "13800000000", System.currentTimeMillis());
        return workflow.createCommand(cmd);
    }

    @Test
    @DisplayName("execute 火灾事件一键响应成功")
    void executeFire() {
        EmergencyCommand cmd = createCommand(EmergencyCommand.IncidentType.FIRE);
        EmergencyCommand result = oneClick.execute(cmd.getId());
        assertThat(result).isNotNull();
        assertThat(result.getCurrentPhase()).isEqualTo(EmergencyCommandPhase.EXECUTING);
        assertThat(result.getAssessmentResult()).isNotNull().isNotEmpty();
        assertThat(result.getDeploymentPlan()).isNotNull();
        assertThat(result.getAssignedDrones()).isNotEmpty();
        assertThat(result.getExecutionLog()).isNotEmpty();
    }

    @Test
    @DisplayName("execute 地震事件一键响应成功")
    void executeEarthquake() {
        EmergencyCommand cmd = createCommand(EmergencyCommand.IncidentType.EARTHQUAKE);
        EmergencyCommand result = oneClick.execute(cmd.getId());
        assertThat(result).isNotNull();
        assertThat(result.getCurrentPhase()).isEqualTo(EmergencyCommandPhase.EXECUTING);
        // 地震预设 12 架无人机
        assertThat(result.getAssignedDrones()).hasSize(12);
    }

    @Test
    @DisplayName("execute 命令不存在返回 null")
    void executeNotFound() {
        assertThat(oneClick.execute("nonexistent")).isNull();
    }

    @Test
    @DisplayName("presetAssess 根据事件类型生成研判结果")
    void presetAssessGeneratesResult() {
        EmergencyCommand cmd = createCommand(EmergencyCommand.IncidentType.FIRE);
        EmergencyCommand assessed = oneClick.presetAssess(cmd);
        assertThat(assessed).isNotNull();
        assertThat(assessed.getCurrentPhase()).isEqualTo(EmergencyCommandPhase.ASSESSED);
        assertThat(assessed.getAssessmentResult()).contains("火灾");
        assertThat(assessed.getAssessmentResult()).contains("无人机");
    }

    @Test
    @DisplayName("presetAssess 不同事件类型生成不同研判")
    void presetAssessDifferentTypes() {
        EmergencyCommand fire = createCommand(EmergencyCommand.IncidentType.FIRE);
        EmergencyCommand earthquake = createCommand(EmergencyCommand.IncidentType.EARTHQUAKE);

        oneClick.presetAssess(fire);
        oneClick.presetAssess(earthquake);

        assertThat(workflow.getCommand(fire.getId()).getAssessmentResult())
                .isNotEqualTo(workflow.getCommand(earthquake.getId()).getAssessmentResult());
    }

    @Test
    @DisplayName("autoDeploy 分配无人机并生成部署计划")
    void autoDeployAssignsDrones() {
        EmergencyCommand cmd = createCommand(EmergencyCommand.IncidentType.FIRE);
        oneClick.presetAssess(cmd);
        EmergencyCommand deployed = oneClick.autoDeploy(cmd);
        assertThat(deployed).isNotNull();
        assertThat(deployed.getCurrentPhase()).isEqualTo(EmergencyCommandPhase.DEPLOYED);
        assertThat(deployed.getAssignedDrones()).isNotEmpty();
        assertThat(deployed.getDeploymentPlan()).isNotNull();
        assertThat(deployed.getDeploymentPlan().getPlanName()).contains("fire");
    }

    @Test
    @DisplayName("autoDeploy 地震事件分配 12 架无人机")
    void autoDeployEarthquakeDrones() {
        EmergencyCommand cmd = createCommand(EmergencyCommand.IncidentType.EARTHQUAKE);
        oneClick.presetAssess(cmd);
        EmergencyCommand deployed = oneClick.autoDeploy(cmd);
        assertThat(deployed.getAssignedDrones()).hasSize(12);
        assertThat(deployed.getAssignedDrones()).contains(1, 6, 12);
    }

    @Test
    @DisplayName("presetFor 每种事件类型都有预设方案")
    void presetForAllTypes() {
        for (EmergencyCommand.IncidentType type : EmergencyCommand.IncidentType.values()) {
            OneClickEmergencyResponse.PresetPlan preset = OneClickEmergencyResponse.presetFor(type);
            assertThat(preset.assessment).isNotEmpty();
            assertThat(preset.strategy).isNotEmpty();
            assertThat(preset.droneCount).isPositive();
            assertThat(preset.radius).isPositive();
            assertThat(preset.estimatedDurationMin).isPositive();
            assertThat(preset.communicationRelay).isNotEmpty();
        }
    }

    @Test
    @DisplayName("execute 完整流程阶段历史记录 3 次转移")
    void executeHistorySize() {
        EmergencyCommand cmd = createCommand(EmergencyCommand.IncidentType.FIRE);
        oneClick.execute(cmd.getId());
        EmergencyCommand result = workflow.getCommand(cmd.getId());
        // assess + deploy + startExecution = 3 次转移
        assertThat(result.getPhaseHistory()).hasSize(3);
        assertThat(result.getPhaseHistory().get(0).getToPhase()).isEqualTo(EmergencyCommandPhase.ASSESSED);
        assertThat(result.getPhaseHistory().get(1).getToPhase()).isEqualTo(EmergencyCommandPhase.DEPLOYED);
        assertThat(result.getPhaseHistory().get(2).getToPhase()).isEqualTo(EmergencyCommandPhase.EXECUTING);
    }
}