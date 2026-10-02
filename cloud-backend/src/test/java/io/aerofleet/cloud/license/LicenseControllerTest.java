package io.aerofleet.cloud.license;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.time.Instant;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * {@link LicenseController} 激活/查询路径的行为测试。
 * <p>
 * 收口 License fail-closed 轮（025b6a7）遗留的未闭合项 ②：「LicenseController 的
 * 激活/查询路径仍无测试」。该轮的 {@code LicenseServiceFailClosedTest} 只钉住了
 * 服务层验签/过期语义，控制器对 HTTP 侧的字段映射、参数缺失的 fail-fast 分支、
 * 服务判定结果到响应信封的透传一直零覆盖——这里逐条补上。
 * <p>
 * 设计：standalone MockMvc + mock {@link LicenseService}。控制器逻辑与拦截器/安全
 * 链解耦（拦截器排除路径已由 {@code LicenseConfigTest} 走真实 MVC 切片覆盖，
 * RBAC 注解覆盖面由 {@code RbacEndpointCoverageTest} 反射钉住），这里只测
 * 「请求体 → 服务调用 → 响应映射」这一层。
 */
class LicenseControllerTest {

    private LicenseService licenseService;
    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        licenseService = mock(LicenseService.class);
        mockMvc = MockMvcBuilders.standaloneSetup(new LicenseController(licenseService)).build();
    }

    private LicenseInfo sampleLicense() {
        return new LicenseInfo("key-material", "tenant-42", "AeroFleet Cloud Standard",
                25, Instant.parse("2030-01-01T00:00:00Z"), Instant.parse("2026-01-01T00:00:00Z"),
                "Acme UAV Co.", true);
    }

    @Test
    @DisplayName("info：License 字段逐项映射到响应（含派生 expired 与 devEdition）")
    void infoMapsLicenseFields() throws Exception {
        when(licenseService.getLicenseInfo()).thenReturn(sampleLicense());
        when(licenseService.isDevLicense()).thenReturn(false);

        mockMvc.perform(get("/api/v1/license/info"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.tenantId").value("tenant-42"))
                .andExpect(jsonPath("$.productName").value("AeroFleet Cloud Standard"))
                .andExpect(jsonPath("$.maxDevices").value(25))
                .andExpect(jsonPath("$.issuedTo").value("Acme UAV Co."))
                .andExpect(jsonPath("$.active").value(true))
                .andExpect(jsonPath("$.expired").value(false))
                .andExpect(jsonPath("$.devEdition").value(false));
    }

    @Test
    @DisplayName("info：dev 版标记透传（devEdition=true）")
    void infoPropagatesDevEditionFlag() throws Exception {
        when(licenseService.getLicenseInfo()).thenReturn(sampleLicense());
        when(licenseService.isDevLicense()).thenReturn(true);

        mockMvc.perform(get("/api/v1/license/info"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.devEdition").value(true));
    }

    @Test
    @DisplayName("activate：缺任一必要参数返回 success=false 并指明缺失项，不触发服务校验")
    void activateWithMissingParamsFailsFast() throws Exception {
        mockMvc.perform(post("/api/v1/license/activate")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"activationCode\":\"code-1\",\"tenantId\":\"tenant-42\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.message").value("缺少必要参数: activationCode, tenantId, machineId"));

        verify(licenseService, never()).validateActivation(anyString(), anyString(), anyString());
    }

    @Test
    @DisplayName("activate：合法激活码 success=true，参数按请求体原样透传给服务层")
    void activateWithValidCodeSucceeds() throws Exception {
        when(licenseService.validateActivation("code-1", "tenant-42", "machine-7")).thenReturn(true);

        mockMvc.perform(post("/api/v1/license/activate")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"activationCode\":\"code-1\",\"tenantId\":\"tenant-42\",\"machineId\":\"machine-7\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.message").value("激活成功"));

        verify(licenseService).validateActivation("code-1", "tenant-42", "machine-7");
    }

    @Test
    @DisplayName("activate：无效激活码 success=false")
    void activateWithInvalidCodeFails() throws Exception {
        when(licenseService.validateActivation(anyString(), anyString(), anyString())).thenReturn(false);

        mockMvc.perform(post("/api/v1/license/activate")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"activationCode\":\"bad\",\"tenantId\":\"t\",\"machineId\":\"m\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.message").value("激活码无效"));
    }

    @Test
    @DisplayName("verify：服务判定有效时 valid=true，expired/active/devEdition 逐项映射")
    void verifyMapsServiceVerdictWhenValid() throws Exception {
        when(licenseService.getLicenseInfo()).thenReturn(sampleLicense());
        when(licenseService.validateLicense(any(LicenseInfo.class))).thenReturn(true);
        when(licenseService.isDevLicense()).thenReturn(false);

        mockMvc.perform(get("/api/v1/license/verify"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.valid").value(true))
                .andExpect(jsonPath("$.expired").value(false))
                .andExpect(jsonPath("$.active").value(true))
                .andExpect(jsonPath("$.devEdition").value(false));
    }

    @Test
    @DisplayName("verify：服务判定无效时 valid=false")
    void verifyMapsServiceVerdictWhenInvalid() throws Exception {
        when(licenseService.getLicenseInfo()).thenReturn(sampleLicense());
        when(licenseService.validateLicense(any(LicenseInfo.class))).thenReturn(false);

        mockMvc.perform(get("/api/v1/license/verify"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.valid").value(false));
    }
}
