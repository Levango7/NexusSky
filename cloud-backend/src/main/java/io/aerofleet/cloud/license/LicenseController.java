package io.aerofleet.cloud.license;


import org.springframework.web.bind.annotation.*;


import java.util.LinkedHashMap;
import java.util.Map;

/**
 * License 管理 REST 端点。
 * <p>
 * 提供查询、激活、验证 License 的 API。
 *
 * @author AeroFleet Cloud Team
 */
@RestController
@RequestMapping("/api/v1/license")
public class LicenseController {


    private final LicenseService licenseService;

    public LicenseController(LicenseService licenseService) {
        this.licenseService = licenseService;
    }

    /** 查询当前 License 信息 */
    @GetMapping("/info")
    public Map<String, Object> info() {
        LicenseInfo info = licenseService.getLicenseInfo();
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("tenantId", info.getTenantId());
        result.put("productName", info.getProductName());
        result.put("maxDevices", info.getMaxDevices());
        result.put("expiryDate", info.getExpiryDate());
        result.put("issuedAt", info.getIssuedAt());
        result.put("issuedTo", info.getIssuedTo());
        result.put("active", info.isActive());
        result.put("expired", info.isExpired());
        result.put("devEdition", licenseService.isDevLicense());
        return result;
    }

    /** 激活 License */
    @PostMapping("/activate")
    public Map<String, Object> activate(@RequestBody Map<String, String> body) {
        String activationCode = body.get("activationCode");
        String tenantId = body.get("tenantId");
        String machineId = body.get("machineId");

        Map<String, Object> result = new LinkedHashMap<>();
        if (activationCode == null || tenantId == null || machineId == null) {
            result.put("success", false);
            result.put("message", "缺少必要参数: activationCode, tenantId, machineId");
            return result;
        }

        boolean valid = licenseService.validateActivation(activationCode, tenantId, machineId);
        result.put("success", valid);
        result.put("message", valid ? "激活成功" : "激活码无效");
        return result;
    }

    /** 验证 License 有效性 */
    @GetMapping("/verify")
    public Map<String, Object> verify() {
        LicenseInfo info = licenseService.getLicenseInfo();
        boolean valid = licenseService.validateLicense(info);
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("valid", valid);
        result.put("expired", info.isExpired());
        result.put("active", info.isActive());
        result.put("devEdition", licenseService.isDevLicense());
        return result;
    }
}