package io.aerofleet.cloud.tenant;

/**
 * 线程本地租户上下文。
 * <p>
 * 每次请求由 {@link TenantInterceptor} 从 JWT/Header 提取 tenantId 并设置到此处，
 * Service 层通过 {@link #getTenantId()} 获取当前租户，实现数据隔离。
 * 请求结束后由拦截器 {@link #clear()} 清理，防止线程池复用导致租户串号。
 *
 * @author AeroFleet Cloud Team
 */
public final class TenantContext {

    private static final ThreadLocal<String> TENANT_ID = new ThreadLocal<>();

    private TenantContext() {
    }

    public static void setTenantId(String tenantId) {
        TENANT_ID.set(tenantId);
    }

    public static String getTenantId() {
        return TENANT_ID.get();
    }

    public static void clear() {
        TENANT_ID.remove();
    }
}