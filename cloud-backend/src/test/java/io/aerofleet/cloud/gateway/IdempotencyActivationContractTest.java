package io.aerofleet.cloud.gateway;

import io.aerofleet.cloud.security.TenantContext;
import io.aerofleet.cloud.security.TenantRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * #149 幂等契约矩阵 · 激活（设备显式登记）重复场景。
 *
 * <p>锁定 {@code POST /api/v1/devices/{sysid}} 的重复提交语义：
 * <ul>
 *   <li>首次登记 → 201 新建；</li>
 *   <li>重复登记（同请求体）→ 200 {@code alreadyRegistered=true}，有效归属不变；</li>
 *   <li>重复登记（不同 tenantId）→ 200 {@code alreadyRegistered=true}，<b>不</b>改绑；
 *       改归属是 {@code PUT /{sysid}/tenant} 的职责；</li>
 *   <li>并发重复登记同一 sysid → 恰好一个 201，其余 200，注册表只有一条目
 *       （{@code DeviceRegistry.provision} 的 {@code putIfAbsent} 保证）；</li>
 *   <li>注销后再登记 → 重新走 201 新建路径。</li>
 * </ul>
 *
 * <p><b>待裁决（OPEN RULING，owner）</b>：重复登记带不同 tenantId 时，响应体的
 * {@code tenantId} 字段回显的是<b>请求值</b>，而注册表里的有效归属保持不变。
 * 本矩阵只钉有效状态（{@code registry.tenantOf}），不断言该回显字段为正确语义；
 * 响应是否应回显有效归属，待 owner 裁决后再定稿。
 */
@DisplayName("幂等契约矩阵：设备激活重复提交")
class IdempotencyActivationContractTest {

    private DeviceRegistry registry;
    private TenantRepository tenantRepository;
    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        registry = new DeviceRegistry();
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
    @DisplayName("首次激活返回 201 并进入白名单")
    void firstActivationCreated() throws Exception {
        mockMvc.perform(post("/api/v1/devices/50")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"tenantId\":1}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.sysid").value(50))
                .andExpect(jsonPath("$.tenantId").value(1))
                .andExpect(jsonPath("$.alreadyRegistered").value(false));

        assertThat(registry.isKnownDevice(50)).isTrue();
    }

    @Test
    @DisplayName("重复激活（同请求体）返回 200 alreadyRegistered=true 且有效归属不变")
    void repeatActivationSameBodyIsIdempotent() throws Exception {
        mockMvc.perform(post("/api/v1/devices/51")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"tenantId\":1}"))
                .andExpect(status().isCreated());

        mockMvc.perform(post("/api/v1/devices/51")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"tenantId\":1}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.sysid").value(51))
                .andExpect(jsonPath("$.alreadyRegistered").value(true));

        assertThat(registry.tenantOf(51)).isEqualTo(1);
    }

    @Test
    @DisplayName("重复激活（不同 tenantId）不改绑：有效归属仍是首次登记值")
    void repeatActivationDifferentTenantDoesNotRebind() throws Exception {
        mockMvc.perform(post("/api/v1/devices/52")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"tenantId\":1}"))
                .andExpect(status().isCreated());

        // 响应回显请求的 tenantId=2，但有效归属必须仍是 1（见类 javadoc 的待裁决项）
        mockMvc.perform(post("/api/v1/devices/52")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"tenantId\":2}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.alreadyRegistered").value(true));

        assertThat(registry.tenantOf(52)).isEqualTo(1);
    }

    @Test
    @DisplayName("并发重复激活同一 sysid：恰好一个 201，其余 200，注册表仅一条目")
    void concurrentDuplicateActivationExactlyOneCreated() throws Exception {
        int sysid = 53;
        int threads = 8;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch start = new CountDownLatch(1);
        List<Integer> codes = new ArrayList<>();
        try {
            for (int i = 0; i < threads; i++) {
                pool.submit(() -> {
                    try {
                        start.await();
                        MvcResult r = mockMvc.perform(post("/api/v1/devices/" + sysid)
                                        .contentType(MediaType.APPLICATION_JSON)
                                        .content("{\"tenantId\":1}"))
                                .andReturn();
                        synchronized (codes) {
                            codes.add(r.getResponse().getStatus());
                        }
                    } catch (Exception e) {
                        throw new RuntimeException(e);
                    }
                });
            }
            start.countDown();
        } finally {
            pool.shutdown();
            assertThat(pool.awaitTermination(30, TimeUnit.SECONDS)).isTrue();
        }

        long created = codes.stream().filter(c -> c == 201).count();
        long existing = codes.stream().filter(c -> c == 200).count();
        assertThat(created).as("恰好一次新建").isEqualTo(1);
        assertThat(existing).as("其余均为幂等已存在").isEqualTo(threads - 1);
        assertThat(registry.isKnownDevice(sysid)).isTrue();
        assertThat(registry.all()).hasSize(1);
    }

    @Test
    @DisplayName("注销后再激活重新走 201 新建路径")
    void reactivationAfterDeregistrationCreatesAgain() throws Exception {
        mockMvc.perform(post("/api/v1/devices/54")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"tenantId\":1}"))
                .andExpect(status().isCreated());
        mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                        .delete("/api/v1/devices/54"))
                .andExpect(status().isOk());

        mockMvc.perform(post("/api/v1/devices/54")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"tenantId\":1}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.alreadyRegistered").value(false));
    }
}
