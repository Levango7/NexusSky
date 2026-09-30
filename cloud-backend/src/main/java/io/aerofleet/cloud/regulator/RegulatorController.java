package io.aerofleet.cloud.regulator;

import io.aerofleet.cloud.api.exception.ApiExceptionHandler.NotFoundException;
import io.aerofleet.cloud.regulator.model.ActivationRequest;
import io.aerofleet.cloud.regulator.model.ActivationResult;
import io.aerofleet.cloud.regulator.model.CancellationRequest;
import io.aerofleet.cloud.regulator.model.CancellationResult;
import io.aerofleet.cloud.regulator.model.ComplianceState;
import io.aerofleet.cloud.regulator.model.ComplianceStatus;
import io.aerofleet.cloud.regulator.model.VerifyRequest;
import io.aerofleet.cloud.regulator.model.VerifyResult;
import io.aerofleet.cloud.regulator.model.VerifyStatus;
import io.aerofleet.cloud.security.RequireRole;
import io.aerofleet.cloud.security.Role;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 监管合规 REST API（C1-T09）。
 * <p>
 * 端点：
 * <ul>
 *   <li>POST /api/v1/regulator/verify — 实名验证</li>
 *   <li>POST /api/v1/regulator/activate — 激活上报</li>
 *   <li>POST /api/v1/regulator/cancel — 注销上报</li>
 *   <li>GET /api/v1/regulator/status/{sysid} — 合规状态详情</li>
 *   <li>GET /api/v1/regulator/status — 合规状态列表</li>
 * </ul>
 * <p>
 * 写操作（verify/activate/cancel）需要 {@code OPERATOR} 角色。
 * 已 {@code CANCELLED} 的无人机拒绝所有监管操作（409）。
 */
@RestController
@RequestMapping("/api/v1/regulator")
@Tag(name = "Regulator", description = "监管合规 REST API：实名验证、激活上报、注销、合规状态查询")
@RequireRole(Role.OBSERVER)
public class RegulatorController {

    private static final Logger log = LoggerFactory.getLogger(RegulatorController.class);

    private final RegulatorReportSink sink;
    private final ComplianceStateManager stateManager;
    private final TelemetryReportService telemetryReportService;

    public RegulatorController(RegulatorReportSink sink,
                               ComplianceStateManager stateManager,
                               TelemetryReportService telemetryReportService) {
        this.sink = sink;
        this.stateManager = stateManager;
        this.telemetryReportService = telemetryReportService;
    }

    /**
     * 实名验证（POST /verify）。
     * <p>
     * 向监管平台发起无人机实名登记状态验证。验证成功时将合规状态流转为 VERIFIED。
     * 已 CANCELLED 的无人机拒绝操作（409）。
     *
     * @param request 验证请求，包含 sysid、productSerialNo、realNameCertNo
     * @return 验证结果（脱敏），包含 verifyStatus、ownerName、registerDate
     */
    @PostMapping("/verify")
    @RequireRole(Role.OPERATOR)
    @Operation(summary = "实名验证", description = "向监管平台发起无人机实名登记状态验证")
    public ResponseEntity<Map<String, Object>> verify(@RequestBody VerifyRequest request) {
        // 已 CANCELLED 的无人机拒绝所有监管操作
        ComplianceStatus existing = stateManager.get(request.sysid());
        if (existing != null && existing.status() == ComplianceState.CANCELLED) {
            return conflict("sysid=" + request.sysid() + " 已注销，拒绝监管操作");
        }

        // 确保 sysid 已初始化为 UNVERIFIED
        if (existing == null) {
            stateManager.transition(request.sysid(), ComplianceState.UNVERIFIED);
        }

        // 调用监管平台验证
        VerifyResult result = sink.verifyRealNameStatus(request);

        // 验证成功时流转状态（仅在 UNVERIFIED 状态时才流转，避免重复流转异常）
        if (result.status() == VerifyStatus.VERIFIED) {
            ComplianceStatus current = stateManager.get(request.sysid());
            if (current != null && current.status() == ComplianceState.UNVERIFIED) {
                stateManager.transition(request.sysid(), ComplianceState.VERIFIED);
            }
            log.info("实名验证成功: sysid={}", request.sysid());
        } else {
            log.warn("实名验证未通过: sysid={}, status={}", request.sysid(), result.status());
        }

        // 返回脱敏结果
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("sysid", request.sysid());
        response.put("verifyStatus", result.status().name());
        response.put("ownerName", result.ownerName());
        response.put("registerDate", result.registerDate());
        if (result.errorMessage() != null) {
            response.put("errorMessage", result.errorMessage());
        }
        return ResponseEntity.ok(response);
    }

