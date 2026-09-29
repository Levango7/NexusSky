package io.aerofleet.cloud.security.chain;

import io.aerofleet.cloud.alarm.AlarmEvent;
import io.aerofleet.cloud.alarm.AlarmEventStore;
import io.aerofleet.cloud.delivery2.DeliveryTask2;
import io.aerofleet.cloud.delivery2.DeliveryTask2Repository;
import io.aerofleet.cloud.flightlog.FlightLogEntity;
import io.aerofleet.cloud.flightlog.FlightLogRepository;
import io.aerofleet.cloud.mapping.MappingTask;
import io.aerofleet.cloud.mapping.MappingTaskRepository;
import io.aerofleet.cloud.orch.entity.OrchestrationPlanEntity;
import io.aerofleet.cloud.orch.enums.PlanStatus;
import io.aerofleet.cloud.orch.repository.OrchestrationPlanRepository;
import io.aerofleet.cloud.security.JwtTokenProvider;
import io.aerofleet.cloud.security.Role;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;

import java.time.Duration;
import java.time.Instant;
import java.util.List;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 鉴权链级测试：以 dev-mode=false + rbac-enabled=true 启动完整过滤器链，
 * 验证「未认证被拒」「OBSERVER 越权被拒」「跨租户读被过滤」三类契约。
 * <p>
 * 存在理由：全仓其余测试都在 test profile（dev-mode=true → anyRequest().permitAll()、
 * rbac-enabled=false）下运行，因此 3760 个用例对鉴权面零覆盖。本类是第一条链级鉴权证据。
 */
@SpringBootTest
@AutoConfigureMockMvc
@TestPropertySource(properties = {
        "aerofleet.security.dev-mode=false",
        "aerofleet.security.rbac-enabled=true",
        "aerofleet.license.enabled=false",
        "spring.datasource.url=jdbc:h2:mem:authchain-test;MODE=PostgreSQL;DB_CLOSE_DELAY=-1;DATABASE_TO_LOWER=TRUE",
        "spring.datasource.driver-class-name=org.h2.Driver",
        "spring.datasource.username=sa",
        "spring.datasource.password=",
        "spring.jpa.hibernate.ddl-auto=create-drop",
        "spring.flyway.enabled=false",
        "spring.cache.type=none",
        "aerofleet.udp-port=0",
        "aerofleet.device-registry.persist=false",
        "aerofleet.flightlog.persist-to-db=true",
        "aerofleet.flightlog.dir=./flight-logs-chain-test",
        "spring.autoconfigure.exclude=org.springframework.boot.autoconfigure.data.redis.RedisAutoConfiguration,org.springframework.boot.autoconfigure.data.redis.RedisRepositoriesAutoConfiguration",
})
@DisplayName("鉴权链：401 / 403 / 跨租户读过滤")
class HttpAuthChainTest {

    /** 属于本测试的两个租户，DB 中无需真实租户行（tenant_id 为普通列）。 */
    private static final int TENANT_A = 7101;
    private static final int TENANT_B = 7102;
    /** 本测试专用的设备 sysid（注册进 DeviceRegistry 并指派给 TENANT_A）。 */
    private static final int DEVICE_SYSID = 7199;

    @Autowired
    private MockMvc mvc;

    @Autowired
    private JwtTokenProvider tokens;

    @Autowired
    private io.aerofleet.cloud.gateway.DeviceRegistry registry;

    @Autowired
    private AlarmEventStore alarmStore;

    @Autowired
    private FlightLogRepository flightLogRepository;

    @Autowired
    private OrchestrationPlanRepository planRepository;

    @Autowired
    private DeliveryTask2Repository deliveryRepository;

    @Autowired
    private MappingTaskRepository mappingRepository;

    private String bearerA;
    private String bearerB;
    private String bearerObserver;

    @BeforeEach
    void setUp() {
        flightLogRepository.deleteAll();
        planRepository.deleteAll();
        deliveryRepository.deleteAll();
        mappingRepository.deleteAll();

        bearerA = bearer(Role.OPERATOR, TENANT_A);
        bearerB = bearer(Role.OPERATOR, TENANT_B);
        bearerObserver = bearer(Role.OBSERVER, TENANT_A);
    }

