package io.aerofleet.cloud.regulator;

import io.aerofleet.cloud.regulator.model.ComplianceState;
import io.aerofleet.cloud.regulator.model.ComplianceStatus;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 合规状态持久化单测（V32；审计 §2.1 第 7 条，本批最高优先）。
 * <p>
 * 核心断言是那句审计结论的可执行版：**重启后已激活设备不得回到 UNVERIFIED**。
 * 另钉：三个 last* 时间戳按语义落点写入（此前声明了却从未写入）、remove 真删行、
 * 落库异常不炸状态机主链、无仓库纯内存语义不变。
 */
@DisplayName("ComplianceStateManager 持久化模式")
class ComplianceStatePersistenceTest {

    private ComplianceStateManager manager;
    private ComplianceStateRepository repository;

    @BeforeEach
    void setUp() {
        manager = new ComplianceStateManager();
        repository = mock(ComplianceStateRepository.class);
        when(repository.findAll()).thenReturn(List.of());
        ReflectionTestUtils.setField(manager, "repository", repository);
    }

    @Test
    @DisplayName("重启语义：水合后已激活设备仍是 ACTIVATED（不回 UNVERIFIED）")
    void hydrationKeepsActivatedState() {
        ComplianceStatus activated = new ComplianceStatus(7, "SN-777", ComplianceState.ACTIVATED,
                "act-1", null, 111L, 222L, 0);
        when(repository.findAll()).thenReturn(List.of(ComplianceStateEntity.fromStatus(activated)));

        manager.hydrateFromRepository();

        ComplianceStatus restored = manager.get(7);
        assertThat(restored).isNotNull();
        assertThat(restored.status()).isEqualTo(ComplianceState.ACTIVATED);
        assertThat(restored.productSerialNo()).isEqualTo("SN-777");
        assertThat(restored.activationId()).isEqualTo("act-1");
    }

    @Test
    @DisplayName("写穿：transition 每次落库（整行覆写）且字段与返回值一致")
    void transitionsWriteThrough() {
        manager.transition(7, ComplianceState.UNVERIFIED, "SN-1");
        manager.transition(7, ComplianceState.VERIFIED);

        ArgumentCaptor<ComplianceStateEntity> captor = ArgumentCaptor.forClass(ComplianceStateEntity.class);
        verify(repository, org.mockito.Mockito.times(2)).save(captor.capture());
        List<ComplianceStateEntity> saved = captor.getAllValues();
        assertThat(saved.get(0).toStatus().status()).isEqualTo(ComplianceState.UNVERIFIED);
        assertThat(saved.get(1).toStatus().status()).isEqualTo(ComplianceState.VERIFIED);
        assertThat(saved.get(1).toStatus().productSerialNo()).isEqualTo("SN-1");
    }

    @Test
    @DisplayName("last* 时间戳按语义落点写入（此前声明了却从未写入的修复）")
    void timestampsPopulatedOnSemanticTransitions() {
        long before = System.currentTimeMillis();
        manager.transition(9, ComplianceState.UNVERIFIED);
        ComplianceStatus verified = manager.transition(9, ComplianceState.VERIFIED);
        ComplianceStatus activated = manager.transition(9, ComplianceState.ACTIVATED);

        assertThat(verified.lastVerifyTime()).isGreaterThanOrEqualTo(before);
        assertThat(activated.lastActivationTime()).isGreaterThanOrEqualTo(before);
        // 未发生的时刻保持 0，不做假填充
        assertThat(activated.lastTelemetryReportTime()).isZero();
        assertThat(activated.lastVerifyTime()).isEqualTo(verified.lastVerifyTime());
    }

    @Test
    @DisplayName("remove 真删行（管理员清理语义；CANCELLED 是通过 transition 留行）")
    void removeDeletesRow() {
        manager.transition(5, ComplianceState.UNVERIFIED);
        manager.remove(5);

        verify(repository).deleteById(5);
        assertThat(manager.get(5)).isNull();
    }

    @Test
    @DisplayName("落库异常不炸状态机主链：transition 照常返回、内存状态已更新")
    void repositoryFailureDoesNotBreakStateMachine() {
        when(repository.save(any(ComplianceStateEntity.class)))
                .thenThrow(new RuntimeException("db down"));

        ComplianceStatus result = manager.transition(3, ComplianceState.UNVERIFIED);

        assertThat(result.status()).isEqualTo(ComplianceState.UNVERIFIED);
        assertThat(manager.get(3)).isNotNull();
    }

    @Test
    @DisplayName("无仓库（纯单测构造）：零仓库交互、语义与持久化前一致")
    void memoryModeUnchanged() {
        ReflectionTestUtils.setField(manager, "repository", null);
        manager.transition(1, ComplianceState.UNVERIFIED);

        assertThat(manager.get(1)).isNotNull();
        verify(repository, never()).save(any());
    }
}
