package io.aerofleet.cloud.surveillance;

import java.time.Instant;
import java.util.Objects;

/**
 * 大华 ICC（智能物联综合管理平台）OpenAPI 的鉴权态。
 *
 * <p><b>依据</b>：大华开放平台公开文档 {@code open-icc.dahuatech.com} 的
 * {@code 对接指南 → 整体流程 → 鉴权认证}（2026-11 核）。文档原文要点：
 * <ul>
 *   <li>access_token 由 {@code POST /evo-apigw/evo-oauth/1.0.0/oauth/extend/token} 取得，</li>
 *   <li>**access_token 有效期 2h**（原文：\"access_token有效期2h\"），</li>
 *   <li>业务接口请求头需带 {@code Authorization: <token_type> <access_token>}，</li>
 *   <li>token_type 一般为固定值 {@code bearer}；</li>
 *   <li>接口权限\"与鉴权用户的组织、部门权限保持一致\"——即 token 不是万能钥匙，</li>
 *       越权访问由平台侧拒绝，客户端不自行判断权限。</li>
 * </ul>
 *
 * <p><b>为什么客户端要自己算过期</b>：文档说 2h，但真实平台上 token 可能因
 * 账号被踢/权限变更/平台重启而提前失效。客户端做两层：
 * <ol>
 *   <li>按 {@link #expiresAt} 提前过期（留安全余量，避免\"边界上刚发出去就失效\"）；</li>
 *   <li>收到 401 时<b>无条件重新取 token 并重试一次</b>——这是文档没有明说但</li>
 *       所有大华 ICC 集成实践都会遇到的行为。</li>
 * </ol>
 *
 * @param accessToken 访问令牌
 * @param tokenType   token 类型（文档示例 {@code bearer}）
 * @param expiresAt   过期时刻（含客户端安全余量后的**实际可用截止**）
 */
record DahuaIccAuth(String accessToken, String tokenType, Instant expiresAt) {

    /** 文档声明的 token 有效期：2 小时。 */
    static final java.time.Duration DOC_TTL = java.time.Duration.ofHours(2);

    /** 客户端安全余量：提前这么久判过期（避免边界上发出的请求必然失败）。 */
    static final java.time.Duration SAFETY_MARGIN = java.time.Duration.ofMinutes(1);

    DahuaIccAuth {
        Objects.requireNonNull(accessToken, "accessToken");
        Objects.requireNonNull(tokenType, "tokenType");
        Objects.requireNonNull(expiresAt, "expiresAt");
    }

    /**
     * 按文档 2h TTL 构造，但**客户端按 TTL-余量**判过期。
     *
     * @param issuedAt 签发时刻（平台上取得 token 的时间，不取本机时钟之外的来源）
     */
    static DahuaIccAuth issuedNow(String accessToken, String tokenType, Instant issuedAt) {
        return new DahuaIccAuth(accessToken, tokenType,
                issuedAt.plus(DOC_TTL).minus(SAFETY_MARGIN));
    }

    /** Authorization 头值（文档：{@code token_type + 空格 + access_token}）。 */
    String authorizationHeader() {
        return tokenType + " " + accessToken;
    }

    /** 当前是否仍可用（已扣安全余量）。 */
    boolean validAt(Instant now) {
        return now.isBefore(expiresAt);
    }
}
