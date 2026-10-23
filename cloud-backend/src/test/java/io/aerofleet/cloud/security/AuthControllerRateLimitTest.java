package io.aerofleet.cloud.security;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.ResponseEntity;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.Duration;
import java.util.HashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 登录限流器与客户端 IP 提取的行为测试。
 *
 * <p><b>为什么单独一个类</b>：2026-10-21 用 PIT 变异测试审 security 包时，
 * {@code AuthController} 有 <b>16 个变异体存活</b>（共 89 个，变异分 46%），
 * 其中 {@code isRateLimited} 与 {@code extractClientIp} 两个方法**没有任何测试碰过** ——
 * 也就是说：把限流阈值从「每分钟 10 次」改成别的、把「是否信任 X-Forwarded-For」
 * 取反、把「计数失败的登录」改成「只计数成功的登录」，**现有测试一律全绿**。
 *
 * <p>这不是理论风险：限流器是暴力破解的第一道闸，而 X-Forwarded-For 的信任开关
 * 一旦被取反，攻击者就能靠伪造转发头绕开限流（每换一个头就是一个新桶）。
 *
 * <p>被本类钉住的变异（2026-10-21 实测，限流器/客户端 IP 相关的存活变异体清零）：
 * <ul>
 *   <li>{@code bucket.timestamps.size() >= MAX_ATTEMPTS_PER_MINUTE} 的 {@code >=} 边界</li>
 *   <li>{@code return false}（不限流）↔ {@code return true}（恒限流）</li>
 *   <li>{@code if (trustForwardedFor)} 的取反</li>
 *   <li>{@code remoteAddr == null || isBlank()} 的取反与「返回 null」</li>
 * </ul>
 */
@DisplayName("AuthController 登录限流与客户端 IP 提取")
class AuthControllerRateLimitTest {

    private static final String SECRET = "this-is-a-test-secret-key-32bytes!";
    private static final String USERS_CONFIG = "admin:admin,operator:op123";
    private static final long EXPIRY_SECONDS = 3600L;

    private AuthController controller;

    @BeforeEach
    void setUp() {
        JwtTokenProvider tokenProvider = new JwtTokenProvider(SECRET);
        // 刻意用**快编码器**而不是 BCrypt：限流与口令编码毫无关系，
        // 而 BCrypt 一次 ~100ms —— PIT 要为**每个变异体**重跑一遍覆盖测试，
        // 用 BCrypt 会让整个 security 包的变异跑从 6 分钟涨到 20 分钟以上。
        PasswordEncoder passwordEncoder = new PasswordEncoder() {
            @Override
            public String encode(CharSequence rawPassword) {
                return "fast:" + rawPassword;
            }

            @Override
            public boolean matches(CharSequence rawPassword, String encodedPassword) {
                return encodedPassword != null && encodedPassword.equals("fast:" + rawPassword);
            }

            @Override
            public boolean upgradeEncoding(String encodedPassword) {
                return false;
            }
        };
        controller = new AuthController(tokenProvider, passwordEncoder, USERS_CONFIG,
                EXPIRY_SECONDS, new StreamTokenService(), null);
    }

    @AfterEach
    void cleanUp() {
        SecurityContextHolder.clearContext();
        ApiKeyContext.clear();
        TenantContext.clear();
    }

    private static Map<String, String> body(String username, String password) {
        Map<String, String> b = new HashMap<>();
        b.put("username", username);
        b.put("password", password);
        return b;
    }

    private static MockHttpServletRequest request(String remoteAddr) {
        MockHttpServletRequest req = new MockHttpServletRequest();
        req.setRemoteAddr(remoteAddr);
        return req;
    }

    private ResponseEntity<Map<String, Object>> login(MockHttpServletRequest req) {
        return controller.login(body("admin", "admin"), req);
    }

    @Test
    @DisplayName("同一 IP 连打满 10 次后，第 11 次返回 429（限流阈值被钉住）")
    void login_eleventhAttemptWithinWindow_returns429() {
        int max = AuthController.MAX_ATTEMPTS_PER_MINUTE;

        for (int i = 1; i <= max; i++) {
            ResponseEntity<Map<String, Object>> resp = login(request("10.0.0.1"));
            assertThat(resp.getStatusCode().value())
                    .as("第 %d 次（阈值内）应当放行", i)
                    .isEqualTo(200);
        }

        ResponseEntity<Map<String, Object>> limited = login(request("10.0.0.1"));
        assertThat(limited.getStatusCode().value())
                .as("第 %d 次（超出阈值）必须被限流", max + 1)
                .isEqualTo(429);
        assertThat(limited.getBody()).containsEntry("error", "too many login attempts, please try again later");
    }

