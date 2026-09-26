package io.aerofleet.cloud.scenario;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.util.ArrayList;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import static io.aerofleet.cloud.api.exception.ApiExceptionHandler.NotFoundException;

/**
 * 场景演练 REST API（P0-2 应急救援场景库）。
 * <p>
 * 演练以模拟执行方式进行（不实际起飞），用于验证场景模板配置的合理性
 * 与协同策略的可行性，结束后生成评估报告。
 * <p>
 * 端点：
 * <ul>
 *   <li>{@code POST /api/scenarios/drill/{templateId}} — 启动演练</li>
 *   <li>{@code GET /api/scenarios/drill/{drillId}/result} — 获取演练评估报告</li>
 *   <li>{@code GET /api/scenarios/drill/history} — 演练历史</li>
 * </ul>
 */
@RestController
@RequestMapping("/api/v1/scenarios/drill")
@Tag(name = "ScenarioDrill", description = "应急救援场景演练：模拟执行与评估报告")
public class ScenarioDrillController {

    private static final Logger log = LoggerFactory.getLogger(ScenarioDrillController.class);

    private final ScenarioTemplateController templateController;
    private final Map<String, DrillResult> drillStore = new ConcurrentHashMap<>();

    public ScenarioDrillController(ScenarioTemplateController templateController) {
        this.templateController = templateController;
    }

    /**
     * 启动演练（模拟执行，不实际起飞）。
     * <p>
     * 加载模板 → 模拟参数校验 → 模拟角色分配 → 模拟航点生成 → 生成评估报告。
     *
     * @param templateId 模板 ID
     * @return 演练评估报告
     * @throws NotFoundException 模板不存在
     */
    @PostMapping("/{templateId}")
    @Operation(summary = "启动演练", description = "按模板 ID 启动模拟演练，不实际起飞。")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "演练评估报告"),
            @ApiResponse(responseCode = "404", description = "模板不存在")
    })
    public DrillResult drill(@PathVariable("templateId") String templateId) {
        ScenarioTemplate template = templateController.get(templateId);
        String drillId = "drill-" + UUID.randomUUID().toString().substring(0, 8);
        Instant start = Instant.now();

        log.info("Drill started: drillId={} templateId={}", drillId, templateId);

        DrillResult result = evaluate(drillId, template, start);
        drillStore.put(drillId, result);
        return result;
    }

    /**
     * 获取演练评估报告。
     *
     * @param drillId 演练 ID
     * @return 演练评估报告
     * @throws NotFoundException 演练不存在
     */
    @GetMapping("/{drillId}/result")
    @Operation(summary = "获取演练评估报告", description = "按 drillId 查询演练评估结果。")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "评估报告"),
            @ApiResponse(responseCode = "404", description = "演练不存在")
    })
    public DrillResult result(@PathVariable("drillId") String drillId) {
        DrillResult r = drillStore.get(drillId);
        if (r == null) {
            throw new NotFoundException("drill not found: " + drillId);
        }
        return r;
    }

    /**
     * 查询演练历史。
     *
     * @return 全部演练记录列表
     */
    @GetMapping("/history")
    @Operation(summary = "演练历史", description = "返回全部演练评估报告。")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "演练列表")
    })
    public List<DrillResult> history() {
        return new ArrayList<>(drillStore.values());
    }

    // =====================================================================
    // 评估逻辑
    // =====================================================================

    /**
     * 评估模板配置的合理性。
     * <p>
     * 检查项：
     * <ul>
     *   <li>无人机数量 &gt; 0</li>
     *   <li>作业半径 &gt; 0</li>
     *   <li>悬停高度 &gt; 0</li>
     *   <li>持续时长 &gt; 0</li>
     *   <li>协同策略与无人机数量匹配</li>
     *   <li>大规模场景无人机数量充足</li>
     *   <li>通信模式与场景匹配</li>
     * </ul>
     */
    private DrillResult evaluate(String drillId, ScenarioTemplate template, Instant start) {
        List<String> passed = new ArrayList<>();
        List<String> failed = new ArrayList<>();
        List<String> recommendations = new ArrayList<>();

        // 检查 1: 基本参数
        if (template.getDroneCount() > 0) {
            passed.add("droneCount > 0");
        } else {
            failed.add("droneCount <= 0");
        }
        if (template.getRadiusKm() > 0) {
            passed.add("radiusKm > 0");
        } else {
            failed.add("radiusKm <= 0");
        }
        if (template.getHoverAltitudeM() > 0) {
            passed.add("hoverAltitudeM > 0");
        } else {
            failed.add("hoverAltitudeM <= 0");
        }
        if (template.getDurationMin() > 0) {
            passed.add("durationMin > 0");
        } else {
            failed.add("durationMin <= 0");
        }

        // 检查 2: 协同策略与无人机数量匹配
        ScenarioTemplate.CollaborationStrategy strategy = template.getCollaborationStrategy();
        int drones = template.getDroneCount();
        if (strategy == ScenarioTemplate.CollaborationStrategy.RECON_RELAY_EXEC && drones < 3) {
            failed.add("RECON_RELAY_EXEC requires at least 3 drones");
            recommendations.add("增加无人机数量至 3 架以上以支持全角色协同");
        } else if (strategy == ScenarioTemplate.CollaborationStrategy.RECON_RELAY && drones < 2) {
            failed.add("RECON_RELAY requires at least 2 drones");
            recommendations.add("增加无人机数量至 2 架以上以支持中继通信");
        } else {
            passed.add("collaboration strategy matches drone count");
        }

        // 检查 3: 大规模场景无人机数量充足
        if (template.getSeverityLevel() == ScenarioTemplate.SeverityLevel.LARGE && drones < 6) {
            failed.add("LARGE scenario should have at least 6 drones");
            recommendations.add("大规模场景建议配置 6 架以上无人机");
        } else {
            passed.add("severity-drone count adequacy");
        }

        // 检查 4: 通信模式与场景匹配
        ScenarioTemplate.CommunicationMode comm = template.getCommunicationMode();
        if (template.getRadiusKm() > 3.0 && comm == ScenarioTemplate.CommunicationMode.MESH) {
            failed.add("radiusKm > 3km should use SAT or BOTH for reliable communication");
            recommendations.add("大半径场景建议使用卫星通信或双链路冗余");
        } else {
            passed.add("communication mode matches scenario scale");
        }

        // 检查 5: 化工厂泄漏悬停时间
        if (template.getDisasterType() == DisasterType.CHEMICAL_LEAK
                && template.getDurationMin() < 120) {
            failed.add("CHEMICAL_LEAK should have durationMin >= 120 for long hover monitoring");
            recommendations.add("化工厂泄漏场景建议持续时长不低于 120 分钟");
        } else {
            passed.add("duration matches disaster type requirement");
        }

        // 计算评分
        int totalChecks = passed.size() + failed.size();
        double score = totalChecks == 0 ? 0.0
                : Math.round((double) passed.size() / totalChecks * 100 * 10) / 10.0;

        if (score < 100.0 && recommendations.isEmpty()) {
            recommendations.add("请检查失败项并调整模板参数");
        }
        if (score == 100.0) {
            recommendations.add("模板配置合理，可用于实际场景启动");
        }

        Instant end = Instant.now();
        log.info("Drill completed: drillId={} score={} passed={} failed={}",
                drillId, score, passed.size(), failed.size());

        return new DrillResult(drillId, template.getId(), start, end,
                passed, failed, score, recommendations);
    }
}