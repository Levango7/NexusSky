package io.aerofleet.cloud.mapping;

import io.aerofleet.cloud.gateway.DeviceRegistry;
import io.aerofleet.cloud.security.TenantContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 测绘任务列表查询的**下推**回归测试。
 *
 * <h2>被钉住的缺陷</h2>
 *
 * {@code MappingController#listTasks} 此前是 {@code taskRepository.findAll()} 后逐行
 * {@code if (tenantId != null && !tenantId.equals(task.getTenantId())) continue;} 加上
 * 状态的 {@code continue}——租户与状态两张皮都在内存里过滤。测绘任务只增不减，
 * 表随运行时长增长，那意味着每次列表请求都把整表拖进堆再逐行跳过。
 *
 * 讽刺的是 {@link MappingTaskRepository#findByTenantId} 早就存在，只是没人用；
 * 本批补齐 {@code findByTenantIdAndStatus} / {@code findByStatus} 并把调用方切过去。
 *
 * <h2>本类证明的不变式</h2>
 *
 * 租户会话下推「租户 + 状态」；租户会话无状态下推「租户」；全局管理员有状态下推「状态」；
 * 只有全局管理员且无状态时才允许 {@code findAll()}。任何情况下都不得
 * 「findAll 后内存过滤租户」。
 */
@DisplayName("MappingController 任务列表查询下推")
class MappingTaskQueryPushdownTest {

    private MappingController controller;
    private MappingTaskRepository taskRepository;
    private final Map<String, MappingTask> taskStore = new ConcurrentHashMap<>();

    @BeforeEach
    void setUp() {
        taskStore.clear();
        taskRepository = Mockito.mock(MappingTaskRepository.class);
        when(taskRepository.save(Mockito.any(MappingTask.class)))
                .thenAnswer(inv -> {
                    MappingTask t = inv.getArgument(0);
                    taskStore.put(t.getId(), t);
                    return t;
                });
        when(taskRepository.findById(Mockito.anyString()))
                .thenAnswer(inv -> Optional.ofNullable(taskStore.get(inv.getArgument(0))));
        controller = new MappingController(new MappingRoutePlanner(), new PhotoCaptureService(),
                new MappingResultService(), new DeviceRegistry(), taskRepository);
        TenantContext.clear();
    }

    @AfterEach
    void tearDown() {
        TenantContext.clear();
    }

    /** 走真实创建端点造一条任务（类型/状态/租户都由端点自己填）。 */
    private void createTask() {
        MappingController.CreateTaskRequest req = new MappingController.CreateTaskRequest();
        req.name = "pushdown-" + taskStore.size();
        req.type = "ORTHO_PHOTO";
        req.sysid = 1;
        req.altitudeM = 100.0;
        req.overlapPct = 80.0;
        req.sidelapPct = 60.0;
        req.cameraAngleDeg = 0.0;
        req.area = null;
        controller.createTask(req);
    }

    @Test
    @DisplayName("租户会话 + 状态：必须下推「租户 AND 状态」，不得 findAll")
    void tenantSessionWithStatusPushesBothDown() {
        TenantContext.setTenantId(7);
        when(taskRepository.findByTenantIdAndStatus(anyInt(), any())).thenReturn(List.of());

        controller.listTasks("PLANNING");

        verify(taskRepository).findByTenantIdAndStatus(7, MappingTask.Status.PLANNING);
        verify(taskRepository, never()).findAll();
    }

    @Test
    @DisplayName("租户会话 + 无状态：必须下推租户，不得 findAll")
    void tenantSessionWithoutStatusPushesTenantDown() {
        TenantContext.setTenantId(7);
        when(taskRepository.findByTenantId(anyInt())).thenReturn(List.of());

        controller.listTasks(null);

        verify(taskRepository).findByTenantId(7);
        verify(taskRepository, never()).findAll();
    }

    @Test
    @DisplayName("全局管理员 + 状态：下推状态，不得 findAll 后内存筛")
    void globalAdminWithStatusPushesStatusDown() {
        TenantContext.clear();
        when(taskRepository.findByStatus(any())).thenReturn(List.of());

        controller.listTasks("PLANNING");

        verify(taskRepository).findByStatus(MappingTask.Status.PLANNING);
        verify(taskRepository, never()).findAll();
    }

    @Test
    @DisplayName("全局管理员 + 无状态：允许 findAll（本就无过滤条件）")
    void globalAdminWithoutStatusMayUseFindAll() {
        TenantContext.clear();
        when(taskRepository.findAll()).thenReturn(new ArrayList<>(taskStore.values()));

        controller.listTasks(null);

        verify(taskRepository).findAll();
        verify(taskRepository, never()).findByTenantId(anyInt());
    }

    @Test
    @DisplayName("返回结构与旧口径一致：入参状态过滤后条数相同、summary 键不变")
    void resultShapeAndCountsUnchanged() {
        TenantContext.setTenantId(7);
        createTask();
        createTask();
        // 下推桩：原样返回 store 里符合状态的（模拟 DB 按状态过滤）
        when(taskRepository.findByTenantIdAndStatus(anyInt(), any())).thenAnswer(inv -> {
            List<MappingTask> out = new ArrayList<>();
            for (MappingTask t : taskStore.values()) {
                if (t.getStatus() == inv.getArgument(1)) {
                    out.add(t);
                }
            }
            return out;
        });

        List<Map<String, Object>> out = controller.listTasks("PLANNING");

        assertThat(out)
                .as("两条同租户同状态任务都应返回")
                .hasSize(2);
        assertThat(out.get(0))
                .as("summary 键与旧口径一致（id/status/type 等）")
                .containsKeys("id", "name", "type", "status");
    }
}
