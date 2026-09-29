package io.aerofleet.cloud.security;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 三态租户域的纯单元测试（无 Spring 上下文）。
 * <p>
 * 覆盖 P0-2 的核心判定：只有「无归属 + ADMIN」才是全局管理员，
 * 「无归属 + 非 ADMIN」必须是看不到任何租户数据的 NO_ACCESS，
 * 且 NO_ACCESS 这个哨兵永不能被当成资源的 tenant_id 写库。
 */
@DisplayName("租户域三态解析")
class TenantScopeResolutionTest {

    @AfterEach
    void clear() {
        TenantContext.clear();
    }

    @Test
    @DisplayName("带租户 claim 就落该租户，与角色无关")
    void tenantClaimWins() {
        assertThat(TenantContext.resolveTenantScope(7, "OPERATOR")).isEqualTo(7);
        assertThat(TenantContext.resolveTenantScope(7, "ADMIN")).isEqualTo(7);
        assertThat(TenantContext.resolveTenantScope(7, null)).isEqualTo(7);
    }

    @Test
    @DisplayName("无租户 claim 时只有 ADMIN 是全局管理员")
    void onlyAdminBecomesGlobal() {
        assertThat(TenantContext.resolveTenantScope(null, "ADMIN")).isNull();
        assertThat(TenantContext.resolveTenantScope(null, "admin")).isNull();
        assertThat(TenantContext.resolveTenantScope(null, " OPERATOR ")).isEqualTo(TenantContext.NO_ACCESS);
        assertThat(TenantContext.resolveTenantScope(null, "OBSERVER")).isEqualTo(TenantContext.NO_ACCESS);
        assertThat(TenantContext.resolveTenantScope(null, null)).isEqualTo(TenantContext.NO_ACCESS);
        assertThat(TenantContext.resolveTenantScope(null, "")).isEqualTo(TenantContext.NO_ACCESS);
    }

    @Test
    @DisplayName("NO_ACCESS 域：不是全局、看不到任何租户、但不会被写进库")
    void noAccessScopeBehaviour() {
        TenantContext.setTenantId(TenantContext.resolveTenantScope(null, "OPERATOR"));

        assertThat(TenantContext.getEffectiveTenantId()).isEqualTo(TenantContext.NO_ACCESS);
        assertThat(TenantContext.isNoAccess()).isTrue();
        assertThat(TenantContext.isGlobalScope()).isFalse();
        // 哨兵绝不能作为归属落库：写入侧退回 null（未归属，等 provisioning 指派）
        assertThat(TenantContext.getWritableTenantId()).isNull();
    }

    @Test
    @DisplayName("全局域：不过滤，写入侧仍按未归属处理")
    void globalScopeBehaviour() {
        TenantContext.setTenantId(TenantContext.resolveTenantScope(null, "ADMIN"));

        assertThat(TenantContext.isGlobalScope()).isTrue();
        assertThat(TenantContext.isNoAccess()).isFalse();
        assertThat(TenantContext.getWritableTenantId()).isNull();
    }

    @Test
    @DisplayName("租户域：可见性与写入都取真实租户")
    void tenantScopeBehaviour() {
        TenantContext.setTenantId(TenantContext.resolveTenantScope(42, "OPERATOR"));

        assertThat(TenantContext.getEffectiveTenantId()).isEqualTo(42);
        assertThat(TenantContext.isGlobalScope()).isFalse();
        assertThat(TenantContext.isNoAccess()).isFalse();
        assertThat(TenantContext.getWritableTenantId()).isEqualTo(42);
    }

    @Test
    @DisplayName("API Key 的租户优先于 JWT 侧上下文（ApiKeyContext 已设值时）")
    void apiKeyContextWinsWhenPresent() {
        TenantContext.setTenantId(7);
        ApiKeyContext.set("key-1", 9, "read", "OPERATOR");
        try {
            assertThat(TenantContext.getEffectiveTenantId()).isEqualTo(9);
        } finally {
            ApiKeyContext.clear();
        }
    }

    @Test
    @DisplayName("API Key 无租户且非 ADMIN 时回落到 NO_ACCESS，而不是全局")
    void tenantlessApiKeyIsNotGlobal() {
        TenantContext.setTenantId(TenantContext.NO_ACCESS);
        ApiKeyContext.set("key-2", null, "read", "OPERATOR");
        try {
            assertThat(TenantContext.getEffectiveTenantId()).isEqualTo(TenantContext.NO_ACCESS);
            assertThat(TenantContext.isGlobalScope()).isFalse();
        } finally {
            ApiKeyContext.clear();
        }
    }
}
