package io.aerofleet.cloud.license;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * REST 路径 → License 模块的映射表（全仓唯一真相源）。
 * <p>
 * <b>为什么单独成类</b>：2026-10-05 核查发现 {@link LicenseInterceptor} 内联的映射表
 * 只覆盖 5 个路径前缀（drones/scheduling/emergency/mesh/twin），而实际存在
 * <b>66 个 @RequestMapping 前缀</b>。2026-10-05 当时的实测：门禁只约束
 * <b>35/343 = 10.2%</b> 的端点
 * （端点总数此后已增至 353，此处保留当时的测量值不改数——改数等于篡改记录；分母的
 * 当前值以 {@code api-reference.md} 为准），
 * 其余 89.8% 只校验「License 是否过期」而不校验「该模块是否已授权」——
 * 结果是买了基础版（M5 mesh + M9 编排）的客户可访问全部应急指挥、安防联动、
 * 卫星中继、5G 基站等未付费模块。<b>三档定价在技术上不可执行。</b>
 * <p>
 * <b>fail-closed 语义</b>：本表按「前缀 → 模块」精确匹配。
 * <ul>
 *   <li>命中前缀 → 校验该模块授权，未授权则 403；</li>
 *   <li>{@link #UNMAPPED_POLICY} 指定的白名单路径（认证 / License 自管理 /
 *       健康探测 / 只读元数据）→ 显式放行，理由写在各条注释；</li>
 *   <li><b>既未命中映射、也不在白名单</b> → 视为 {@link #MODULE_UNCLASSIFIED}
 *       并拒绝（而非静默放行）。新增控制器若忘记登记模块，会在测试与运行期
 *       同时暴露，而不是悄悄变成免费功能。</li>
 * </ul>
 * <p>
 * <b>维护契约</b>：新增任何 {@code @RestController} 后，必须在本表登记其
 * {@code @RequestMapping} 前缀。{@code LicenseModuleCoverageTest} 会扫描全部
 * 控制器类并断言「每个前缀都能在本表找到归属」，未登记者测试红。
 *
 * @author AeroFleet Cloud Team
 */
final class LicenseModuleMap {

    /** 模块名常量：与 {@link LicenseService#ALL_MODULES} 保持同集合。 */
    static final String CORE = "core";
    static final String FLEET = "fleet";
    /** M5 AODV-lite mesh 自愈组网（2026-10-07 从 {@link #NETWORK} 拆出，基础版）。 */
    static final String MESH = "mesh";
    /** M9 应急任务编排 + 编排计划（2026-10-07 从 {@link #EMERGENCY} 拆出，基础版）。 */
    static final String ORCH = "orch";
    static final String EMERGENCY = "emergency";
    static final String NETWORK = "network";
    static final String ADVANCED = "advanced";

    /**
     * 未归类前缀使用的哨兵模块名。
     * <p>
     * 该值<b>不在</b> {@link LicenseService#ALL_MODULES} 中，因此
     * {@code LicenseInfo.hasModule(UNCLASSIFIED)} 恒为 false —— 这就是
     * fail-closed 的实现方式：未登记 = 永远不授权。
     */
    static final String MODULE_UNCLASSIFIED = "__unclassified__";

    /**
     * 无需模块授权的路径前缀白名单（精确前缀匹配，含子路径）。
     * <p>
     * 每条都必须写明「为什么不能要求模块授权」。默认拒绝，白名单是例外。
     */
    private static final List<String> UNMAPPED_POLICY = List.of(
            // 认证：拿不到 license 也要能登录，否则部署无法自救（鸡生蛋）
            "/api/v1/auth",
            // License 自管理：换 license、查状态必须可达
            "/api/v1/license",
            // 健康探测：供 K8s 探针与运维监控，与业务授权无关
            "/api/v1/health",
            // OpenAPI 元数据：仅供接入方拉接口定义，不含业务数据
            "/api/v1/openapi"
    );

    /**
     * 前缀 → 模块。key 为类级 {@code @RequestMapping} 的完整前缀。
     * <p>
     * 排序仅为可读性；匹配用「最长前缀优先」，故 {@code /api/v1/city-twin/models}
     * 不会被 {@code /api/v1/city-twin} 误吸。
     */
    private static final Map<String, String> PREFIX_TO_MODULE = build();

    private LicenseModuleMap() {
    }

    private static Map<String, String> build() {
        Map<String, String> m = new LinkedHashMap<>();

        // ============ core：机队、设备、任务、遥测 —— 基础版即可获得的「飞起来」能力 ============
        m.put("/api/v1/drones", CORE);
        m.put("/api/v1/devices", CORE);
        m.put("/api/v1/telemetry", CORE);
        m.put("/api/v1/mission", CORE);
        m.put("/api/v1/flightlog", CORE);
        m.put("/api/v1/tracking", CORE);
        m.put("/api/v1/env", CORE);
        m.put("/api/v1/env-alerts", CORE);
        m.put("/api/v1/geofence", CORE);
        m.put("/api/v1/rid", CORE);
        m.put("/api/v1/regulator", CORE);
        m.put("/api/v1/tenants", CORE);
        m.put("/api/v1/users", CORE);
        m.put("/api/v1/audit", CORE);
        m.put("/api/v1/webhooks", CORE);
        m.put("/api/v1/maintenance", CORE);
        // 硬件抽象（M4，FR-24~FR-28）：雷达 / 旋翼气动 / LiDAR / IMU。
        // 注意：HardwareDataController 的类级注解是裸 "/api/v1"，真实资源前缀在第二段，
        // 故此处登记的是「第二段」而非 "/api/v1"（登记 "/api/v1" 会把整个 API 面吞进 core，
        // 反而让未付费模块搭便车）。
        m.put("/api/v1/radar", CORE);
        m.put("/api/v1/rotor", CORE);
        m.put("/api/v1/lidar", CORE);
        m.put("/api/v1/imu", CORE);

        // ============ fleet：调度、编队、作业（喷洒/物流/测绘/巡检） ============
        m.put("/api/v1/scheduling", FLEET);
        m.put("/api/v1/formation", FLEET);
        m.put("/api/v1/squad", FLEET);
        m.put("/api/v1/autodispatch", FLEET);
        m.put("/api/v1/show", FLEET);
        m.put("/api/v1/spray", FLEET);
        m.put("/api/v1/delivery", FLEET);
        m.put("/api/v1/mapping", FLEET);
        m.put("/api/v1/inspection", FLEET);
        m.put("/api/v1/drone-lock", FLEET);
        // 缺陷报告与工单闭环（F4）：巡检发现缺陷→工单→复检闭环，与 inspection
        // 同属作业质量域——归 fleet 档（档位调整属产品决策）。
        m.put("/api/v1/defects", FLEET);
        // 行业航线库（F3）：杆塔/光伏/管线/河湖四类模板，与 inspection/mapping 同属
        // 任务作业范畴——归 fleet 档。
        m.put("/api/v1/route-templates", FLEET);
        // 机巢管控（F2）：无人值守定时作业是调度作业域的延伸（定时巡检 = scheduling
        // 的无人化），机巢是作业基础设施而非应急/重资产链路——归 fleet 档。
        // 定价文档未单列机巢，此为按语义的首个归类，档位调整属产品决策。
        m.put("/api/v1/docks", FLEET);

        // ============ orch：M9 应急任务编排 + 编排计划（基础版；2026-07 从 emergency 拆出）============
        // 定价文档 §5.1 把「M9 编排」列为**基础版**卖点，故与 4a 空地一体化指挥分开。
        // EmergencyOrchController（M9 应急任务编排 FR-30）与 OrchestrationController（编排计划）
        // 属编排域，进基础版。
        m.put("/api/v1/emergency", ORCH);
        m.put("/api/v1/orch", ORCH);

        // ============ emergency：4a 空地一体化指挥 + 告警 + 安防联动（应急版核心付费点）============
        m.put("/api/v1/emergency-command", EMERGENCY);
        m.put("/api/v1/air-ground", EMERGENCY);
        m.put("/api/v1/alarms", EMERGENCY);
        m.put("/api/v1/offline-alarm", EMERGENCY);
        m.put("/api/v1/surveillance", EMERGENCY);
        // 视频融合聚合视图（GCS VideoFusionPanel 的 4 个端点）。商业分档上「视频/视觉」
        // 属应急版，与 surveillance / vision / alarms 同档。
        // 2026-10-06 补：此前该前缀无后端控制器，面板运行期必然 404，故从未登记；
        // 控制器补齐后必须登记，否则面板在 prod（license.enabled=true）会被 403。
        m.put("/api/v1/video-fusion", EMERGENCY);
        m.put("/api/v1/video-stream", EMERGENCY);
        m.put("/api/v1/scenarios", EMERGENCY);
        m.put("/api/v1/disaster", EMERGENCY);
        m.put("/api/v1/voice-intercom", EMERGENCY);
        m.put("/api/v1/vision", EMERGENCY);
        m.put("/api/v1/cv-eval", EMERGENCY);

        // ============ mesh：M5 AODV-lite 自愈组网（基础版；2026-10-07 从 network 拆出）============
        // 定价文档 §5.1 把「M5 mesh」列为**基础版**卖点。mesh 是单机多机的组网自愈，
        // 与 5G 基站 / 卫星中继这类重资产链路不是同一交付形态，故独立成模块。
        m.put("/api/v1/mesh", MESH);

        // ============ network：基站 / 卫星 / 链路适配（完整版付费点）============
        m.put("/api/v1/celltowers", NETWORK);
        m.put("/api/v1/sat-link", NETWORK);
        m.put("/api/v1/comm-adapt", NETWORK);
        m.put("/api/v1/loRa", NETWORK);
        m.put("/api/v1/edge", NETWORK);

        // ============ advanced：数字孪生 / AI 决策 / 城市孪生 ============
        m.put("/api/v1/twin", ADVANCED);
        m.put("/api/v1/city-twin", ADVANCED);
        m.put("/api/v1/ai", ADVANCED);
        m.put("/api/v1/voice-cmd", ADVANCED);
        m.put("/api/v1/thermal", ADVANCED);
        m.put("/api/v1/multispectral", ADVANCED);
        m.put("/api/v1/obstacle", ADVANCED);
        m.put("/api/v1/terrain", ADVANCED);

        return Map.copyOf(m);
    }

    /**
     * 解析请求 URI 到所需模块。
     *
     * @param requestURI 请求路径
     * @return 所需模块名；路径在白名单内时返回 {@code null}（无需模块授权）；
     *         其余未登记路径返回 {@link #MODULE_UNCLASSIFIED}（调用方必须拒绝）
     */
    static String resolve(String requestURI) {
        if (requestURI == null || requestURI.isEmpty()) {
            return MODULE_UNCLASSIFIED;
        }
        // 去掉查询串与末尾斜杠，避免 "/api/v1/drones?x=1" 或 "/api/v1/drones/" 漏配
        String path = requestURI;
        int q = path.indexOf('?');
        if (q >= 0) {
            path = path.substring(0, q);
        }
        while (path.length() > 1 && path.endsWith("/")) {
            path = path.substring(0, path.length() - 1);
        }

        // 白名单：精确前缀 + 其后必须紧跟 '/' 或结束（避免 /api/v1/healthz 误命中 /api/v1/health）
        for (String allowed : UNMAPPED_POLICY) {
            if (matchesPrefix(path, allowed)) {
                return null;
            }
        }

        // 最长前缀优先：/api/v1/city-twin/models 应命中 /api/v1/city-twin 而非退化为未归类
        String best = null;
        for (String prefix : PREFIX_TO_MODULE.keySet()) {
            if (matchesPrefix(path, prefix)
                    && (best == null || prefix.length() > best.length())) {
                best = prefix;
            }
        }
        return best == null ? MODULE_UNCLASSIFIED : PREFIX_TO_MODULE.get(best);
    }

    /** 前缀匹配：path 等于 prefix，或以 prefix + "/" 开头。 */
    private static boolean matchesPrefix(String path, String prefix) {
        return path.equals(prefix) || path.startsWith(prefix + "/");
    }

    /** 供测试断言：全部已登记前缀。 */
    static java.util.Set<String> registeredPrefixes() {
        return PREFIX_TO_MODULE.keySet();
    }

    /** 供测试断言：模块名 → 该模块下已登记的前缀数。 */
    static Map<String, Integer> prefixesPerModule() {
        Map<String, Integer> counts = new LinkedHashMap<>();
        for (String module : PREFIX_TO_MODULE.values()) {
            counts.merge(module, 1, Integer::sum);
        }
        return counts;
    }

    /** 供测试断言：白名单路径。 */
    static List<String> unmappedPolicy() {
        return UNMAPPED_POLICY;
    }
}
