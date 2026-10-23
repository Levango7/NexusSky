package io.aerofleet.cloud.security;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * API Key 认证零件的最小契约（P1 #7 变异审计收口）。
 *
 * <p>这类断言单独成文件的原因：它们所属的类（认证 token、JPA 实体）没有独立测试类，
 * 而"认证态/身份/缺席语义"这类契约此前没有任何用例钉住——PIT 里对应的
 * setAuthenticated / getPrincipal / getter 返回空替换全都存活。契约本身很小，
 * 但被删掉/替换后调用方（过滤器、拦截器、列表 DTO）拿到的就是"看起来可用实则未认证"的凭证。
 */
@DisplayName("API Key 认证零件最小契约（token 认证态 / 实体 null 约定）")
class ApiKeyAuthContractTest {

    @Test
    @DisplayName("已认证 token：isAuthenticated=true 且 principal=keyId（凭证身份不许丢）")
    void authenticatedTokenCarriesIdentity() {
        ApiKeyAuthenticationToken token =
                new ApiKeyAuthenticationToken("nsk_1_aaaa", 1, "[ingest]", "OPERATOR");

        // 删掉构造器里的 setAuthenticated(true)：token 会以"未认证"态进入
        // SecurityContext，认证链把已验过的请求当匿名处理。
        assertThat(token.isAuthenticated()).isTrue();
        // getPrincipal 返回 null：下游任何按 principal 记账/判定的地方都失明。
        assertThat(token.getPrincipal()).isEqualTo("nsk_1_aaaa");
        assertThat(token.getKeyId()).isEqualTo("nsk_1_aaaa");
        assertThat(token.getRole()).isEqualTo("OPERATOR");
        assertThat(token.getCredentials()).isNull(); // 明文凭证不进 token
    }

    @Test
    @DisplayName("未认证 token：isAuthenticated=false（认证前初始态）")
    void unauthenticatedTokenIsNotTrusted() {
        assertThat(new ApiKeyAuthenticationToken().isAuthenticated()).isFalse();
    }

    @Test
    @DisplayName("流令牌 token：构造即认证且身份可读（删掉 setAuthenticated 会让 SSE 认证链失明）")
    void streamTokenCarriesIdentity() {
        StreamTokenAuthenticationToken token =
                new StreamTokenAuthenticationToken("stream-1", "OBSERVER", 7);

        assertThat(token.isAuthenticated()).isTrue();
        assertThat(token.getPrincipal()).isEqualTo("stream-1");
        assertThat(token.getRole()).isEqualTo("OBSERVER");
    }

    @Test
    @DisplayName("ApiKeyEntity：未赋值的 name/scopes 是 null 而不是空串（缺席语义）")
    void entityAbsenceIsNull() {
        ApiKeyEntity entity = new ApiKeyEntity();

        // getter 把 null 替换成 ""：JSON 响应里"没有名字"会伪装成"空名字"，
        // 列表 DTO / 日志 / 对账脚本对"缺席"的判定全部失真。
        assertThat(entity.getName()).isNull();
        assertThat(entity.getScopes()).isNull();
    }
}
