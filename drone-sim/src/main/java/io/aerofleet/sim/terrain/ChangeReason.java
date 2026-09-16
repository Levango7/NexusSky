package io.aerofleet.sim.terrain;

/**
 * 变更原因枚举（FR-27, §7.4）。
 * <p>
 * 取值：
 * <pre>
 * EARTHQUAKE=0 地震
 * LANDSLIDE=1  泥石流
 * FIRE=2       火灾
 * </pre>
 */
public enum ChangeReason {
    EARTHQUAKE(0),
    LANDSLIDE(1),
    FIRE(2);

    public final int code;

    ChangeReason(int code) {
        this.code = code;
    }

    public static ChangeReason fromCode(int code) {
        for (ChangeReason r : values()) {
            if (r.code == code) {
                return r;
            }
        }
        return EARTHQUAKE;  // 默认
    }
}
