package io.aerofleet.sim.celltower;

/**
 * 终端接入结果值类（M6 移动基站载荷抽象，FR-TERM-02）。
 * <p>
 * 携带接入成功/失败标志、错误码、负载均衡建议目标 sysid、人类可读消息。不可变。
 */
public final class AccessResult {

    /** 接入成功标志。 */
    public final boolean success;
    /** 错误码（失败时有效），可能值见常量定义。 */
    public final String errorCode;
    /** 负载均衡建议目标 sysid（失败且可引导时有效），0 表示无建议。 */
    public final int redirectSysid;
    /** 人类可读消息。 */
    public final String message;

    // ===== 错误码常量（FR-TERM-02 / FR-CAP-02 / FR-CT-06）=====
    public static final String OK = "OK";
    public static final String ALREADY_REGISTERED = "ALREADY_REGISTERED";
    public static final String OUT_OF_COVERAGE = "OUT_OF_COVERAGE";
    public static final String SIGNAL_TOO_WEAK = "SIGNAL_TOO_WEAK";
    public static final String CELL_CAPACITY_FULL = "CELL_CAPACITY_FULL";
    public static final String CELL_TYPE_INVALID = "CELL_TYPE_INVALID";
    public static final String CELL_PARAM_OUT_OF_RANGE = "CELL_PARAM_OUT_OF_RANGE";
    public static final String TERMINAL_GPS_INVALID = "TERMINAL_GPS_INVALID";
    public static final String TERMINAL_TYPE_UNSUPPORTED = "TERMINAL_TYPE_UNSUPPORTED";
    public static final String REGISTRY_FULL = "REGISTRY_FULL";

    private AccessResult(boolean success, String errorCode, int redirectSysid, String message) {
        this.success = success;
        this.errorCode = errorCode;
        this.redirectSysid = redirectSysid;
        this.message = message;
    }

    /** 接入成功。 */
    public static AccessResult ok() {
        return new AccessResult(true, OK, 0, "registered");
    }

    /** 接入失败（无引导）。 */
    public static AccessResult fail(String errorCode, String message) {
        return new AccessResult(false, errorCode, 0, message);
    }

    /** 接入失败 + 负载均衡引导至 redirectSysid。 */
    public static AccessResult redirect(String errorCode, int redirectSysid, String message) {
        return new AccessResult(false, errorCode, redirectSysid, message);
    }

    /** 已注册（幂等返回原会话）。 */
    public static AccessResult alreadyRegistered() {
        return new AccessResult(true, ALREADY_REGISTERED, 0, "already registered");
    }

    @Override
    public String toString() {
        return "AccessResult{success=" + success
                + ", errorCode='" + errorCode + "'"
                + (redirectSysid > 0 ? ", redirect=" + redirectSysid : "")
                + ", message='" + message + "'}";
    }
}