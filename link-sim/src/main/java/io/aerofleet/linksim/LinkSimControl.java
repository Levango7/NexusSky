package io.aerofleet.linksim;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

/**
 * link-sim 运行时损伤控制（P2 #20）。
 *
 * <p>把上下行两个 {@link ImpairmentEngine} 暴露成可读可改的语义层：
 * <ul>
 *   <li>{@link #view()}：当前画像/模式 + 十元组参数 + 累计统计；</li>
 *   <li>{@link #patch(Map)}：{@code profile}（整画像切换）、{@code reset}
 *       （恢复当前画像默认值）、或一组自定义损伤参数（局部更新）。</li>
 * </ul>
 *
 * <p><b>方向</b>：{@code direction=uplink|downlink|both}（默认 both）。真实链路
 * 上下行不对称，控制面保留按方向改的能力；前端面板默认同时改两个方向。
 *
 * <p><b>丢包语义</b>：自定义模式下的 {@code dropPct} 是<b>独立随机丢包</b>
 * （Bernoulli，无突发记忆），与画像的 Gilbert-Elliot 突发模型是两种模式；
 * 响应里的 {@code dropModel} 明确区分 {@code ge} / {@code uniform}。
 *
 * <p><b>线程模型</b>：patch/view 用同一把锁保证"两个方向 + 模式标记"原子生效；
 * 引擎自己的 apply/verdict 各自同步。控制面只在 PATCH 时短暂持锁，不参与
 * 数据面转发。
 */
final class LinkSimControl {

    /** 参数上限：不是物理定律，是防止误操作把仿真链路调成不可观测（如延迟 1 小时）。 */
    static final double MAX_DELAY_MS = 5_000;
    static final double MAX_JITTER_MS = 5_000;
    static final double MAX_BANDWIDTH_KBPS = 1_000_000;
    static final double MAX_PARTITION_CYCLE_SEC = 3_600;
    static final double MAX_PARTITION_DOWN_SEC = 3_600;

    private static final Set<String> ALLOWED_FIELDS = Set.of(
            "direction", "profile", "reset",
            "delayMs", "jitterMs", "dropPct", "bandwidthKbps",
            "partitionCycleSec", "partitionDownSec");

    /** 一个方向的运行态：引擎 + 最近一次选择的画像 + 是否已被自定义参数覆盖。 */
    private static final class Direction {
        final ImpairmentEngine engine;
        volatile LinkProfile profile;
        volatile boolean custom;
        /** 丢包模型是否已被 dropPct 切到独立随机（false=画像的 GE）。 */
        volatile boolean uniformDrop;

        Direction(LinkProfile profile, ImpairmentEngine engine) {
            this.profile = profile;
            this.engine = engine;
        }
    }

    private final Direction uplink;
    private final Direction downlink;
    private final boolean envCoupled;

    LinkSimControl(LinkProfile uplinkProfile, ImpairmentEngine uplinkEngine,
                   LinkProfile downlinkProfile, ImpairmentEngine downlinkEngine,
                   boolean envCoupled) {
        this.uplink = new Direction(uplinkProfile, uplinkEngine);
        this.downlink = new Direction(downlinkProfile, downlinkEngine);
        this.envCoupled = envCoupled;
    }

    /**
     * 控制请求不合法（映射为 HTTP 400），与内部错误分开。
     * <p><b>不加 final</b>：安全画像的控制层（{@link SecurityControl}）复用这一
     * 类型，让控制服务器只需一处 catch 就能把"请求错"映射成 400。若给校验异常
     * 各起一个类型，漏接的那个会逃成 500——把请求错误报成服务器错误，排障路径
     * 直接指错方向（实测踩过：安全画像 PATCH 空 body 返回 500 而非 400）。
     */
    static class BadRequestException extends RuntimeException {
        BadRequestException(String message) {
            super(message);
        }
    }

    /**
     * 当前状态视图（GET /profile）。
     */
    synchronized Map<String, Object> view() {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("profile", uplink.profile == downlink.profile
                ? uplink.profile.name
                : uplink.profile.name + "/" + downlink.profile.name);
        out.put("mode", modeOf(uplink, downlink));
        out.put("envCoupled", envCoupled);
        out.put("profiles", LinkProfile.nameList());
        out.put("uplink", directionView(uplink));
        out.put("downlink", directionView(downlink));
        Map<String, Object> limits = new LinkedHashMap<>();
        limits.put("delayMsMax", MAX_DELAY_MS);
        limits.put("jitterMsMax", MAX_JITTER_MS);
        limits.put("bandwidthKbpsMax", MAX_BANDWIDTH_KBPS);
        limits.put("partitionCycleSecMax", MAX_PARTITION_CYCLE_SEC);
        limits.put("partitionDownSecMax", MAX_PARTITION_DOWN_SEC);
        out.put("limits", limits);
        return out;
    }

