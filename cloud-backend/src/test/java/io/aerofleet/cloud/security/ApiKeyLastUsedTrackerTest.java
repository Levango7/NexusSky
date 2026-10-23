package io.aerofleet.cloud.security;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.Instant;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * {@link ApiKeyLastUsedTracker} 单元测试。
 * <p>
 * 钉住的语义：同一 Key 多次使用只刷一条（取最新时间）、不同 Key 各刷各的、
 * flush 后 pending 清空（不丢并发新记录的口径靠逐条移除）、无仓库时安全 no-op、
 * 刷库异常被吞（调度器不能死）。
 */
@DisplayName("ApiKeyLastUsedTracker lastUsedAt 合并写")
class ApiKeyLastUsedTrackerTest {

    @Test
    @DisplayName("同一 Key 多次 record 合并为一次 UPDATE，时间取最新")
    void mergesRecordsPerKey() {
        ApiKeyRepository repository = mock(ApiKeyRepository.class);
        when(repository.updateLastUsedAt(anyString(), org.mockito.ArgumentMatchers.any()))
                .thenReturn(1);
        ApiKeyLastUsedTracker tracker = new ApiKeyLastUsedTracker(repository);
        try {
            tracker.record("nsk_1_aaaa");
            tracker.record("nsk_1_aaaa");
            tracker.record("nsk_1_aaaa");

            tracker.flush();

            ArgumentCaptor<Instant> instantCaptor = ArgumentCaptor.forClass(Instant.class);
            verify(repository, times(1)).updateLastUsedAt(org.mockito.ArgumentMatchers.eq("nsk_1_aaaa"),
                    instantCaptor.capture());
            // 刷的是「最新一次使用」：不早于 record 时刻
            assertThat(instantCaptor.getValue()).isAfter(Instant.now().minusSeconds(60));
            assertThat(tracker.pendingSize()).isZero();
        } finally {
            tracker.shutdown();
        }
    }

    @Test
    @DisplayName("不同 Key 各自刷写，互不覆盖")
    void flushesEachKeyIndependently() {
        ApiKeyRepository repository = mock(ApiKeyRepository.class);
        when(repository.updateLastUsedAt(anyString(), org.mockito.ArgumentMatchers.any()))
                .thenReturn(1);
        ApiKeyLastUsedTracker tracker = new ApiKeyLastUsedTracker(repository);
        try {
            tracker.record("nsk_1_aaaa");
            tracker.record("nsk_2_bbbb");

            tracker.flush();

            verify(repository).updateLastUsedAt(org.mockito.ArgumentMatchers.eq("nsk_1_aaaa"),
                    org.mockito.ArgumentMatchers.any());
            verify(repository).updateLastUsedAt(org.mockito.ArgumentMatchers.eq("nsk_2_bbbb"),
                    org.mockito.ArgumentMatchers.any());
            assertThat(tracker.pendingSize()).isZero();
        } finally {
            tracker.shutdown();
        }
    }

    @Test
    @DisplayName("record(null) 静默忽略")
    void ignoresNullKeyId() {
        ApiKeyLastUsedTracker tracker = new ApiKeyLastUsedTracker(mock(ApiKeyRepository.class));
        try {
            assertThatCode(() -> tracker.record(null)).doesNotThrowAnyException();
            assertThat(tracker.pendingSize()).isZero();
        } finally {
            tracker.shutdown();
        }
    }

    @Test
    @DisplayName("无 ApiKeyRepository（内存模式）时 flush 是安全 no-op")
    void flushWithoutRepositoryIsNoop() {
        ApiKeyLastUsedTracker tracker = new ApiKeyLastUsedTracker(null);
        try {
            tracker.record("nsk_1_aaaa");
            assertThatCode(tracker::flush).doesNotThrowAnyException();
        } finally {
            tracker.shutdown();
        }
    }

    @Test
    @DisplayName("单条刷库异常只丢本条，其余 Key 照常刷")
    void swallowsPerKeyFailure() {
        ApiKeyRepository repository = mock(ApiKeyRepository.class);
        when(repository.updateLastUsedAt(org.mockito.ArgumentMatchers.eq("bad"), org.mockito.ArgumentMatchers.any()))
                .thenThrow(new RuntimeException("db down"));
        when(repository.updateLastUsedAt(org.mockito.ArgumentMatchers.eq("good"), org.mockito.ArgumentMatchers.any()))
                .thenReturn(1);
        ApiKeyLastUsedTracker tracker = new ApiKeyLastUsedTracker(repository);
        try {
            tracker.record("bad");
            tracker.record("good");

            assertThatCode(tracker::flush).doesNotThrowAnyException();

            verify(repository).updateLastUsedAt(org.mockito.ArgumentMatchers.eq("good"),
                    org.mockito.ArgumentMatchers.any());
        } finally {
            tracker.shutdown();
        }
    }

