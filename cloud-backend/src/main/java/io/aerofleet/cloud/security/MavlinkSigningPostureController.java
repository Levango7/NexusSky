package io.aerofleet.cloud.security;

import io.aerofleet.cloud.security.SigningPostureService.Posture;
import io.aerofleet.cloud.security.SigningPostureService.PostureEvent;
import io.aerofleet.cloud.security.SigningPostureService.Readiness;
// RequireRole / Role 与本类同包（io.aerofleet.cloud.security），不需要 import
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 签名收紧姿态端点：把 {@code reject-unsigned} 的切换从"改配置 + 重启 + 祈祷"变成
 * 一次<b>带就绪校验、可回退、有审计</b>的操作。
 *
 * <p>动机见 {@link SigningPostureService} 类注释与 {@code docs/signing-prod-rollout.md}：
 * strict 下"设备没配密钥"的表现是那台机遥测静默消失，所以翻牌必须先把"哪几台还没证据"摊开。
 *
 * <p>权限口径与密钥轮换一致：<b>读</b> OBSERVER（指纹/名单属运维信息，不开放匿名）；
 * <b>写</b> ADMIN 且必须是 JWT 登录（API Key 不授予翻牌能力——它是机器凭据，不该能改变安全姿态）。
 */
@RestController
@RequestMapping("/api/v1/mavlink/signing/posture")
public class MavlinkSigningPostureController {

    private final SigningPostureService posture;

    public MavlinkSigningPostureController(SigningPostureService posture) {
        this.posture = posture;
    }

    /** 当前姿态 + 就绪判定 + 最近变更。收紧前该被反复调用的就是这个。 */
    @GetMapping
    @RequireRole(Role.OBSERVER)
    public ResponseEntity<Map<String, Object>> status() {
        return ResponseEntity.ok(body(posture.evaluateReadiness(), posture.recentEvents()));
    }

    /**
     * 收紧为 STRICT。不就绪返回 <b>409</b> 并附缺失 sysid 名单（不是 400：调用方给的信息没错，
     * 是服务端状态不允许），让 GCS 能直接把"该去配哪几台"显示出来。
     */
    @PostMapping("/strict")
    @RequireRole(Role.ADMIN)
    public ResponseEntity<Map<String, Object>> tighten(
            @RequestBody(required = false) Map<String, Object> req) {
        String denial = jwtOperatorDenial();
        if (denial != null) {
            return error(HttpStatus.UNAUTHORIZED, denial);
        }
        try {
            Readiness r = posture.requestStrict(operator(), reason(req));
            return ResponseEntity.ok(body(r, posture.recentEvents()));
        } catch (SigningPostureService.PostureRejectedException e) {
            // 消息里已含名单（服务层负责，保证非 HTTP 调用方也能看到）；响应体再给一份结构化的，
            // GCS 才能直接渲染"该去配哪几台"而不是去 parse 中文句子。
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("error", e.getMessage());
            m.put("missingSigningEvidence", e.missing());
            m.put("posture", posture.currentPosture().name());
            return ResponseEntity.status(HttpStatus.CONFLICT).body(m);
        }
    }

    /** 退回 PERMISSIVE（运维处置中途出问题时的退路）。 */
    @PostMapping("/permissive")
    @RequireRole(Role.ADMIN)
    public ResponseEntity<Map<String, Object>> loosen(
            @RequestBody(required = false) Map<String, Object> req) {
        String denial = jwtOperatorDenial();
        if (denial != null) {
            return error(HttpStatus.UNAUTHORIZED, denial);
        }
        try {
            Readiness r = posture.requestPermissive(operator(), reason(req));
            return ResponseEntity.ok(body(r, posture.recentEvents()));
        } catch (SigningPostureService.PostureRejectedException e) {
            return error(HttpStatus.CONFLICT, e.getMessage());
        }
    }

    // ===== 内部 =====

    private Map<String, Object> body(Readiness r, List<PostureEvent> events) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("posture", r.current().name());
        m.put("readyToTighten", r.ready() && r.current() != Posture.STRICT);
        m.put("onlineDevices", r.onlineDevices());
        m.put("coveredDevices", r.covered());
        m.put("missingSigningEvidence", r.missing());
        m.put("evidenceWindowMinutes", r.evidenceWindow().toMinutes());
        m.put("autoRollback", posture.isAutoRollback());
        m.put("evaluatedAt", r.evaluatedAt().toString());
        m.put("recentEvents", events.stream().map(e -> Map.of(
                "at", e.at().toString(), "from", e.from().name(), "to", e.to().name(),
                "actor", e.actor() == null ? "" : e.actor(),
                "reason", e.reason() == null ? "" : e.reason())).toList());
        return m;
    }

    /** 与密钥轮换同口径：必须是 JWT 登录，API Key 不放行。 */
    private String jwtOperatorDenial() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null || !(auth.getPrincipal() instanceof Jwt)) {
            return "JWT authentication required to change MAVLink signing posture"
                    + "（API Key 不具备改变安全姿态的权限）";
        }
        return null;
    }

    private String operator() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        Jwt jwt = (Jwt) auth.getPrincipal();
        String sub = jwt.getClaimAsString("preferred_username");
        return sub == null || sub.isEmpty() ? jwt.getSubject() : sub;
    }

    private String reason(Map<String, Object> req) {
        Object r = req == null ? null : req.get("reason");
        // 允许空 reason，但留个标记：没有理由的翻牌在审计里就该看得出来
        return r == null || String.valueOf(r).isBlank() ? "(未填写原因)" : String.valueOf(r);
    }

    private ResponseEntity<Map<String, Object>> error(HttpStatus status, String message) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("error", message);
        m.put("posture", posture.currentPosture().name());
        return ResponseEntity.status(status).body(m);
    }
}
