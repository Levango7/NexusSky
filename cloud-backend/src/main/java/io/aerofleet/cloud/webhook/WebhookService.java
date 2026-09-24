package io.aerofleet.cloud.webhook;

import io.aerofleet.cloud.security.TenantContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.client.RestTemplate;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;

/**
 * Webhook 服务：注册/注销/列出/触发推送。
 * <p>
 * 客户通过 {@link #register} 注册回调 URL，系统在事件发生时通过 {@link #trigger}
 * 主动推送通知。推送使用 HMAC-SHA256 签名，客户可验证请求来源。
 * <p>
 * 租户隔离：所有操作基于 {@link TenantContext#getEffectiveTenantId()} 自动过滤，
 * 注册时绑定当前租户 ID，查询和推送时仅访问当前租户的 webhook。
 * <p>
 * 推送失败时记录日志但不抛异常，确保不阻塞主流程。
 */
@Component
public class WebhookService {

    private static final Logger log = LoggerFactory.getLogger(WebhookService.class);

    /** HMAC-SHA256 算法名称。 */
    private static final String HMAC_ALGORITHM = "HmacSHA256";

    @Autowired(required = false)
    private WebhookRepository webhookRepository;

    private final RestTemplate restTemplate = new RestTemplate();

    /**
     * 注册 webhook。
     * <p>
     * 将回调 URL、签名密钥和事件类型列表持久化，绑定当前租户 ID。
     *
     * @param url    回调 URL
     * @param secret HMAC 签名密钥（可为 null，表示不签名）
     * @param events 订阅的事件类型列表
     * @return 已注册的 webhook 实体，若 repository 不可用则返回 null
     */
    @Transactional
    public WebhookEntity register(String url, String secret, List<String> events) {
        if (webhookRepository == null) {
            log.warn("WebhookRepository not available, cannot register webhook");
            return null;
        }

        Integer tenantId = TenantContext.getEffectiveTenantId();
        Instant now = Instant.now();

        WebhookEntity entity = new WebhookEntity();
        entity.setUrl(url);
        entity.setSecret(secret);
        entity.setEvents(events != null ? String.join(",", events) : "");
        entity.setTenantId(tenantId);
        entity.setEnabled(true);
        entity.setCreatedAt(now);
        entity.setUpdatedAt(now);

        webhookRepository.save(entity);
        log.info("Webhook 注册成功: id={} url={} events={} tenantId={}",
                entity.getId(), url, entity.getEvents(), tenantId);

        return entity;
    }

    /**
     * 注销 webhook。
     *
     * @param id webhook ID
     * @return true 表示注销成功，false 表示不存在或不可用
     */
    @Transactional
    public boolean unregister(Long id) {
        if (webhookRepository == null) {
            log.warn("WebhookRepository not available, cannot unregister webhook");
            return false;
        }

        Integer tenantId = TenantContext.getEffectiveTenantId();
        var entityOpt = webhookRepository.findById(id);
        if (entityOpt.isEmpty()) {
            return false;
        }

        WebhookEntity entity = entityOpt.get();
        // 租户隔离：仅允许注销当前租户的 webhook（全局管理员可注销所有）
        if (tenantId != null && entity.getTenantId() != null && !tenantId.equals(entity.getTenantId())) {
            log.warn("Webhook 注销被拒绝（租户不匹配）: id={} webhookTenantId={} currentTenantId={}",
                    id, entity.getTenantId(), tenantId);
            return false;
        }

        webhookRepository.delete(entity);
        log.info("Webhook 注销成功: id={}", id);
        return true;
    }

    /**
     * 列出当前租户的所有 webhook。
     *
     * @return webhook 实体列表，若 repository 不可用则返回空列表
     */
    public List<WebhookEntity> list() {
        if (webhookRepository == null) {
            log.warn("WebhookRepository not available, cannot list webhooks");
            return List.of();
        }

        Integer tenantId = TenantContext.getEffectiveTenantId();
        if (tenantId == null) {
            // 全局管理员：返回所有 webhook
            return webhookRepository.findAll();
        }
        return webhookRepository.findByTenantId(tenantId);
    }

