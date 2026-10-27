package io.aerofleet.cloud.api.service;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 应急编排计划落库单测（V37；P1#8 裁决：新表独立落库）。
 * <p>
 * 核心断言：① start 写穿（快照含 phases/events 嵌套结构）；② abort 更新写穿；
 * ③ 重启读穿重建（修掉"planId 失效、进度不可查"）；④ 无仓库纯内存语义不变。
 */
@DisplayName("应急编排计划落库（V37）")
class EmergencyPlanPersistenceTest {

    private EmergencyOrchService newService(EmergencyPlanRepository repo) {
        EmergencyOrchService svc = new EmergencyOrchService(null, mock(ApplicationEventPublisher.class));
        ReflectionTestUtils.setField(svc, "planRepository", repo);
        return svc;
    }

    @Test
    @DisplayName("start 写穿：快照 JSON 含嵌套 phases/events，status/tenant 提列可查")
    void startWritesThroughSnapshot() throws Exception {
        EmergencyPlanRepository repo = mock(EmergencyPlanRepository.class);
        when(repo.findById(org.mockito.ArgumentMatchers.anyLong())).thenReturn(Optional.empty());
        EmergencyOrchService svc = newService(repo);

        long planId = svc.start(1, 399000000, 1163000000, 500, List.of(1, 2));

        ArgumentCaptor<EmergencyPlanEntity> captor = ArgumentCaptor.forClass(EmergencyPlanEntity.class);
        verify(repo).save(captor.capture());
        EmergencyPlanEntity saved = captor.getValue();
        assertThat(saved.getPlanId()).isEqualTo(planId);
        assertThat(saved.getSnapshot()).contains("phases").contains("events");
    }

    @Test
    @DisplayName("abort 写穿：status 列更新为 ABORTED")
    void abortUpdatesStatusColumn() {
        EmergencyPlanRepository repo = mock(EmergencyPlanRepository.class);
        when(repo.findById(org.mockito.ArgumentMatchers.anyLong())).thenReturn(Optional.empty());
        EmergencyOrchService svc = newService(repo);
        long planId = svc.start(1, 0, 0, 0, List.of());

        svc.abort(planId);

        ArgumentCaptor<EmergencyPlanEntity> captor = ArgumentCaptor.forClass(EmergencyPlanEntity.class);
        verify(repo, org.mockito.Mockito.atLeast(2)).save(captor.capture());
        assertThat(captor.getValue().getStatus()).isEqualTo("ABORTED");
    }

    @Test
    @DisplayName("重启读穿：内存空 + 库有快照 ⇒ getPlan 重建（嵌套结构无损）")
    void readThroughRebuildsPlan() throws Exception {
        EmergencyPlanRepository repo = mock(EmergencyPlanRepository.class);
        when(repo.findById(org.mockito.ArgumentMatchers.anyLong())).thenReturn(Optional.empty());
        EmergencyOrchService writer = newService(repo);
        long planId = writer.start(1, 399000000, 1163000000, 500, List.of(7));

        ArgumentCaptor<EmergencyPlanEntity> captor = ArgumentCaptor.forClass(EmergencyPlanEntity.class);
        verify(repo).save(captor.capture());
        EmergencyPlanEntity persisted = captor.getValue();

        // "重启"：新实例（内存空），同一份库内容
        EmergencyPlanRepository repo2 = mock(EmergencyPlanRepository.class);
        when(repo2.findById(planId)).thenReturn(Optional.of(persisted));
        EmergencyOrchService afterRestart = newService(repo2);

        Map<String, Object> recovered = afterRestart.getPlan(planId);

        assertThat(recovered).isNotNull();
        assertThat(recovered.get("planId")).isEqualTo((int) planId);
        assertThat((List<?>) recovered.get("phases")).isNotEmpty();
        assertThat((List<?>) recovered.get("events")).isNotEmpty();
    }

    @Test
    @DisplayName("无仓库（纯单测构造）：零仓库交互、语义与持久化前一致")
    void memoryModeUnchanged() {
        EmergencyPlanRepository repo = mock(EmergencyPlanRepository.class);
        EmergencyOrchService svc = newService(null);

        long planId = svc.start(1, 0, 0, 0, List.of());
        assertThat(svc.getPlan(planId)).isNotNull();
        verify(repo, never()).save(any());
    }
}