    /**
     * 应用一次控制请求（PATCH /profile）。
     *
     * <p>三种互斥形态：
     * <ol>
     *   <li>{@code profile}: 切换到指定画像（两个方向都回到该画像的 GE 默认值）；</li>
     *   <li>{@code reset}: 恢复<b>当前</b>画像默认值（自定义改动丢弃）；</li>
     *   <li>自定义字段：局部更新，未给字段保持当前值；{@code dropPct} 存在时
     *       整条丢包模型切到独立随机（见类注释）。</li>
     * </ol>
     *
     * @return 应用后的完整视图（与 GET 同形）
     * @throws BadRequestException 字段名未知、类型/范围非法、或三种形态混用
     */
    synchronized Map<String, Object> patch(Map<String, Object> body) {
        if (body == null) {
            throw new BadRequestException("request body must be a JSON object");
        }
        for (String key : body.keySet()) {
            if (!ALLOWED_FIELDS.contains(key)) {
                throw new BadRequestException("unknown field '" + key + "'");
            }
        }

        String direction = optString(body, "direction", "both");
        if (!direction.equals("both") && !direction.equals("uplink") && !direction.equals("downlink")) {
            throw new BadRequestException("direction must be one of both/uplink/downlink");
        }

        boolean hasProfile = body.containsKey("profile");
        boolean hasReset = body.containsKey("reset");
        boolean hasDamage = body.keySet().stream().anyMatch(k -> !k.equals("direction")
                && !k.equals("profile") && !k.equals("reset"));
        if (hasProfile && hasReset) {
            throw new BadRequestException("'profile' and 'reset' are mutually exclusive");
        }
        if ((hasProfile || hasReset) && hasDamage) {
            throw new BadRequestException("'profile'/'reset' cannot be combined with damage fields");
        }
        if (!hasProfile && !hasReset && !hasDamage) {
            throw new BadRequestException("nothing to update: give 'profile', 'reset', or damage fields");
        }

        Direction[] targets = targets(direction);

        if (hasProfile) {
            Object raw = body.get("profile");
            if (!(raw instanceof String name) || name.isBlank()) {
                throw new BadRequestException("'profile' must be a non-empty string");
            }
            LinkProfile profile = LinkProfile.of(name);
            if (profile == null) {
                throw new BadRequestException("unknown profile '" + name
                        + "'; available: " + LinkProfile.names());
            }
            for (Direction d : targets) {
                d.profile = profile;
                d.custom = false;
                d.uniformDrop = false;
                d.engine.apply(ImpairmentSpec.of(profile));
            }
            return view();
        }

        if (hasReset) {
            Object raw = body.get("reset");
            if (!(raw instanceof Boolean b) || !b) {
                throw new BadRequestException("'reset' must be true (false is a no-op; omit it instead)");
            }
            for (Direction d : targets) {
                d.custom = false;
                d.uniformDrop = false;
                d.engine.apply(ImpairmentSpec.of(d.profile));
            }
            return view();
        }

        // 先校验所有字段，再统一应用——避免上下行改到一半才 400。
        Double delay = body.containsKey("delayMs") ? number(body, "delayMs", 0, MAX_DELAY_MS) : null;
        Double jitter = body.containsKey("jitterMs") ? number(body, "jitterMs", 0, MAX_JITTER_MS) : null;
        Double dropPct = body.containsKey("dropPct") ? number(body, "dropPct", 0, 100) : null;
        Double bandwidthKbps = body.containsKey("bandwidthKbps")
                ? number(body, "bandwidthKbps", 0, MAX_BANDWIDTH_KBPS) : null;
        Double cycleSec = body.containsKey("partitionCycleSec")
                ? number(body, "partitionCycleSec", 0, MAX_PARTITION_CYCLE_SEC) : null;
        Double downSec = body.containsKey("partitionDownSec")
                ? number(body, "partitionDownSec", 0, MAX_PARTITION_DOWN_SEC) : null;

        for (Direction d : targets) {
            ImpairmentSpec base = d.engine.spec();
            double newCycle = cycleSec != null ? cycleSec : base.partitionCycleSec();
            double newDown = downSec != null ? downSec : base.partitionDownSec();
            if (newDown > 0 && newCycle <= 0) {
                throw new BadRequestException(
                        "partitionDownSec requires partitionCycleSec > 0");
            }
            if (newDown > newCycle) {
                throw new BadRequestException("partitionDownSec (" + newDown
                        + ") must not exceed partitionCycleSec (" + newCycle + ")");
            }
        }

        for (Direction d : targets) {
            ImpairmentSpec base = d.engine.spec();
            double newDelay = delay != null ? delay : base.delayMs();
            double newJitter = jitter != null ? jitter : base.jitterMs();
            double rate = base.rateBytesPerSec();
            double burst = base.burstBytes();
            if (bandwidthKbps != null) {
                rate = bandwidthKbps * 1000.0 / 8.0;   // kbps -> bytes/s
                burst = rate == 0 ? 2_048 : Math.max(2_048, rate / 10.0);
            }
            double newCycle = cycleSec != null ? cycleSec : base.partitionCycleSec();
            double newDown = downSec != null ? downSec : base.partitionDownSec();

            ImpairmentSpec next = new ImpairmentSpec(newDelay, newJitter,
                    base.pGoodDrop(), base.pBadDrop(), base.pGoodToBad(), base.pBadToGood(),
                    burst, rate, Math.max(0, newCycle - newDown), newDown);
            if (dropPct != null) {
                next = next.withUniformDrop(dropPct);
                d.uniformDrop = true;
            }
            d.engine.apply(next);
            d.custom = true;
        }
        return view();
    }

