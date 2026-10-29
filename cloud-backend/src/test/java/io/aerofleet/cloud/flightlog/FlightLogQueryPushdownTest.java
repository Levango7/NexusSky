package io.aerofleet.cloud.flightlog;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.ArgumentCaptor;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;

import java.lang.reflect.Field;
import java.nio.file.Path;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

/**
 * {@link FlightLogService#query} 的"最新 N 条"是否真的下推到数据库。
 * <p>
 * 背景（2026-10-29）：DB 路径此前是四条"整天全量"查询 + 内存 {@code subList} 截尾。
 * {@code flight_log} 每个遥测点一行（1 机 1 天 1Hz ≈ 8.6 万行），单机演示与 CI
 * （{@code persist-to-db} 默认 false，走 JSONL）都碰不到；一旦落库跑上几天，
 * 一次 {@code GET /api/v1/flight-log?limit=200} 就要把整天数据拖进堆。
 * 同文件 JSONL 路径早已用定长环形缓冲解决，这里补齐 DB 路径。
 * <p>
 * 这些用例钉三件事：
 * ① {@code limit>0} ⇒ 只调用带 {@code Pageable} 的 {@code findNewestFirst}，
 *    <b>绝不</b>再碰四条全量查询；
 * ② 租户条件与分页一起下推（{@code null}=全局管理员）；
 * ③ 仓储返回"最新在前"，服务对外仍必须是旧→新（末尾=最新），否则 limit 语义反转。
 */
class FlightLogQueryPushdownTest {

    @TempDir
    Path tmp;

    @AfterEach
    void clearTenant() {
        io.aerofleet.cloud.security.TenantContext.clear();
    }

    private static FlightLogEntity row(double alt) {
        return row(alt, null);
    }

    private static FlightLogEntity row(double alt, Integer tenantId) {
        FlightLogEntity e = new FlightLogEntity();
        e.setTimestamp(Instant.parse("2026-10-29T03:00:00Z"));
        e.setType("telemetry");
        e.setSysid(1);
        e.setRelativeAlt(alt);
        e.setTenantId(tenantId);
        return e;
    }

    private FlightLogService serviceWith(FlightLogRepository repository) throws Exception {
        FlightLogService svc = new FlightLogService(tmp.toString(), 0,
                new com.fasterxml.jackson.databind.ObjectMapper());
        setField(svc, "persistToDb", Boolean.TRUE);
        setField(svc, "flightLogRepository", repository);
        return svc;
    }

    private static void setField(Object target, String name, Object value) throws Exception {
        Field f = FlightLogService.class.getDeclaredField(name);
        f.setAccessible(true);
        if (value instanceof Boolean b && f.getType() == boolean.class) {
            f.setBoolean(target, b);
        } else {
            f.set(target, value);
        }
    }

    @Test
    @DisplayName("limit>0：分页与租户下推给 findNewestFirst，不再全量取回")
    void limitIsPushedDownWithTenant() throws Exception {
        FlightLogRepository repo = mock(FlightLogRepository.class);
        // 仓储按契约返回"最新在前"，服务必须反转成旧→新
        when(repo.findNewestFirst(any(), any(), eq(7), eq("telemetry"), eq(1), any(Pageable.class)))
                .thenReturn(List.of(row(3.0, 7), row(2.0, 7), row(1.0, 7)));

        io.aerofleet.cloud.security.TenantContext.setTenantId(7);
        List<Map<String, Object>> out = serviceWith(repo)
                .query(LocalDate.of(2026, 10, 29), "telemetry", 1, 3);

        assertThat(out).extracting(m -> m.get("relativeAlt"))
                .as("对外仍是旧→新：末尾才是最新")
                .containsExactly(1.0, 2.0, 3.0);

        ArgumentCaptor<Pageable> page = ArgumentCaptor.forClass(Pageable.class);
        verify(repo).findNewestFirst(any(), any(), eq(7), eq("telemetry"), eq(1), page.capture());
        assertThat(page.getValue()).isEqualTo(PageRequest.of(0, 3));

        verify(repo, never()).findByTypeAndSysidAndTimestampBetweenOrderByTimestampAscIdAsc(any(), anyInt(), any(), any());
        verify(repo, never()).findByTypeAndTimestampBetweenOrderByTimestampAscIdAsc(any(), any(), any());
        verify(repo, never()).findBySysidAndTimestampBetweenOrderByTimestampAscIdAsc(anyInt(), any(), any());
        verify(repo, never()).findByTimestampBetweenOrderByTimestampAscIdAsc(any(), any());
    }

    @Test
    @DisplayName("无租户上下文时按全局口径下推（tenantId=null）")
    void globalScopePushesNullTenant() throws Exception {
        FlightLogRepository repo = mock(FlightLogRepository.class);
        when(repo.findNewestFirst(any(), any(), isNull(), isNull(), isNull(), any(Pageable.class)))
                .thenReturn(List.of(row(1.0)));

        List<Map<String, Object>> out = serviceWith(repo)
                .query(LocalDate.of(2026, 10, 29), null, null, 200);

        assertThat(out).hasSize(1);
        verify(repo).findNewestFirst(any(), any(), isNull(), isNull(), isNull(),
                eq(PageRequest.of(0, 200)));
    }

    @Test
    @DisplayName("limit<=0 仍表示「当天全部」，走全量路径且不调用分页查询")
    void nonPositiveLimitKeepsWholeDaySemantics() throws Exception {
        FlightLogRepository repo = mock(FlightLogRepository.class);
        when(repo.findByTypeAndSysidAndTimestampBetweenOrderByTimestampAscIdAsc(
                eq("telemetry"), eq(5), any(), any()))
                .thenReturn(List.of(row(1.0), row(2.0)));

        List<Map<String, Object>> out = serviceWith(repo)
                .query(LocalDate.of(2026, 10, 29), "telemetry", 5, 0);

        assertThat(out).extracting(m -> m.get("relativeAlt")).containsExactly(1.0, 2.0);
        verify(repo).findByTypeAndSysidAndTimestampBetweenOrderByTimestampAscIdAsc(
                eq("telemetry"), eq(5), any(), any());
        verify(repo, never()).findNewestFirst(any(), any(), any(), any(), any(), any());
        verifyNoMoreInteractions(repo);
    }
}
