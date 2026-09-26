package io.aerofleet.cloud.scenario;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 预设场景模板工厂（P0-2）。
 * <p>
 * 提供 6 种灾害 × 3 种规模 = 18 个内置预设模板，覆盖典型应急场景：
 * <ul>
 *   <li>火灾 SMALL: 2 架, 1km, 侦察优先</li>
 *   <li>火灾 LARGE: 6 架, 3km, 侦察+中继+执行</li>
 *   <li>洪水 SMALL: 3 架, 2km, 侦察+搜救</li>
 *   <li>地震 LARGE: 12 架, 5km, 全角色协同</li>
 *   <li>泥石流 MEDIUM: 6 架, 3km, 侦察+中继</li>
 *   <li>化工厂泄漏 LARGE: 8 架, 2km, 侦察+监测（悬停时间长）</li>
 *   <li>群体性事件 MEDIUM: 4 架, 1km, 侦察+中继</li>
 * </ul>
 */
public final class ScenarioPresetFactory {

    private static final List<ScenarioTemplate> PRESETS = buildPresets();

    private ScenarioPresetFactory() {
    }

    /**
     * 获取全部 18 个预设模板（不可变列表）。
     *
     * @return 18 个预设模板的不可变视图
     */
    public static List<ScenarioTemplate> all() {
        return Collections.unmodifiableList(PRESETS);
    }

    /**
     * 按灾害类型筛选预设模板。
     *
     * @param disasterType 灾害类型
     * @return 匹配的预设模板列表（每个灾害类型固定 3 个：SMALL/MEDIUM/LARGE）
     */
    public static List<ScenarioTemplate> byDisasterType(DisasterType disasterType) {
        List<ScenarioTemplate> result = new ArrayList<>();
        for (ScenarioTemplate t : PRESETS) {
            if (t.getDisasterType() == disasterType) {
                result.add(t);
            }
        }
        return result;
    }

    /**
     * 按 ID 查找预设模板。
     *
     * @param id 模板 ID
     * @return 匹配的模板；不存在返回 null
     */
    public static ScenarioTemplate byId(String id) {
        for (ScenarioTemplate t : PRESETS) {
            if (t.getId().equals(id)) {
                return t;
            }
        }
        return null;
    }

    /** 预设模板总数（6 灾害 × 3 规模）。 */
    public static int size() {
        return PRESETS.size();
    }

