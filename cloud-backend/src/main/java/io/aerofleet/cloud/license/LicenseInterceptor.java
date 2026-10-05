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
 * 模块校验：请求路径 → 模块的映射与白名单<b>全部</b>收敛在 {@link LicenseModuleMap}
 * （全仓唯一真相源）。本类只负责「解析 → 判定 → 拒绝」的管道，不再内联路径表。
 * <p>
 * <b>fail-closed（2026-10-05 修正）</b>：此前本类内联的映射表只覆盖 5 个路径前缀，
 * 实测只约束 35/343 = 10.2% 的端点，其余 89.8% 只校验有效期而不校验模块授权——
 * 买了基础版的客户可访问全部未付费模块，三档定价技术上不可执行。
 * 现改为：未在 {@link LicenseModuleMap} 登记的路径一律按未授权处理并 403。
 * <p>
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
            String requiredModule = LicenseModuleMap.resolve(request.getRequestURI());
            // null = 白名单路径（认证 / License 自管理 / 健康 / OpenAPI），显式放行
            if (requiredModule != null) {
                // 未归类前缀（MODULE_UNCLASSIFIED）不在 ALL_MODULES 中，
                // hasModule 恒 false → 必然 403。这是「新增控制器忘记登记模块」
                // 被暴露而非静默变免费功能的机制。
                if (LicenseModuleMap.MODULE_UNCLASSIFIED.equals(requiredModule)) {
                    log.error("模块映射缺失，按未授权拒绝（fail-closed）: {} {} —— "
                                    + "请将此前缀登记进 LicenseModuleMap.PREFIX_TO_MODULE",
                            request.getMethod(), request.getRequestURI());
                    writeForbidden(response, "Module Not Licensed",
                            "接口未登记模块归属，已按未授权拒绝（请联系供应商）");
                    return false;
                }
                if (!info.hasModule(requiredModule)) {
                    log.warn("模块授权校验失败: 请求路径={}, 需要模块={}, 已授权模块={}, tenant={}",
                            request.getRequestURI(), requiredModule, info.getModules(), info.getTenantId());
                    writeForbidden(response, "Module Not Licensed",
                            "当前 License 未授权模块: " + requiredModule);
                    return false;
                }
            }
        }

        return true;
    }

    /** 写出 403 + JSON 错误体（有效期失败与模块失败共用，避免两处各写一份格式）。 */
    private void writeForbidden(HttpServletResponse response, String error, String message)
            throws Exception {
        response.setStatus(HttpServletResponse.SC_FORBIDDEN);
        response.setContentType("application/json;charset=UTF-8");
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("code", 403);
        body.put("error", error);
        body.put("message", message);
        response.getWriter().write(objectMapper.writeValueAsString(body));
    }
}
