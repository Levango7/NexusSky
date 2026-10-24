package io.aerofleet.cloud.security;

import io.aerofleet.mavlink.security.MavlinkSignatureConfig;
import io.aerofleet.mavlink.security.SigningKeyManager;
import io.aerofleet.mavlink.security.SigningKeyStoreWriter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;

/**
 * MAVLink v2 签名密钥轮换端点（C5）。
 *
 * <p><b>能力边界——先说清楚它不做什么</b>：MAVLink v2 签名的 13 字节数据块是
 * {@code linkId(1) | timestamp(6) | signature(6)}，<b>没有 key id</b>。所以这里
 * <b>没有宽限期</b>：轮换即硬切换，远端无人机必须在同一维护窗口内换上新密钥，
 * 否则它们的下行帧会被 {@code UdpGateway} 按验签失败丢弃。API Key 那种
 * "新旧并存 graceHours" 在 MAVLink 层<b>无法表达</b>，不要照搬那个心智模型。
 *
 * <p><b>返回体永不含密钥材料</b>：只给指纹（SHA-256 前 4 字节 hex）。
 * 这是刻意的——指纹能支撑运维对账（"这台上是不是新密钥"）却无法反推密钥。
 *
 * <p><b>门禁</b>：
 * <ul>
 *   <li>{@code @RequireRole(ADMIN)} + 必须 JWT 登录（与 API Key 轮换同口径）；</li>
 *   <li>签名未启用 → 503：不给没开的特性做运维操作，避免"以为在轮换其实在改一个
 *       完全不生效的文件"；</li>
 *   <li>非多机模式 → 409：单密钥来自配置，改它要重新部署，不是 REST 能解决的；</li>
 *   <li>密钥文件损坏/路径非法 → 409，原文件不动（写入器保证）。</li>
 * </ul>
 *
 * <p>写入由 {@link SigningKeyStoreWriter} 原子完成，随后
 * {@link SigningKeyManager#reloadIfNeeded()} 按 mtime 热更，<b>无需重启</b>。
 */
@RestController
@RequestMapping("/api/v1/mavlink/signing")
public class MavlinkSigningKeyController {

    private static final Logger log = LoggerFactory.getLogger(MavlinkSigningKeyController.class);

    private final SigningKeyStoreWriter writer = new SigningKeyStoreWriter();

    @Autowired(required = false)
    private MavlinkSignatureConfig signatureConfig;

    @Autowired(required = false)
    private SigningKeyManager signingKeyManager;

    /**
     * 设备注册表——轮换成功后要把指纹记进"发放账"（{@code devices.signing_key_fingerprint}）。
     *
     * <p>{@code required = false} 是因为本控制器在签名关闭时也存在；缺它轮换本身仍能成功，
     * 但响应会如实写 {@code provisioningLedger=UNAVAILABLE}，<b>不假装记上了</b>。
     */
    @Autowired(required = false)
    private io.aerofleet.cloud.gateway.DeviceRegistry deviceRegistry;

    /** keystore 里当前有密钥的设备（sysid → 条目）；签名未启用或非多机模式时为空。 */
    private Map<Integer, SigningKeyManager.KeyEntry> keystoreEntries() {
        Map<Integer, SigningKeyManager.KeyEntry> out = new TreeMap<>();
        if (signatureConfig == null || signingKeyManager == null
                || !signatureConfig.isEnabled() || !signingKeyManager.isMultiMode()) {
            return out;
        }
        // keyFor 会触发热重载检查，所以这里反映的是文件当前真实内容
        for (int sysid = 1; sysid <= 255; sysid++) {
            SigningKeyManager.KeyEntry e = signingKeyManager.keyFor(sysid);
            if (e != null) {
                out.put(sysid, e);
            }
        }
        return out;
    }

    /**
     * 查看当前签名状态与各 sysid 的密钥指纹（<b>不含密钥</b>）。
     *
     * <p>签名未启用时返回 enabled=false 与原因，而不是 503——查询状态应该总是可读的；
     * 只有<b>写</b>操作（轮换）才在未启用时拒绝。
     *
     * <p>读操作按仓内惯例要 OBSERVER；返回体只有指纹（SHA-256 前 4 字节），
     * 不足以反推密钥，但仍属运维面信息，不开放匿名。
     */
    @GetMapping("/keys")
    @RequireRole(Role.OBSERVER)
    public ResponseEntity<Map<String, Object>> listKeys() {
        Map<String, Object> resp = new LinkedHashMap<>();
        boolean enabled = signatureConfig != null && signatureConfig.isEnabled()
                && signingKeyManager != null;
        resp.put("enabled", enabled);
        resp.put("multiKeyMode", enabled && signingKeyManager.isMultiMode());
        resp.put("rejectUnsigned", enabled && signatureConfig.isRejectUnsigned());
        resp.put("keyStorePath", enabled && signingKeyManager.isMultiMode()
                ? signingKeyManager.getKeyStorePath() : null);
        resp.put("globalKeyFingerprint",
                enabled ? signingKeyManager.getGlobalKeyFingerprint() : null);

        Map<String, Object> entries = new TreeMap<>();
        for (Map.Entry<Integer, SigningKeyManager.KeyEntry> en : keystoreEntries().entrySet()) {
            SigningKeyManager.KeyEntry e = en.getValue();
            Map<String, Object> one = new LinkedHashMap<>();
            one.put("linkId", e.linkId());
            one.put("fingerprint", signingKeyManager.fingerprint(e.key()));
            entries.put(String.valueOf(en.getKey()), one);
        }
        resp.put("entries", entries);
        return ResponseEntity.ok(resp);
    }

