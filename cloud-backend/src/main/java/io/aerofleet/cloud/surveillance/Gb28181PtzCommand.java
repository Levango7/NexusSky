package io.aerofleet.cloud.surveillance;

/**
 * GB28181 PTZ 控制指令码（E5，国标 §A.3 控制 PTZ 指令格式）。
 * <p>
 * 4 字节：{@code A5 0F 01 <动作组合位>}——动作位可按位 OR 组合
 * （上=0x08/下=0x04/左=0x20/右=0x10/放大=0x80/缩小=0x40）。
 * 输出真实国标字节，可逐字节断言。
 */
public final class Gb28181PtzCommand {

    public static final String UP = "up";
    public static final String DOWN = "down";
    public static final String LEFT = "left";
    public static final String RIGHT = "right";
    public static final String ZOOM_IN = "zoom_in";
    public static final String ZOOM_OUT = "zoom_out";

    private static final java.util.Map<String, Integer> ACTIONS = java.util.Map.of(
            UP, 0x08, DOWN, 0x04, LEFT, 0x20, RIGHT, 0x10, ZOOM_IN, 0x80, ZOOM_OUT, 0x40);

    private Gb28181PtzCommand() {
    }

    /**
     * 动作名 → 国标指令字节。
     *
     * @throws IllegalArgumentException 未知动作名（不静默忽略——PTZ 是外部可见行为）
     */
    public static byte[] encode(String action) {
        Integer bits = ACTIONS.get(action == null ? "" : action);
        if (bits == null) {
            throw new IllegalArgumentException(
                    "unknown PTZ action: " + action + " (supported: " + ACTIONS.keySet() + ")");
        }
        return new byte[]{(byte) 0xA5, 0x0F, 0x01, bits.byteValue()};
    }

    /** hex 字符串（信令下发用：A50F0108 形态）。 */
    public static String encodeHex(String action) {
        byte[] b = encode(action);
        StringBuilder sb = new StringBuilder(8);
        for (byte x : b) {
            sb.append(String.format("%02X", x));
        }
        return sb.toString();
    }
}
