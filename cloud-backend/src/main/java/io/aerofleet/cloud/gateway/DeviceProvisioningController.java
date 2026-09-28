package io.aerofleet.cloud.gateway;

import io.aerofleet.cloud.security.RequireRole;
import io.aerofleet.cloud.security.Role;
import io.aerofleet.cloud.security.TenantRepository;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 设备归属（provisioning）API，仅 ADMIN。
 * <p>
 * 设备快照由 UDP 接收线程创建（{@code DeviceRegistry.registerIfAbsent}），
 * 该线程没有请求上下文，因此注册时无法确定租户——快照的 tenantId 为 null，
 * 语义是「未归属」，只对全局管理员上下文可见，对任何租户都不可见、不可控。
 * 本控制器提供把设备绑定/解绑到租户的显式入口。
 * <p>
 * 端点：
 * <ul>
 *   <li>GET  /api/v1/devices/unassigned — 待归属设备 sysid 列表</li>
 *   <li>PUT  /api/v1/devices/{sysid}/tenant — 绑定（{"tenantId":N}）或解绑（{"tenantId":null}）</li>
 * </ul>
 * <p>
 * 注意：{@code aerofleet.device-registry.persist=false}（默认）时注册表是内存态，
 * 指派结果不跨重启保留；需要持久化归属请把该开关置 true。
 */
@RestController
@RequestMapping("/api/v1/devices")
public class DeviceProvisioningController {

    private final DeviceRegistry registry;
    private final TenantRepository tenantRepository;

    public DeviceProvisioningController(DeviceRegistry registry,
                                        TenantRepository tenantRepository) {
        this.registry = registry;
        this.tenantRepository = tenantRepository;
    }

    /**
     * 列出尚未归属任何租户的设备。
     *
     * @return {sysids: [...]}
     */
    @GetMapping("/unassigned")
    @RequireRole(Role.ADMIN)
    public ResponseEntity<Map<String, Object>> listUnassigned() {
        Map<String, Object> resp = new LinkedHashMap<>();
        resp.put("sysids", registry.unassigned());
        return ResponseEntity.ok(resp);
    }

    /**
     * 为设备指定或解除租户归属。
     *
     * @param sysid 设备 MAVLink sysid，未知返回 404
     * @param body  {"tenantId": N} 绑定；{"tenantId": null} 解绑；缺字段返回 400
     * @return {sysid, tenantId, assigned}
     */
    @PutMapping("/{sysid}/tenant")
    @RequireRole(Role.ADMIN)
    public ResponseEntity<?> assignTenant(@PathVariable int sysid,
                                          @RequestBody Map<String, Object> body) {
        if (body == null || !body.containsKey("tenantId")) {
            return errorResponse(HttpStatus.BAD_REQUEST, "tenantId is required (null to unassign)");
        }

        Object raw = body.get("tenantId");
        Integer tenantId = null;
        if (raw != null) {
            if (raw instanceof Number n) {
                tenantId = n.intValue();
            } else {
                try {
                    tenantId = Integer.parseInt(raw.toString().trim());
                } catch (NumberFormatException e) {
                    return errorResponse(HttpStatus.BAD_REQUEST, "tenantId must be an integer");
                }
            }
            if (!tenantRepository.existsById(tenantId)) {
                return errorResponse(HttpStatus.NOT_FOUND, "tenant not found: " + tenantId);
            }
        }

        if (!registry.assignTenant(sysid, tenantId)) {
            return errorResponse(HttpStatus.NOT_FOUND,
                    "device unknown: sysid=" + sysid + "（设备需先上线心跳，或在 persist=true 下已入库）");
        }

        Map<String, Object> resp = new LinkedHashMap<>();
        resp.put("sysid", sysid);
        resp.put("tenantId", tenantId);
        resp.put("assigned", tenantId != null);
        return ResponseEntity.ok(resp);
    }

    private ResponseEntity<Map<String, Object>> errorResponse(HttpStatus status, String message) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("error", message);
        return ResponseEntity.status(status).body(body);
    }
}
