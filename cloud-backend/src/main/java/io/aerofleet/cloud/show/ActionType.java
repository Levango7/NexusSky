package io.aerofleet.cloud.show;

/**
 * 动作类型。
 * <p>
 * 编队表演中单个动作的种类，由 {@link ActionSequenceService} 编排为动作序列。
 */
public enum ActionType {
    /** 起飞 — 无人机从地面起飞到指定高度。 */
    TAKEOFF,
    /** 移动到队形 — 无人机移动到初始队形中的指定位置。 */
    MOVE_TO_FORMATION,
    /** 队形变换 — 从当前队形过渡到新队形。 */
    TRANSITION_FORMATION,
    /** 悬停 — 无人机在当前位置悬停等待。 */
    HOVER,
    /** 降落 — 无人机降落到地面。 */
    LAND,
    /** 灯光开启 — 打开无人机灯光。 */
    LIGHT_ON,
    /** 灯光关闭 — 关闭无人机灯光。 */
    LIGHT_OFF,
    /** 颜色变换 — 改变无人机灯光颜色。 */
    COLOR_CHANGE
}