package io.aerofleet.cloud.security;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Base64;
import java.util.HashSet;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * StreamTokenService 单测：签发格式、单次用语义、TTL、容量防线与授权绑定。
 * <p>
 * 时钟注入可控（原子递增的 epoch 毫秒），不依赖真实睡眠。
 */
@DisplayName("StreamTokenService SSE 流令牌服务")
class StreamTokenServiceTest {

    /** 可控时钟：手动递增，精确卡 TTL 边界。 */
    private static final class ManualClock {
        long now = 1_700_000_000_000L;

        long get() {
            return now;
        }

        void advance(long millis) {
            now += millis;
        }
    }

    private ManualClock clock;
    private StreamTokenService service;

    private StreamTokenService newService() {
        clock = new ManualClock();
        return new StreamTokenService(clock::get);
    }

    @Test
    @DisplayName("签发格式：43 字符 Base64url（32 字节熵），且多次签发互不相同")
    void issue_formatAndUniqueness() {
        service = newService();
        Set<String> seen = new HashSet<>();
        for (int i = 0; i < 100; i++) {
            String token = service.issue("user", "OBSERVER", null);
            assertThat(token).hasSize(43);
            // Base64url 字符集：字母数字 + - _
            assertThat(token).matches("[A-Za-z0-9_-]{43}");
            // 编码应可逆（32 字节随机量）
            assertThat(Base64.getUrlDecoder().decode(token)).hasSize(32);
            seen.add(token);
        }
        assertThat(seen).hasSize(100);
    }

    @Test
    @DisplayName("单次用：首次消费返回签发快照，第二次消费为 null")
    void consume_isSingleUse() {
        service = newService();
        String token = service.issue("admin", "ADMIN", 7);

        StreamTokenService.Grant first = service.consume(token);
        assertThat(first).isNotNull();
        assertThat(first.subject()).isEqualTo("admin");
        assertThat(first.role()).isEqualTo("ADMIN");
        assertThat(first.tenantScope()).isEqualTo(7);

        assertThat(service.consume(token)).isNull();
    }

    @Test
    @DisplayName("TTL 边界：到期前一毫秒可消费，越过过期线后消费为 null")
    void consume_expiresAfterTtl() {
        service = newService();
        String token = service.issue("admin", "ADMIN", null);

        clock.advance(StreamTokenService.TTL_MILLIS - 1);
        assertThat(service.consume(token)).isNotNull();

        String token2 = service.issue("admin", "ADMIN", null);
        clock.advance(StreamTokenService.TTL_MILLIS);
        assertThat(service.consume(token2)).isNull();
    }

    @Test
    @DisplayName("消费已过期令牌不残留条目（惰性清理生效）")
    void issue_sweepsExpiredEntries() {
        service = newService();
        service.issue("admin", "ADMIN", null);
        clock.advance(StreamTokenService.TTL_MILLIS + 1);
        // 过期条目在下次签发时被清走
        service.issue("admin", "ADMIN", null);
        assertThat(service.size()).isEqualTo(1);
    }

    @Test
    @DisplayName("单 subject 上限：第 9 枚签发会淘汰最早的未用令牌")
    void issue_perSubjectCapEvictsOldest() {
        service = newService();
        String firstToken = null;
        for (int i = 0; i < StreamTokenService.MAX_PER_SUBJECT; i++) {
            String token = service.issue("storm-user", "OBSERVER", null);
            if (i == 0) {
                firstToken = token;
            }
            clock.advance(1_000);
        }
        assertThat(service.size()).isEqualTo(StreamTokenService.MAX_PER_SUBJECT);

        // 第 9 枚：最早那枚（firstToken）被淘汰，总量不变
        String ninth = service.issue("storm-user", "OBSERVER", null);
        assertThat(ninth).isNotBlank();
        assertThat(service.size()).isEqualTo(StreamTokenService.MAX_PER_SUBJECT);
        assertThat(service.consume(firstToken)).isNull();
        assertThat(service.consume(ninth)).isNotNull();
    }

    @Test
    @DisplayName("单 subject 上限不误伤其他 subject 的令牌")
    void issue_perSubjectCapDoesNotEvictOtherSubjects() {
        service = newService();
        String otherToken = service.issue("other-user", "OBSERVER", null);
        for (int i = 0; i < StreamTokenService.MAX_PER_SUBJECT + 2; i++) {
            service.issue("storm-user", "OBSERVER", null);
        }
        // storm-user 的风暴不影响 other-user 的令牌
        assertThat(service.consume(otherToken)).isNotNull();
    }

    @Test
    @DisplayName("全局容量上限：活令牌占满后拒发返回 null（fail-closed）")
    void issue_totalCapRejects() {
        service = newService();
        // 不同 subject 各签 MAX_PER_SUBJECT 枚，直到总量贴满上限
        String firstToken = null;
        int subjects = StreamTokenService.MAX_TOTAL_TOKENS / StreamTokenService.MAX_PER_SUBJECT;
        for (int s = 0; s < subjects; s++) {
            for (int i = 0; i < StreamTokenService.MAX_PER_SUBJECT; i++) {
                String token = service.issue("user-" + s, "OBSERVER", null);
                assertThat(token).isNotNull();
                if (s == 0 && i == 0) {
                    firstToken = token;
                }
            }
        }
        assertThat(service.size()).isEqualTo(StreamTokenService.MAX_TOTAL_TOKENS);

        // 贴满后再签任何 subject 一律拒发（fail-closed）
        assertThat(service.issue("new-user", "OBSERVER", null)).isNull();
        // 拒发不影响存量：已签令牌仍可消费，且腾出槽位后立即可再签
        assertThat(service.consume(firstToken)).isNotNull();
        assertThat(service.issue("after-evict", "OBSERVER", null)).isNotNull();
    }

    @Test
    @DisplayName("授权绑定三态租户域：null / 租户 ID / NO_ACCESS 哨兵原样往返")
    void consume_bindsTenantScopeVerbatim() {
        service = newService();
        String adminToken = service.issue("admin", "ADMIN", null);
        String tenantToken = service.issue("op", "OPERATOR", 7);
        String noAccessToken = service.issue("observer-no-tenant", "OBSERVER",
                TenantContext.NO_ACCESS);

        assertThat(service.consume(adminToken).tenantScope()).isNull();
        assertThat(service.consume(tenantToken).tenantScope()).isEqualTo(7);
        assertThat(service.consume(noAccessToken).tenantScope())
                .isEqualTo(TenantContext.NO_ACCESS);
    }

    @Test
    @DisplayName("consume 对 null / 空串 / 未知令牌一律 null")
    void consume_rejectsMalformedInput() {
        service = newService();
        assertThat(service.consume(null)).isNull();
        assertThat(service.consume("")).isNull();
        assertThat(service.consume("nsk_not_a_real_token_000000000000000")).isNull();
    }
}
