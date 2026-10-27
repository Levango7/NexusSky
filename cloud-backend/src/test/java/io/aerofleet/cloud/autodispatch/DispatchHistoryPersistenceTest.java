package io.aerofleet.cloud.autodispatch;

import io.aerofleet.cloud.gateway.DeviceRegistry;
import io.aerofleet.cloud.gateway.DroneSnapshot;
import io.aerofleet.cloud.mission.common.DroneCommandService;
import io.aerofleet.cloud.mission.common.MissionUploadResult;
import io.aerofleet.cloud.tracking.FlightTrackStore;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.data.domain.PageRequest;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 出警历史持久化单测（V31；审计 §2.1 第 4 条）。
 * <p>
 * 走**真实 dispatch 主路径**（mock 命令面）验证写穿，而不是直接调私有方法——
 * 写穿必须挂在真实出警上，否则又是"测了旁路没测主链"。另钉三条：
 * 启动恢复顺序、库与内存同口径裁剪（最新 500）、落库异常不炸出警主流程。
 */
@DisplayName("AutoDispatchService 出警历史持久化")
class DispatchHistoryPersistenceTest {

    private DeviceRegistry registry;
    private FlightTrackStore trackStore;
    private AutoDispatchService service;
    private DispatchHistoryRepository repository;

    @BeforeEach
    void setUp() {
        registry = new DeviceRegistry();
        trackStore = new FlightTrackStore();
        AutoDispatchConfig config = new AutoDispatchConfig();
        config.setEnabled(true);
        service = new AutoDispatchService(registry, trackStore, config);
        DroneCommandService commandService = mock(DroneCommandService.class);
        when(commandService.uploadMission(anyInt(), anyList()))
                .thenReturn(MissionUploadResult.ok(4));
        ReflectionTestUtils.setField(service, "commandService", commandService);
        repository = mock(DispatchHistoryRepository.class);
        when(repository.count()).thenReturn(1L);
        ReflectionTestUtils.setField(service, "historyRepository", repository);
    }

    private void registerDrone(int sysid, double lat, double lon, int batteryPct) {
        DroneSnapshot drone = registry.registerIfAbsent(sysid);
        drone.online = true;
        drone.lat = lat;
        drone.lon = lon;
        drone.battery = batteryPct;
        drone.armed = false;
    }

    private static DispatchRecordEntity entity(String id, long triggerTime) {
        DispatchRecord r = new DispatchRecord(id, "alarm-" + id, triggerTime, 39.9, 116.3,
                "SUCCESS", List.of(new DispatchResult.DispatchedDrone(1, true, 30)));
        return DispatchRecordEntity.fromRecord(r);
    }

    @Test
    @DisplayName("真实出警会写穿到仓库：save 的实体逐字段等于本次派遣（含 drones 快照）")
    void dispatchWritesThroughToRepository() {
        registerDrone(1, 39.901, 116.301, 90);

        DispatchResult result = service.dispatchDrone(39.9, 116.3, "alarm-1", 1);
        assertThat(result.getStatus()).isEqualTo(DispatchResult.Status.SUCCESS);

        ArgumentCaptor<DispatchRecordEntity> captor = ArgumentCaptor.forClass(DispatchRecordEntity.class);
        verify(repository).save(captor.capture());
        DispatchRecord persisted = captor.getValue().toRecord();
        assertThat(persisted.getAlarmId()).isEqualTo("alarm-1");
        assertThat(persisted.getStatus()).isEqualTo("SUCCESS");
        assertThat(persisted.getDispatchedDrones()).hasSize(1);
        assertThat(persisted.getDispatchedDrones().get(0).getSysid()).isEqualTo(1);
    }

    @Test
    @DisplayName("启动恢复：库中最新在前 → 内存队列顺序一致（getHistory 最新在前）")
    void restoreOrdersNewestFirst() {
        when(repository.findAllByOrderByTriggerTimeDescDispatchIdDesc(
                PageRequest.of(0, 500)))
                .thenReturn(List.of(entity("new", 2000L), entity("old", 1000L)));

        service.restoreHistoryFromRepository();

        List<DispatchRecord> history = service.getHistory(10);
        assertThat(history).extracting(DispatchRecord::getDispatchId)
                .containsExactly("new", "old");
    }

    @Test
    @DisplayName("同口径裁剪：库超 500 ⇒ 删掉最旧的超出部分（不发明新留存策略）")
    void pruneKeepsLatest500() {
        when(repository.count()).thenReturn(501L);
        when(repository.findAllByOrderByTriggerTimeAscDispatchIdAsc(PageRequest.of(0, 1)))
                .thenReturn(List.of(entity("oldest", 1L)));
        registerDrone(1, 39.901, 116.301, 90);

        service.dispatchDrone(39.9, 116.3, "alarm-1", 1);

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<DispatchRecordEntity>> captor = ArgumentCaptor.forClass(List.class);
        verify(repository).deleteAllInBatch(captor.capture());
        assertThat(captor.getValue()).hasSize(1);
        assertThat(captor.getValue().get(0).getDispatchId()).isEqualTo("oldest");
    }

    @Test
    @DisplayName("落库异常不影响出警主流程：result 照常 SUCCESS、内存历史仍在")
    void repositoryFailureDoesNotBreakDispatch() {
        when(repository.save(any(DispatchRecordEntity.class)))
                .thenThrow(new RuntimeException("db down"));
        registerDrone(1, 39.901, 116.301, 90);

        DispatchResult result = service.dispatchDrone(39.9, 116.3, "alarm-1", 1);

        assertThat(result.getStatus()).isEqualTo(DispatchResult.Status.SUCCESS);
        assertThat(service.getHistory(10)).hasSize(1);
    }

    @Test
    @DisplayName("无仓库（纯单测构造）：行为与持久化前一致，零仓库交互")
    void memoryModeUnchanged() {
        ReflectionTestUtils.setField(service, "historyRepository", null);
        registerDrone(1, 39.901, 116.301, 90);

        service.dispatchDrone(39.9, 116.3, "alarm-1", 1);

        assertThat(service.getHistory(10)).hasSize(1);
        verify(repository, never()).save(any());
    }

    @Test
    @DisplayName("恢复时坏 JSON 不炸历史读取（降级空 drones 列表）")
    void corruptJsonDegradesGracefully() {
        DispatchRecordEntity bad = entity("bad", 3000L);
        ReflectionTestUtils.setField(bad, "dispatchedDronesJson", "{not json");
        when(repository.findAllByOrderByTriggerTimeDescDispatchIdDesc(PageRequest.of(0, 500)))
                .thenReturn(List.of(bad));

        service.restoreHistoryFromRepository();

        List<DispatchRecord> history = service.getHistory(10);
        assertThat(history).hasSize(1);
        assertThat(history.get(0).getDispatchedDrones()).isEmpty();
    }
}
