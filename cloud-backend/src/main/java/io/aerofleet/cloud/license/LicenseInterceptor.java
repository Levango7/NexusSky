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
 *
 * @author AeroFleet Cloud Team
 */
@Component
public class LicenseInterceptor implements HandlerInterceptor {

    private static final Logger log = LoggerFactory.getLogger(LicenseInterceptor.class);

    private final LicenseService licenseService;
    private final boolean enabled;
    private final ObjectMapper objectMapper;

    public LicenseInterceptor(LicenseService licenseService,
                              @Value("${aerofleet.license.enabled:false}") boolean enabled) {
        this.licenseService = licenseService;
        this.enabled = enabled;
        this.objectMapper = new ObjectMapper();
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
        return true;
    }
}