    /**
     * 轮换指定 sysid 的签名密钥。
     *
     * <p>请求体：{@code {"key": "新密钥"}}。<b>没有 graceHours</b>——见类注释的协议边界。
     *
     * <p>响应：{@code {sysid, linkId, oldFingerprint, newFingerprint, path, totalEntries,
     * provisioningLedger}}，全部是指纹与元信息，不含密钥。
     * {@code provisioningLedger} 说明发放账那一步的真实下场（{@code PERSISTED} /
     * {@code MEMORY_ONLY} / {@code UNAVAILABLE}）——轮换成功但没记上账是两种不同的运维后果，
     * 别让响应体把它们说成同一件事。
     */
    @PostMapping("/keys/{sysid}/rotate")
    @RequireRole(Role.ADMIN)
    public ResponseEntity<Map<String, Object>> rotateKey(@PathVariable int sysid,
                                                         @RequestBody(required = false)
                                                         Map<String, Object> body) {
        if (signatureConfig == null || signingKeyManager == null
                || !signatureConfig.isEnabled()) {
            return errorResponse(HttpStatus.SERVICE_UNAVAILABLE,
                    "MAVLink 签名未启用（mavlink.signing.enabled=false），无可轮换的密钥");
        }
        if (!signingKeyManager.isMultiMode()) {
            return errorResponse(HttpStatus.CONFLICT,
                    "当前为单密钥模式（未配置 mavlink.signing.key-store-path）；"
                            + "该模式的密钥来自配置，轮换需要重新部署而非调用本端点");
        }

        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null || !(auth.getPrincipal() instanceof Jwt jwt)) {
            return errorResponse(HttpStatus.UNAUTHORIZED,
                    "JWT authentication required to rotate MAVLink signing key");
        }
        String operator = jwt.getClaimAsString("preferred_username");
        if (operator == null || operator.isEmpty()) {
            operator = jwt.getSubject();
        }

        if (body == null || body.get("key") == null) {
            return errorResponse(HttpStatus.BAD_REQUEST,
                    "请求体缺少 key 字段：{\"key\":\"新密钥\"}");
        }
        String newKey = String.valueOf(body.get("key"));
        if (newKey.isBlank()) {
            return errorResponse(HttpStatus.BAD_REQUEST, "新密钥不能为空白");
        }

        SigningKeyStoreWriter.RotationResult result;
        try {
            result = writer.rotate(signingKeyManager, sysid, newKey);
        } catch (IllegalArgumentException e) {
            // 参数/状态类问题：不碰文件，4xx
            log.warn("MAVLink 签名密钥轮换被拒绝: sysid={} operator={} reason={}",
                    sysid, operator, e.getMessage());
            return errorResponse(HttpStatus.CONFLICT, e.getMessage());
        } catch (Exception e) {
            // IO/原子写失败：5xx，且线上文件未被破坏（写入器保证）
            log.error("MAVLink 签名密钥轮换失败: sysid={} operator={}", sysid, operator, e);
            return errorResponse(HttpStatus.INTERNAL_SERVER_ERROR,
                    "密钥轮换失败，密钥库文件未被修改: " + e.getMessage());
        }

        // 审计：只记指纹，不记密钥
        log.info("MAVLink 签名密钥轮换成功: sysid={} linkId={} {} -> {} operator={} path={}",
                result.sysid(), result.linkId(), result.oldFingerprint(),
                result.newFingerprint(), operator, result.path());

        // 发放账：记下"地面已为这台生成/轮换过什么指纹"，写进 V28 的
        // devices.signing_key_fingerprint。缺这一步时那一列恒为 NULL，
        // 手册里"已生成、等现场写进飞控"与"从没配过"就分不开。
        String ledger;
        if (deviceRegistry == null) {
            ledger = "UNAVAILABLE";
            log.warn("签名密钥已轮换但发放账没记上（DeviceRegistry 不可用）: sysid={} —— "
                    + "这台机会在 GET /api/v1/mavlink/signing/provisioning 里显示为『从未发过密钥』",
                    result.sysid());
        } else {
            deviceRegistry.recordSigningKeyFingerprint(result.sysid(), result.newFingerprint());
            ledger = deviceRegistry.isPersisting() ? "PERSISTED" : "MEMORY_ONLY";
        }