    /**
     * 触发事件推送。
     * <p>
     * 查找所有已启用且订阅了该事件的 webhook，用 RestTemplate POST 推送 payload。
     * 推送时使用 HMAC-SHA256 对 payload 签名，签名放入 X-Webhook-Signature Header。
     * <p>
     * 推送失败时记录日志但不抛异常，确保不阻塞主流程。
     *
     * @param event   事件类型
     * @param payload 推送的数据
     */
    public void trigger(String event, Map<String, Object> payload) {
        if (webhookRepository == null) {
            return;
        }

        List<WebhookEntity> webhooks = webhookRepository.findByEnabledTrueAndEventsContaining(event);
        if (webhooks.isEmpty()) {
            return;
        }

        log.info("Webhook 触发: event={} webhookCount={}", event, webhooks.size());

        for (WebhookEntity webhook : webhooks) {
            try {
                pushToWebhook(webhook, event, payload);
            } catch (Exception e) {
                log.warn("Webhook 推送失败: id={} url={} err={}",
                        webhook.getId(), webhook.getUrl(), e.getMessage());
            }
        }
    }

    /**
     * 向单个 webhook 推送事件。
     * <p>
     * 使用 RestTemplate 发送 POST 请求，payload 为 JSON 格式。
     * 若 webhook 配置了 secret，则计算 HMAC-SHA256 签名并放入 Header。
     */
    private void pushToWebhook(WebhookEntity webhook, String event, Map<String, Object> payload) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.set("X-Webhook-Event", event);

        // 构建推送 body：包含事件类型和 payload
        Map<String, Object> body = new java.util.LinkedHashMap<>();
        body.put("event", event);
        body.put("timestamp", Instant.now().toString());
        body.put("data", payload);

        // 序列化 body 用于签名计算
        String bodyJson = serializeBody(body);

        // HMAC-SHA256 签名
        if (webhook.getSecret() != null && !webhook.getSecret().isBlank()) {
            String signature = hmacSha256Hex(webhook.getSecret(), bodyJson);
            headers.set("X-Webhook-Signature", signature);
        }

        HttpEntity<Map<String, Object>> request = new HttpEntity<>(body, headers);
        restTemplate.postForEntity(webhook.getUrl(), request, String.class);

        log.debug("Webhook 推送成功: id={} url={} event={}",
                webhook.getId(), webhook.getUrl(), event);
    }

    /** 计算 HMAC-SHA256 签名，返回 Hex 编码。 */
    private static String hmacSha256Hex(String secret, String data) {
        try {
            Mac mac = Mac.getInstance(HMAC_ALGORITHM);
            SecretKeySpec keySpec = new SecretKeySpec(
                    secret.getBytes(StandardCharsets.UTF_8), HMAC_ALGORITHM);
            mac.init(keySpec);
            byte[] hmacBytes = mac.doFinal(data.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hmacBytes);
        } catch (Exception e) {
            log.error("HMAC-SHA256 签名失败: {}", e.getMessage());
            return "";
        }
    }

    /** 简易 JSON 序列化（用于签名计算，不依赖 Jackson）。 */
    private static String serializeBody(Map<String, Object> body) {
        StringBuilder sb = new StringBuilder("{");
        boolean first = true;
        for (Map.Entry<String, Object> entry : body.entrySet()) {
            if (!first) {
                sb.append(",");
            }
            first = false;
            sb.append("\"").append(entry.getKey()).append("\":");
            Object val = entry.getValue();
            if (val == null) {
                sb.append("null");
            } else if (val instanceof String) {
                sb.append("\"").append(val).append("\"");
            } else {
                sb.append(val);
            }
        }
        sb.append("}");
        return sb.toString();
    }
}