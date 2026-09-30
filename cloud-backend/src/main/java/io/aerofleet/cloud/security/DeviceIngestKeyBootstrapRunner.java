package io.aerofleet.cloud.security;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;

/**
 * 设备/边缘摄取通道的 API Key 引导（bootstrap）。
 * <p>
 * <b>背景</b>：RBAC 默认拒绝之后，四条摄取腿（{@code POST /api/v1/edge/results}、
 * {@code POST /api/v1/loRa/alarm}、{@code POST /api/v1/offline-alarm/batch-upload} 与
 * {@code /flush}、{@code POST /api/v1/alarms/events}）要求 {@link Role#OPERATOR}。而生产模式
 * 下这些路径本来就 over {@code SecurityConfig} 的 {@code anyRequest().authenticated()}——
 * 也就是说 compose/prod 里匿名摄取一直是 401，仓库里却<b>没有任何发放凭据的路径</b>
 * （唯一发 {@code X-API-Key} 的调用方是 sdk-java 的 {@code NexusSkyClient}）。
 * 铸 key 的 {@code POST /api/v1/auth/keys} 又要求 ADMIN，于是新部署在"先要有账号才能发凭据、
 * 先要有凭据才能上报"之间循环。
 * <p>
 * 因此这里与 {@link AdminBootstrapRunner} 同一手法：仅当部署方显式提供
 * {@code aerofleet.security.device-ingest-api-key} 时才介入，未配置时完全不动
 * （含 CI 的 dev profile 与本仓所有测试）。库里只存 SHA-256 哈希，明文不落库、不入仓。
 * <p>
 * <b>这是共享静态密钥，不是每机一密钥</b>：一把 key 覆盖整个部署的所有设备与边缘节点，
 * 撤销粒度只有"整体换 key"（改环境变量重启即生效，因为每次启动按同一 keyId 覆写）。
 * 真正的按设备/租户发放是后续项；本类的价值在于让"摄取必须带凭据"从一句契约文档
 * 变成可运行的通路。日志里会 WARN 提醒这一点，别把它当长期方案。
 */
@Component
public class DeviceIngestKeyBootstrapRunner implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(DeviceIngestKeyBootstrapRunner.class);

    /** 固定主键：重复启动覆写同一条记录，避免 api_keys 里堆积引导 key。 */
    static final String KEY_ID = "device-ingest";

    /** 与 ApiKeyController 铸造口径一致的最小长度，防止弱口令出厂。 */
    private static final int MIN_KEY_LENGTH = 16;

    private final ApiKeyRepository apiKeyRepository;
    private final String plainKey;
    private final Integer tenantId;

    public DeviceIngestKeyBootstrapRunner(
            @Autowired(required = false) ApiKeyRepository apiKeyRepository,
            @Value("${aerofleet.security.device-ingest-api-key:}") String plainKey,
            @Value("${aerofleet.security.device-ingest-tenant-id:1}") Integer tenantId) {
        this.apiKeyRepository = apiKeyRepository;
        this.plainKey = plainKey;
        this.tenantId = tenantId;
    }

    @Override
    public void run(ApplicationArguments args) {
        if (plainKey == null || plainKey.isBlank()) {
            return;
        }
        if (apiKeyRepository == null) {
            log.warn("已配置 device-ingest-api-key 但 ApiKeyRepository 不可用（无数据源？），跳过摄取凭据引导");
            return;
        }
        if (plainKey.length() < MIN_KEY_LENGTH) {
            log.warn("device-ingest-api-key 长度不足 {}，拒绝引导摄取凭据（避免弱密钥出厂）", MIN_KEY_LENGTH);
            return;
        }

        ApiKeyEntity entity = apiKeyRepository.findByKeyId(KEY_ID).orElseGet(ApiKeyEntity::new);
        entity.setKeyId(KEY_ID);
        entity.setKeyHash(sha256Hex(plainKey));
        entity.setTenantId(tenantId);
        entity.setName("device-ingest (bootstrap)");
        // scopes 目前只随上下文透传、没有任何授权判定读它（见 ApiKeyContext.getScopes 的调用面），
        // 这里写标签值仅作运维辨识，不要当成能力边界。
        entity.setScopes("[ingest]");
        entity.setRole(Role.OPERATOR.name());
        entity.setRevoked(false);
        if (entity.getCreatedAt() == null) {
            entity.setCreatedAt(Instant.now());
        }
        apiKeyRepository.save(entity);

        log.warn("已引导共享摄取凭据（keyId={} role=OPERATOR tenantId={}）：一把 key 覆盖全部设备与边缘节点，"
                + "轮换方式是改环境变量重启；请勿把它当作每机一密钥的方案", KEY_ID, tenantId);
    }

    /** 与 {@link ApiKeyFilter} 认证侧同一哈希算法（SHA-256 hex，小写）。 */
    private static String sha256Hex(String input) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(input.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder(hash.length * 2);
            for (byte b : hash) {
                sb.append(String.format("%02x", b));
            }
            return sb.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 not available", e);
        }
    }
}
