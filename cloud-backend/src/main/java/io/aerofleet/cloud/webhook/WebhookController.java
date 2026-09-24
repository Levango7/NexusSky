package io.aerofleet.cloud.webhook;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Webhook 管理端点。
 * <p>
 * 所有端点需认证，租户隔离由 {@link WebhookService} 基于 {@link io.aerofleet.cloud.security.TenantContext} 实现：
 * <ul>
 *   <li>POST /api/v1/webhooks — 注册 webhook</li>
 *   <li>GET /api/v1/webhooks — 列出当前租户的 webhook</li>
 *   <li>DELETE /api/v1/webhooks/{id} — 注销 webhook</li>
 * </ul>
 * <p>
 * 注册请求体格式：
 * <pre>{@code
 * {
 *   "url": "https://example.com/webhook",
 *   "secret": "my-hmac-secret",
 *   "events": ["device.online", "device.offline"]
 * }
 * }</pre>
 */
@RestController
@RequestMapping("/api/v1/webhooks")
public class WebhookController {

    private static final Logger log = LoggerFactory.getLogger(WebhookController.class);

    @Autowired(required = false)
    private WebhookService webhookService;

    /**
     * 注册 webhook。
     * <p>
     * POST /api/v1/webhooks {url, secret?, events} → {id, url, events, enabled, ...}
     *
     * @param body 请求体，包含 url（必填）、secret（可选）、events（必填）
     * @return 已注册的 webhook 信息
     */
    @PostMapping
    public ResponseEntity<Map<String, Object>> register(@RequestBody Map<String, Object> body) {
        if (webhookService == null) {
            return errorResponse(HttpStatus.SERVICE_UNAVAILABLE,
                    "Webhook service not available");
        }

        String url = (String) body.get("url");
        if (url == null || url.isBlank()) {
            return errorResponse(HttpStatus.BAD_REQUEST, "url is required");
        }

        String secret = (String) body.get("secret");

        @SuppressWarnings("unchecked")
        List<String> events = (List<String>) body.get("events");
        if (events == null || events.isEmpty()) {
            return errorResponse(HttpStatus.BAD_REQUEST, "events is required");
        }

        WebhookEntity entity = webhookService.register(url, secret, events);
        if (entity == null) {
            return errorResponse(HttpStatus.SERVICE_UNAVAILABLE,
                    "Webhook repository not available");
        }

        log.info("Webhook 注册请求: url={} events={}", url, events);

        Map<String, Object> resp = new LinkedHashMap<>();
        resp.put("id", entity.getId());
        resp.put("url", entity.getUrl());
        resp.put("events", entity.getEvents());
        resp.put("enabled", entity.getEnabled());
        resp.put("createdAt", entity.getCreatedAt() != null ? entity.getCreatedAt().toString() : null);

        return ResponseEntity.status(HttpStatus.CREATED).body(resp);
    }

    /**
     * 列出当前租户的 webhook。
     * <p>
     * GET /api/v1/webhooks → [{id, url, events, enabled, createdAt, updatedAt}]
     *
     * @return webhook 列表（不含 secret）
     */
    @GetMapping
    public ResponseEntity<?> list() {
        if (webhookService == null) {
            return errorResponse(HttpStatus.SERVICE_UNAVAILABLE,
                    "Webhook service not available");
        }

        List<WebhookEntity> webhooks = webhookService.list();

        List<Map<String, Object>> result = webhooks.stream()
                .map(this::toDto)
                .toList();

        return ResponseEntity.ok(result);
    }

    /**
     * 注销 webhook。
     * <p>
     * DELETE /api/v1/webhooks/{id} → {id, deleted: true}
     *
     * @param id webhook ID
     * @return 注销结果
     */
    @DeleteMapping("/{id}")
    public ResponseEntity<Map<String, Object>> unregister(@PathVariable Long id) {
        if (webhookService == null) {
            return errorResponse(HttpStatus.SERVICE_UNAVAILABLE,
                    "Webhook service not available");
        }

        boolean deleted = webhookService.unregister(id);
        if (!deleted) {
            return errorResponse(HttpStatus.NOT_FOUND, "Webhook not found or not accessible");
        }

        Map<String, Object> resp = new LinkedHashMap<>();
        resp.put("id", id);
        resp.put("deleted", true);

        return ResponseEntity.ok(resp);
    }

    // --- 辅助方法 ---

    /** 将实体转换为 DTO（不含 secret）。 */
    private Map<String, Object> toDto(WebhookEntity entity) {
        Map<String, Object> dto = new LinkedHashMap<>();
        dto.put("id", entity.getId());
        dto.put("url", entity.getUrl());
        dto.put("events", entity.getEvents());
        dto.put("enabled", entity.getEnabled());
        dto.put("createdAt", entity.getCreatedAt() != null ? entity.getCreatedAt().toString() : null);
        dto.put("updatedAt", entity.getUpdatedAt() != null ? entity.getUpdatedAt().toString() : null);
        return dto;
    }

    private ResponseEntity<Map<String, Object>> errorResponse(HttpStatus status, String message) {
        Map<String, Object> body = new HashMap<>();
        body.put("error", message);
        return ResponseEntity.status(status).body(body);
    }
}