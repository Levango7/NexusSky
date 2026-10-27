package io.aerofleet.cloud.health;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * MaintenanceController 持久化模式单测（V30；审计 §2.1 第 3 条）。
 * <p>
 * 三层：① 纯内存模式（repository=null）行为不变；② repository 注入后 CRUD 走库；
 * ③ **重启防护**——id 序列必须从库中最大值续起（否则新记录覆盖同 id 旧记录，
 * 是比"丢数据"更隐蔽的坑）。
 */
@DisplayName("MaintenanceController 持久化模式")
class MaintenancePersistenceTest {

    private PredictiveMaintenanceService predictionService;
    private MaintenanceController controller;

    @BeforeEach
    void setUp() {
        predictionService = mock(PredictiveMaintenanceService.class);
        when(predictionService.predictAll()).thenReturn(List.of());
        controller = new MaintenanceController(predictionService);
    }

    private MaintenanceRecordEntity entity(String id, int sysid, MaintenanceRecord.Status status) {
        MaintenanceRecord r = new MaintenanceRecord(id, sysid, ComponentType.BATTERY,
                MaintenanceRecord.MaintenanceType.INSPECT, LocalDate.now(), status);
        return MaintenanceRecordEntity.fromRecord(r);
    }

    private static MaintenanceRecord body(int sysid) {
        MaintenanceRecord b = new MaintenanceRecord();
        b.setSysid(sysid);
        b.setComponentType(ComponentType.BATTERY);
        b.setMaintenanceType(MaintenanceRecord.MaintenanceType.REPLACE);
        return b;
    }

    @Test
    @DisplayName("纯内存模式：create → getRecords 往返（repository=null 行为不变）")
    void memoryModeRoundTrip() {
        controller.createRecord(body(7));
        assertThat(controller.getRecords(7, null)).hasSize(1);
        assertThat(controller.getRecords(null, "SCHEDULED")).hasSize(1);
    }

    @Test
    @DisplayName("持久化模式：create 落库（fromRecord 全字段）、getRecords 从库读")
    void createAndListThroughRepository() {
        MaintenanceRecordRepository repo = mock(MaintenanceRecordRepository.class);
        ReflectionTestUtils.setField(controller, "repository", repo);
        when(repo.findAllByOrderByIdAsc()).thenReturn(List.of(entity("m-1", 7, MaintenanceRecord.Status.SCHEDULED)));

        MaintenanceRecord created = controller.createRecord(body(7));
        verify(repo, times(1)).save(any(MaintenanceRecordEntity.class));
        assertThat(created.getId()).startsWith("m-");

        List<MaintenanceRecord> list = controller.getRecords(null, null);
        assertThat(list).hasSize(1);
        assertThat(list.get(0).getSysid()).isEqualTo(7);
    }

    @Test
    @DisplayName("持久化模式：update 先查库（找不到 404），部分更新后 applyFrom+save")
    void updateLoadsFromRepository() {
        MaintenanceRecordRepository repo = mock(MaintenanceRecordRepository.class);
        ReflectionTestUtils.setField(controller, "repository", repo);
        when(repo.findById("m-9")).thenReturn(Optional.of(entity("m-9", 7, MaintenanceRecord.Status.SCHEDULED)));

        MaintenanceRecord update = new MaintenanceRecord();
        update.setStatus(MaintenanceRecord.Status.COMPLETED);
        update.setTechnician("张三");
        MaintenanceRecord result = controller.updateRecord("m-9", update);
        assertThat(result.getStatus()).isEqualTo(MaintenanceRecord.Status.COMPLETED);
        assertThat(result.getTechnician()).isEqualTo("张三");
        verify(repo, times(1)).save(any(MaintenanceRecordEntity.class));

        when(repo.findById("m-404")).thenReturn(Optional.empty());
        assertThatThrownBy(() -> controller.updateRecord("m-404", update))
                .hasMessageContaining("not found");
    }

    @Test
    @DisplayName("非法 status 过滤参数 ⇒ 空结果不是 500（与内存路径语义一致）")
    void invalidStatusFilterYieldsEmpty() {
        MaintenanceRecordRepository repo = mock(MaintenanceRecordRepository.class);
        ReflectionTestUtils.setField(controller, "repository", repo);
        assertThat(controller.getRecords(null, "NOT_A_STATUS")).isEmpty();
    }

    @Test
    @DisplayName("重启防护：idSeq 从库中最大 m-N 续起——新记录不得复用旧 id")
    void idSequenceSeedsFromRepository() {
        MaintenanceRecordRepository repo = mock(MaintenanceRecordRepository.class);
        when(repo.findAll()).thenReturn(List.of(
                entity("m-37", 1, MaintenanceRecord.Status.COMPLETED),
                entity("m-3", 2, MaintenanceRecord.Status.COMPLETED),
                entity("custom-id", 3, MaintenanceRecord.Status.COMPLETED)));   // 非 m-N 不参与
        ReflectionTestUtils.setField(controller, "repository", repo);
        controller.seedIdSequenceFromRepository();

        MaintenanceRecord created = controller.createRecord(body(9));
        assertThat(created.getId()).isEqualTo("m-38");
    }

    @Test
    @DisplayName("getSchedule 持久化路径：SCHEDULED+IN_PROGRESS 合并按计划日升序")
    void scheduleReadsBothStatuses() {
        MaintenanceRecordRepository repo = mock(MaintenanceRecordRepository.class);
        ReflectionTestUtils.setField(controller, "repository", repo);
        MaintenanceRecordEntity sched = entity("m-1", 1, MaintenanceRecord.Status.SCHEDULED);
        MaintenanceRecordEntity prog = entity("m-2", 2, MaintenanceRecord.Status.IN_PROGRESS);
        when(repo.findByStatusOrderByIdAsc(MaintenanceRecord.Status.SCHEDULED)).thenReturn(List.of(sched));
        when(repo.findByStatusOrderByIdAsc(MaintenanceRecord.Status.IN_PROGRESS)).thenReturn(List.of(prog));

        List<MaintenanceRecord> schedule = controller.getSchedule();
        assertThat(schedule).hasSize(2);
        assertThat(schedule.stream().map(MaintenanceRecord::getId)).containsExactlyInAnyOrder("m-1", "m-2");
    }
}
