package io.aerofleet.cloud.security;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.security.SecureRandom;
import java.util.Base64;
import java.util.Iterator;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;

/**
 * SSE 流令牌服务：签发与消费「短命、单次用」的 opaque 令牌。
 * <p>
 * 背景：浏览器 {@code EventSource} 无法携带 {@code Authorization} 头，生产链上
 * {@code GET /api/v1/alarms/stream} 与 {@code GET /api/v1/surveillance/devices/{id}/events}
 * 此前必然 401。把长效 JWT 放进 query 又会把 bearer 暴露给访问日志/代理日志，
 * 所以采用签发式短令牌：前端每次建流前先 POST /api/v1/auth/stream-token（带正常
 * 认证头）换取一枚 {@value #TTL_MILLIS} ms 内单次有效的 opaque token，再把它放进
 * {@code ?streamToken=} 参数。前端消费方本就有 5s 重连回退循环，每次重订阅取新
 * 令牌的成本被现有结构消化。
 * <p>
 * 语义：
 * <ul>
 *   <li><b>单次用</b>：{@link #consume(String)} 原子移除条目，第二次消费必失败。
 *       令牌即使被日志/Referer 泄露，重放窗口也只有一次连接建立。</li>
 *   <li><b>TTL {@value #TTL_MILLIS} ms</b>：只够「签发→建流」的往返；与
 *       {@link ApiKeyCache} 的多节点传播上界同量级。</li>
 *   <li><b>绑定签发现场</b>：令牌携带签发时的 subject / role / 租户域（三态值，
 *       含 {@link TenantContext#NO_ACCESS} 哨兵），消费时原样恢复 —— 令牌不放大
 *       权限，签发时看不到的数据建流后也看不到。</li>
 *   <li><b>容量防线</b>：单 subject 未用令牌至多 {@value #MAX_PER_SUBJECT} 枚
 *       （超出淘汰最早的，防重连风暴堆积）；总量至多 {@value #MAX_TOTAL_TOKENS}
 *       枚（超出拒发，防已认证账号刷内存）。</li>
 * </ul>
 * <p>
 * 与 {@link ApiKeyCache} 一致的明示运维语义：存储是 JVM 内
 * {@link ConcurrentHashMap}，多节点部署下需负载均衡会话亲和（或接受跨节点 401
 * 后由前端 5s 重连重签）。见 docs/security-design.md。
 * <p>
 * 线程安全：{@link ConcurrentHashMap}，条目不可变。时钟可注入（测试用），
 * 生产取 {@link System#currentTimeMillis()}。
 *
 * @see StreamTokenFilter
 * @see AuthController#issueStreamToken(jakarta.servlet.http.HttpServletRequest)
 */
@Component
public class StreamTokenService {

    private static final Logger log = LoggerFactory.getLogger(StreamTokenService.class);

    /** 令牌有效期（毫秒）：只覆盖「签发→建流」往返，也是重放窗口的上界。 */
    static final long TTL_MILLIS = 60_000;

    /** 单 subject 并存未用令牌上限：超出淘汰最早的（均在 TTL 窗口内，损失 ≤60s 可用期）。 */
    static final int MAX_PER_SUBJECT = 8;

    /** 全局并存令牌上限：超出拒发（fail-closed），防已认证账号无限刷令牌占内存。 */
    static final int MAX_TOTAL_TOKENS = 4096;

    /** 随机字节长度：256-bit 熵，Base64url 编码后 43 字符。 */
    private static final int TOKEN_BYTES = 32;

    private final ConcurrentHashMap<String, Entry> tokens = new ConcurrentHashMap<>();
    private final Supplier<Long> clockMillis;
    private final SecureRandom secureRandom = new SecureRandom();

    public StreamTokenService() {
        this(System::currentTimeMillis);
    }

    /** 测试用：注入可控时钟（epoch 毫秒）。 */
    StreamTokenService(Supplier<Long> clockMillis) {
        this.clockMillis = clockMillis;
    }

