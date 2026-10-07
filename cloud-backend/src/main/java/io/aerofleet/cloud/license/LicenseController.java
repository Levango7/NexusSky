package io.aerofleet.cloud.license;


import org.springframework.web.bind.annotation.*;


import java.util.LinkedHashMap;
import java.util.Map;
import io.aerofleet.cloud.security.RequireRole;
import io.aerofleet.cloud.security.Role;

/**
 * License 管理 REST 端点。
 * <p>
 * 提供查询、激活、验证 License 的 API。
 *
 * @author AeroFleet Cloud Team
 */
@RestController
@RequestMapping("/api/v1/license")
@RequireRole(Role.OBSERVER)
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
        // 档位与模块：运维问「这台部署卖的是哪一档、实际能开哪些模块」时的唯一入口。
        // tier 由模块集合反查（LicenseTier.inferTier），为 null 表示该组合不对应任何
        // 可售档位——配合 validateLicense 的档位绑定校验，这种情况在 prod 下应当起不来。
        String tier = LicenseTier.inferTier(info.getModules());
        result.put("tier", tier);
        result.put("tierDisplayName", LicenseTier.displayName(tier));
        result.put("modules", info.getModules());
        result.put("tierBindingEnforced", licenseService.isTierBindingEnforced());
        result.put("tierBindingViolation", licenseService.tierBindingViolation(info.getModules()));
        return result;
    }

    /** 激活 License */
    @PostMapping("/activate")
    @RequireRole(Role.ADMIN)
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
        // valid=false 时把档位违规原因一并返回：运维不必再去翻日志就能看出
        // 是「过期」还是「模块集合不对应任何可售档位」。
        result.put("tierBindingViolation", licenseService.tierBindingViolation(info.getModules()));
        return result;
    }
}