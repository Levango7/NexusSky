package io.aerofleet.cloud.mission;

import io.aerofleet.cloud.security.RequireRole;
import io.aerofleet.cloud.security.Role;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import static io.aerofleet.cloud.api.ApiExceptionHandler.BadRequestException;
import static io.aerofleet.cloud.api.ApiExceptionHandler.NotFoundException;

/**
 * 应急指挥工作流 REST 端点（接报→研判→部署→执行→评估→总结）。
 * <p>
 * 独立路径前缀 /api/emergency-command/*，提供应急指挥命令的全生命周期管理。
 * <p>
 * 端点清单：
 * <pre>
 * POST /api/emergency-command              创建指挥命令（接报）
 * GET  /api/emergency-command              列出指挥命令（支持阶段筛选）
 * GET  /api/emergency-command/{id}         获取命令详情
 * POST /api/emergency-command/{id}/assess  研判
 * POST /api/emergency-command/{id}/deploy  部署
 * POST /api/emergency-command/{id}/execute 开始执行
 * POST /api/emergency-command/{id}/evaluate 评估
 * POST /api/emergency-command/{id}/close   总结关闭
 * POST /api/emergency-command/{id}/one-click 一键应急响应
 * GET  /api/emergency-command/{id}/history 获取阶段转移历史
 * </pre>
 */
@RestController
@RequestMapping("/api/emergency-command")
@Tag(name = "EmergencyCommand", description = "应急指挥工作流 REST API：接报→研判→部署→执行→评估→总结全生命周期管理")
public class EmergencyCommandController {

    private final EmergencyCommandWorkflow workflow;
    private final OneClickEmergencyResponse oneClickResponse;

    public EmergencyCommandController(EmergencyCommandWorkflow workflow,
                                      OneClickEmergencyResponse oneClickResponse) {
        this.workflow = workflow;
        this.oneClickResponse = oneClickResponse;
    }

    @Operation(summary = "创建指挥命令（接报）", description = "需要 OPERATOR 角色；lat ∈ [-90,90]，lon ∈ [-180,180]")
    @ApiResponses({
        @ApiResponse(responseCode = "200", description = "创建成功"),
        @ApiResponse(responseCode = "400", description = "参数非法")
    })
    @PostMapping
    @RequireRole(Role.OPERATOR)
    public ResponseEntity<Map<String, Object>> create(@RequestBody Map<String, Object> body) {
        EmergencyCommand.IncidentType type = EmergencyCommand.IncidentType.fromString(
                str(body, "incidentType", "其他"));
        EmergencyCommand.Severity severity = EmergencyCommand.Severity.fromString(
                str(body, "severity", "INFO"));
        double lat = numDouble(body, "lat", 0);
        double lon = numDouble(body, "lon", 0);
        double alt = numDouble(body, "alt", 0);
        // 地理坐标范围校验：lat ∈ [-90,90]，lon ∈ [-180,180]
        if (lat < -90 || lat > 90) {
            throw new BadRequestException("lat must be in [-90, 90], got: " + lat);
        }
        if (lon < -180 || lon > 180) {
            throw new BadRequestException("lon must be in [-180, 180], got: " + lon);
        }
        String description = str(body, "description", "");
        String reporterName = str(body, "reporterName", "");
        String reporterContact = str(body, "reporterContact", "");
        long receiveTimeMs = System.currentTimeMillis();

        EmergencyCommand cmd = new EmergencyCommand(
                null, type, severity, new EmergencyCommand.Location(lat, lon, alt),
                description, reporterName, reporterContact, receiveTimeMs);
        EmergencyCommand created = workflow.createCommand(cmd);
        return ResponseEntity.ok(toMap(created));
    }

    @Operation(summary = "列出指挥命令（支持阶段筛选）", description = "phase 参数可选，用于按阶段过滤")
    @ApiResponse(responseCode = "200", description = "命令列表")
    @GetMapping
    public ResponseEntity<Map<String, Object>> list(
            @RequestParam(value = "phase", required = false) String phase) {
        EmergencyCommandPhase phaseFilter = parsePhase(phase);
        List<EmergencyCommand> cmds = workflow.listCommands(phaseFilter);
        List<Map<String, Object>> items = new ArrayList<>();
        for (EmergencyCommand cmd : cmds) {
            items.add(toMap(cmd));
        }
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("commands", items);
        result.put("total", items.size());
        return ResponseEntity.ok(result);
    }

    @Operation(summary = "获取命令详情")
    @ApiResponses({
        @ApiResponse(responseCode = "200", description = "命令详情"),
        @ApiResponse(responseCode = "404", description = "命令不存在")
    })
    @GetMapping("/{id}")
    public ResponseEntity<Map<String, Object>> get(@PathVariable("id") String id) {
        EmergencyCommand cmd = workflow.getCommand(id);
        if (cmd == null) {
            throw new NotFoundException("command " + id + " not found");
        }
        return ResponseEntity.ok(toMap(cmd));
    }

