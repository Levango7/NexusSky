package io.aerofleet.cloud.surveillance;

import java.util.Objects;

/**
 * 布控球快速部署结果模型。
 * <p>
 * 描述单个设备在 {@link RapidDeployService#scanAndDeploy} 流程中的部署结局，
 * 供 REST 层 {@code /api/surveillance/rapid-deploy} 端点批量返回。
 * <ul>
 *   <li>{@link Status#SUCCESS} — 设备发现并成功注册到 Registry，{@link #rtspUrl} 非空</li>
 *   <li>{@link Status#SKIPPED} — 设备已注册（同 IP 已存在）或仅扫描不注册，不重复入表</li>
 *   <li>{@link Status#FAILED} — 发现成功但获取能力/RTSP 流失败，未注册到 Registry</li>
 * </ul>
 * <p>
 * 该类为不可变值对象，字段全部 final，便于在并发部署流程中安全传递。
 */
public class DeployResult {

    /** 部署状态枚举。 */
    public enum Status {
        /** 成功：设备已发现并注册到 Registry，RTSP 流已获取。 */
        SUCCESS,
        /** 跳过：设备已注册（同 IP 已存在）或仅扫描不注册。 */
        SKIPPED,
        /** 失败：发现成功但获取能力/RTSP 流失败，未注册。 */
        FAILED
    }

    /** 设备唯一标识（SUCCESS 时为生成的新 ID，SKIPPED 时为已注册设备 ID，FAILED 时为发现时的临时 ID）。 */
    private final String deviceId;
    /** 设备 IP 地址。 */
    private final String ip;
    /** 设备厂商名称（如 "HIKVISION"、"DAHUA"、"UNIVIEW"）。 */
    private final String vendor;
    /** 部署状态。 */
    private final Status status;
    /** 原因说明（如 "already registered"、"discovered and registered"、"connection failed"）。 */
    private final String message;
    /** RTSP 流 URL（仅 SUCCESS 时非空）。 */
    private final String rtspUrl;

    /**
     * 构造部署结果。
     *
     * @param deviceId 设备 ID
     * @param ip       设备 IP
     * @param vendor   设备厂商名称
     * @param status   部署状态
     * @param message  原因说明
     * @param rtspUrl  RTSP 流 URL（可为 null）
     */
    public DeployResult(String deviceId, String ip, String vendor,
                        Status status, String message, String rtspUrl) {
        this.deviceId = deviceId;
        this.ip = ip;
        this.vendor = vendor;
        this.status = status;
        this.message = message;
        this.rtspUrl = rtspUrl;
    }

    /** 创建 SUCCESS 结果的便捷工厂方法。 */
    public static DeployResult success(String deviceId, String ip, String vendor,
                                       String message, String rtspUrl) {
        return new DeployResult(deviceId, ip, vendor, Status.SUCCESS, message, rtspUrl);
    }

    /** 创建 SKIPPED 结果的便捷工厂方法。 */
    public static DeployResult skipped(String deviceId, String ip, String vendor, String message) {
        return new DeployResult(deviceId, ip, vendor, Status.SKIPPED, message, null);
    }

    /** 创建 FAILED 结果的便捷工厂方法。 */
    public static DeployResult failed(String deviceId, String ip, String vendor, String message) {
        return new DeployResult(deviceId, ip, vendor, Status.FAILED, message, null);
    }

    public String getDeviceId() {
        return deviceId;
    }

    public String getIp() {
        return ip;
    }

    public String getVendor() {
        return vendor;
    }

    public Status getStatus() {
        return status;
    }

    public String getMessage() {
        return message;
    }

    public String getRtspUrl() {
        return rtspUrl;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof DeployResult)) return false;
        DeployResult that = (DeployResult) o;
        return Objects.equals(deviceId, that.deviceId)
                && Objects.equals(ip, that.ip)
                && Objects.equals(vendor, that.vendor)
                && status == that.status
                && Objects.equals(message, that.message)
                && Objects.equals(rtspUrl, that.rtspUrl);
    }

    @Override
    public int hashCode() {
        return Objects.hash(deviceId, ip, vendor, status, message, rtspUrl);
    }

    @Override
    public String toString() {
        return "DeployResult{deviceId=" + deviceId
                + ", ip=" + ip
                + ", vendor=" + vendor
                + ", status=" + status
                + ", message=" + message
                + ", rtspUrl=" + rtspUrl + "}";
    }
}