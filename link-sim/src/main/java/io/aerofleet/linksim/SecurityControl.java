package io.aerofleet.linksim;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 安全画像的运行时控制（P2 #20）。
 *
 * <p><b>为什么不复用 {@link LinkSimControl}</b>：那套语义层围绕十元组损伤参数
 * （延迟/抖动/GE 四参数/令牌桶/分区）设计，安全画像一个都不占——硬套会出现
 * "delayMs=0 恒定、改了也没人看"的假旋钮。这里只暴露<b>真实存在</b>的参数：
 * {@code tamperRate}（篡改概率）。签名剥离是动作全部命中（检测到签名帧必剥），
 * 无概率可调，于是<b>不给它滑块</b>——给一个拖了不改变任何行为的控件，
 * 正是本仓一直在清的病（P1 #11 刚清过一批假滑块）。
 *
 * <p><b>stats 口径为什么不套 ImpairmentEngine.Stats</b>：那套键名是
 * forwarded/dropped/dropPct/avgDelayMs，为丢包模型服务。安全画像里
 * "篡改后的帧"<b>照发不误</b>（否则对端 CRC 阶段就丢掉，永远走不到验签失败），
 * 把它填进 "dropped" 等于告诉面板"这些包丢了"——事实上一个都没少到。
 * 因此通用键按安全画像的真实语义填（dropped=0：安全画像从不丢包），
 * 另给 tampered/stripped/tamperRatio 三个<b>如实描述发生了什么</b>的键；
 * 前端按 mode==="security" 分支显示，不把通用滑块的 0 值当默认值秀出来。
 */
final class SecurityControl {

    /**
     * 控制请求不合法（映射 HTTP 400）。
     * <p>必须继承 {@link LinkSimControl.BadRequestException}：控制服务器只捕获
     * 那一个类型——自成一套的话安全画像的校验失败会逃成 500，而 500 说的是
     * "服务器错了"，实际错在请求，两者排障路径完全不同。
     */
    static final class BadRequestException extends LinkSimControl.BadRequestException {
        BadRequestException(String message) {
            super(message);
        }
    }

    /**
     * 安全画像的控制面视图。
     *
     * @param profile    画像名（tamper/unsigned）
     * @param tamperRate 当前篡改概率
     * @param forwarded  正常转发帧数
     * @param tampered   篡改后转发帧数（签名坏，但对端确实收到了）
     * @param stripped   剥离签名后转发帧数
     * @param tamperRatio 出过安全动作的帧占比（tampered+strip)/total
     */
    record Snapshot(String profile, double tamperRate, long forwarded, long tampered,
                    long stripped, double tamperRatio) {
    }

    private final SecurityImpairmentEngine engine;

    SecurityControl(SecurityImpairmentEngine engine) {
        this.engine = engine;
    }

    /** GET /profile 的视图。 */
    synchronized Map<String, Object> view() {
        SecurityControl.Snapshot s = engine.snapshot();
        LinkProfile profile = engine.profile();

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("profile", s.profile());
        out.put("mode", "security");
        out.put("envCoupled", false);

        Map<String, Object> sec = new LinkedHashMap<>();
        // kind 用枚举常量名（SIGNING_TAMPER/SIGNING_UNSIGNED），profile 用别名
        // （tamper/unsigned）——后者与其它画像同一口径，给前端一致的下拉/展示。
        sec.put("kind", profile.name());
        sec.put("tamperRate", s.tamperRate());
        sec.put("tunable", "tamperRate");
        Map<String, Object> limits = new LinkedHashMap<>();
        limits.put("tamperRateMin", SecurityImpairmentEngine.TAMPER_RATE_MIN);
        limits.put("tamperRateMax", SecurityImpairmentEngine.TAMPER_RATE_MAX);
        sec.put("limits", limits);
        // 明说哪些参数<b>没有</b>滑块：签名剥离是检测到签名帧必剥（动作全命中），
        // 没有概率可调，给滑块就是假控件。
        sec.put("fixed", java.util.Map.of(
                "stripSignature", "all-or-nothing（检测到签名帧必剥，无概率可调）"));
        out.put("security", sec);

        Map<String, Object> up = directionView(s);
        out.put("uplink", up);
        out.put("downlink", up);
        return out;
    }

    private static Map<String, Object> directionView(SecurityControl.Snapshot s) {
        Map<String, Object> up = new LinkedHashMap<>();
        up.put("profile", s.profile());
        up.put("mode", "security");
        up.put("tamperRate", s.tamperRate());
        up.put("stats", statsOf(s));
        return up;
    }

    private static Map<String, Object> statsOf(SecurityControl.Snapshot s) {
        Map<String, Object> stats = new LinkedHashMap<>();
        // 通用键：安全画像从不丢包、无延迟——两个 0 是事实，不是占位
        stats.put("forwarded", s.forwarded());
        stats.put("dropped", 0L);
        stats.put("dropPct", 0.0);
        stats.put("avgDelayMs", 0L);
        // 安全画像专属键：如实描述"帧到了但被做过什么"
        stats.put("tampered", s.tampered());
        stats.put("stripped", s.stripped());
        stats.put("tamperRatio", s.tamperRatio());
        return stats;
    }

    /**
     * PATCH /profile：只接受 {@code tamperRate}（0~1）。
     *
     * <p>其它字段（delayMs/dropPct/...）一律 400 并说明原因——对安全画像它们
     * 不存在，接受会等于"记录了但没有任何效果"（夹带也不行：{@code {"delayMs":1,
     * "tamperRate":0.5}} 里 delayMs 无人消费，收下它就是在伪造"已应用"）。
     */
    synchronized Map<String, Object> patch(Map<String, Object> body) {
        if (body == null || body.isEmpty()) {
            throw new BadRequestException(
                    "security profiles expose only 'tamperRate' (0.0-1.0)");
        }
        for (String key : body.keySet()) {
            if (!"tamperRate".equals(key)) {
                throw new BadRequestException(
                        "security profiles expose only 'tamperRate' (0.0-1.0); got '"
                                + key + "'");
            }
        }
        Object raw = body.get("tamperRate");
        if (!(raw instanceof Number n) || Double.isNaN(n.doubleValue())) {
            throw new BadRequestException("'tamperRate' must be a finite number");
        }
        double rate = n.doubleValue();
        if (rate < SecurityImpairmentEngine.TAMPER_RATE_MIN
                || rate > SecurityImpairmentEngine.TAMPER_RATE_MAX) {
            throw new BadRequestException("'tamperRate' out of range ["
                    + SecurityImpairmentEngine.TAMPER_RATE_MIN + ", "
                    + SecurityImpairmentEngine.TAMPER_RATE_MAX + "]: " + rate);
        }
        engine.applyTamperRate(rate);
        return view();
    }
}