    private String bearer(Role role, Integer tenantId) {
        return "Bearer " + tokens.generateToken("chain-user-" + tenantId + "-" + role,
                role, tenantId, Duration.ofMinutes(10));
    }

    // ==================== 1. 匿名访问必须被拒 ====================

    @Test
    @DisplayName("未携带凭据访问业务端点一律 401")
    void anonymousRequestsAreRejected() throws Exception {
        List<String> endpoints = List.of(
                "/api/v1/drones",
                "/api/v1/alarms/events",
                "/api/v1/flightlog",
                "/api/v1/orch/plans",
                "/api/v1/delivery2/tasks",
                "/api/v1/mapping/tasks",
                "/api/v1/show/formations",
                "/api/v1/geofence/zones");

        for (String endpoint : endpoints) {
            mvc.perform(get(endpoint))
                    .andExpect(status().isUnauthorized());
        }
    }

    @Test
    @DisplayName("健康检查仍允许匿名（白名单未被扩大）")
    void healthStaysAnonymous() throws Exception {
        mvc.perform(get("/actuator/health")).andExpect(status().isOk());
    }

    // ==================== 2. OBSERVER 不得触达写端点 ====================

    @Test
    @DisplayName("OBSERVER 不得触达调度配置/围栏删除/编队创建")
    void observerCannotReachPrivilegedWriteEndpoints() throws Exception {
        // 修复前这三个写端点没有任何 @RequireRole（拦截器「无注解=放行」），
        // 实测 OBSERVER 也能触发（expected 403 but was 200）；此处钉住分档，防止注解被摘掉。
        mvc.perform(put("/api/v1/autodispatch/config")
                        .header("Authorization", bearerObserver)
                        .contentType("application/json")
                        .content("{}"))
                .andExpect(status().isForbidden());

        mvc.perform(delete("/api/v1/geofence/zones/999999")
                        .header("Authorization", bearerObserver))
                .andExpect(status().isForbidden());

        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                        .post("/api/v1/formation")
                        .header("Authorization", bearerObserver)
                        .contentType("application/json")
                        .content("{}"))
                .andExpect(status().isForbidden());
    }

    // ==================== 3. 跨租户读必须被过滤 ====================

    @Test
    @DisplayName("他租户报警事件不可见")
    void alarmEventsAreTenantScoped() throws Exception {
        AlarmEvent event = new AlarmEvent();
        event.setId("chain-alarm-1");
        event.setSourceDeviceId("chain-dev-1");
        event.setEventType(AlarmEvent.EventType.INTRUSION);
        event.setSeverity(AlarmEvent.Severity.CRITICAL);
        event.setDescription("chain-test alarm");
        event.setTimestampMs(System.currentTimeMillis());
        event.setTenantId(TENANT_A);
        alarmStore.store(event);

        mvc.perform(get("/api/v1/alarms/events")
                        .header("Authorization", bearerA))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.total").value(1));

        mvc.perform(get("/api/v1/alarms/events")
                        .header("Authorization", bearerB))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.total").value(0));
    }

    @Test
    @DisplayName("他租户飞行日志不可见")
    void flightLogsAreTenantScoped() throws Exception {
        FlightLogEntity logA = new FlightLogEntity();
        logA.setTimestamp(Instant.now());
        logA.setType("alert");
        logA.setSysid(7101);
        logA.setText("chain-test log for tenant A");
        logA.setTenantId(TENANT_A);
        flightLogRepository.save(logA);

        mvc.perform(get("/api/v1/flightlog")
                        .header("Authorization", bearerA)
                        .param("sysid", "7101"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1));

        mvc.perform(get("/api/v1/flightlog")
                        .header("Authorization", bearerB)
                        .param("sysid", "7101"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(0));
    }

    @Test
    @DisplayName("他租户编排计划不可见")
    void orchPlansAreTenantScoped() throws Exception {
        OrchestrationPlanEntity plan = new OrchestrationPlanEntity();
        plan.setName("chain-plan-A");
        plan.setStatus(PlanStatus.DRAFT);
        plan.setResourcePool("[]");
        plan.setTenantId(TENANT_A);
        planRepository.save(plan);

        mvc.perform(get("/api/v1/orch/plans")
                        .header("Authorization", bearerA))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1));

        mvc.perform(get("/api/v1/orch/plans")
                        .header("Authorization", bearerB))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(0));
    }

    @Test
    @DisplayName("他租户配送任务不可见")
    void deliveryTasksAreTenantScoped() throws Exception {
        DeliveryTask2 task = new DeliveryTask2();
        task.setId("chain-delivery-A");
        task.setTenantId(TENANT_A);
        deliveryRepository.save(task);

        mvc.perform(get("/api/v1/delivery2/tasks")
                        .header("Authorization", bearerA))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[?(@.id=='chain-delivery-A')]").isNotEmpty());

        mvc.perform(get("/api/v1/delivery2/tasks")
                        .header("Authorization", bearerB))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(0));
    }

    @Test
    @DisplayName("他租户测绘任务不可见")
    void mappingTasksAreTenantScoped() throws Exception {
        MappingTask task = new MappingTask();
        task.setId("chain-mapping-A");
        task.setName("chain-mapping-A");
        task.setType(io.aerofleet.cloud.mapping.MappingType.ORTHO_PHOTO);
        task.setStatus(MappingTask.Status.PENDING);
        task.setTenantId(TENANT_A);
        mappingRepository.save(task);

        mvc.perform(get("/api/v1/mapping/tasks")
                        .header("Authorization", bearerA))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1));

        mvc.perform(get("/api/v1/mapping/tasks")
                        .header("Authorization", bearerB))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(0));
    }

    @Test
    @DisplayName("无租户归属的 OPERATOR 不再等价全局管理员（P0-2）")
    void tenantlessOperatorSeesNoTenantData() throws Exception {
        registry.registerIfAbsent(DEVICE_SYSID);
        registry.assignTenant(DEVICE_SYSID, TENANT_A);

        String tenantlessOperator = "Bearer " + tokens.generateToken("tenantless-op",
                Role.OPERATOR, null, Duration.ofMinutes(10));

        // 归属本租户的人看得到
        mvc.perform(get("/api/v1/drones").header("Authorization", bearerA))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[?(@.sysid==" + DEVICE_SYSID + ")]").isNotEmpty());

        // 无 tenant_id claim 的 OPERATOR：既不是任何租户，也不该是全局管理员
        mvc.perform(get("/api/v1/drones").header("Authorization", tenantlessOperator))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(0));

        mvc.perform(get("/api/v1/alarms/events").header("Authorization", tenantlessOperator))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.total").value(0));
    }

    @Test
    @DisplayName("无租户归属的 ADMIN 才是显式全局管理员（P0-2 的另一半）")
    void tenantlessAdminIsGlobalScope() throws Exception {
        registry.registerIfAbsent(DEVICE_SYSID);
        registry.assignTenant(DEVICE_SYSID, TENANT_A);

        String tenantlessAdmin = "Bearer " + tokens.generateToken("tenantless-admin",
                Role.ADMIN, null, Duration.ofMinutes(10));

        mvc.perform(get("/api/v1/drones").header("Authorization", tenantlessAdmin))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[?(@.sysid==" + DEVICE_SYSID + ")]").isNotEmpty());
    }

    @Test
    @DisplayName("按 ID 直取他租户资源必须 404（IDOR）")
    void directIdAccessToOtherTenantIsNotFound() throws Exception {
        OrchestrationPlanEntity plan = new OrchestrationPlanEntity();
        plan.setName("chain-plan-idor");
        plan.setStatus(PlanStatus.DRAFT);
        plan.setResourcePool("[]");
        plan.setTenantId(TENANT_A);
        planRepository.save(plan);

        mvc.perform(get("/api/v1/orch/plans/" + plan.getPlanId())
                        .header("Authorization", bearerB))
                .andExpect(status().isNotFound());

        mvc.perform(get("/api/v1/orch/plans/" + plan.getPlanId())
                        .header("Authorization", bearerA))
                .andExpect(status().isOk());
    }
}
