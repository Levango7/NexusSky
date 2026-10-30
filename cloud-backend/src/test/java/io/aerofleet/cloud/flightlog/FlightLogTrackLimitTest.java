package io.aerofleet.cloud.flightlog;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.aerofleet.cloud.gateway.DroneSnapshot;
import io.aerofleet.cloud.gateway.TrackPoint;
import io.aerofleet.cloud.security.TenantContext;
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
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * {@link FlightLogService#trackFor(LocalDate, int, int)} 的 limit 语义。
 *
 * <p>背景：轨迹端点原先把「某日完整轨迹」当成唯一契约，1 机 1 天 1 Hz 就是 8.6 万行；
 * 主地图只画最新 1500 点（{@code mergeTrack} 截断），剩下的 98% 拉回来即丢。
 * 但法证/遗失无人机搜索又确实需要完整轨迹，所以不能一刀切封顶：
 * {@code limit<=0} 保持全天，{@code limit>0} 取最新 N 点（仍旧→新）。
 *
 * <p>这里钉住三件事：①DB 路径把租户与 LIMIT 一起下推，不是全量取回再截尾；
 * ②默认（不传 limit）仍走全天查询，行为不变；③JSONL 路径也用环形缓冲只留最新 N。
 */
class FlightLogTrackLimitTest {

    @TempDir
    Path tmp;

    @AfterEach
    void clearTenant() {
        TenantContext.clear();
    }

    private FlightLogService dbService(FlightLogRepository repository) throws Exception {
        FlightLogService svc = new FlightLogService(tmp.toString(), 0, new ObjectMapper());
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

    private static FlightLogEntity row(int sysid, double lat, Integer tenantId) {
        FlightLogEntity e = new FlightLogEntity();
        e.setTimestamp(Instant.parse("2026-10-31T03:00:00Z"));
        e.setType("telemetry");
        e.setSysid(sysid);
        e.setLat(lat);
        e.setLon(120.0);
        e.setRelativeAlt(100.0);
        e.setTenantId(tenantId);
        return e;
    }

    @Test
    @DisplayName("DB limit>0：租户与 LIMIT 一起下推，返回最新 N 点且旧→新")
    void dbLimitIsPushedDownWithTenant() throws Exception {
        FlightLogRepository repo = mock(FlightLogRepository.class);
        // 仓储按契约返回「最新在前」，服务必须反转成对外契约的旧→新
        when(repo.findNewestFirst(any(), any(), eq(7), eq("telemetry"), eq(9), any(Pageable.class)))
                .thenReturn(List.of(row(9, 32.0, 7), row(9, 31.0, 7)));

        TenantContext.setTenantId(7);
        List<TrackPoint> out = dbService(repo).trackFor(LocalDate.of(2026, 10, 31), 9, 2);

        assertThat(out).extracting(p -> p.lat).containsExactly(31.0, 32.0);

        ArgumentCaptor<Pageable> page = ArgumentCaptor.forClass(Pageable.class);
        verify(repo).findNewestFirst(any(), any(), eq(7), eq("telemetry"), eq(9), page.capture());
        assertThat(page.getValue())
                .as("LIMIT 必须下推，不能先取回整天再在内存里截尾")
                .isEqualTo(PageRequest.of(0, 2));
        verify(repo, never())
                .findByTypeAndSysidAndTimestampBetweenOrderByTimestampAscIdAsc(any(), eq(9), any(), any());
    }

    @Test
    @DisplayName("DB 默认（limit 省略/0）：仍走全天查询，行为不变")
    void dbDefaultKeepsWholeDay() throws Exception {
        FlightLogRepository repo = mock(FlightLogRepository.class);
        when(repo.findByTypeAndSysidAndTimestampBetweenOrderByTimestampAscIdAsc(
                eq("telemetry"), eq(9), any(), any()))
                .thenReturn(List.of(row(9, 30.0, null), row(9, 31.0, null), row(9, 32.0, null)));

        List<TrackPoint> out = dbService(repo).trackFor(LocalDate.of(2026, 10, 31), 9);

        assertThat(out).extracting(p -> p.lat).containsExactly(30.0, 31.0, 32.0);
        verify(repo, never()).findNewestFirst(any(), any(), any(), any(), any(), any());
    }

    @Test
    @DisplayName("JSONL limit>0：只保留最新 N 点，仍按旧→新")
    void jsonlLimitKeepsNewestN() throws Exception {
        FlightLogService svc = new FlightLogService(tmp.toString(), 0, new ObjectMapper());
        int sysid = 424242; // 独立 temp dir，不存在上一轮残留

        for (int i = 0; i < 5; i++) {
            DroneSnapshot s = new DroneSnapshot(sysid);
            s.lat = 30.0 + i;
            s.lon = 120.0 + i;
            s.relativeAlt = 100.0 + i;
            s.online = true;
            svc.telemetry(s);
        }

        assertThat(svc.trackFor(LocalDate.now(), sysid))
                .as("不传 limit 仍是当天全部（法证契约）")
                .hasSize(5);
        assertThat(svc.trackFor(LocalDate.now(), sysid, 2))
                .extracting(p -> p.lat)
                .containsExactly(33.0, 34.0);
    }
}
