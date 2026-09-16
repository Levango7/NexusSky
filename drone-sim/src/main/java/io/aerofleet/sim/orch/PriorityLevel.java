package io.aerofleet.sim.orch;

/**
 * 应急任务优先级枚举（M9 应急任务编排，T4 优先级调度）。
 * <p>
 * 定义 4 级优先级，code 越小优先级越高，调度时从最高优先级队列开始扫描：
 * <pre>
 * 1. SEARCH_RESCUE  搜救通讯   最高优先级（生命救援）
 * 2. COMMAND        指挥通讯   次高优先级（应急指挥链）
 * 3. MAPPING        灾区测绘   中优先级（灾情评估）
 * 4. ROUTINE        常规巡检   最低优先级（日常任务）
 * </pre>
 * 每个枚举携带 code（序号）与 label（中文标签）。
 */
public enum PriorityLevel {

    /** 优先级 1：搜救通讯（最高）。 */
    SEARCH_RESCUE(1, "搜救通讯"),
    /** 优先级 2：指挥通讯。 */
    COMMAND(2, "指挥通讯"),
    /** 优先级 3：灾区测绘。 */
    MAPPING(3, "灾区测绘"),
    /** 优先级 4：常规巡检（最低）。 */
    ROUTINE(4, "常规巡检");

    /** 优先级序号（1~4，越小越高）。 */
    public final int code;
    /** 优先级中文标签。 */
    public final String label;

    PriorityLevel(int code, String label) {
        this.code = code;
        this.label = label;
    }

    /**
     * 按 code 反查优先级枚举。
     *
     * @param code 优先级序号（1~4）
     * @return 对应枚举
     * @throws IllegalArgumentException code 越界
     */
    public static PriorityLevel fromCode(int code) {
        for (PriorityLevel p : values()) {
            if (p.code == code) {
                return p;
            }
        }
        throw new IllegalArgumentException("Unknown priority code: " + code);
    }
}