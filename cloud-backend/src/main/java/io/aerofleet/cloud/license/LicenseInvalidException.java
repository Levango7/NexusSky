package io.aerofleet.cloud.license;

/**
 * License 无效异常。
 * <p>
 * 当 License 签名验证失败、格式不合法或关键信息缺失时抛出此异常。
 *
 * @author AeroFleet Cloud Team
 */
public class LicenseInvalidException extends RuntimeException {

    public LicenseInvalidException(String message) {
        super(message);
    }

    public LicenseInvalidException(String message, Throwable cause) {
        super(message, cause);
    }
}