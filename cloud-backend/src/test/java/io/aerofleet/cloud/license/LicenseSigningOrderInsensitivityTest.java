package io.aerofleet.cloud.license;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Base64;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 签名规范化必须对集合迭代顺序不敏感（2026-10-06 落地档位绑定时由 Qoder 会话发现并修复）。
 *
 * <h2>发现的缺口</h2>
 * {@code LicenseSigner.serializeForSigning} 把 License 序列化成 Map 再输出，其中
 * {@code modules} 数组的顺序 = 该集合在当时的迭代顺序。而集合顺序**两端都不可复现**：
 * 签发端的集合实现由调用方决定（{@code LicenseTier.modulesOf} 返回 {@code Set.copyOf}，
 * 顺序受 JVM 级 SALT 影响，逐 JVM 运行变化），解析端由 Jackson 建成 {@code HashSet}
 * （顺序由哈希决定）。于是同一份 License、同一把密钥，可能出现
 * <b>签发端验得过、部署端验不过</b>——后果是 fail-closed 拒绝启动，而且现场几乎不可能
 * 把"起不来"联系到"集合迭代顺序"。
 *
 * <h2>实测复现（修复前）</h2>
 * 同集合、仅迭代顺序不同：{@code verify(signSide=[fleet,core,emergency], verifySide=[core,emergency,fleet])}
 * → {@code false}；本类 {@link #sameSetDifferentIterationOrderStillVerifies()} 修复前红。
 * 该现象在 {@code LicenseTierBindingEnforcementTest} 的启动期用例上表现为"同一个用例
 * 有时过有时不过"，最初被误判为构造器差异——真正的变量是 JVM salt 与 HashSet 顺序的巧合。
 *
 * <h2>为什么钉"两次签名逐字节相同"而不是只钉 verify</h2>
 * verify 只能证明"这一次凑巧一致"；签名相等才等价于 javadoc 的承诺——
 * 「给定同一份 License，两端算出字节级相同的 JSON」。
 */
@DisplayName("License 签名规范化：集合顺序不敏感")
class LicenseSigningOrderInsensitivityTest {

    private final ObjectMapper mapper = new ObjectMapper()
            .registerModule(new JavaTimeModule())
            .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES);

    private LicenseInfo infoWith(Set<String> modules) {
        LicenseInfo i = new LicenseInfo();
        i.setTenantId("acme");
        i.setProductName("AeroFleet Cloud");
        i.setMaxDevices(50);
        i.setModules(modules);
        i.setActive(true);
        i.setIssuedAt(Instant.parse("2026-01-01T00:00:00Z"));
        i.setExpiryDate(Instant.parse("2030-01-01T00:00:00Z"));
        i.setIssuedTo("Acme Drone Co");
        i.setLicenseId("LIC-2026-0002");
        return i;
    }

    @Test
    @DisplayName("同一集合、不同迭代顺序 → 签名逐字节相同（规范化的一部分）")
    void signatureIsOrderInsensitive() {
        LicenseSigner signer = new LicenseSigner(mapper);
        LicenseInfo a = infoWith(new LinkedHashSet<>(List.of("fleet", "core", "emergency")));
        LicenseInfo b = infoWith(new LinkedHashSet<>(List.of("core", "emergency", "fleet")));

        assertThat(signer.sign(a))
                .as("集合顺序不携带语义：排序后的规范形必须相等，否则两端签名输入会漂移")
                .isEqualTo(signer.sign(b));
    }

    @Test
    @DisplayName("解析后的对象（Jackson 建成 HashSet）验签必须通过——不依赖顺序巧合")
    void sameSetDifferentIterationOrderStillVerifies() throws Exception {
        LicenseSigner signer = new LicenseSigner(mapper);
        LicenseInfo signSide = infoWith(new LinkedHashSet<>(List.of("fleet", "core", "emergency")));
        String signature = signer.sign(signSide);
        String payload = Base64.getEncoder().encodeToString(
                mapper.writeValueAsString(signSide).getBytes(StandardCharsets.UTF_8));

        LicenseInfo parsed = mapper.readValue(
                new String(Base64.getDecoder().decode(payload), StandardCharsets.UTF_8), LicenseInfo.class);
        assertThat(signer.verify(parsed, signature))
                .as("生产路径就是「签发端序列化 → 部署端解析后验签」，此处必须稳定通过")
                .isTrue();

        // 反向对照：手工构造一个顺序不同的等价对象，也必须验得过（证明与顺序无关，
        // 而不是"这次两边的顺序恰好一致"）
        assertThat(signer.verify(
                infoWith(new LinkedHashSet<>(List.of("core", "emergency", "fleet"))), signature))
                .as("同一集合的另一种迭代顺序不得导致验签失败")
                .isTrue();
    }

    @Test
    @DisplayName("排序不削弱密码学边界：篡改 payload 仍然验不过")
    void tamperingStillFails() throws Exception {
        LicenseSigner signer = new LicenseSigner(mapper);
        LicenseInfo info = infoWith(new LinkedHashSet<>(List.of("core", "fleet", "emergency")));
        String signature = signer.sign(info);

        LicenseInfo tampered = infoWith(new LinkedHashSet<>(List.of("core", "fleet", "emergency", "network")));
        assertThat(signer.verify(tampered, signature))
                .as("多签一个模块仍必须验不过——规范化只排序，不放宽任何字段")
                .isFalse();
    }
}
