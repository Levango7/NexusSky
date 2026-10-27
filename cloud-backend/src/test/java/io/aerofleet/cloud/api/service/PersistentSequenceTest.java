package io.aerofleet.cloud.api.service;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 单调序列发号器单测（V35；§5 Q4/Q5 裁决：持久化续号）。
 * <p>
 * 核心断言是可执行版的裁决：「重启后不复用」。另钉：基准语义（首个号=base+1，
 * 与两个消费方原内存语义逐位等价）、库值更大时以库为准、落库失败降级不阻塞、
 * 无仓库返回 null 让调用方走内存路径。
 */
@DisplayName("PersistentSequence 持久化续号")
class PersistentSequenceTest {

    private PersistentSequence newSequence(SequenceCounterRepository repo) {
        PersistentSequence seq = new PersistentSequence();
        ReflectionTestUtils.setField(seq, "repository", repo);
        return seq;
    }

    @Test
    @DisplayName("基准语义：首个号 = base+1，与原内存语义逐位等价；逐发写穿")
    void firstIssuedEqualsBasePlusOne() {
        SequenceCounterRepository repo = mock(SequenceCounterRepository.class);
        when(repo.findById("dock_tid")).thenReturn(Optional.empty());
        PersistentSequence seq = newSequence(repo);

        assertThat(seq.next("dock_tid", 0)).isEqualTo(1L);
        assertThat(seq.next("dock_tid", 0)).isEqualTo(2L);
        assertThat(seq.next("orch_plan_id", 10000)).isEqualTo(10001L);
        verify(repo, org.mockito.Mockito.atLeast(3)).save(any(SequenceCounterEntity.class));
    }

    @Test
    @DisplayName("重启续号：库中最后发出值更大 ⇒ 从它续起（不复用）")
    void restartContinuesFromPersistedValue() {
        SequenceCounterRepository repo = mock(SequenceCounterRepository.class);
        when(repo.findById("dock_tid")).thenReturn(Optional.of(new SequenceCounterEntity("dock_tid", 57)));

        PersistentSequence afterRestart = newSequence(repo);

        assertThat(afterRestart.next("dock_tid", 0)).isEqualTo(58L);
    }

    @Test
    @DisplayName("库值小于基准（历史遗留）⇒ 以基准为准，不倒退")
    void baseWinsWhenPersistedValueIsSmaller() {
        SequenceCounterRepository repo = mock(SequenceCounterRepository.class);
        when(repo.findById("orch_plan_id")).thenReturn(Optional.of(
                new SequenceCounterEntity("orch_plan_id", 3)));

        assertThat(newSequence(repo).next("orch_plan_id", 10000)).isEqualTo(10001L);
    }

    @Test
    @DisplayName("落库失败降级：号照发（进程内仍单调）、不抛异常")
    void repositoryFailureDoesNotBlockIssuance() {
        SequenceCounterRepository repo = mock(SequenceCounterRepository.class);
        when(repo.findById("dock_tid")).thenReturn(Optional.empty());
        when(repo.save(any(SequenceCounterEntity.class))).thenThrow(new RuntimeException("db down"));

        PersistentSequence seq = newSequence(repo);
        assertThat(seq.next("dock_tid", 0)).isEqualTo(1L);
        assertThat(seq.next("dock_tid", 0)).isEqualTo(2L);
    }

    @Test
    @DisplayName("无仓库：返回 null（调用方走各自内存路径），零仓库交互")
    void noRepositoryYieldsNull() {
        SequenceCounterRepository repo = mock(SequenceCounterRepository.class);
        PersistentSequence seq = newSequence(null);

        assertThat(seq.next("dock_tid", 0)).isNull();
        verify(repo, never()).save(any());
    }
}
