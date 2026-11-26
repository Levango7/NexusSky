package io.aerofleet.cloud.gateway;

import io.aerofleet.cloud.security.TenantRepository;
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
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * #149 幂等契约矩阵 · 注销（设备撤销登记）重复场景。
 *
 * <p>锁定 {@code DELETE /api/v1/devices/{sysid}} 的重复提交语义：
 * <ul>
 *   <li>首次注销 → 200 {@code deregistered=true}；</li>
 *   <li>重复注销 → 404 {@code device unknown}（撤销是吸收性操作，
 *       第二次起设备已不存在）；</li>
 *   <li>并发重复注销同一 sysid → 恰好一个 200，其余 404
 *       （{@code DeviceRegistry.deregister} 的 sysid 条带锁串行化）；</li>
 *   <li>注销后再登记 → 重新走 201 新建路径。</li>
 * </ul>
 */
@DisplayName("幂等契约矩阵：设备注销重复提交")
class IdempotencyDeregistrationContractTest {

    private DeviceRegistry registry;
    private TenantRepository tenantRepository;
    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        registry = new DeviceRegistry();
        tenantRepository = mock(TenantRepository.class);
        when(tenantRepository.existsById(anyInt())).thenReturn(true);
        when(tenantRepository.findById(anyInt())).thenReturn(Optional.empty());
        mockMvc = MockMvcBuilders.standaloneSetup(
                new DeviceProvisioningController(registry, tenantRepository)).build();
    }

    private void provision(int sysid) throws Exception {
        mockMvc.perform(post("/api/v1/devices/" + sysid)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"tenantId\":1}"))
                .andExpect(status().isCreated());
    }

    @Test
    @DisplayName("首次注销返回 200 deregistered=true 并移出白名单")
    void firstDeregistrationSucceeds() throws Exception {
        provision(60);

        mockMvc.perform(delete("/api/v1/devices/60"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.sysid").value(60))
                .andExpect(jsonPath("$.deregistered").value(true));

        assertThat(registry.isKnownDevice(60)).isFalse();
    }

    @Test
    @DisplayName("重复注销第二次起返回 404 device unknown")
    void repeatDeregistrationReturnsNotFound() throws Exception {
        provision(61);

        mockMvc.perform(delete("/api/v1/devices/61"))
                .andExpect(status().isOk());

        mockMvc.perform(delete("/api/v1/devices/61"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error").value(
                        org.hamcrest.Matchers.containsString("device unknown")));
    }

    @Test
    @DisplayName("并发重复注销同一 sysid：恰好一个 200，其余 404")
    void concurrentDuplicateDeregistrationExactlyOneSucceeds() throws Exception {
        int sysid = 62;
        provision(sysid);

        int threads = 8;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch start = new CountDownLatch(1);
        List<Integer> codes = new ArrayList<>();
        try {
            for (int i = 0; i < threads; i++) {
                pool.submit(() -> {
                    try {
                        start.await();
                        MvcResult r = mockMvc.perform(delete("/api/v1/devices/" + sysid))
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

        long ok = codes.stream().filter(c -> c == 200).count();
        long notFound = codes.stream().filter(c -> c == 404).count();
        assertThat(ok).as("恰好一次注销生效").isEqualTo(1);
        assertThat(notFound).as("其余均因设备已不存在").isEqualTo(threads - 1);
        assertThat(registry.isKnownDevice(sysid)).isFalse();
    }

    @Test
    @DisplayName("注销后再登记重新走 201 新建路径")
    void reProvisionAfterDeregistrationCreatesAgain() throws Exception {
        provision(63);
        mockMvc.perform(delete("/api/v1/devices/63")).andExpect(status().isOk());

        mockMvc.perform(post("/api/v1/devices/63")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"tenantId\":1}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.alreadyRegistered").value(false));
    }
}
