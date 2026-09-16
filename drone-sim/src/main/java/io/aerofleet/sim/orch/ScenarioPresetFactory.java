package io.aerofleet.sim.orch;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * 场景预设工厂（M9 应急任务编排，T4 场景预设）。
 * <p>
 * 按类型构造 {@link ScenarioPreset}，提供 4 种内置预设：
 * <ul>
 *   <li>type=0 地震：5km, LTE, HAPS+mesh, 8-15架, 搜救0.6/指挥0.3/测绘0.1, 损毁45%, 地形中等</li>
 *   <li>type=1 泥石流：5km近似, LoRa+LTE, LEO+HAPS+mesh, 5-10架, 指挥0.5/搜救0.3/测绘0.2, 损毁20%, 地形严重</li>
 *   <li>type=2 火灾：3km, WiFi+LTE, HAPS+mesh, 6-12架, 测绘0.5/指挥0.3/搜救0.2, 损毁10%, 地形轻微</li>
 *   <li>type=3 自定义：5km, LTE, HAPS+mesh, 5-20架, 均匀0.25, 损毁20%, 地形中等</li>
 * </ul>
 * 所有方法静态，工厂无状态。
 */
public final class ScenarioPresetFactory {

    private ScenarioPresetFactory() {
        // 工具类，禁止实例化
    }

    /**
     * 按类型获取场景预设。
     *
     * @param type 场景类型（0=地震, 1=泥石流, 2=火灾, 3=自定义）
     * @return 场景预设
     * @throws IllegalArgumentException type 越界
     */
    public static ScenarioPreset get(int type) {
        switch (type) {
            case 0:
                // 地震：5km, LTE(1), HAPS+mesh(2), 8-15架, 搜救0.6/指挥0.3/测绘0.1, 损毁45%, 地形中等(1)
                return new ScenarioPreset(
                        0, "地震", "破坏性地震，建筑倒塌严重，需大规模搜救与指挥",
                        5.0, 1, 2, 8, 15,
                        0.6, 0.3, 0.1,
                        0.45, 1);
            case 1:
                // 泥石流：2x10km 用 5km 近似, LoRa+LTE(3), LEO+HAPS+mesh(3), 5-10架, 指挥0.5/搜救0.3/测绘0.2, 损毁20%, 地形严重(2)
                return new ScenarioPreset(
                        1, "泥石流", "泥石流沿沟谷蔓延，地形变化剧烈，需远程中继",
                        5.0, 3, 3, 5, 10,
                        0.3, 0.5, 0.2,
                        0.20, 2);
            case 2:
                // 火灾：3km, WiFi+LTE(2), HAPS+mesh(2), 6-12架, 测绘0.5/指挥0.3/搜救0.2, 损毁10%, 地形轻微(0)
                return new ScenarioPreset(
                        2, "火灾", "大面积火灾，需实时测绘火线与指挥扑救",
                        3.0, 2, 2, 6, 12,
                        0.2, 0.3, 0.5,
                        0.10, 0);
            case 3:
                // 自定义：5km, LTE(1), HAPS+mesh(2), 5-20架, 均匀0.25, 损毁20%, 地形中等(1)
                return new ScenarioPreset(
                        3, "自定义", "自定义场景，参数可按需调整",
                        5.0, 1, 2, 5, 20,
                        0.25, 0.25, 0.25,
                        0.20, 1);
            default:
                throw new IllegalArgumentException("Unknown scenario type: " + type
                        + ", expected 0=地震, 1=泥石流, 2=火灾, 3=自定义");
        }
    }

    /**
     * 返回所有 4 种内置预设。
     *
     * @return 不可变预设列表（按 type 0~3 顺序）
     */
    public static List<ScenarioPreset> all() {
        List<ScenarioPreset> list = new ArrayList<>(4);
        list.add(get(0));
        list.add(get(1));
        list.add(get(2));
        list.add(get(3));
        return Collections.unmodifiableList(list);
    }
}