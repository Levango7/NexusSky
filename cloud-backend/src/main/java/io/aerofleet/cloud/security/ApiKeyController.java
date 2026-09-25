package io.aerofleet.cloud.security;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * API Key 管理端点。
 * <p>
 * 所有端点需 JWT 认证（不能用 API Key 创建/管理 API Key）：
 * <ul>
 *   <li>POST /api/v1/auth/api-key — 生成 API Key</li>
 *   <li>DELETE /api/v1/auth/api-key/{keyId} — 撤销 API Key</li>
 *   <li>GET /api/v1/auth/api-key — 列出当前用户的 API Key（脱敏显示）</li>
 * </ul>
 * <p>
 * API Key 格式：{@code nsk_<32位随机hex>}（NexusSky Key 前缀），
 * 使用 {@link SecureRandom} 生成随机部分，确保不可预测。
 * <p>
 * 安全设计：数据库中只存储 API Key 的 SHA-256 哈希（keyHash），
 * 明文 API Key 仅在创建时返回一次，后续不可查看。
 */
@RestController
@RequestMapping("/api/v1/auth/api-key")
public class ApiKeyController {

    private static final Logger log = LoggerFactory.getLogger(ApiKeyController.class);

    /** API Key 前缀，格式为 nsk_（NexusSky Key）。 */
    private static final String KEY_PREFIX = "nsk_";

    /** API Key 随机部分的字节长度（32 字节 = 64 hex 字符）。 */
    private static final int RANDOM_BYTES = 32;

    /** 默认有效期：365 天。 */
    private static final long DEFAULT_EXPIRY_DAYS = 365;

    private final SecureRandom secureRandom = new SecureRandom();

    @Autowired(required = false)
    private ApiKeyRepository apiKeyRepository;

    @Autowired(required = false)
    private UserRepository userRepository;

    /**
     * 生成 API Key（需 JWT 认证）。
     * <p>
     * POST /api/v1/auth/api-key {name, scopes?, expiresAt?} → {keyId, apiKey, maskedKey, ...}
     * <p>
     * 完整 API Key 仅在创建时返回一次，后续不可查看。
     * 数据库中只存储 SHA-256 哈希，不存储明文。
     */
    @PostMapping
    public ResponseEntity<Map<String, Object>> createApiKey(@RequestBody Map<String, Object> body) {
        if (apiKeyRepository == null) {
            return errorResponse(HttpStatus.SERVICE_UNAVAILABLE,
                    "API Key repository not available");
        }

        // 从 JWT 认证上下文获取用户信息
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null || !(auth.getPrincipal() instanceof Jwt jwt)) {
            return errorResponse(HttpStatus.UNAUTHORIZED,
                    "JWT authentication required to create API Key");
        }

        String username = jwt.getSubject();
        Integer tenantId = extractClaimAsInteger(jwt, "tenant_id");

        // 查找用户实体获取 userId
        Integer userId = null;
        if (userRepository != null) {
            var userOpt = userRepository.findByUsername(username);
            if (userOpt.isPresent()) {
                userId = userOpt.get().getId();
            }
        }

        // 解析请求参数
        String name = (String) body.get("name");
        if (name == null || name.isBlank()) {
            return errorResponse(HttpStatus.BAD_REQUEST, "name is required");
        }

        @SuppressWarnings("unchecked")
        List<String> scopesList = (List<String>) body.get("scopes");
        String scopes = scopesList != null ? scopesList.toString() : "[]";

        // 解析过期时间（可选，默认 365 天）
        Instant expiresAt;
        Object expiresAtObj = body.get("expiresAt");
        if (expiresAtObj != null) {
            try {
                expiresAt = Instant.parse(expiresAtObj.toString());
            } catch (Exception e) {
                return errorResponse(HttpStatus.BAD_REQUEST,
                        "expiresAt must be ISO-8601 format (e.g., 2025-12-31T23:59:59Z)");
            }
        } else {
            expiresAt = Instant.now().plus(DEFAULT_EXPIRY_DAYS, ChronoUnit.DAYS);
        }

