package io.aerofleet.cloud.surveillance.onvif;

/**
 * live ONVIF 调用失败。
 *
 * <p>携带 HTTP 状态码（{@link #httpStatus()}，非 HTTP 失败时为 0），
 * 便于上层区分「设备回了 401/500」与「连不上/解析失败」。
 *
 * <p>消息里<b>不含</b>凭据：{@code OnvifSoapClient} 只拼接服务 URL 与设备返回的
 * 错误文本，从不把 WS-Security 里的 username/password/digest 写进异常。
 */
public class OnvifException extends RuntimeException {

    private final int httpStatus;

    public OnvifException(String message) {
        this(message, 0, null);
    }

    public OnvifException(String message, Throwable cause) {
        this(message, 0, cause);
    }

    public OnvifException(String message, int httpStatus, Throwable cause) {
        super(message, cause);
        this.httpStatus = httpStatus;
    }

    /** HTTP 状态码；非 HTTP 层失败为 0。 */
    public int httpStatus() {
        return httpStatus;
    }
}
