package io.aerofleet.cloud.api.controller;

import io.aerofleet.cloud.api.service.ScenarioRegistryService;
import io.aerofleet.cloud.security.RequireRole;
import io.aerofleet.cloud.security.Role;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/**
 * V43 场景注册表 REST API：阳台落点 / 农资流水 / 应急节点角色。
 *
 * <p>只读查询 OBSERVER 可用，写入需 OPERATOR；归属前缀在
 * {@code LicenseModuleMap} 登记为 core（fail-closed 覆盖测试要求）。
 */
@RestController
@RequestMapping("/api/v1/scenarios")
@RequireRole(Role.OBSERVER)
public class ScenarioRegistryController {

    private final ScenarioRegistryService service;

    public ScenarioRegistryController(ScenarioRegistryService service) {
        this.service = service;
    }

    @PostMapping("/balcony-drop-points")
    @RequireRole(Role.OPERATOR)
    public Map<String, Object> createBalconyDropPoint(@RequestBody Map<String, Object> body) {
        return service.createBalconyDropPoint(body);
    }

    @GetMapping("/balcony-drop-points")
    public List<Map<String, Object>> balconyDropPoints(
            @RequestParam(required = false) String buildingId) {
        return service.balconyDropPoints(buildingId);
    }

    @PostMapping("/agri-supply-ledger")
    @RequireRole(Role.OPERATOR)
    public Map<String, Object> createAgriSupply(@RequestBody Map<String, Object> body) {
        return service.createAgriSupply(body);
    }

    @GetMapping("/agri-supply-ledger")
    public List<Map<String, Object>> agriSupply(
            @RequestParam(required = false) String taskId) {
        return service.agriSupply(taskId);
    }

    @PostMapping("/emergency-node-roles")
    @RequireRole(Role.OPERATOR)
    public Map<String, Object> createEmergencyRole(@RequestBody Map<String, Object> body) {
        return service.createEmergencyRole(body);
    }

    @GetMapping("/emergency-node-roles")
    public List<Map<String, Object>> emergencyRoles(
            @RequestParam(required = false) String planId) {
        return service.emergencyRoles(planId);
    }
}
