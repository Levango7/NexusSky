package io.aerofleet.cloud.gateway;

import io.aerofleet.cloud.security.TenantContext;
import io.aerofleet.cloud.security.TenantRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * {@link DeviceProvisioningController} REST 端点测试（P0-1 设备归属指派）。
 * <p>
 * standaloneSetup 风格：真实 DeviceRegistry + mock TenantRepository，不启动 Spring 上下文。
 */
@DisplayName("DeviceProvisioningController 设备归属端点 (P0-1)")
class DeviceProvisioningControllerTest {

    private DeviceRegistry registry;
    private TenantRepository tenantRepository;
    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        registry = new DeviceRegistry();
        registry.registerIfAbsent(7);
        registry.registerIfAbsent(8);

        tenantRepository = mock(TenantRepository.class);
        when(tenantRepository.existsById(anyInt())).thenAnswer(inv -> {
            Integer id = inv.getArgument(0);
            return id.equals(1) || id.equals(2);
        });
        when(tenantRepository.findById(anyInt())).thenReturn(Optional.empty());

        mockMvc = MockMvcBuilders.standaloneSetup(
                new DeviceProvisioningController(registry, tenantRepository)).build();
    }

    @AfterEach
    void clearTenant() {
        TenantContext.clear();
    }

    @Test
    @DisplayName("PUT 绑定租户返回 200 且设备对该租户可见")
    void assignTenantBindsDevice() throws Exception {
        mockMvc.perform(put("/api/v1/devices/7/tenant")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"tenantId\":2}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.sysid").value(7))
                .andExpect(jsonPath("$.tenantId").value(2))
                .andExpect(jsonPath("$.assigned").value(true));

        TenantContext.setTenantId(2);
        assertThat(registry.get(7)).isNotNull();
        TenantContext.setTenantId(3);
        assertThat(registry.get(7)).isNull();
    }

    @Test
    @DisplayName("PUT 未知 sysid 返回 404")
    void assignUnknownDeviceReturnsNotFound() throws Exception {
        mockMvc.perform(put("/api/v1/devices/99/tenant")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"tenantId\":2}"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error").value(org.hamcrest.Matchers.containsString("device unknown")));
    }

    @Test
    @DisplayName("PUT 不存在的租户返回 404")
    void assignUnknownTenantReturnsNotFound() throws Exception {
        mockMvc.perform(put("/api/v1/devices/7/tenant")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"tenantId\":404}"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error").value(org.hamcrest.Matchers.containsString("tenant not found")));
    }

    @Test
    @DisplayName("PUT 缺 tenantId 字段返回 400")
    void assignWithoutTenantIdReturnsBadRequest() throws Exception {
        mockMvc.perform(put("/api/v1/devices/7/tenant")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("PUT tenantId 非整数返回 400")
    void assignNonIntegerTenantReturnsBadRequest() throws Exception {
        mockMvc.perform(put("/api/v1/devices/7/tenant")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"tenantId\":\"abc\"}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("PUT tenantId=null 解绑，assigned=false 且回到未归属")
    void assignNullUnbindsDevice() throws Exception {
        mockMvc.perform(put("/api/v1/devices/7/tenant")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"tenantId\":2}"))
                .andExpect(status().isOk());

        mockMvc.perform(put("/api/v1/devices/7/tenant")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"tenantId\":null}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.assigned").value(false))
                .andExpect(jsonPath("$.tenantId").doesNotExist());

        TenantContext.setTenantId(2);
        assertThat(registry.get(7)).isNull();
    }

    @Test
    @DisplayName("GET /unassigned 返回无归属设备列表")
    void unassignedListsUnownedDevices() throws Exception {
        registry.assignTenant(8, 1);

        mockMvc.perform(get("/api/v1/devices/unassigned"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.sysids[0]").value(7))
                .andExpect(jsonPath("$.sysids.length()").value(1));
    }

    // ===== 显式登记设备（#46 prod 白名单死锁）=====

    @Test
    @DisplayName("POST /devices/{sysid} 登记新设备返回 201 并进入白名单")
    void provisionRegistersNewDevice() throws Exception {
        mockMvc.perform(post("/api/v1/devices/30")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"tenantId\":1}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.sysid").value(30))
                .andExpect(jsonPath("$.tenantId").value(1))
                .andExpect(jsonPath("$.alreadyRegistered").value(false))
                // 本测试的注册表未开持久化：内存条目重启即失，prod 靠 persist=true 兜住
                .andExpect(jsonPath("$.persisted").value(false));

        assertThat(registry.isKnownDevice(30)).isTrue();
        TenantContext.setTenantId(1);
        assertThat(registry.get(30)).isNotNull();
    }

    @Test
    @DisplayName("POST 无请求体也可登记，登记为未归属（对任何租户不可见）")
    void provisionWithoutBodyRegistersUnassigned() throws Exception {
        mockMvc.perform(post("/api/v1/devices/31"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.tenantId").doesNotExist());

        TenantContext.setTenantId(1);
        assertThat(registry.isKnownDevice(31)).isTrue();
        assertThat(registry.get(31)).isNull();
    }

    @Test
    @DisplayName("POST 已存在设备返回 200 alreadyRegistered=true 且不覆盖归属")
    void provisionExistingDeviceIsIdempotent() throws Exception {
        registry.assignTenant(7, 2);

        mockMvc.perform(post("/api/v1/devices/7"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.alreadyRegistered").value(true));

        assertThat(registry.tenantOf(7)).isEqualTo(2);
    }

    @Test
    @DisplayName("POST sysid 越界返回 400（0 保留、255 是 GCS 自身）")
    void provisionRejectsOutOfRangeSysid() throws Exception {
        mockMvc.perform(post("/api/v1/devices/0"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value(org.hamcrest.Matchers.containsString("1..254")));
        mockMvc.perform(post("/api/v1/devices/255"))
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("POST 租户不存在 404、tenantId 非整数 400，两种失败都不落库")
    void provisionValidatesTenant() throws Exception {
        mockMvc.perform(post("/api/v1/devices/32")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"tenantId\":404}"))
                .andExpect(status().isNotFound());
        mockMvc.perform(post("/api/v1/devices/33")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"tenantId\":\"x\"}"))
                .andExpect(status().isBadRequest());

        assertThat(registry.isKnownDevice(32)).isFalse();
        assertThat(registry.isKnownDevice(33)).isFalse();
    }
}