    private static List<ScenarioTemplate> buildPresets() {
        List<ScenarioTemplate> list = new ArrayList<>();
        Map<String, ScenarioTemplate> registry = new LinkedHashMap<>();

        // === 火灾 FIRE ===
        put(registry, list, "preset-FIRE-SMALL", "火灾-小规模", DisasterType.FIRE,
                ScenarioTemplate.SeverityLevel.SMALL,
                "小范围火情侦察：2 架无人机执行火情侦察与态势评估。",
                2, 1.0, 80, 30,
                ScenarioTemplate.CollaborationStrategy.RECON_ONLY,
                ScenarioTemplate.CommunicationMode.MESH);
        put(registry, list, "preset-FIRE-MEDIUM", "火灾-中规模", DisasterType.FIRE,
                ScenarioTemplate.SeverityLevel.MEDIUM,
                "中等范围火灾：4 架无人机执行侦察与中继通信。",
                4, 2.0, 100, 60,
                ScenarioTemplate.CollaborationStrategy.RECON_RELAY,
                ScenarioTemplate.CommunicationMode.MESH);
        put(registry, list, "preset-FIRE-LARGE", "火灾-大规模", DisasterType.FIRE,
                ScenarioTemplate.SeverityLevel.LARGE,
                "大范围火灾协同：6 架无人机执行侦察+中继+灭火指挥。",
                6, 3.0, 120, 120,
                ScenarioTemplate.CollaborationStrategy.RECON_RELAY_EXEC,
                ScenarioTemplate.CommunicationMode.BOTH);

        // === 洪水 FLOOD ===
        put(registry, list, "preset-FLOOD-SMALL", "洪水-小规模", DisasterType.FLOOD,
                ScenarioTemplate.SeverityLevel.SMALL,
                "小范围水域搜救：3 架无人机执行水面侦察与搜救定位。",
                3, 2.0, 60, 45,
                ScenarioTemplate.CollaborationStrategy.RECON_ONLY,
                ScenarioTemplate.CommunicationMode.MESH);
        put(registry, list, "preset-FLOOD-MEDIUM", "洪水-中规模", DisasterType.FLOOD,
                ScenarioTemplate.SeverityLevel.MEDIUM,
                "中等范围洪涝：6 架无人机执行侦察+中继+物资投递引导。",
                6, 3.0, 80, 90,
                ScenarioTemplate.CollaborationStrategy.RECON_RELAY,
                ScenarioTemplate.CommunicationMode.BOTH);
        put(registry, list, "preset-FLOOD-LARGE", "洪水-大规模", DisasterType.FLOOD,
                ScenarioTemplate.SeverityLevel.LARGE,
                "大范围洪涝灾害：10 架无人机执行全角色协同搜救。",
                10, 5.0, 100, 180,
                ScenarioTemplate.CollaborationStrategy.RECON_RELAY_EXEC,
                ScenarioTemplate.CommunicationMode.BOTH);

        // === 地震 EARTHQUAKE ===
        put(registry, list, "preset-EARTHQUAKE-SMALL", "地震-小规模", DisasterType.EARTHQUAKE,
                ScenarioTemplate.SeverityLevel.SMALL,
                "小范围地震救援：4 架无人机执行侦察+中继通信。",
                4, 2.0, 100, 60,
                ScenarioTemplate.CollaborationStrategy.RECON_RELAY,
                ScenarioTemplate.CommunicationMode.MESH);
        put(registry, list, "preset-EARTHQUAKE-MEDIUM", "地震-中规模", DisasterType.EARTHQUAKE,
                ScenarioTemplate.SeverityLevel.MEDIUM,
                "中等范围地震：8 架无人机执行侦察+中继+搜救执行。",
                8, 4.0, 120, 120,
                ScenarioTemplate.CollaborationStrategy.RECON_RELAY_EXEC,
                ScenarioTemplate.CommunicationMode.BOTH);
        put(registry, list, "preset-EARTHQUAKE-LARGE", "地震-大规模", DisasterType.EARTHQUAKE,
                ScenarioTemplate.SeverityLevel.LARGE,
                "大范围地震灾害：12 架无人机执行全角色协同搜救与灾损评估。",
                12, 5.0, 150, 240,
                ScenarioTemplate.CollaborationStrategy.RECON_RELAY_EXEC,
                ScenarioTemplate.CommunicationMode.BOTH);

        // === 泥石流 MUDSLIDE ===
        put(registry, list, "preset-MUDSLIDE-SMALL", "泥石流-小规模", DisasterType.MUDSLIDE,
                ScenarioTemplate.SeverityLevel.SMALL,
                "小范围泥石流：3 架无人机执行地质灾害侦察。",
                3, 1.0, 80, 30,
                ScenarioTemplate.CollaborationStrategy.RECON_ONLY,
                ScenarioTemplate.CommunicationMode.MESH);
        put(registry, list, "preset-MUDSLIDE-MEDIUM", "泥石流-中规模", DisasterType.MUDSLIDE,
                ScenarioTemplate.SeverityLevel.MEDIUM,
                "中等范围泥石流：6 架无人机执行侦察+中继+警戒区监测。",
                6, 3.0, 100, 90,
                ScenarioTemplate.CollaborationStrategy.RECON_RELAY,
                ScenarioTemplate.CommunicationMode.MESH);
        put(registry, list, "preset-MUDSLIDE-LARGE", "泥石流-大规模", DisasterType.MUDSLIDE,
                ScenarioTemplate.SeverityLevel.LARGE,
                "大范围泥石流灾害：8 架无人机执行全角色协同。",
                8, 4.0, 120, 150,
                ScenarioTemplate.CollaborationStrategy.RECON_RELAY_EXEC,
                ScenarioTemplate.CommunicationMode.BOTH);

        // === 化工厂泄漏 CHEMICAL_LEAK ===
        put(registry, list, "preset-CHEMICAL_LEAK-SMALL", "化工厂泄漏-小规模", DisasterType.CHEMICAL_LEAK,
                ScenarioTemplate.SeverityLevel.SMALL,
                "小范围泄漏监测：3 架无人机长时悬停执行化学监测。",
                3, 1.0, 50, 120,
                ScenarioTemplate.CollaborationStrategy.RECON_ONLY,
                ScenarioTemplate.CommunicationMode.MESH);
        put(registry, list, "preset-CHEMICAL_LEAK-MEDIUM", "化工厂泄漏-中规模", DisasterType.CHEMICAL_LEAK,
                ScenarioTemplate.SeverityLevel.MEDIUM,
                "中等范围泄漏：5 架无人机执行侦察+中继+毒气扩散建模。",
                5, 2.0, 60, 180,
                ScenarioTemplate.CollaborationStrategy.RECON_RELAY,
                ScenarioTemplate.CommunicationMode.BOTH);
        put(registry, list, "preset-CHEMICAL_LEAK-LARGE", "化工厂泄漏-大规模", DisasterType.CHEMICAL_LEAK,
                ScenarioTemplate.SeverityLevel.LARGE,
                "大范围化工厂泄漏：8 架无人机长时悬停执行侦察+监测+中继。",
                8, 2.0, 80, 300,
                ScenarioTemplate.CollaborationStrategy.RECON_RELAY_EXEC,
                ScenarioTemplate.CommunicationMode.BOTH);

        // === 群体性事件 MASS_EVENT ===
        put(registry, list, "preset-MASS_EVENT-SMALL", "群体性事件-小规模", DisasterType.MASS_EVENT,
                ScenarioTemplate.SeverityLevel.SMALL,
                "小型集会监测：2 架无人机执行人群密度侦察。",
                2, 0.5, 60, 30,
                ScenarioTemplate.CollaborationStrategy.RECON_ONLY,
                ScenarioTemplate.CommunicationMode.MESH);
        put(registry, list, "preset-MASS_EVENT-MEDIUM", "群体性事件-中规模", DisasterType.MASS_EVENT,
                ScenarioTemplate.SeverityLevel.MEDIUM,
                "中等规模集会：4 架无人机执行侦察+中继+秩序巡查。",
                4, 1.0, 80, 60,
                ScenarioTemplate.CollaborationStrategy.RECON_RELAY,
                ScenarioTemplate.CommunicationMode.MESH);
        put(registry, list, "preset-MASS_EVENT-LARGE", "群体性事件-大规模", DisasterType.MASS_EVENT,
                ScenarioTemplate.SeverityLevel.LARGE,
                "大规模集会：8 架无人机执行全角色协同监测。",
                8, 2.0, 100, 120,
                ScenarioTemplate.CollaborationStrategy.RECON_RELAY_EXEC,
                ScenarioTemplate.CommunicationMode.BOTH);

        return Collections.unmodifiableList(list);
    }

    private static void put(Map<String, ScenarioTemplate> registry, List<ScenarioTemplate> list,
                            String id, String name, DisasterType disasterType,
                            ScenarioTemplate.SeverityLevel severity, String description,
                            int droneCount, double radiusKm, double hoverAltM, int durationMin,
                            ScenarioTemplate.CollaborationStrategy strategy,
                            ScenarioTemplate.CommunicationMode commMode) {
        ScenarioTemplate t = new ScenarioTemplate(id, name, disasterType, severity,
                description, droneCount, radiusKm, hoverAltM, durationMin, strategy, commMode);
        registry.put(id, t);
        list.add(t);
    }
}