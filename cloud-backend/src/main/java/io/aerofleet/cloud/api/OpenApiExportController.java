package io.aerofleet.cloud.api;

import io.swagger.v3.core.util.Json;
import io.swagger.v3.core.util.Yaml;
import io.swagger.v3.oas.models.OpenAPI;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springdoc.core.service.OpenAPIService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Locale;

/**
 * OpenAPI Spec 导出端点。
 * <p>
 * 提供经过认证的 OpenAPI spec 导出，供客户生成 SDK 或集成工具链。
 * 与 SpringDoc 内置的 {@code /v3/api-docs} 不同，此端点：
 * <ul>
 *   <li>在所有环境（包括 prod）均可访问，不受 {@code springdoc.api-docs.enabled} 影响</li>
 *   <li>需要 JWT 或 API Key 认证（防止未授权 spec 泄露）</li>
 *   <li>提供 JSON 和 YAML 两种格式</li>
 * </ul>
 * <p>
 * 端点：
 * <ul>
 *   <li>GET /api/v1/openapi/json — 导出 OpenAPI JSON</li>
 *   <li>GET /api/v1/openapi/yaml — 导出 OpenAPI YAML</li>
 * </ul>
 */
@RestController
@RequestMapping("/api/v1/openapi")
public class OpenApiExportController {

    private static final Logger log = LoggerFactory.getLogger(OpenApiExportController.class);

    @Autowired(required = false)
    private OpenAPIService openAPIService;

    /**
     * 导出 OpenAPI JSON spec。
     * <p>
     * GET /api/v1/openapi/json → application/json
     * <p>
     * 需要认证（JWT Bearer Token 或 X-API-Key Header）。
     *
     * @return OpenAPI JSON 字符串
     */
    @GetMapping(value = "/json", produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<String> exportJson() {
        if (openAPIService == null) {
            log.warn("OpenAPIService 不可用（springdoc.api-docs.enabled=false）");
            return ResponseEntity.status(503).body("OpenAPI spec 导出未启用");
        }
        OpenAPI openAPI = openAPIService.build(Locale.getDefault());
        String json = Json.pretty(openAPI);
        log.debug("OpenAPI JSON spec 导出成功");
        return ResponseEntity.ok()
                .contentType(MediaType.APPLICATION_JSON)
                .body(json);
    }

    /**
     * 导出 OpenAPI YAML spec。
     * <p>
     * GET /api/v1/openapi/yaml → application/yaml
     * <p>
     * 需要认证（JWT Bearer Token 或 X-API-Key Header）。
     *
     * @return OpenAPI YAML 字符串
     */
    @GetMapping(value = "/yaml", produces = "application/yaml")
    public ResponseEntity<String> exportYaml() {
        if (openAPIService == null) {
            log.warn("OpenAPIService 不可用（springdoc.api-docs.enabled=false）");
            return ResponseEntity.status(503).body("OpenAPI spec 导出未启用");
        }
        OpenAPI openAPI = openAPIService.build(Locale.getDefault());
        String yaml = Yaml.pretty(openAPI);
        log.debug("OpenAPI YAML spec 导出成功");
        return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType("application/yaml"))
                .body(yaml);
    }
}