    @Operation(summary = "研判", description = "需要 OPERATOR 角色")
    @ApiResponses({
        @ApiResponse(responseCode = "200", description = "研判成功"),
        @ApiResponse(responseCode = "400", description = "命令不存在或阶段非法")
    })
    @PostMapping("/{id}/assess")
    @RequireRole(Role.OPERATOR)
    public ResponseEntity<Map<String, Object>> assess(@PathVariable("id") String id,
                                                       @RequestBody Map<String, Object> body) {
        String assessmentResult = str(body, "assessmentResult", "");
        String operator = str(body, "operator", "system");
        EmergencyCommand cmd = workflow.assess(id, assessmentResult, operator);
        if (cmd == null) {
            throw new BadRequestException("assess failed: command " + id
                    + " not found or invalid phase");
        }
        return ResponseEntity.ok(toMap(cmd));
    }

    @Operation(summary = "部署", description = "需要 OPERATOR 角色；estimatedDurationMin 必须 > 0")
    @ApiResponses({
        @ApiResponse(responseCode = "200", description = "部署成功"),
        @ApiResponse(responseCode = "400", description = "命令不存在或参数非法")
    })
    @PostMapping("/{id}/deploy")
    @RequireRole(Role.OPERATOR)
    public ResponseEntity<Map<String, Object>> deploy(@PathVariable("id") String id,
                                                       @RequestBody Map<String, Object> body) {
        String planName = str(body, "planName", "auto-plan");
        String strategy = str(body, "strategy", "default");
        int duration = numInt(body, "estimatedDurationMin", 60);
        // 预计时长必须为正数
        if (duration <= 0) {
            throw new BadRequestException("estimatedDurationMin must be > 0, got: " + duration);
        }
        String relay = str(body, "communicationRelay", "mesh");
        String operator = str(body, "operator", "system");

        EmergencyCommand.DeploymentPlan plan = new EmergencyCommand.DeploymentPlan(
                planName, strategy, duration, relay);
        EmergencyCommand cmd = workflow.deploy(id, plan, operator);
        if (cmd == null) {
            throw new BadRequestException("deploy failed: command " + id
                    + " not found or invalid phase");
        }
        return ResponseEntity.ok(toMap(cmd));
    }

    @Operation(summary = "开始执行", description = "需要 OPERATOR 角色")
    @ApiResponses({
        @ApiResponse(responseCode = "200", description = "执行已启动"),
        @ApiResponse(responseCode = "400", description = "命令不存在或阶段非法")
    })
    @PostMapping("/{id}/execute")
    @RequireRole(Role.OPERATOR)
    public ResponseEntity<Map<String, Object>> execute(@PathVariable("id") String id,
                                                        @RequestBody Map<String, Object> body) {
        String operator = str(body, "operator", "system");
        EmergencyCommand cmd = workflow.startExecution(id, operator);
        if (cmd == null) {
            throw new BadRequestException("execute failed: command " + id
                    + " not found or invalid phase");
        }
        return ResponseEntity.ok(toMap(cmd));
    }

    @Operation(summary = "评估", description = "需要 OPERATOR 角色")
    @ApiResponses({
        @ApiResponse(responseCode = "200", description = "评估成功"),
        @ApiResponse(responseCode = "400", description = "命令不存在或阶段非法")
    })
    @PostMapping("/{id}/evaluate")
    @RequireRole(Role.OPERATOR)
    public ResponseEntity<Map<String, Object>> evaluate(@PathVariable("id") String id,
                                                         @RequestBody Map<String, Object> body) {
        String evaluationResult = str(body, "evaluationResult", "");
        String operator = str(body, "operator", "system");
        EmergencyCommand cmd = workflow.evaluate(id, evaluationResult, operator);
        if (cmd == null) {
            throw new BadRequestException("evaluate failed: command " + id
                    + " not found or invalid phase");
        }
        return ResponseEntity.ok(toMap(cmd));
    }

    @Operation(summary = "总结关闭", description = "需要 OPERATOR 角色")
    @ApiResponses({
        @ApiResponse(responseCode = "200", description = "关闭成功"),
        @ApiResponse(responseCode = "400", description = "命令不存在或阶段非法")
    })
    @PostMapping("/{id}/close")
    @RequireRole(Role.OPERATOR)
    public ResponseEntity<Map<String, Object>> close(@PathVariable("id") String id,
                                                      @RequestBody Map<String, Object> body) {
        String summary = str(body, "summary", "");
        String operator = str(body, "operator", "system");
        EmergencyCommand cmd = workflow.close(id, summary, operator);
        if (cmd == null) {
            throw new BadRequestException("close failed: command " + id
                    + " not found or invalid phase");
        }
        return ResponseEntity.ok(toMap(cmd));
    }

    @Operation(summary = "一键应急响应", description = "自动走完接报→研判→部署→执行全流程；需要 OPERATOR 角色")
    @ApiResponses({
        @ApiResponse(responseCode = "200", description = "一键响应完成"),
        @ApiResponse(responseCode = "400", description = "命令不存在")
    })
    @PostMapping("/{id}/one-click")
    @RequireRole(Role.OPERATOR)
    public ResponseEntity<Map<String, Object>> oneClick(@PathVariable("id") String id) {
        EmergencyCommand cmd = oneClickResponse.execute(id);
        if (cmd == null) {
            throw new BadRequestException("one-click failed: command " + id + " not found");
        }
        return ResponseEntity.ok(toMap(cmd));
    }

