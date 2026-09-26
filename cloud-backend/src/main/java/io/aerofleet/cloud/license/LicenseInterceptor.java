package io.aerofleet.cloud.license;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * License 校验拦截器。
 * <p>
 * 在每个 HTTP 请求到达 Controller 之前校验 License 有效性。
 * <ul>
 *   <li>{@code aerofleet.license.enabled=false}（默认）时跳过校验，不破坏现有测试；</li>
 *   <li>{@code aerofleet.license.enabled=true} 时校验 License，无效则返回 403 + JSON 错误。</li>
 * </ul>
 * <p>
 * 模块校验：根据请求路径映射到对应模块，检查该模块是否在 License 授权范围内。
 * 模块映射：
 * <ul>
 *   <li>/api/v1/drones → core</li>
 *   <li>/api/v1/scheduling → fleet</li>
 *   <li>/api/v1/emergency → emergency</li>
 *   <li>/api/v1/mesh → network</li>
 *   <li>/api/v1/twin → advanced</li>
 * </ul>
 * dev 模式（{@code aerofleet.security.dev-mode=true}）跳过模块校验。
 *
 * @author AeroFleet Cloud Team
 */
@Component
public class LicenseInterceptor implements HandlerInterceptor {

    private static final Logger log = LoggerFactory.getLogger(LicenseInterceptor.class);

    private final LicenseService licenseService;
    private final boolean enabled;
    private final boolean devMode;
    private final ObjectMapper objectMapper;

    public LicenseInterceptor(LicenseService licenseService,
                              @Value("${aerofleet.license.enabled:false}") boolean enabled,
                              @Value("${aerofleet.security.dev-mode:false}") boolean devMode,
                              ObjectMapper objectMapper) {
        this.licenseService = licenseService;
        this.enabled = enabled;
        this.devMode = devMode;
        this.objectMapper = objectMapper;
    }

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) throws Exception {
        if (!enabled) {
            return true;
        }

        LicenseInfo info = licenseService.getLicenseInfo();
        if (!licenseService.validateLicense(info)) {
            log.warn("License 校验失败，拒绝请求: {} {}", request.getMethod(), request.getRequestURI());
            response.setStatus(HttpServletResponse.SC_FORBIDDEN);
            response.setContentType("application/json;charset=UTF-8");
            Map<String, Object> error = new LinkedHashMap<>();
            error.put("code", 403);
            error.put("error", "License Invalid");
            error.put("message", "License 已过期或未激活，请联系供应商");
            response.getWriter().write(objectMapper.writeValueAsString(error));
            return false;
        }

        // 模块校验（dev 模式跳过）
        if (!devMode) {
            String requiredModule = mapPathToModule(request.getRequestURI());
            if (requiredModule != null && !info.hasModule(requiredModule)) {
                log.warn("模块授权校验失败: 请求路径={}, 需要模块={}, 已授权模块={}, tenant={}",
                        request.getRequestURI(), requiredModule, info.getModules(), info.getTenantId());
                response.setStatus(HttpServletResponse.SC_FORBIDDEN);
                response.setContentType("application/json;charset=UTF-8");
                Map<String, Object> error = new LinkedHashMap<>();
                error.put("code", 403);
                error.put("error", "Module Not Licensed");
                error.put("message", "当前 License 未授权模块: " + requiredModule);
                response.getWriter().write(objectMapper.writeValueAsString(error));
                return false;
            }
        }

        return true;
    }

    /**
     * 将 API 请求路径映射到对应的模块名。
     * <p>
     * 模块映射规则：
     * <ul>
     *   <li>/api/v1/drones → core</li>
     *   <li>/api/v1/scheduling → fleet</li>
     *   <li>/api/v1/emergency → emergency</li>
     *   <li>/api/v1/mesh → network</li>
     *   <li>/api/v1/twin → advanced</li>
     * </ul>
     * 其他路径返回 null（不需要模块授权）。
     *
     * @param requestURI 请求 URI
     * @return 模块名，或 null（不需要模块授权）
     */
    private String mapPathToModule(String requestURI) {
        if (requestURI == null) {
            return null;
        }
        if (requestURI.startsWith("/api/v1/drones")) {
            return "core";
        }
        if (requestURI.startsWith("/api/v1/scheduling")) {
            return "fleet";
        }
        if (requestURI.startsWith("/api/v1/emergency")) {
            return "emergency";
        }
        if (requestURI.startsWith("/api/v1/mesh")) {
            return "network";
        }
        if (requestURI.startsWith("/api/v1/twin")) {
            return "advanced";
        }
        return null;
    }
}
