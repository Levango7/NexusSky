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
import java.net.InetAddress;
import java.net.URI;
import java.net.URISyntaxException;
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

        // P0-fix: SSRF 防护 — 校验 webhook URL 合法性
        validateWebhookUrl(url);

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

        // P0-fix: 租户隔离 — 仅查询当前租户的 webhook，防止跨租户数据泄露
        Integer tenantId = TenantContext.getEffectiveTenantId();
        List<WebhookEntity> webhooks;
        if (tenantId != null) {
            webhooks = webhookRepository.findByEnabledTrueAndEventsContainingAndTenantId(event, tenantId);
        } else {
            // 全局管理员：查询所有租户的 webhook
            webhooks = webhookRepository.findByEnabledTrueAndEventsContaining(event);
        }
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
     * 校验 webhook URL 合法性，防止 SSRF 攻击。
     * <p>
     * 校验规则：
     * <ul>
     *   <li>必须以 http:// 或 https:// 开头</li>
     *   <li>禁止解析到私有 IP 段：10.x.x.x、172.16-31.x.x、192.168.x.x、127.x.x.x、169.254.x.x</li>
     *   <li>禁止 localhost 主机名</li>
     * </ul>
     *
     * @param url 待校验的 URL
     * @throws IllegalArgumentException 校验失败时抛出
     */
    private void validateWebhookUrl(String url) {
        if (url == null || url.isBlank()) {
            throw new IllegalArgumentException("Webhook URL is required");
        }

        // 必须以 http:// 或 https:// 开头
        if (!url.startsWith("http://") && !url.startsWith("https://")) {
            throw new IllegalArgumentException("Webhook URL must start with http:// or https://");
        }

        URI uri;
        try {
            uri = new URI(url);
        } catch (URISyntaxException e) {
            throw new IllegalArgumentException("Invalid webhook URL: " + e.getMessage());
        }

        String host = uri.getHost();
        if (host == null || host.isBlank()) {
            throw new IllegalArgumentException("Webhook URL must have a valid host");
        }

        // 禁止 localhost 主机名
        if ("localhost".equalsIgnoreCase(host)) {
            throw new IllegalArgumentException("Webhook URL must not use localhost");
        }

        // 解析 host 为 IP 地址，检查是否为私有地址
        InetAddress address;
        try {
            address = InetAddress.getByName(host);
        } catch (java.net.UnknownHostException e) {
            throw new IllegalArgumentException("Cannot resolve webhook URL host: " + host);
        }

        if (isPrivateAddress(address)) {
            throw new IllegalArgumentException("Webhook URL must not point to a private/internal address");
        }
    }

    /**
     * 检查 IP 地址是否属于私有或内部保留地址段。
     *
     * @param address 待检查的 IP 地址
     * @return true 表示是私有地址
     */
    private static boolean isPrivateAddress(InetAddress address) {
        return address.isSiteLocalAddress()   // 10.x.x.x, 172.16-31.x.x, 192.168.x.x
                || address.isLoopbackAddress() // 127.x.x.x
                || address.isLinkLocalAddress() // 169.254.x.x
                || address.isAnyLocalAddress(); // 0.0.0.0
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