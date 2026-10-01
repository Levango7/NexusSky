package io.aerofleet.cloud.gateway;

import io.aerofleet.cloud.security.RequireRole;
import io.aerofleet.cloud.security.Role;
import io.aerofleet.cloud.security.TenantRepository;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
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
 * 设备快照有两个创建入口：UDP 接收线程（{@code DeviceRegistry.registerIfAbsent}）与
 * 本控制器的显式登记（{@code DeviceRegistry.provision}）。前者没有请求上下文，
 * 因此注册时无法确定租户——快照的 tenantId 为 null，语义是「未归属」，
 * 只对全局管理员上下文可见，对任何租户都不可见、不可控。
 * 本控制器提供登记设备与绑定/解绑租户的显式入口。
 * <p>
 * 端点：
 * <ul>
 *   <li>GET  /api/v1/devices/unassigned — 待归属设备 sysid 列表</li>
 *   <li>POST /api/v1/devices/{sysid} — 把设备登记进白名单（可带 {"tenantId":N}）</li>
 *   <li>DELETE /api/v1/devices/{sysid} — 撤销登记（白名单移除，persist 时连库行一起删）</li>
 *   <li>PUT  /api/v1/devices/{sysid}/tenant — 绑定（{"tenantId":N}）或解绑（{"tenantId":null}）</li>
 * </ul>
 * <p>
 * POST 那条是 prod 白名单的注册腿：白名单开启时陌生 sysid 的帧在进 ingest 之前就被丢弃，
 * 而快照过去只由被放行的帧创建，首台设备因此永远登记不上（死锁）。
 * <p>
 * 注意：{@code aerofleet.device-registry.persist=false} 时注册表是内存态，登记与归属都不跨重启保留；
 * prod profile 已置 true（见 application-prod.properties），dev/test 仍为 false。
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
     * 把设备登记进白名单（prod 白名单开启时首台设备的入口）。
     *
     * @param sysid 1..254，越界返回 400
     * @param body  可选 {@code {"tenantId": N}}；缺省登记为未归属
     * @return 201 新建 / 200 已存在（{@code alreadyRegistered=true}），均带 {@code persisted} 说明是否入库
     */
    @PostMapping("/{sysid}")
    @RequireRole(Role.ADMIN)
    public ResponseEntity<?> provision(@PathVariable int sysid,
                                       @RequestBody(required = false) Map<String, Object> body) {
        Integer tenantId = null;
        if (body != null && body.containsKey("tenantId")) {
            try {
                tenantId = parseTenantId(body.get("tenantId"));
            } catch (IllegalArgumentException e) {
                return errorResponse(HttpStatus.BAD_REQUEST, e.getMessage());
            }
            if (tenantId != null && !tenantRepository.existsById(tenantId)) {
                return errorResponse(HttpStatus.NOT_FOUND, "tenant not found: " + tenantId);
            }
        }

        boolean created;
        try {
            created = registry.provision(sysid, tenantId);
        } catch (IllegalArgumentException e) {
            return errorResponse(HttpStatus.BAD_REQUEST, e.getMessage());
        }

        Map<String, Object> resp = new LinkedHashMap<>();
        resp.put("sysid", sysid);
        resp.put("tenantId", tenantId);
        resp.put("persisted", registry.isPersisting());
        resp.put("alreadyRegistered", !created);
        return ResponseEntity.status(created ? HttpStatus.CREATED : HttpStatus.OK).body(resp);
    }

    /**
     * 撤销一台已登记设备（白名单移除；持久化开启时连库里的行一起删）。
     *
     * @param sysid 设备 MAVLink sysid
     * @return 200 {@code {sysid, deregistered:true, persisted}}；设备未知 404
     */
    @DeleteMapping("/{sysid}")
    @RequireRole(Role.ADMIN)
    public ResponseEntity<?> deregister(@PathVariable int sysid) {
        if (!registry.deregister(sysid)) {
            return errorResponse(HttpStatus.NOT_FOUND, "device unknown: sysid=" + sysid);
        }
        Map<String, Object> resp = new LinkedHashMap<>();
        resp.put("sysid", sysid);
        resp.put("deregistered", true);
        resp.put("persisted", registry.isPersisting());
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

        Integer tenantId;
        try {
            tenantId = parseTenantId(body.get("tenantId"));
        } catch (IllegalArgumentException e) {
            return errorResponse(HttpStatus.BAD_REQUEST, e.getMessage());
        }
        if (tenantId != null && !tenantRepository.existsById(tenantId)) {
            return errorResponse(HttpStatus.NOT_FOUND, "tenant not found: " + tenantId);
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

    /** 解析请求体里的 tenantId：数字、或数字字符串；JSON null 表示解绑/不指定。 */
    private static Integer parseTenantId(Object raw) {
        if (raw == null) {
            return null;
        }
        if (raw instanceof Number n) {
            return n.intValue();
        }
        try {
            return Integer.parseInt(raw.toString().trim());
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("tenantId must be an integer");
        }
    }
}