    private Direction[] targets(String direction) {
        return switch (direction) {
            case "uplink" -> new Direction[]{uplink};
            case "downlink" -> new Direction[]{downlink};
            default -> new Direction[]{uplink, downlink};
        };
    }

    private static String modeOf(Direction up, Direction down) {
        if (up.custom == down.custom) {
            return up.custom ? "custom" : "profile";
        }
        return "mixed";
    }

    private static Map<String, Object> directionView(Direction d) {
        ImpairmentSpec s = d.engine.spec();
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("profile", d.profile.name);
        m.put("mode", d.custom ? "custom" : "profile");
        m.put("delayMs", s.delayMs());
        m.put("jitterMs", s.jitterMs());
        m.put("dropModel", d.uniformDrop ? "uniform" : "ge");
        m.put("dropPct", s.pGoodDrop() * 100.0);
        m.put("pGoodDrop", s.pGoodDrop());
        m.put("pBadDrop", s.pBadDrop());
        m.put("pGoodToBad", s.pGoodToBad());
        m.put("pBadToGood", s.pBadToGood());
        m.put("bandwidthKbps", s.bandwidthKbps());
        m.put("partitionCycleSec", s.partitionCycleSec());
        m.put("partitionDownSec", s.partitionDownSec());
        ImpairmentEngine.Stats st = d.engine.statsSnapshot();
        Map<String, Object> stats = new LinkedHashMap<>();
        stats.put("forwarded", st.forwarded());
        stats.put("dropped", st.dropped());
        stats.put("dropPct", st.dropPct());
        stats.put("avgDelayMs", st.avgDelayMs());
        m.put("stats", stats);
        return m;
    }

    private static String optString(Map<String, Object> body, String key, String fallback) {
        if (!body.containsKey(key)) {
            return fallback;
        }
        Object raw = body.get(key);
        if (!(raw instanceof String s) || s.isBlank()) {
            throw new BadRequestException("'" + key + "' must be a non-empty string");
        }
        return s;
    }

    private static double number(Map<String, Object> body, String key, double min, double max) {
        Object raw = body.get(key);
        if (!(raw instanceof Number n)) {
            throw new BadRequestException("'" + key + "' must be a number");
        }
        double v = n.doubleValue();
        if (Double.isNaN(v) || Double.isInfinite(v)) {
            throw new BadRequestException("'" + key + "' must be finite");
        }
        if (v < min || v > max) {
            throw new BadRequestException("'" + key + "' out of range [" + min + ", " + max + "]: " + v);
        }
        return v;
    }
}