    @Test
    @DisplayName("flush 调度线程存在且是守护线程（工厂坏了只会静默不启动）")
    void flusherThreadIsDaemonAndStarted() {
        java.util.HashSet<Thread> before =
                new java.util.HashSet<>(Thread.getAllStackTraces().keySet());
        ApiKeyLastUsedTracker tracker = new ApiKeyLastUsedTracker(mock(ApiKeyRepository.class));
        try {
            Thread flusher = Thread.getAllStackTraces().keySet().stream()
                    .filter(t -> !before.contains(t) && "api-key-lastused-flusher".equals(t.getName()))
                    .findFirst()
                    .orElse(null);
            assertThat(flusher)
                    .as("构造后调度线程必须存在：ThreadFactory 返回 null 时 ThreadPoolExecutor "
                            + "静默放弃启动（不抛错），而所有现有测试都直调 flush()，谁都不撞这个坑")
                    .isNotNull();
            assertThat(flusher.isDaemon())
                    .as("非守护 flush 线程会挂住测试 JVM 退出——守护属性不是装饰")
                    .isTrue();
        } finally {
            tracker.shutdown();
        }
    }

    @Test
    @DisplayName("两次 record 后 flush：UPDATE 必须被调用（merge 重映射删键会静默丢记录）")
    void doubleRecordStillFlushes() {
        ApiKeyRepository repository = mock(ApiKeyRepository.class);
        when(repository.updateLastUsedAt(org.mockito.ArgumentMatchers.eq("nsk_1_aaaa"),
                org.mockito.ArgumentMatchers.any())).thenReturn(1);
        ApiKeyLastUsedTracker tracker = new ApiKeyLastUsedTracker(repository);
        try {
            tracker.record("nsk_1_aaaa");
            tracker.record("nsk_1_aaaa");
            // 注意：三次 record 的同键用例证明不了这个契约——第三次会重新插入，
            // 恰好把"第二次 merge 返回 null 删掉整条"的变异体伪装成活。两次才现形。
            tracker.flush();

            verify(repository).updateLastUsedAt(org.mockito.ArgumentMatchers.eq("nsk_1_aaaa"),
                    org.mockito.ArgumentMatchers.any());
        } finally {
            tracker.shutdown();
        }
    }

    @Test
    @DisplayName("同一 Key 多次 record：刷库时间取最新一次（remap 取反会把旧时间刷进库）")
    void flushUsesLatestRecordTime() throws Exception {
        ApiKeyRepository repository = mock(ApiKeyRepository.class);
        when(repository.updateLastUsedAt(anyString(), org.mockito.ArgumentMatchers.any()))
                .thenReturn(1);
        ApiKeyLastUsedTracker tracker = new ApiKeyLastUsedTracker(repository);
        try {
            tracker.record("nsk_1_aaaa");
            Thread.sleep(20); // 保证第二次的时间戳严格晚于第一次（时钟粒度兜底）
            Instant second = Instant.now();
            tracker.record("nsk_1_aaaa");

            tracker.flush();

            ArgumentCaptor<Instant> captor = ArgumentCaptor.forClass(Instant.class);
            verify(repository).updateLastUsedAt(org.mockito.ArgumentMatchers.eq("nsk_1_aaaa"),
                    captor.capture());
            assertThat(captor.getValue())
                    .as("刷库时间必须是最新一次 record：remap 取反（保留旧值）下，刷的是第一次的时刻")
                    .isAfterOrEqualTo(second);
        } finally {
            tracker.shutdown();
        }
    }

    @Test
    @DisplayName("record 后 pendingSize 计数非零（此前只断言过空方向）")
    void pendingSizeCountsPending() {
        ApiKeyLastUsedTracker tracker = new ApiKeyLastUsedTracker(mock(ApiKeyRepository.class));
        try {
            tracker.record("nsk_1_aaaa");
            assertThat(tracker.pendingSize()).isEqualTo(1);
        } finally {
            tracker.shutdown();
        }
    }

    @Test
    @DisplayName("shutdown 前把 pending 刷库（退出兜底）")
    void shutdownFlushesPending() {
        ApiKeyRepository repository = mock(ApiKeyRepository.class);
        when(repository.updateLastUsedAt(anyString(), org.mockito.ArgumentMatchers.any()))
                .thenReturn(1);
        ApiKeyLastUsedTracker tracker = new ApiKeyLastUsedTracker(repository);

        tracker.record("nsk_1_aaaa");
        tracker.shutdown();

        verify(repository).updateLastUsedAt(org.mockito.ArgumentMatchers.eq("nsk_1_aaaa"),
                org.mockito.ArgumentMatchers.any());
        assertThat(tracker.pendingSize()).isZero();
        assertThat(Map.of()).isEmpty();
    }
}
