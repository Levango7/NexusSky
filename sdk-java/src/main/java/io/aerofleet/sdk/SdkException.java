package io.aerofleet.sdk;

/**
 * SDK 异常基类，封装所有 API 调用中可能出现的错误。
 */
public class SdkException extends RuntimeException {

    private final int statusCode;

    /**
     * 构造一个不带 HTTP 状态码的 SDK 异常。
     *
     * @param message 错误描述
     */
    public SdkException(String message) {
        super(message);
        this.statusCode = -1;
    }

    /**
     * 构造一个带 HTTP 状态码的 SDK 异常。
     *
     * @param message    错误描述
     * @param statusCode HTTP 响应状态码（-1 表示非 HTTP 错误）
     */
    public SdkException(String message, int statusCode) {
        super(message);
        this.statusCode = statusCode;
    }

    /**
     * 构造一个带原因链的 SDK 异常。
     *
     * @param message 错误描述
     * @param cause   底层异常
     */
    public SdkException(String message, Throwable cause) {
        super(message, cause);
        this.statusCode = -1;
    }

    /**
     * 构造一个带 HTTP 状态码和原因链的 SDK 异常。
     *
     * @param message    错误描述
     * @param statusCode HTTP 响应状态码
     * @param cause      底层异常
     */
    public SdkException(String message, int statusCode, Throwable cause) {
        super(message, cause);
        this.statusCode = statusCode;
    }

    /**
     * 返回 HTTP 响应状态码，-1 表示非 HTTP 错误（如网络中断、JSON 解析失败等）。
     *
     * @return HTTP 状态码或 -1
     */
    public int getStatusCode() {
        return statusCode;
    }
}