        Map<String, Object> resp = new LinkedHashMap<>();
        resp.put("sysid", result.sysid());
        resp.put("linkId", result.linkId());
        resp.put("oldFingerprint", result.oldFingerprint());
        resp.put("newFingerprint", result.newFingerprint());
        resp.put("path", result.path());
        resp.put("totalEntries", result.totalEntries());
        resp.put("provisioningLedger", ledger);
        // 提醒运维这一步的协议后果，别让人以为有宽限期
        resp.put("warning",
                "MAVLink v2 签名无 key id 字段，本次为硬切换：远端设备须在同一维护窗口内"
                        + "换上新密钥，否则其下行帧将按验签失败被丢弃。轮换已热更，无需重启。");
        return ResponseEntity.ok(resp);
    }

    /**
     * 发放配置单：逐机列出「地面给过它什么密钥指纹」与「是否真见过它的签名帧」。
     *
     * <p><b>为什么需要它</b>：收紧手册第 1 步要"逐机发密钥"，而在此之前仓里只有
     * {@code GET /keys}（keystore 里现在有什么）和 {@code GET /posture}（在线设备是否都有验签证据），
     * <b>没有一处回答得了"这台机我到底配过没有"</b>——V28 那两列里指纹那一半
     * 既没有写入方也没有读面。
     *
     * <p><b>状态只有两种，含义不同</b>：
     * <ul>
     *   <li>{@code VERIFIED}：{@code signing_verified_at} 非空——地面<b>真看到过</b>这台的签名帧，
     *       这是唯一能支撑"翻 strict"的证据；</li>
     *   <li>{@code ISSUED_UNVERIFIED}：有指纹（keystore 里有它，或轮换时记进过发放账），
     *       但从没看到它的签名帧——通常是"密钥已生成、还没写进飞控"。</li>
     * </ul>
     * 本端点<b>不判"能不能翻 strict"</b>（那要看在线设备集合，是 {@code /posture} 的职责），只把账列清。
     *
     * <p>返回体同样永不含密钥材料：只有指纹、linkId 与时间戳。
     */
    @GetMapping("/provisioning")
    @RequireRole(Role.ADMIN)
    public ResponseEntity<Map<String, Object>> provisioningSheet() {
        Map<Integer, SigningKeyManager.KeyEntry> keystore = keystoreEntries();
        Map<Integer, String> issued = deviceRegistry == null
                ? Map.of() : deviceRegistry.signingKeyFingerprintSnapshot();
        Map<Integer, Instant> verified = deviceRegistry == null
                ? Map.of() : deviceRegistry.signingVerifiedSnapshot();

        Set<Integer> sysids = new TreeSet<>();
        sysids.addAll(keystore.keySet());
        sysids.addAll(issued.keySet());
        sysids.addAll(verified.keySet());

        List<Map<String, Object>> devices = new ArrayList<>();
        int verifiedCount = 0;
        int mismatchCount = 0;
        for (int sysid : sysids) {
            String issuedFp = issued.get(sysid);
            String storeFp = keystore.containsKey(sysid)
                    ? signingKeyManager.fingerprint(keystore.get(sysid).key()) : null;
            Instant seen = verified.get(sysid);
            // 发放账里的指纹与 keystore 当前的不一致 ⇒ 密钥后来又被换过（或现场写的是另一把），
            // 这正是"以为配过了其实配的是旧的"那种状态，必须让它看得见。
            boolean mismatch = issuedFp != null && storeFp != null && !issuedFp.equals(storeFp);
            Map<String, Object> one = new LinkedHashMap<>();
            one.put("sysid", sysid);
            one.put("linkId", keystore.containsKey(sysid) ? keystore.get(sysid).linkId() : null);
            one.put("issuedFingerprint", issuedFp);
            one.put("keyStoreFingerprint", storeFp);
            one.put("fingerprintMismatch", mismatch);
            one.put("signingVerifiedAt", seen);
            one.put("state", seen != null ? "VERIFIED" : "ISSUED_UNVERIFIED");
            devices.add(one);
            if (seen != null) {
                verifiedCount++;
            }
            if (mismatch) {
                mismatchCount++;
            }
        }

        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("devices", devices.size());
        summary.put("verified", verifiedCount);
        summary.put("issuedUnverified", devices.size() - verifiedCount);
        summary.put("fingerprintMismatch", mismatchCount);

        Map<String, Object> resp = new LinkedHashMap<>();
        resp.put("enabled", signatureConfig != null && signatureConfig.isEnabled());
        resp.put("ledgerAvailable", deviceRegistry != null);
        resp.put("summary", summary);
        resp.put("devices", devices);
        resp.put("note", "state=VERIFIED 由观测得出（地面真看到过该机的签名帧）；"
                + "ISSUED_UNVERIFIED 表示地面已生成/轮换过密钥但从未看到它的签名帧。"
                + "本表不含密钥材料，也不判定『能不能翻 strict』——后者看 GET /api/v1/mavlink/signing/posture。");
        return ResponseEntity.ok(resp);
    }

    private ResponseEntity<Map<String, Object>> errorResponse(HttpStatus status, String message) {
        Map<String, Object> resp = new LinkedHashMap<>();
        resp.put("error", message);
        return ResponseEntity.status(status).body(resp);
    }
}
