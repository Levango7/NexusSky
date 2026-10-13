package io.aerofleet.cloud.orch.adapter;

import io.aerofleet.cloud.api.service.EmergencyOrchService;
import io.aerofleet.cloud.delivery2.DeliveryController2;
import io.aerofleet.cloud.delivery2.DeliveryStatus;
import io.aerofleet.cloud.delivery2.DeliveryTask2;
import io.aerofleet.cloud.delivery2.Payload;
import io.aerofleet.cloud.mapping.MappingController;
import io.aerofleet.cloud.mission.formation.Formation;
import io.aerofleet.cloud.mission.formation.FormationCreateRequest;
import io.aerofleet.cloud.mission.formation.FormationGeometry;
import io.aerofleet.cloud.mission.formation.FormationService;
import io.aerofleet.cloud.orch.enums.ModuleType;
import io.aerofleet.cloud.show.ShowController;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 五个业务模块适配器（配送 / 编队 / 测绘 / 表演 / 应急）的契约测试。
 * <p>
 * <b>为什么值得钉这三类事实</b>：适配器是编排层与业务模块之间唯一的一层翻译，它做三件事，
 * 每一件坏掉都只会在运行时暴露：
 * <ol>
 *   <li><b>路由字符串</b>——{@code StepExecutor} 用 {@code adapters.get(module.name())} 按字符串
 *       查适配器（StepExecutor.java:218），key 就是 {@link ModuleAdapter#getModuleType()}。
 *       任何一处把 "FORMATION" 写成 "FORMAION"，该模块的每一步都会变成
 *       「未找到模块类型 FORMATION 的适配器」，而编译与既有单测全都不会响。</li>
 *   <li><b>Map → DTO 的键名翻译</b>——参数来自计划里的 JSON 字符串，键名靠约定；
 *       上游改键名或适配器抄错键名，结果是「参数静默取默认值」，任务照样创建成功但飞错高度。</li>
 *   <li><b>失败不越界</b>——每个方法都把协作者的异常吞成 {@link ModuleResult#fail}，
 *       编排线程因此不会被一个模块的故障带崩；这条靠"抛出去就红"来守。</li>
 * </ol>
 * <p>
 * <b>刻意没有断言的两件事</b>：
 * <ul>
 *   <li>测绘/表演的 {@code getTaskStatus} 在上游返回 null 时会走 NPE→fail 这条路，
 *       其 {@code errorMessage} 是 JDK 生成的空指针提示文案，随 JDK 版本变化，故只断"失败且不抛"。</li>
 *   <li>{@code getOrDefault("status", "UNKNOWN")} / {@code getOrDefault("status", "PENDING")}
 *       这类缺省分支<b>在当前接线下不可达</b>：{@code MappingController.requireTask}(:444) 对未知或
 *       他租户单号是直接抛 {@code NotFoundException}，而 {@code taskSummary} 恒写入 {@code status}。
 *       把它们钉成契约等于把防御性死代码变成"必须保留的行为"，所以只留这条注释。</li>
 * </ul>
 * 结论：五个适配器对"任务查不到"的对外答复其实是一致的——都是 fail，区别只在靠显式 null 检查
 * （配送/编队/应急）还是靠上游异常被吞（测绘/表演）。
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("ModuleAdapter 五实现：路由字符串、键名翻译、失败不越界")
class ModuleAdapterContractTest {

    /** 用于「路由字符串 ↔ 枚举名」双向核对：每个适配器配一个刚建好的替身协作者。 */
    private static List<ModuleAdapter> allAdapters() {
        return List.of(
                new DeliveryAdapter(mock(DeliveryController2.class)),
                new FormationAdapter(mock(FormationService.class)),
                new MappingAdapter(mock(MappingController.class)),
                new ShowAdapter(mock(ShowController.class)),
                new EmergencyAdapter(mock(EmergencyOrchService.class)));
    }

    @ParameterizedTest(name = "ModuleType.{0} 必须能被某个适配器的 getModuleType() 命中")
    @EnumSource(ModuleType.class)
    @DisplayName("getModuleType() 的字符串就是 StepExecutor 的查表 key")
    void routingStringCoversEveryModuleType(ModuleType type) {
        assertThat(allAdapters())
                .extracting(ModuleAdapter::getModuleType)
                .as("StepExecutor 用 ModuleType.name() 作 key 查适配器，任何一处不等就静默失败")
                .contains(type.name());
    }

    @Test
    @DisplayName("路由字符串不能多出枚举里没有的名字（否则注册表里是永远查不到的死条目）")
    void routingStringsAreAllValidModuleTypes() {
        assertThat(allAdapters())
                .extracting(ModuleAdapter::getModuleType)
                .containsExactlyInAnyOrderElementsOf(
                        java.util.Arrays.stream(ModuleType.values()).map(Enum::name).toList());
    }

    @Nested
    @DisplayName("DELIVERY：DeliveryController2 翻译")
    class DeliveryContract {

        @Mock
        private DeliveryController2 controller;

        private DeliveryAdapter adapter() {
            return new DeliveryAdapter(controller);
        }

        @Test
        @DisplayName("createTask 逐键翻译参数并把上游给的单号回带出去")
        void createTranslatesParamsAndCarriesId() {
            DeliveryTask2 created = new DeliveryTask2();
            created.setId("DT-1");
            created.setStatus(DeliveryTask2.Status.PENDING);
            when(controller.createTask(any(DeliveryTask2.class))).thenReturn(created);

            ModuleResult result = adapter().createTask(Map.of(
                    "type", "MEDICAL_SAMPLE",
                    "senderLat", 30.1, "senderLon", 120.1,
                    "receiverLat", 30.2, "receiverLon", 120.2,
                    "receiverName", "市一院",
                    "payloadWeightKg", 2.5, "payloadType", "BLOOD_SAMPLE",
                    "priority", "HIGH",
                    "assignedSysid", 231));

            assertThat(result.isSuccess()).isTrue();
            // CREATE_AND_START_TASK 会把这里返回的 taskId 直接喂给 startTask，所以它必须是单号本身
            assertThat(result.getTaskId()).isEqualTo("DT-1");
            assertThat(result.getStatus()).isEqualTo("PENDING");

            ArgumentCaptor<DeliveryTask2> sent = ArgumentCaptor.forClass(DeliveryTask2.class);
            verify(controller).createTask(sent.capture());
            DeliveryTask2 dto = sent.getValue();
            assertThat(dto.getType()).isEqualTo(DeliveryTask2.Type.MEDICAL_SAMPLE);
            assertThat(dto.getSenderLat()).isEqualTo(30.1);
            assertThat(dto.getReceiverLat()).isEqualTo(30.2);
            assertThat(dto.getReceiverName()).isEqualTo("市一院");
            assertThat(dto.getPayload().getWeightKg()).isEqualTo(2.5);
            assertThat(dto.getPayload().getType()).isEqualTo(Payload.Type.BLOOD_SAMPLE);
            assertThat(dto.getPriority()).isEqualTo(DeliveryTask2.Priority.HIGH);
            assertThat(dto.getAssignedSysid()).isEqualTo(231);
        }

        @Test
        @DisplayName("状态查询用 DeliveryStatus.phase 而不是任务对象的 status")
        void statusUsesPhaseName() {
            when(controller.getStatus("DT-1")).thenReturn(new DeliveryStatus(
                    "DT-1", DeliveryStatus.Phase.IN_TRANSIT, 0.0, 0.0, 1.2, 8.0,
                    DeliveryStatus.PayloadCondition.NORMAL));

            ModuleResult result = adapter().getTaskStatus("DT-1");

            assertThat(result.isSuccess()).isTrue();
            assertThat(result.getStatus()).isEqualTo("IN_TRANSIT");
        }

        @Test
        @DisplayName("上游抛异常时不越界抛出，失败原因原样带回")
        void createFailureDoesNotEscape() {
            when(controller.createTask(any(DeliveryTask2.class)))
                    .thenThrow(new IllegalStateException("指派无人机不在位"));

            ModuleResult result = adapter().createTask(Map.of("type", "REGULAR_PARCEL"));

            assertThat(result.isSuccess()).isFalse();
            assertThat(result.getErrorMessage()).isEqualTo("指派无人机不在位");
        }

        @Test
        @DisplayName("查不到状态时回 fail，而不是 ok 带一个空状态")
        void missingStatusIsFailure() {
            when(controller.getStatus("DT-404")).thenReturn(null);

            ModuleResult result = adapter().getTaskStatus("DT-404");

            assertThat(result.isSuccess()).isFalse();
            assertThat(result.getTaskId()).isEqualTo("DT-404");
            assertThat(result.getErrorMessage()).contains("DT-404");
        }
    }

    @Nested
    @DisplayName("FORMATION：FormationService 命令翻译，中止=解散")
    class FormationContract {

        @Mock
        private FormationService service;

        private FormationAdapter adapter() {
            return new FormationAdapter(service);
        }

        private Formation formation(int id, Formation.FormationState state) {
            Formation f = new Formation(id, Set.of(101, 102), FormationGeometry.Shape.LINE,
                    5.0, 0.0, 0.0, 0.0, 0.0, Map.of(), 101);
            f.state = state;
            return f;
        }

        @Test
        @DisplayName("createTask 翻译成员/队形/间距，并把编队号转成字符串单号")
        void createTranslatesRequest() {
            when(service.create(any(FormationCreateRequest.class))).thenReturn(
                    new FormationService.FormationCreateResult(
                            7, Map.of(), Formation.FormationState.FORMING, 101));

            ModuleResult result = adapter().createTask(Map.of(
                    "members", List.of(101, 102, 103),
                    "shape", "circle", // 小写：适配器负责归一化成枚举
                    "spacing", 8.0, "heading", 90.0,
                    "refLat", 30.25, "refLon", 120.15, "refAlt", 120.0,
                    "leaderSysid", 101));

            assertThat(result.isSuccess()).isTrue();
            assertThat(result.getTaskId()).isEqualTo("7");
            assertThat(result.getStatus()).isEqualTo("FORMING");

            ArgumentCaptor<FormationCreateRequest> sent =
                    ArgumentCaptor.forClass(FormationCreateRequest.class);
            verify(service).create(sent.capture());
            FormationCreateRequest req = sent.getValue();
            assertThat(req.members).containsExactlyInAnyOrder(101, 102, 103);
            assertThat(req.shape).isEqualTo(FormationGeometry.Shape.CIRCLE);
            assertThat(req.spacing).isEqualTo(8.0);
            assertThat(req.heading).isEqualTo(90.0);
            assertThat(req.refLat).isEqualTo(30.25);
            assertThat(req.refAlt).isEqualTo(120.0);
            assertThat(req.leaderSysid).isEqualTo(101);
        }

        @Test
        @DisplayName("startTask 下发 takeoff 命令（缺省高度 10m）并回读编队状态")
        void startSendsTakeoff() {
            when(service.formation(7)).thenReturn(formation(7, Formation.FormationState.STABLE));

            ModuleResult result = adapter().startTask("7");

            assertThat(result.isSuccess()).isTrue();
            assertThat(result.getStatus()).isEqualTo("STABLE");

            ArgumentCaptor<FormationService.FormationCommand> cmd =
                    ArgumentCaptor.forClass(FormationService.FormationCommand.class);
            verify(service).command(eq(7), cmd.capture());
            assertThat(cmd.getValue().type).isEqualTo(FormationService.FormationCommand.Type.TAKEOFF);
            assertThat(cmd.getValue().alt).isEqualTo(10.0);
        }

        @Test
        @DisplayName("abortTask 下发的是 dissolve——编队没有「暂停」这档语义")
        void abortSendsDissolve() {
            when(service.formation(7)).thenReturn(formation(7, Formation.FormationState.DISSOLVED));

            ModuleResult result = adapter().abortTask("7");

            assertThat(result.isSuccess()).isTrue();
            assertThat(result.getStatus()).isEqualTo("DISSOLVED");

            ArgumentCaptor<FormationService.FormationCommand> cmd =
                    ArgumentCaptor.forClass(FormationService.FormationCommand.class);
            verify(service).command(eq(7), cmd.capture());
            assertThat(cmd.getValue().type).isEqualTo(FormationService.FormationCommand.Type.DISSOLVE);
        }

        @Test
        @DisplayName("编队不存在、或单号不是数字：一律 fail 且不抛出")
        void badTaskIdFailsWithoutRaising() {
            when(service.formation(999)).thenReturn(null);

            assertThat(adapter().getTaskStatus("999").isSuccess()).isFalse();

            ModuleResult notNumeric = adapter().getTaskStatus("step-3");
            assertThat(notNumeric.isSuccess()).isFalse();
            assertThat(notNumeric.getTaskId()).isEqualTo("step-3");
        }
    }

    @Nested
    @DisplayName("MAPPING：MappingController 翻译，含嵌套区域定义")
    class MappingContract {

        @Mock
        private MappingController controller;

        private MappingAdapter adapter() {
            return new MappingAdapter(controller);
        }

        @Test
        @DisplayName("createTask 翻译测绘参数与多边形顶点，单号来自上游返回的 id")
        void createTranslatesRequestIncludingArea() {
            when(controller.createTask(any(MappingController.CreateTaskRequest.class)))
                    .thenReturn(Map.of("id", "MT-1", "status", "PENDING"));

            ModuleResult result = adapter().createTask(Map.of(
                    "name", "河道巡查",
                    "type", "DEM",
                    "sysid", 231,
                    "altitudeM", 120.0,
                    "overlapPct", 75.0,
                    "sidelapPct", 55.0,
                    "cameraAngleDeg", -45.0,
                    "area", Map.of(
                            "type", "polygon",
                            "points", List.of(
                                    Map.of("lat", 30.20, "lon", 120.10),
                                    Map.of("lat", 30.20, "lon", 120.14)))));

            assertThat(result.isSuccess()).isTrue();
            assertThat(result.getTaskId()).isEqualTo("MT-1");
            assertThat(result.getStatus()).isEqualTo("PENDING");

            ArgumentCaptor<MappingController.CreateTaskRequest> sent =
                    ArgumentCaptor.forClass(MappingController.CreateTaskRequest.class);
            verify(controller).createTask(sent.capture());
            MappingController.CreateTaskRequest req = sent.getValue();
            assertThat(req.name).isEqualTo("河道巡查");
            assertThat(req.type).isEqualTo("DEM");
            assertThat(req.sysid).isEqualTo(231);
            assertThat(req.altitudeM).isEqualTo(120.0);
            assertThat(req.overlapPct).isEqualTo(75.0);
            assertThat(req.cameraAngleDeg).isEqualTo(-45.0);
            assertThat(req.area.type).isEqualTo("polygon");
            assertThat(req.area.points).hasSize(2);
            assertThat(req.area.points.get(1)).containsExactly(30.20, 120.14);
        }

        @Test
        @DisplayName("上游抛异常时不越界抛出，失败原因原样带回")
        void createFailureDoesNotEscape() {
            when(controller.createTask(any(MappingController.CreateTaskRequest.class)))
                    .thenThrow(new IllegalStateException("区域顶点少于 3 个"));

            ModuleResult result = adapter().createTask(Map.of("name", "坏区域"));

            assertThat(result.isSuccess()).isFalse();
            assertThat(result.getErrorMessage()).isEqualTo("区域顶点少于 3 个");
        }

        @Test
        @DisplayName("上游返回 null 时 fail 且不抛出（生产里未知单号是 requireTask 抛 404，两者都得落在边界内）")
        void unknownTaskFailsWithoutRaising() {
            when(controller.getTask("MT-404")).thenReturn(null);

            ModuleResult result = adapter().getTaskStatus("MT-404");

            assertThat(result.isSuccess()).isFalse();
            assertThat(result.getTaskId()).isEqualTo("MT-404");
        }
    }

    @Nested
    @DisplayName("SHOW：ShowController 翻译")
    class ShowContract {

        @Mock
        private ShowController controller;

        private ShowAdapter adapter() {
            return new ShowAdapter(controller);
        }

        @Test
        @DisplayName("createTask 翻译队形号与参与机列表，缺省 60s/50m")
        void createTranslatesRequest() {
            when(controller.createTask(any(ShowController.CreateTaskRequest.class)))
                    .thenReturn(Map.of("id", "ST-1", "status", "CREATED"));

            ModuleResult result = adapter().createTask(Map.of(
                    "name", "国庆灯光秀",
                    "formationId", "F-7",
                    "droneSysids", List.of(101, 102, 103),
                    "centerLat", 30.25, "centerLon", 120.15));

            assertThat(result.isSuccess()).isTrue();
            assertThat(result.getTaskId()).isEqualTo("ST-1");
            assertThat(result.getStatus()).isEqualTo("CREATED");

            ArgumentCaptor<ShowController.CreateTaskRequest> sent =
                    ArgumentCaptor.forClass(ShowController.CreateTaskRequest.class);
            verify(controller).createTask(sent.capture());
            ShowController.CreateTaskRequest req = sent.getValue();
            assertThat(req.name).isEqualTo("国庆灯光秀");
            assertThat(req.formationId).isEqualTo("F-7");
            assertThat(req.droneSysids).containsExactly(101, 102, 103);
            assertThat(req.durationSec).isEqualTo(60);
            assertThat(req.altitudeM).isEqualTo(50.0);
            assertThat(req.centerLat).isEqualTo(30.25);
        }

        @Test
        @DisplayName("start/abort 把上游返回体的 status 透传成对外状态串")
        void startAndAbortPassThroughStatus() {
            when(controller.startTask("ST-1")).thenReturn(Map.of("status", "PERFORMING"));
            when(controller.abortTask("ST-1")).thenReturn(Map.of("status", "ABORTED"));

            assertThat(adapter().startTask("ST-1").getStatus()).isEqualTo("PERFORMING");
            assertThat(adapter().abortTask("ST-1").getStatus()).isEqualTo("ABORTED");
        }

        @Test
        @DisplayName("上游抛异常时不越界抛出，失败原因原样带回")
        void failureDoesNotEscape() {
            when(controller.startTask("ST-404"))
                    .thenThrow(new IllegalStateException("表演任务不存在"));

            ModuleResult result = adapter().startTask("ST-404");

            assertThat(result.isSuccess()).isFalse();
            assertThat(result.getErrorMessage()).isEqualTo("表演任务不存在");
        }
    }

    @Nested
    @DisplayName("EMERGENCY：应急的「创建即启动」不对称语义")
    class EmergencyContract {

        @Mock
        private EmergencyOrchService service;

        private EmergencyAdapter adapter() {
            return new EmergencyAdapter(service);
        }

        @Test
        @DisplayName("createTask 直接调 start()——应急计划没有「先建后启」两步")
        void createStartsImmediately() {
            when(service.start(anyInt(), anyInt(), anyInt(), anyInt(), any())).thenReturn(42L);
            when(service.getPlan(42L)).thenReturn(Map.of("status", "ACTIVE"));

            ModuleResult result = adapter().createTask(Map.of(
                    "scenarioType", 1,
                    "centerLat", 3025, "centerLon", 12015,
                    "radius", 2000,
                    "droneIds", List.of(101, 102)));

            assertThat(result.isSuccess()).isTrue();
            assertThat(result.getTaskId()).isEqualTo("42");
            assertThat(result.getStatus()).isEqualTo("ACTIVE");
            verify(service, times(1)).start(eq(1), eq(3025), eq(12015), eq(2000),
                    eq(List.of(101, 102)));
        }

        @Test
        @DisplayName("startTask 只回读状态，不再触发第二次 start()")
        void startTaskDoesNotStartAgain() {
            when(service.getPlan(42L)).thenReturn(Map.of("status", "ACTIVE"));

            ModuleResult result = adapter().startTask("42");

            assertThat(result.isSuccess()).isTrue();
            verify(service, never()).start(anyInt(), anyInt(), anyInt(), anyInt(), any());
        }

        @Test
        @DisplayName("abort 返回 false 时是失败而不是「当作已中止」")
        void abortReturnsFalseIsFailure() {
            when(service.abort(42L)).thenReturn(false);

            ModuleResult result = adapter().abortTask("42");

            assertThat(result.isSuccess()).isFalse();
            assertThat(result.getErrorMessage()).contains("already aborted");
        }

        @Test
        @DisplayName("计划不存在或单号不是数字：fail 且不抛出")
        void badTaskIdFailsWithoutRaising() {
            when(service.getPlan(999L)).thenReturn(null);

            assertThat(adapter().getTaskStatus("999").isSuccess()).isFalse();

            ModuleResult notNumeric = adapter().startTask("step-9");
            assertThat(notNumeric.isSuccess()).isFalse();
            assertThat(notNumeric.getTaskId()).isEqualTo("step-9");
            verify(service, never()).start(anyInt(), anyInt(), anyInt(), anyInt(), any());
        }
    }
}