        // 生成完整 API Key：nsk_<32位随机hex>
        String randomHex = generateRandomHex(RANDOM_BYTES);
        String apiKey = KEY_PREFIX + randomHex;

        // 生成 keyId（展示标识，不含完整 Key）
        String keyId = KEY_PREFIX + tenantId + "_" + randomHex.substring(0, 8);

        // 计算 SHA-256 哈希，数据库只存储哈希
        String keyHash = sha256Hex(apiKey);

        // 生成脱敏 Key（仅保留前4后4字符）
        String maskedKey = maskKey(apiKey);

        // 创建并保存实体
        Instant now = Instant.now();
        ApiKeyEntity entity = new ApiKeyEntity();
        entity.setKeyId(keyId);
        entity.setKeyHash(keyHash);
        entity.setTenantId(tenantId);
        entity.setUserId(userId);
        entity.setName(name);
        entity.setScopes(scopes);
        entity.setCreatedAt(now);
        entity.setExpiresAt(expiresAt);
        entity.setLastUsedAt(null);
        entity.setRevoked(false);
        entity.setMaskedKey(maskedKey);

        apiKeyRepository.save(entity);

        log.info("API Key 创建成功: keyId={}, name={}, username={}, tenantId={}",
                keyId, name, username, tenantId);

        // 返回完整 API Key（仅此一次）
        Map<String, Object> resp = new LinkedHashMap<>();
        resp.put("keyId", keyId);
        resp.put("apiKey", apiKey);
        resp.put("maskedKey", maskedKey);
        resp.put("name", name);
        resp.put("scopes", scopes);
        resp.put("createdAt", now.toString());
        resp.put("expiresAt", expiresAt.toString());
        resp.put("warning", "This is the only time the full API Key will be shown. Please save it securely.");

