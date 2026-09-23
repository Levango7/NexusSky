package io.aerofleet.cloud.surveillance;

import java.util.Objects;

/**
 * PTZ 云台控制命令 DTO。
 * <p>
 * 封装云台控制的方向、速度和持续模式，替代简单的字符串命令（如 "up"/"down"）。
 * 各厂商适配器将此 DTO 映射为各自 SDK 的 PTZ 控制调用：
 * <ul>
 *   <li>海康 ISAPI：映射为 ISAPI PTZ Control 请求</li>
 *   <li>大华 DHSDK：映射为 CLIENT_PTZControlEx 调用</li>
 *   <li>宇视 SDK：映射为 UV_PTZControl 调用</li>
 *   <li>ONVIF：映射为 ContinuousMove / Stop SOAP 请求</li>
 * </ul>
 */
public class PtzCommand {

    /** PTZ 方向枚举。 */
    public enum Direction {
        /** 上仰。 */
        UP,
        /** 下俯。 */
        DOWN,
        /** 左转。 */
        LEFT,
        /** 右转。 */
        RIGHT,
        /** 放大（zoom in）。 */
        ZOOM_IN,
        /** 缩小（zoom out）。 */
        ZOOM_OUT,
        /** 停止当前运动。 */
        STOP
    }

    /** 命令方向。 */
    public final Direction direction;
    /** 运动速度（0.0~1.0，0 表示停止，默认 0.5）。 */
    public final double speed;
    /** 是否持续运动（true=持续移动直到 STOP，false=单步移动）。 */
    public final boolean continuous;

    /** 默认速度。 */
    public static final double DEFAULT_SPEED = 0.5;

    /**
     * 创建 PTZ 命令。
     *
     * @param direction  方向（不可为 null）
     * @param speed      速度（0.0~1.0）
     * @param continuous 是否持续运动
     */
    public PtzCommand(Direction direction, double speed, boolean continuous) {
        if (direction == null) {
            throw new IllegalArgumentException("direction must not be null");
        }
        if (speed < 0.0 || speed > 1.0) {
            throw new IllegalArgumentException("speed must be in [0.0, 1.0]");
        }
        this.direction = direction;
        this.speed = speed;
        this.continuous = continuous;
    }

    /**
     * 创建默认速度的持续运动命令。
     *
     * @param direction 方向
     * @return 持续运动命令（speed=0.5, continuous=true）
     */
    public static PtzCommand continuous(Direction direction) {
        return new PtzCommand(direction, DEFAULT_SPEED, true);
    }

    /**
     * 创建 STOP 命令（停止所有运动）。
     *
     * @return STOP 命令
     */
    public static PtzCommand stop() {
        return new PtzCommand(Direction.STOP, 0.0, false);
    }

    /**
     * 从字符串命令创建 PtzCommand（兼容旧接口）。
     * <p>
     * 支持的字符串：up/down/left/right/zoomIn/zoomOut/stop
     *
     * @param cmd 字符串命令
     * @return 对应的 PtzCommand
     */
    public static PtzCommand fromString(String cmd) {
        if (cmd == null || cmd.isBlank()) {
            throw new IllegalArgumentException("cmd must not be blank");
        }
        return switch (cmd.toLowerCase()) {
            case "up" -> continuous(Direction.UP);
            case "down" -> continuous(Direction.DOWN);
            case "left" -> continuous(Direction.LEFT);
            case "right" -> continuous(Direction.RIGHT);
            case "zoomin" -> continuous(Direction.ZOOM_IN);
            case "zoomout" -> continuous(Direction.ZOOM_OUT);
            case "stop" -> stop();
            default -> throw new IllegalArgumentException("unsupported ptz command: " + cmd);
        };
    }

    /**
     * 转换为字符串命令（兼容旧接口）。
     *
     * @return 字符串命令（up/down/left/right/zoomIn/zoomOut/stop）
     */
    public String toCommandString() {
        return switch (direction) {
            case UP -> "up";
            case DOWN -> "down";
            case LEFT -> "left";
            case RIGHT -> "right";
            case ZOOM_IN -> "zoomIn";
            case ZOOM_OUT -> "zoomOut";
            case STOP -> "stop";
        };
    }

    /** 是否为 STOP 命令。 */
    public boolean isStop() {
        return direction == Direction.STOP;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof PtzCommand)) return false;
        PtzCommand that = (PtzCommand) o;
        return direction == that.direction
                && Double.compare(that.speed, speed) == 0
                && continuous == that.continuous;
    }

    @Override
    public int hashCode() {
        return Objects.hash(direction, speed, continuous);
    }

    @Override
    public String toString() {
        return "PtzCommand{direction=" + direction
                + ", speed=" + speed
                + ", continuous=" + continuous + "}";
    }
}