    @Operation(summary = "获取阶段转移历史")
    @ApiResponses({
        @ApiResponse(responseCode = "200", description = "阶段历史"),
        @ApiResponse(responseCode = "404", description = "命令不存在")
    })
    @GetMapping("/{id}/history")
    public ResponseEntity<Map<String, Object>> history(@PathVariable("id") String id) {
        EmergencyCommand cmd = workflow.getCommand(id);
        if (cmd == null) {
            throw new NotFoundException("command " + id + " not found");
        }
        List<Map<String, Object>> history = new ArrayList<>();
        for (EmergencyCommand.PhaseTransition t : cmd.getPhaseHistory()) {
            Map<String, Object> entry = new LinkedHashMap<>();
            entry.put("fromPhase", t.getFromPhase().name());
            entry.put("toPhase", t.getToPhase().name());
            entry.put("fromPhaseDisplay", t.getFromPhase().displayName());
            entry.put("toPhaseDisplay", t.getToPhase().displayName());
            entry.put("timestampMs", t.getTimestampMs());
            entry.put("operatorName", t.getOperatorName());
            entry.put("notes", t.getNotes());
            history.add(entry);
        }
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("commandId", id);
        result.put("currentPhase", cmd.getCurrentPhase().name());
        result.put("history", history);
        return ResponseEntity.ok(result);
    }

    // =====================================================================
    // 转换与解析辅助
    // =====================================================================

    /** 将 EmergencyCommand 转为响应 map。 */
    static Map<String, Object> toMap(EmergencyCommand cmd) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", cmd.getId());
        m.put("incidentType", cmd.getIncidentType().name());
        m.put("incidentTypeDisplay", cmd.getIncidentType().displayName());
        m.put("severity", cmd.getSeverity().name());
        m.put("severityLevel", cmd.getSeverity().level());
        Map<String, Object> loc = new LinkedHashMap<>();
        loc.put("lat", cmd.getLocation().getLat());
        loc.put("lon", cmd.getLocation().getLon());
        loc.put("alt", cmd.getLocation().getAlt());
        m.put("location", loc);
        m.put("description", cmd.getDescription());
        m.put("reporterName", cmd.getReporterName());
        m.put("reporterContact", cmd.getReporterContact());
        m.put("receiveTimeMs", cmd.getReceiveTimeMs());
        m.put("currentPhase", cmd.getCurrentPhase().name());
        m.put("currentPhaseDisplay", cmd.getCurrentPhase().displayName());
        m.put("assignedDrones", new ArrayList<>(cmd.getAssignedDrones()));
        m.put("assessmentResult", cmd.getAssessmentResult());
        if (cmd.getDeploymentPlan() != null) {
            Map<String, Object> plan = new LinkedHashMap<>();
            plan.put("planName", cmd.getDeploymentPlan().getPlanName());
            plan.put("strategy", cmd.getDeploymentPlan().getStrategy());
            plan.put("estimatedDurationMin", cmd.getDeploymentPlan().getEstimatedDurationMin());
            plan.put("communicationRelay", cmd.getDeploymentPlan().getCommunicationRelay());
            m.put("deploymentPlan", plan);
        } else {
            m.put("deploymentPlan", null);
        }
        m.put("executionLog", cmd.getExecutionLog());
        m.put("evaluationResult", cmd.getEvaluationResult());
        m.put("summary", cmd.getSummary());
        m.put("closedTimeMs", cmd.getClosedTimeMs());
        m.put("phaseHistorySize", cmd.getPhaseHistory().size());
        return m;
    }

    /** 解析阶段字符串，null/空返回 null（不过滤）。 */
    private static EmergencyCommandPhase parsePhase(String s) {
        if (s == null || s.isBlank()) {
            return null;
        }
        try {
            return EmergencyCommandPhase.valueOf(s.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            throw new BadRequestException("invalid phase: " + s);
        }
    }

    private static String str(Map<String, Object> body, String key, String def) {
        Object v = body.get(key);
        return v == null ? def : v.toString();
    }

    private static int numInt(Map<String, Object> body, String key, int def) {
        Object v = body.get(key);
        if (v == null) return def;
        if (v instanceof Number) return ((Number) v).intValue();
        try {
            return Integer.parseInt(v.toString());
        } catch (NumberFormatException e) {
            throw new BadRequestException("field '" + key + "' is not a valid integer: " + v);
        }
    }

    private static double numDouble(Map<String, Object> body, String key, double def) {
        Object v = body.get(key);
        if (v == null) return def;
        if (v instanceof Number) return ((Number) v).doubleValue();
        try {
            return Double.parseDouble(v.toString());
        } catch (NumberFormatException e) {
            throw new BadRequestException("field '" + key + "' is not a valid double: " + v);
        }
    }
}