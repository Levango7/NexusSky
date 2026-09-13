package io.aerofleet.mavlink;

/** MAVLink 编解码与解析异常（CRC 不匹配、帧损坏、消息未知且无法校验等）。 */
public class MavlinkException extends RuntimeException {

    public MavlinkException(String message) {
        super(message);
    }

    public MavlinkException(String message, Throwable cause) {
        super(message, cause);
    }
}
