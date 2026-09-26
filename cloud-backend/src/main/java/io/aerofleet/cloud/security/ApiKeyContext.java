package io.aerofleet.cloud.security;

/**
 * API Key 上下文，基于 ThreadLocal 存储当前请求的 API Key 信息。
 * <p>
 * 由 {@link ApiKeyFilter} 在请求开始时设置，请求结束时清理。
 * 业务代码通过 {@link #getKeyId()}、{@link #getTenantId()}、{@link #getScopes()}
 * 获取当前 API Key 的相关信息。
 * <p>
 * 线程安全：每个线程持有独立的 ThreadLocal 副本，
 * 但必须确保在请求结束时调用 {@link #clear()} 防止线程池复用导致的上下文泄漏。
 */
public final class ApiKeyContext {

    private static final ThreadLocal<ApiKeyInfo> CONTEXT = new ThreadLocal<>();

    private ApiKeyContext() {
        // 工具类，禁止实例化
    }

    /**
     * 设置当前请求的 API Key 信息。
     *
     * @param keyId    API Key ID
     * @param tenantId 租户 ID
     * @param scopes   权限范围（JSON 数组字符串）
     */
    public static void set(String keyId, Integer tenantId, String scopes) {
        CONTEXT.set(new ApiKeyInfo(keyId, tenantId, scopes));
    }

    /**
     * 获取当前请求的 API Key ID。
     *
     * @return API Key ID，null 表示未通过 API Key 认证
     */
    public static String getKeyId() {
        ApiKeyInfo info = CONTEXT.get();
        return info == null ? null : info.keyId;
    }

    /**
     * 获取当前请求的租户 ID（从 API Key 提取）。
     *
     * @return 租户 ID，null 表示未通过 API Key 认证或无租户
     */
    public static Integer getTenantId() {
        ApiKeyInfo info = CONTEXT.get();
        return info == null ? null : info.tenantId;
    }

    /**
     * 获取当前请求的权限范围。
     *
     * @return 权限范围字符串（JSON 数组），null 表示未通过 API Key 认证
     */
    public static String getScopes() {
        ApiKeyInfo info = CONTEXT.get();
        return info == null ? null : info.scopes;
    }

    /**
     * 判断当前请求是否通过 API Key 认证。
     *
     * @return true 表示当前请求使用 API Key 认证
     */
    public static boolean isPresent() {
        return CONTEXT.get() != null;
    }

    /**
     * 清理当前线程的 API Key 上下文。
     * <p>
     * 必须在请求结束时调用（通常在 filter 的 finally 块中），
     * 防止线程池复用时上下文泄漏到后续请求。
     */
    public static void clear() {
        CONTEXT.remove();
    }

    /** 不可变的 API Key 信息持有对象。 */
    private static final class ApiKeyInfo {
        final String keyId;
        final Integer tenantId;
        final String scopes;

        ApiKeyInfo(String keyId, Integer tenantId, String scopes) {
            this.keyId = keyId;
            this.tenantId = tenantId;
            this.scopes = scopes;
        }
    }
}