package io.aerofleet.cloud.security;

import io.aerofleet.cloud.gateway.DeviceRegistry;
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
 *   <li>POST /api/v1/auth/api-key — 生成 API Key（普通 Key，或带 {@code sysid} 的设备 Key）</li>
 *   <li>POST /api/v1/auth/api-key/{keyId}/rotate — 轮换 API Key（可带宽限期）</li>
 *   <li>DELETE /api/v1/auth/api-key/{keyId} — 撤销 API Key</li>
 *   <li>GET /api/v1/auth/api-key — 列出当前用户的 API Key（脱敏显示）</li>
 * </ul>
 * <p>
 * API Key 格式：{@code nsk_<64位随机hex>}（NexusSky Key 前缀），
 * 使用 {@link SecureRandom} 生成随机部分，确保不可预测。
 * <p>
 * <b>设备 Key</b>（body 带 {@code sysid}）：绑定单台设备，role 固定 {@code OPERATOR}
 * （设备永远不需要 ADMIN），tenantId 取自 devices 表该 sysid 行——设备必须已登记且
 * 已归属租户（未归属设备签出的 Key 会落 NO_ACCESS，数据写进去任何租户都看不见，
 * 属于配置错误，就地 400 拒绝）。撤销/轮换粒度 = 单台设备，一把 Key 泄露只换一台的。
 * <p>
 * <b>轮换</b>：{@code graceHours>0} 时旧 Key 保持有效至 {@code now+graceHours}
 * （宽限期内新旧并存，设备可从容换钥，掉线窗口可控）；缺省 0 = 立即失效。
 * 新 Key 继承旧 Key 的剩余有效期。新旧 Key 的撤销/失效对本 JVM 即时生效
 * （{@link ApiKeyCache#invalidate}），多节点至多一个缓存 TTL（60s）。
 * <p>
 * 安全设计：数据库中只存储 API Key 的 SHA-256 哈希（keyHash），
 * 明文 API Key 仅在创建/轮换时返回一次，后续不可查看。
 */
@RestController
@RequestMapping("/api/v1/auth/api-key")
@RequireRole(Role.OBSERVER)
public class ApiKeyController {

    private static final Logger log = LoggerFactory.getLogger(ApiKeyController.class);

    /** API Key 前缀，格式为 nsk_（NexusSky Key）。 */
    private static final String KEY_PREFIX = "nsk_";

    /** API Key 随机部分的字节长度（32 字节 = 64 hex 字符）。 */
    private static final int RANDOM_BYTES = 32;

    /** 默认有效期：365 天。 */
    private static final long DEFAULT_EXPIRY_DAYS = 365;

    /** 设备 sysid 合法范围（与 DeviceRegistry 白名单口径一致）。 */
    private static final int MIN_SYSID = 1;
    private static final int MAX_SYSID = 254;

    /** 轮换宽限期上限：一年（防手滑写成天文数字）。 */
    private static final long MAX_GRACE_HOURS = 8760;

    private final SecureRandom secureRandom = new SecureRandom();

    @Autowired(required = false)
    private ApiKeyRepository apiKeyRepository;

    @Autowired(required = false)
    private UserRepository userRepository;

    @Autowired(required = false)
    private DeviceRegistry deviceRegistry;

    @Autowired(required = false)
    private ApiKeyCache apiKeyCache;

    /**
     * 生成 API Key（需 JWT 认证 + ADMIN）。
     * <p>
     * POST /api/v1/auth/api-key {name, scopes?, expiresAt?, sysid?} →
     * {keyId, apiKey, maskedKey, name, scopes, role, sysid?, createdAt, expiresAt, ...}
     * <p>
     * 带 {@code sysid} 时签发<b>设备 Key</b>（role 固定 OPERATOR、tenantId 取自设备行，
     * 设备须已登记且已归属租户）；不带则是普通用户/租户级 Key（role 继承签发者）。
     * 完整 API Key 仅在创建时返回一次，后续不可查看。
     * 数据库中只存储 SHA-256 哈希，不存储明文。
     */
    @PostMapping
    @RequireRole(Role.ADMIN)
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

        // Key 继承签发者的角色，供 RoleInterceptor 在纯 API Key 调用（SDK 路径）下判定
        String role = jwt.getClaimAsString("role");

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

        // 设备 Key 分支：body 带 sysid 时绑定单台设备（role 固定 OPERATOR、租户取自设备行）
        Integer sysid = parseSysid(body.get("sysid"));
        if (body.containsKey("sysid") && sysid == null) {
            return errorResponse(HttpStatus.BAD_REQUEST,
                    "sysid must be an integer in [" + MIN_SYSID + ", " + MAX_SYSID + "]");
        }
        if (sysid != null) {
            if (deviceRegistry == null) {
                return errorResponse(HttpStatus.SERVICE_UNAVAILABLE,
                        "Device registry not available");
            }
            // 读注册表而非 devices 表：dev/test 下 device-registry.persist=false，
            // provisioning/心跳只更新内存注册表，devices 表恒为空——读库会把
            // 刚登记的设备误判成未知（404）。注册表才是两种模式一致的"已登记"真值源。
            if (!deviceRegistry.isKnownDevice(sysid)) {
                return errorResponse(HttpStatus.NOT_FOUND,
                        "device unknown: sysid=" + sysid + "（先 POST /api/v1/devices/" + sysid + " 登记）");
            }
            Integer deviceTenantId = deviceRegistry.tenantOf(sysid);
            if (deviceTenantId == null) {
                return errorResponse(HttpStatus.BAD_REQUEST,
                        "device has no tenant: sysid=" + sysid
                                + "（先 PUT /api/v1/devices/" + sysid + "/tenant 归属租户，"
                                + "否则该 Key 的数据任何租户都不可见）");
            }
            // 跨租户防护：租户级 ADMIN 只能给本租户设备签 Key，全局管理员不受限
            boolean privileged = tenantId == null || Role.ADMIN.name().equalsIgnoreCase(role);
            if (!privileged && !deviceTenantId.equals(tenantId)) {
                log.warn("设备 Key 签发被拒绝（租户不匹配）: sysid={} deviceTenantId={} issuerTenantId={} username={}",
                        sysid, deviceTenantId, tenantId, username);
                return errorResponse(HttpStatus.FORBIDDEN, "device does not belong to your tenant");
            }
            // 设备 Key 的固定语义：role=OPERATOR、租户=设备租户、无 user 归属
            role = Role.OPERATOR.name();
            tenantId = deviceTenantId;
            userId = null;
            if (scopesList == null) {
                scopes = "[ingest]";
            }
        }

        MintedKey minted = mintKey(tenantId, userId, sysid, name, scopes, role, expiresAt);
        apiKeyRepository.save(minted.entity());

        log.info("API Key 创建成功: keyId={}, name={}, username={}, tenantId={}, sysid={}",
                minted.entity().getKeyId(), name, username, tenantId, sysid);

        // 返回完整 API Key（仅此一次）
        Map<String, Object> resp = new LinkedHashMap<>();
        resp.put("keyId", minted.entity().getKeyId());
        resp.put("apiKey", minted.apiKey());
        resp.put("maskedKey", minted.entity().getMaskedKey());
        resp.put("name", name);
        resp.put("scopes", scopes);
        resp.put("role", role);
        resp.put("sysid", sysid);
        resp.put("createdAt", minted.entity().getCreatedAt().toString());
        resp.put("expiresAt", expiresAt.toString());
        resp.put("warning", "This is the only time the full API Key will be shown. Please save it securely.");

        return ResponseEntity.status(HttpStatus.CREATED).body(resp);
    }

    /**
     * 轮换 API Key（需 JWT 认证 + ADMIN）。
     * <p>
     * POST /api/v1/auth/api-key/{keyId}/rotate {graceHours?} →
     * {keyId, apiKey(新，仅此一次), oldKeyId, oldKeyExpiresAt | oldKeyRevoked, graceHours}
     * <p>
     * 新 Key 继承旧 Key 的绑定（租户/设备/sysid/scopes/role）与剩余有效期；
     * {@code graceHours>0} 时旧 Key 宽限至 {@code now+graceHours}（新旧并存，
     * 设备从容换钥），缺省 0 = 旧 Key 立即失效。本 JVM 内即时生效（缓存失效）。
     */
    @PostMapping("/{keyId}/rotate")
    @RequireRole(Role.ADMIN)
    public ResponseEntity<Map<String, Object>> rotateApiKey(@PathVariable String keyId,
                                                            @RequestBody(required = false) Map<String, Object> body) {
        if (apiKeyRepository == null) {
            return errorResponse(HttpStatus.SERVICE_UNAVAILABLE,
                    "API Key repository not available");
        }

        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null || !(auth.getPrincipal() instanceof Jwt jwt)) {
            return errorResponse(HttpStatus.UNAUTHORIZED,
                    "JWT authentication required to rotate API Key");
        }

        Integer currentTenantId = extractClaimAsInteger(jwt, "tenant_id");
        String currentRole = jwt.getClaimAsString("role");

        // 宽限期解析：缺省 0（立即失效）；0..MAX_GRACE_HOURS
        long graceHours = 0;
        if (body != null && body.get("graceHours") != null) {
            try {
                graceHours = Long.parseLong(body.get("graceHours").toString().trim());
            } catch (NumberFormatException e) {
                return errorResponse(HttpStatus.BAD_REQUEST, "graceHours must be a non-negative integer");
            }
            if (graceHours < 0 || graceHours > MAX_GRACE_HOURS) {
                return errorResponse(HttpStatus.BAD_REQUEST,
                        "graceHours must be in [0, " + MAX_GRACE_HOURS + "]");
            }
        }

        var entityOpt = apiKeyRepository.findByKeyId(keyId);
        if (entityOpt.isEmpty()) {
            return errorResponse(HttpStatus.NOT_FOUND, "API Key not found");
        }

        ApiKeyEntity old = entityOpt.get();
        // 跨租户防护：与撤销同一口径
        boolean privileged = currentTenantId == null
                || Role.ADMIN.name().equalsIgnoreCase(currentRole);
        if (!privileged && (old.getTenantId() == null || !currentTenantId.equals(old.getTenantId()))) {
            log.warn("API Key 轮换被拒绝（租户不匹配）: keyId={} keyTenantId={} currentTenantId={} username={}",
                    keyId, old.getTenantId(), currentTenantId, jwt.getSubject());
            return errorResponse(HttpStatus.FORBIDDEN, "API Key does not belong to your tenant");
        }

        Instant now = Instant.now();

        // 新 Key 继承剩余有效期：永久 Key 保持永久；已过期的旧 Key 轮换 = 重新计时默认有效期
        Instant newExpiresAt;
        if (old.getExpiresAt() == null) {
            newExpiresAt = null;
        } else if (old.getExpiresAt().isAfter(now)) {
            newExpiresAt = old.getExpiresAt();
        } else {
            newExpiresAt = now.plus(DEFAULT_EXPIRY_DAYS, ChronoUnit.DAYS);
        }

        MintedKey minted = mintKey(old.getTenantId(), old.getUserId(), old.getSysid(),
                old.getName(), old.getScopes(), old.getRole(), newExpiresAt);
        apiKeyRepository.save(minted.entity());

        // 旧 Key 处置：宽限期 → 缩短 expiresAt（不撤销，宽限期内新旧并存）；否则立即撤销
        if (graceHours > 0) {
            Instant graceExpiry = now.plus(graceHours, ChronoUnit.HOURS);
            // 旧 Key 原有更早的过期时间时不放宽
            if (old.getExpiresAt() == null || old.getExpiresAt().isAfter(graceExpiry)) {
                old.setExpiresAt(graceExpiry);
            }
        } else {
            old.setRevoked(true);
        }
        apiKeyRepository.save(old);

        // 本 JVM 内旧 Key 即时失效（多节点至多一个缓存 TTL）
        if (apiKeyCache != null) {
            apiKeyCache.invalidate(old.getKeyHash());
        }

        log.info("API Key 轮换成功: oldKeyId={} newKeyId={} graceHours={} sysid={}",
                keyId, minted.entity().getKeyId(), graceHours, old.getSysid());

        Map<String, Object> resp = new LinkedHashMap<>();
        resp.put("keyId", minted.entity().getKeyId());
        resp.put("apiKey", minted.apiKey());
        resp.put("maskedKey", minted.entity().getMaskedKey());
        resp.put("oldKeyId", keyId);
        if (graceHours > 0) {
            resp.put("oldKeyExpiresAt", old.getExpiresAt().toString());
        } else {
            resp.put("oldKeyRevoked", true);
        }
        resp.put("graceHours", graceHours);
        resp.put("expiresAt", newExpiresAt != null ? newExpiresAt.toString() : null);
        resp.put("warning", "This is the only time the full API Key will be shown. Please save it securely.");

        return ResponseEntity.status(HttpStatus.CREATED).body(resp);
    }

    /**
     * 撤销 API Key（需 JWT 认证）。
     * <p>
     * DELETE /api/v1/auth/api-key/{keyId} → {keyId, revoked: true}
     */
    @DeleteMapping("/{keyId}")
    @RequireRole(Role.ADMIN)
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
        String currentRole = jwt.getClaimAsString("role");

        var entityOpt = apiKeyRepository.findByKeyId(keyId);
        if (entityOpt.isEmpty()) {
            return errorResponse(HttpStatus.NOT_FOUND, "API Key not found");
        }

        ApiKeyEntity entity = entityOpt.get();
        // 全局管理员上下文（无 tenant_id）或 ADMIN 角色可跨租户撤销；
        // 其余情况要求两侧租户都明确且相等——未归属 Key（tenant_id 为空）只允许管理员撤销，
        // 否则历史上一方为 null 就会跳过比对，任何已认证用户都能撤销别人的 Key。
        boolean privileged = currentTenantId == null
                || Role.ADMIN.name().equalsIgnoreCase(currentRole);
        if (!privileged && (entity.getTenantId() == null || !currentTenantId.equals(entity.getTenantId()))) {
            log.warn("API Key 撤销被拒绝（租户不匹配）: keyId={} keyTenantId={} currentTenantId={} username={}",
                    keyId, entity.getTenantId(), currentTenantId, jwt.getSubject());
            return errorResponse(HttpStatus.FORBIDDEN, "API Key does not belong to your tenant");
        }

        entity.setRevoked(true);
        apiKeyRepository.save(entity);

        // 本 JVM 内即时失效（多节点至多一个缓存 TTL）
        if (apiKeyCache != null) {
            apiKeyCache.invalidate(entity.getKeyHash());
        }

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

    /**
     * 铸造一把新 Key：生成明文、keyId、SHA-256 哈希与脱敏串，装配实体（未落库）。
     * 明文只在返回值里出现一次，调用方直接返回给客户端，任何地方不留存。
     */
    private MintedKey mintKey(Integer tenantId, Integer userId, Integer sysid,
                              String name, String scopes, String role, Instant expiresAt) {
        String randomHex = generateRandomHex(RANDOM_BYTES);
        String apiKey = KEY_PREFIX + randomHex;
        String keyId = KEY_PREFIX + tenantId + "_" + randomHex.substring(0, 8);

        ApiKeyEntity entity = new ApiKeyEntity();
        entity.setKeyId(keyId);
        entity.setKeyHash(sha256Hex(apiKey));
        entity.setTenantId(tenantId);
        entity.setUserId(userId);
        entity.setSysid(sysid);
        entity.setName(name);
        entity.setScopes(scopes);
        entity.setRole(role);
        entity.setCreatedAt(Instant.now());
        entity.setExpiresAt(expiresAt);
        entity.setLastUsedAt(null);
        entity.setRevoked(false);
        entity.setMaskedKey(maskKey(apiKey));
        return new MintedKey(apiKey, entity);
    }

    /** 解析 body 里的 sysid：数字或数字字符串，越界/非数字返回 null。 */
    private static Integer parseSysid(Object raw) {
        if (raw == null) {
            return null;
        }
        int sysid;
        if (raw instanceof Number n) {
            sysid = n.intValue();
        } else {
            try {
                sysid = Integer.parseInt(raw.toString().trim());
            } catch (NumberFormatException e) {
                return null;
            }
        }
        if (sysid < MIN_SYSID || sysid > MAX_SYSID) {
            return null;
        }
        return sysid;
    }

    /** 铸造结果：明文 Key（仅此一次）+ 待落库实体。 */
    private record MintedKey(String apiKey, ApiKeyEntity entity) {
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
        dto.put("role", entity.getRole());
        dto.put("sysid", entity.getSysid());
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