    @Test
    @DisplayName("限流是按 IP 分桶的：另一个 IP 不受影响")
    void login_rateLimitIsPerIp_otherIpStillAllowed() {
        int max = AuthController.MAX_ATTEMPTS_PER_MINUTE;

        for (int i = 0; i < max; i++) {
            login(request("10.0.0.1"));
        }
        assertThat(login(request("10.0.0.1")).getStatusCode().value()).isEqualTo(429);

        ResponseEntity<Map<String, Object>> otherIp = login(request("10.0.0.2"));
        assertThat(otherIp.getStatusCode().value())
                .as("限流必须按 IP 分桶，不能变成全局封禁")
                .isEqualTo(200);
    }

    @Test
    @DisplayName("限流也统计失败的登录（暴力破解用错口令也照样被限）")
    void login_rateLimitCountsFailedAttempts() {
        int max = AuthController.MAX_ATTEMPTS_PER_MINUTE;

        for (int i = 0; i < max; i++) {
            ResponseEntity<Map<String, Object>> resp =
                    controller.login(body("admin", "wrong-password"), request("10.0.0.3"));
            assertThat(resp.getStatusCode().value()).isEqualTo(401);
        }

        ResponseEntity<Map<String, Object>> afterFailures =
                controller.login(body("admin", "admin"), request("10.0.0.3"));
        assertThat(afterFailures.getStatusCode().value())
                .as("失败的尝试必须计入限流，否则暴力破解不受限")
                .isEqualTo(429);
    }

    @Test
    @DisplayName("默认不信任 X-Forwarded-For（伪造转发头不能换桶）")
    void login_forwardedForIgnoredByDefault_sameRemoteAddrSharesBucket() {
        int max = AuthController.MAX_ATTEMPTS_PER_MINUTE;

        for (int i = 0; i < max; i++) {
            MockHttpServletRequest req = request("10.0.0.4");
            req.addHeader("X-Forwarded-For", "1.1.1.1");
            login(req);
        }

        // 换一个伪造的转发头，但 remoteAddr 相同 ⇒ 仍是同一个桶 ⇒ 必须被限流
        MockHttpServletRequest spoofed = request("10.0.0.4");
        spoofed.addHeader("X-Forwarded-For", "2.2.2.2");
        ResponseEntity<Map<String, Object>> resp = login(spoofed);
        assertThat(resp.getStatusCode().value())
                .as("默认必须忽略 X-Forwarded-For，否则伪造头即可绕开限流")
                .isEqualTo(429);
    }

    @Test
    @DisplayName("开启信任后按 X-Forwarded-For 的首个地址分桶")
    void login_forwardedForTrusted_whenEnabled_usesFirstXffEntry() {
        ReflectionTestUtils.setField(controller, "trustForwardedFor", true);
        int max = AuthController.MAX_ATTEMPTS_PER_MINUTE;

        for (int i = 0; i < max; i++) {
            MockHttpServletRequest req = request("10.0.0.5");
            req.addHeader("X-Forwarded-For", "3.3.3.3, 4.4.4.4");
            login(req);
        }

        // 同一个 XFF（首个地址相同）⇒ 同一个桶 ⇒ 被限流
        MockHttpServletRequest sameXff = request("10.0.0.5");
        sameXff.addHeader("X-Forwarded-For", "3.3.3.3");
        assertThat(login(sameXff).getStatusCode().value())
                .as("信任转发头时必须按 XFF 首个地址分桶")
                .isEqualTo(429);

        // 换 XFF ⇒ 换桶 ⇒ 不被限
        MockHttpServletRequest otherXff = request("10.0.0.5");
        otherXff.addHeader("X-Forwarded-For", "5.5.5.5");
        assertThat(login(otherXff).getStatusCode().value())
                .as("不同的 XFF 属于不同的桶")
                .isEqualTo(200);
    }

    @Test
    @DisplayName("X-Forwarded-For 为空白时回退到 remoteAddr")
    void login_blankForwardedFor_fallsBackToRemoteAddr() {
        int max = AuthController.MAX_ATTEMPTS_PER_MINUTE;

        for (int i = 0; i < max; i++) {
            MockHttpServletRequest req = request("10.0.0.6");
            req.addHeader("X-Forwarded-For", "   ");
            login(req);
        }
        MockHttpServletRequest blank = request("10.0.0.6");
        blank.addHeader("X-Forwarded-For", "  ");
        assertThat(login(blank).getStatusCode().value())
                .as("空白转发头必须回退到 remoteAddr（否则每条空白头都是一个新桶）")
                .isEqualTo(429);
    }