    /**
     * 签发一枚流令牌。
     *
     * @param subject     签发主体（JWT 用户名或 API Key ID，仅用于容量记账与日志）
     * @param role        签发时的角色名（RBAC 判定用）
     * @param tenantScope 签发时的有效租户域（三态：null=全局管理员 / 租户 ID / NO_ACCESS）
     * @return 令牌字符串；全局容量已满时返回 null（调用方应回 503）
     */
    public String issue(String subject, String role, Integer tenantScope) {
        long now = clockMillis.get();
        // 惰性清理过期条目，保证容量判定基于活令牌
        tokens.entrySet().removeIf(e -> now >= e.getValue().expiresAtMillis);
        // 单 subject 上限：淘汰该 subject 最早的未用令牌（重连风暴时旧的必然接近过期）
        if (countForSubject(subject) >= MAX_PER_SUBJECT) {
            evictOldestForSubject(subject);
        }
        if (tokens.size() >= MAX_TOTAL_TOKENS) {
            log.warn("流令牌总量已达上限 {}，拒发（疑似异常客户端刷令牌）", MAX_TOTAL_TOKENS);
            return null;
        }
        String token = newToken();
        tokens.put(token, new Entry(subject, role, tenantScope, now + TTL_MILLIS));
        log.debug("签发流令牌: subject={}, role={}, tenantScope={}, ttlMs={}",
                subject, role, tenantScope, TTL_MILLIS);
        return token;
    }

    /**
     * 消费一枚流令牌（单次用：原子移除）。
     *
     * @param token 令牌字符串
     * @return 签发时绑定的授权快照；令牌不存在、已用或已过期时返回 null
     */
    public Grant consume(String token) {
        if (token == null || token.isEmpty()) {
            return null;
        }
        Entry entry = tokens.remove(token);
        if (entry == null) {
            return null;
        }
        // 竞态兜底：签发后恰在消费瞬间越过过期线
        if (clockMillis.get() >= entry.expiresAtMillis) {
            return null;
        }
        return new Grant(entry.subject, entry.role, entry.tenantScope);
    }

    /** 当前并存令牌数（观测与测试用）。 */
    public int size() {
        return tokens.size();
    }

    /** 生成不与现存令牌冲突的随机令牌（32 字节 → Base64url 无填充，43 字符）。 */
    private String newToken() {
        byte[] bytes = new byte[TOKEN_BYTES];
        while (true) {
            secureRandom.nextBytes(bytes);
            String token = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
            if (!tokens.containsKey(token)) {
                return token;
            }
        }
    }

    private int countForSubject(String subject) {
        int count = 0;
        for (Entry entry : tokens.values()) {
            if (entry.subject != null && entry.subject.equals(subject)) {
                count++;
            }
        }
        return count;
    }

    /** 淘汰指定 subject 最早过期的令牌（容量控制路径，O(n) 且 n ≤ {@value #MAX_TOTAL_TOKENS}）。 */
    private void evictOldestForSubject(String subject) {
        String oldestKey = null;
        long oldestExpiry = Long.MAX_VALUE;
        for (Map.Entry<String, Entry> e : tokens.entrySet()) {
            Entry entry = e.getValue();
            if (entry.subject != null && entry.subject.equals(subject)
                    && entry.expiresAtMillis < oldestExpiry) {
                oldestExpiry = entry.expiresAtMillis;
                oldestKey = e.getKey();
            }
        }
        if (oldestKey != null) {
            tokens.remove(oldestKey);
        }
    }

    /** 清掉已过期条目（测试辅助；生产靠 issue/consume 惰性淘汰）。 */
    void evictExpired() {
        long now = clockMillis.get();
        Iterator<Map.Entry<String, Entry>> it = tokens.entrySet().iterator();
        while (it.hasNext()) {
            if (now >= it.next().getValue().expiresAtMillis) {
                it.remove();
            }
        }
    }

    /** 令牌绑定的授权快照（签发现场的三元组）。 */
    public record Grant(String subject, String role, Integer tenantScope) {
    }

    /** 存储条目：授权快照 + 过期时间（不可变）。 */
    private static final class Entry {
        final String subject;
        final String role;
        final Integer tenantScope;
        final long expiresAtMillis;

        Entry(String subject, String role, Integer tenantScope, long expiresAtMillis) {
            this.subject = subject;
            this.role = role;
            this.tenantScope = tenantScope;
            this.expiresAtMillis = expiresAtMillis;
        }
    }
}