    /**
     * 激活上报（POST /activate）。
     * <p>
     * 前置校验合规状态为 VERIFIED（否则 409），向监管平台上报激活信息。
     * 成功时流转状态为 ACTIVATED 并启动遥测上报。
     *
     * @param request 激活请求，包含 sysid、productSerialNo、activationTime、latitude、longitude
     * @return 激活结果，包含 success、activationId
     */
    @PostMapping("/activate")
    @RequireRole(Role.OPERATOR)
    @Operation(summary = "激活上报", description = "向监管平台上报无人机激活信息")
    public ResponseEntity<Map<String, Object>> activate(@RequestBody ActivationRequest request) {
        // 已 CANCELLED 的无人机拒绝所有监管操作
        ComplianceStatus existing = stateManager.get(request.sysid());
        if (existing != null && existing.status() == ComplianceState.CANCELLED) {
            return conflict("sysid=" + request.sysid() + " 已注销，拒绝监管操作");
        }

        // 前置校验合规状态为 VERIFIED
        if (existing == null || existing.status() != ComplianceState.VERIFIED) {
            String currentStatus = existing != null ? existing.status().name() : "UNVERIFIED";
            return conflict("sysid=" + request.sysid()
                    + " 合规状态为 " + currentStatus + "，需为 VERIFIED 才能激活");
        }

        // 设置激活时间（如果客户端未提供）
        ActivationRequest actualRequest = request;
        if (request.activationTime() == 0) {
            actualRequest = new ActivationRequest(
                    request.sysid(), request.productSerialNo(),
                    System.currentTimeMillis(),
                    request.latitude(), request.longitude());
        }

        // 调用监管平台激活上报
        ActivationResult result = sink.reportActivation(actualRequest);

        // 成功时状态流转 + 启动遥测上报
        if (result.success()) {
            stateManager.transition(request.sysid(), ComplianceState.ACTIVATED);
            telemetryReportService.startReporting(request.sysid());
            log.info("激活上报成功: sysid={}, activationId={}", request.sysid(), result.activationId());
        } else {
            log.warn("激活上报失败: sysid={}, error={}", request.sysid(), result.errorMessage());
        }

        // 返回结果
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("sysid", request.sysid());
        response.put("success", result.success());
        response.put("activationId", result.activationId());
        if (result.errorMessage() != null) {
            response.put("errorMessage", result.errorMessage());
        }
        return ResponseEntity.ok(response);
    }

    /**
     * 注销上报（POST /cancel）。
     * <p>
     * 前置校验合规状态为 ACTIVATED/OPERATING（否则 409），先停止遥测上报，
     * 再向监管平台上报注销信息。成功时流转状态为 CANCELLED。
     *
     * @param request 注销请求，包含 sysid、productSerialNo、cancellationReason
     * @return 注销结果，包含 success、cancellationId
     */
    @PostMapping("/cancel")
    @RequireRole(Role.OPERATOR)
    @Operation(summary = "注销上报", description = "向监管平台上报无人机注销信息")
    public ResponseEntity<Map<String, Object>> cancel(@RequestBody CancellationRequest request) {
        // 已 CANCELLED 的无人机拒绝所有监管操作
        ComplianceStatus existing = stateManager.get(request.sysid());
        if (existing != null && existing.status() == ComplianceState.CANCELLED) {
            return conflict("sysid=" + request.sysid() + " 已注销，拒绝监管操作");
        }

        // 前置校验合规状态为 ACTIVATED/OPERATING
        if (existing == null
                || (existing.status() != ComplianceState.ACTIVATED
                && existing.status() != ComplianceState.OPERATING)) {
            String currentStatus = existing != null ? existing.status().name() : "UNVERIFIED";
            return conflict("sysid=" + request.sysid()
                    + " 合规状态为 " + currentStatus + "，需为 ACTIVATED/OPERATING 才能注销");
        }

        // 先停止遥测上报
        telemetryReportService.stopReporting(request.sysid());

        // 调用监管平台注销上报
        CancellationResult result = sink.reportCancellation(request);

        // 成功时状态流转
        if (result.success()) {
            stateManager.transition(request.sysid(), ComplianceState.CANCELLED);
            log.info("注销上报成功: sysid={}, cancellationId={}", request.sysid(), result.cancellationId());
        } else {
            log.warn("注销上报失败: sysid={}, error={}", request.sysid(), result.errorMessage());
        }

        // 返回结果
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("sysid", request.sysid());
        response.put("success", result.success());
        response.put("cancellationId", result.cancellationId());
        if (result.errorMessage() != null) {
            response.put("errorMessage", result.errorMessage());
        }
        return ResponseEntity.ok(response);
    }

    /**
     * 合规状态详情（GET /status/{sysid}）。
     * <p>
     * 返回指定无人机的合规状态详情。sysid 不存在时返回 404。
     *
     * @param sysid 无人机系统标识
     * @return 合规状态详情
     */
    @GetMapping("/status/{sysid}")
    @Operation(summary = "合规状态详情", description = "查询单架无人机的合规状态详情")
    public Map<String, Object> getStatus(@PathVariable int sysid) {
        ComplianceStatus status = stateManager.get(sysid);
        if (status == null) {
            throw new NotFoundException("sysid=" + sysid + " 合规状态记录不存在");
        }
        return toStatusMap(status);
    }

    /**
     * 合规状态列表（GET /status）。
     * <p>
     * 返回全部无人机的合规状态列表。
     *
     * @return 合规状态列表
     */
    @GetMapping("/status")
    @Operation(summary = "合规状态列表", description = "查询全部无人机的合规状态列表")
    public List<Map<String, Object>> getAllStatus() {
        List<ComplianceStatus> all = stateManager.getAll();
        List<Map<String, Object>> result = new ArrayList<>(all.size());
        for (ComplianceStatus status : all) {
            result.add(toStatusMap(status));
        }
        return result;
    }

    // --- 私有辅助方法 ---

    /**
     * 构建 409 Conflict 响应。
     *
     * @param message 错误描述
     * @return 409 响应实体
     */
    private ResponseEntity<Map<String, Object>> conflict(String message) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("error", message);
        return ResponseEntity.status(HttpStatus.CONFLICT).body(body);
    }

    /**
     * 将 ComplianceStatus 转换为 Map 格式输出。
     *
     * @param status 合规状态记录
     * @return Map 格式的状态详情
     */
    private Map<String, Object> toStatusMap(ComplianceStatus status) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("sysid", status.sysid());
        map.put("productSerialNo", status.productSerialNo());
        map.put("status", status.status().name());
        map.put("activationId", status.activationId());
        map.put("cancellationId", status.cancellationId());
        map.put("lastVerifyTime", status.lastVerifyTime());
        map.put("lastActivationTime", status.lastActivationTime());
        map.put("lastTelemetryReportTime", status.lastTelemetryReportTime());
        return map;
    }
}