        return ResponseEntity.status(HttpStatus.CREATED).body(resp);
    }

    /**
     * 撤销 API Key（需 JWT 认证）。
     * <p>
     * DELETE /api/v1/auth/api-key/{keyId} → {keyId, revoked: true}
     */
    @DeleteMapping("/{keyId}")
    public ResponseEntity<Map<String, Object>> revokeApiKey(@PathVariable String keyId) {
        if (apiKeyRepository == null) {
            return errorResponse(HttpStatus.SERVICE_UNAVAILABLE,
                    "API Key repository not available");
        }

        // 从 JWT 认证上下文获取用户信息
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null || !(auth.getPrincipal() instanceof Jwt)) {
            return errorResponse(HttpStatus.UNAUTHORIZED,
                    "JWT authentication required to revoke API Key");
        }

        Jwt jwt = (Jwt) auth.getPrincipal();
        Integer currentTenantId = extractClaimAsInteger(jwt, "tenant_id");
        String currentUsername = jwt.getSubject();

        var entityOpt = apiKeyRepository.findByKeyId(keyId);
        if (entityOpt.isEmpty()) {
            return errorResponse(HttpStatus.NOT_FOUND, "API Key not found");
        }

        ApiKeyEntity entity = entityOpt.get();
        if (currentTenantId != null && entity.getTenantId() != null
                && !currentTenantId.equals(entity.getTenantId())) {
            log.warn("API Key 撤销被拒绝（租户不匹配）: keyId={} keyTenantId={} currentTenantId={}",
                    keyId, entity.getTenantId(), currentTenantId);
            return errorResponse(HttpStatus.FORBIDDEN, "API Key does not belong to your tenant");
        }

        entity.setRevoked(true);
        apiKeyRepository.save(entity);

        log.info("API Key 撤销成功: keyId={}", keyId);

        Map<String, Object> resp = new LinkedHashMap<>();
        resp.put("keyId", keyId);
        resp.put("revoked", true);

        return ResponseEntity.ok(resp);
    }

    /**
     * 列出当前用户的 API Key（脱敏显示，需 JWT 认证）。
     * <p>
     * GET /api/v1/auth/api-key → [{keyId, maskedKey, name, scopes, createdAt, expiresAt, lastUsedAt, revoked}]
     */
    @GetMapping
    public ResponseEntity<?> listApiKeys() {
        if (apiKeyRepository == null) {
            return errorResponse(HttpStatus.SERVICE_UNAVAILABLE,
                    "API Key repository not available");
        }

        // 从 JWT 认证上下文获取用户信息
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null || !(auth.getPrincipal() instanceof Jwt jwt)) {
            return errorResponse(HttpStatus.UNAUTHORIZED,
                    "JWT authentication required to list API Keys");
        }

        String username = jwt.getSubject();
        Integer tenantId = extractClaimAsInteger(jwt, "tenant_id");

        Integer userId = null;
        if (userRepository != null) {
            var userOpt = userRepository.findByUsername(username);
            if (userOpt.isPresent()) {
                userId = userOpt.get().getId();
            }
        }

        List<ApiKeyEntity> keys;
        if (userId != null) {
            keys = apiKeyRepository.findByUserId(userId);
        } else if (tenantId != null) {
            keys = apiKeyRepository.findByTenantId(tenantId);
        } else {
            keys = List.of();
        }

        // 脱敏返回
        List<Map<String, Object>> result = keys.stream()
                .map(this::toMaskedDto)
                .toList();

        return ResponseEntity.ok(result);
    }

    // --- 辅助方法 ---

    /** 从 JWT claim 提取 Integer 值。 */
    private Integer extractClaimAsInteger(Jwt jwt, String claimName) {
        Object claim = jwt.getClaim(claimName);
        if (claim == null) {
            return null;
        }
        if (claim instanceof Integer) {
            return (Integer) claim;
        }
        if (claim instanceof Number) {
            return ((Number) claim).intValue();
        }
        try {
            return Integer.valueOf(claim.toString());
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /** 使用 SecureRandom 生成指定字节数的随机 hex 字符串。 */
    private String generateRandomHex(int numBytes) {
        byte[] bytes = new byte[numBytes];
        secureRandom.nextBytes(bytes);
        return HexFormat.of().formatHex(bytes);
    }

    /** 计算字符串的 SHA-256 哈希，返回 Hex 编码的哈希值。 */
    private static String sha256Hex(String input) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hashBytes = digest.digest(input.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hashBytes);
        } catch (NoSuchAlgorithmException e) {
            throw new RuntimeException("SHA-256 算法不可用", e);
        }
    }

    /** 生成脱敏 Key，仅保留前4后4字符，中间用 **** 替代。 */
    private String maskKey(String key) {
        if (key == null || key.length() <= 8) {
            return "****";
        }
        return key.substring(0, 4) + "****" + key.substring(key.length() - 4);
    }

    /** 将实体转换为脱敏 DTO（不含完整 Key）。 */
    private Map<String, Object> toMaskedDto(ApiKeyEntity entity) {
        Map<String, Object> dto = new LinkedHashMap<>();
        dto.put("keyId", entity.getKeyId());
        dto.put("maskedKey", entity.getMaskedKey());
        dto.put("name", entity.getName());
        dto.put("scopes", entity.getScopes());
        dto.put("createdAt", entity.getCreatedAt() != null ? entity.getCreatedAt().toString() : null);
        dto.put("expiresAt", entity.getExpiresAt() != null ? entity.getExpiresAt().toString() : null);
        dto.put("lastUsedAt", entity.getLastUsedAt() != null ? entity.getLastUsedAt().toString() : null);
        dto.put("revoked", entity.isRevoked());
        return dto;
    }

    private ResponseEntity<Map<String, Object>> errorResponse(HttpStatus status, String message) {
        Map<String, Object> body = new HashMap<>();
        body.put("error", message);
        return ResponseEntity.status(status).body(body);
    }
}