    @Test
    @DisplayName("限流桶按窗口过期：旧记录清掉后重新放行")
    void login_rateLimitBucketExpiresAfterWindow() throws Exception {
        int max = AuthController.MAX_ATTEMPTS_PER_MINUTE;
        for (int i = 0; i < max; i++) {
            login(request("10.0.0.7"));
        }
        assertThat(login(request("10.0.0.7")).getStatusCode().value()).isEqualTo(429);

        // 把桶里的时间戳推到窗口之外（窗口 = 60s）
        // 注意：这里刻意把反射失败转成**断言失败**而不是抛异常 ——
        // PIT 把"测试抛异常"记成 RUN_ERROR（既不算杀掉也不算存活），
        // 会让变异分数莫名其妙地抖；而"桶结构被改坏"本身就是真实缺陷，
        // 用断言报出来才对。
        try {
            controller.cleanupRateBuckets();
            for (java.lang.reflect.Field f : AuthController.class.getDeclaredFields()) {
                if (!f.getName().equals("loginRateBuckets")) {
                    continue;
                }
                f.setAccessible(true);
                Object buckets = f.get(controller);
                assertThat(buckets).as("限流桶表不应为 null").isNotNull();
                for (Object bucket : ((Map<?, ?>) buckets).values()) {
                    for (java.lang.reflect.Field bf : bucket.getClass().getDeclaredFields()) {
                        if (!bf.getName().equals("timestamps")) {
                            continue;
                        }
                        bf.setAccessible(true);
                        @SuppressWarnings("unchecked")
                        java.util.List<Long> ts = (java.util.List<Long>) bf.get(bucket);
                        assertThat(ts).as("时间戳列表不应为 null").isNotNull();
                        for (int i = 0; i < ts.size(); i++) {
                            ts.set(i, ts.get(i) - 120_000L);
                        }
                    }
                }
            }
        } catch (IllegalAccessException e) {
            throw new AssertionError("无法访问限流桶内部结构（限流实现被改动了）", e);
        }
        controller.cleanupRateBuckets();

        assertThat(login(request("10.0.0.7")).getStatusCode().value())
                .as("窗口外的旧记录必须被清掉，否则封禁永不解除")
                .isEqualTo(200);
    }

    @Test
    @DisplayName("限流提示信息不得泄露账号是否存在")
    void login_rateLimitedMessage_doesNotLeakAccountExistence() {
        int max = AuthController.MAX_ATTEMPTS_PER_MINUTE;
        for (int i = 0; i < max; i++) {
            controller.login(body("admin", "admin"), request("10.0.0.8"));
        }
        ResponseEntity<Map<String, Object>> limited =
                controller.login(body("admin", "admin"), request("10.0.0.8"));
        assertThat(limited.getStatusCode().value()).isEqualTo(429);
        String err = String.valueOf(limited.getBody().get("error"));
        assertThat(err).doesNotContain("admin").doesNotContain("password");
    }

    @Test
    @DisplayName("限流桶数量达到上限后，新 IP 也被限流（防内存耗尽的那道闸）")
    void login_bucketTableFull_newIpAlsoLimited() throws Exception {
        // 直接把桶表塞满（真去登录一万个 IP 太慢）；这一步测的是**闸门本身**：
        // 桶表满 ⇒ 即使是新 IP 也要被限流，否则攻击者换 IP 就能无限占内存。
        int cap = 10_000;   // 与 AuthController.MAX_RATE_BUCKETS 一致
        java.lang.reflect.Field f = AuthController.class.getDeclaredField("loginRateBuckets");
        f.setAccessible(true);
        @SuppressWarnings("unchecked")
        java.util.Map<String, Object> buckets = (java.util.Map<String, Object>) f.get(controller);
        // 用**真的** RateBucket 实例（它是私有内部类，只能反射造），
        // 别塞别的对象 —— 泛型擦除后强转会炸，那会让本用例变成"测了个寂寞"。
        Class<?> bucketClass = Class.forName("io.aerofleet.cloud.security.AuthController$RateBucket");
        java.lang.reflect.Constructor<?> ctor = bucketClass.getDeclaredConstructor();
        ctor.setAccessible(true);
        for (int i = 0; i < cap; i++) {
            buckets.put("10.1." + (i / 256) + "." + (i % 256), ctor.newInstance());
        }
        assertThat(buckets).hasSize(cap);

        ResponseEntity<Map<String, Object>> resp = login(request("203.0.113.9"));

        assertThat(resp.getStatusCode().value())
                .as("桶表满时新 IP 也必须被限流（防内存耗尽的闸门）")
                .isEqualTo(429);
    }

    // ===== 变异收口（P1 #7）：窗口边界 + 清理定时器（反射构造桶，零 sleep）=====
    //
    // 生存机理（A/B 实测复核过同族形状）：三个判定都用真实时钟跑，30/60 秒级的
    // 边界无法靠 sleep 命中；而 cleanupRateBuckets 是 @Scheduled，测试框架根本不
    // 调度它。所以这里直接把桶表反射出来按"精确时间差"构造，再直调被测方法。

    @SuppressWarnings("unchecked")
    private java.util.Map<String, Object> buckets() throws Exception {
        java.lang.reflect.Field f = AuthController.class.getDeclaredField("loginRateBuckets");
        f.setAccessible(true);
        return (java.util.Map<String, Object>) f.get(controller);
    }

    private Object newBucket() throws Exception {
        Class<?> bucketClass = Class.forName("io.aerofleet.cloud.security.AuthController$RateBucket");
        java.lang.reflect.Constructor<?> ctor = bucketClass.getDeclaredConstructor();
        ctor.setAccessible(true);
        return ctor.newInstance();
    }

    private java.util.List<Long> timestampsOf(Object bucket) throws Exception {
        java.lang.reflect.Field f = bucket.getClass().getDeclaredField("timestamps");
        f.setAccessible(true);
        return (java.util.List<Long>) f.get(bucket);
    }

    // 注：这里**刻意没有**「恰在窗口整点」的用例（isRateLimited 与 cleanup 各一，
    // 2026-10-24 PIT 第五轮实测已删）。原因：被测代码直接读 System.currentTimeMillis()，
    // 测试无法注入时钟——「now2 - ts == WINDOW_MS 恰好相等」需要测试内的时钟捕获与
    // 被测代码内部的时钟捕获落在同一毫秒（Δ=0）。surefire 下 Δ 恒 0 看似稳定，PIT 的
    // 插桩 JVM 里 Δ≥1ms 时**原始代码自己就把整点记录清了**，用例在未变异代码上就红，
    // 被 PIT 排除出变异验证（cleanupKeepsExactlyAtWindowEdge 实测触发）。这正是
    // surveillance 第四轮 prune 年龄边界「> → >= 需要精确相等才分叉 ⇒ 不可稳定测」
    // 的同款判定：边界点两侧皆合理语义，墙钟竞态命中概率取决于调度器心情。
    // ⇒ 两个边界变异体（isRateLimited 的 L343、cleanup 的 L358）定性为不可稳定测，
    // 其余 7 个确定性变异体由下列用例钉死。

    @Test
    @DisplayName("cleanupRateBuckets：新桶原样保留（Math/Negate/恒 true 三个变异都会清掉它）")
    void cleanupKeepsFreshBuckets() throws Exception {
        long now = System.currentTimeMillis();
        java.util.Map<String, Object> buckets = buckets();
        Object fresh = newBucket();
        timestampsOf(fresh).add(now); // 刚发生的尝试（now - ts == 0）
        buckets.put("10.8.8.8", fresh);

        controller.cleanupRateBuckets();

        // now-ts → now+ts（Math）/ > → <=（Negate）/ return isEmpty → 恒 true（BooleanTrue）
        // 三种变异都会把新桶清掉/删掉——线上表现为"限流保护每 60 秒被定时器自己清空"。
        assertThat(buckets)
                .as("新桶必须存活：清掉它的变异会让限流每隔一个周期形同虚设")
                .containsKey("10.8.8.8");
        assertThat(timestampsOf(buckets.get("10.8.8.8")))
                .as("新桶里的时间戳也必须原样保留").hasSize(1);
    }

    @Test
    @DisplayName("cleanupRateBuckets：只剩过期记录的空桶被移除（return isEmpty → 恒 false 变异会保留它）")
    void cleanupDropsEmptyBuckets() throws Exception {
        long now = System.currentTimeMillis();
        java.util.Map<String, Object> buckets = buckets();
        Object stale = newBucket();
        timestampsOf(stale).add(now - 120_000L); // 两窗口前
        buckets.put("10.6.6.6", stale);

        controller.cleanupRateBuckets();

        assertThat(buckets).as("过期空桶必须移除，否则空桶无限堆积").doesNotContainKey("10.6.6.6");
    }
}
