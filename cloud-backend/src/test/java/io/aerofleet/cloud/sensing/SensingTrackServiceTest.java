package io.aerofleet.cloud.sensing;

import io.aerofleet.cloud.gateway.DeviceRegistry;
import io.aerofleet.cloud.gateway.DroneSnapshot;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** E2/E4 非合作目标感知：SPI 契约、聚合 upsert/清理、近域告警三段语义。 */
@DisplayName("侦测态势聚合（E2+E4）")
class SensingTrackServiceTest {

    private DeviceRegistry registry;
    private SensingTrackService svc;

    @BeforeEach
    void setUp() {
        registry = mock(DeviceRegistry.class);
        svc = new SensingTrackService(List.of(), registry, 30_000, 1000);
    }

    private static SensingTrack track(String id, String type, String source,
                                      double lat, double lon, long seenMs) {
        return new SensingTrack(id, type, source, lat, lon, 100, 10, 90, 0.9,
                "DRONE", seenMs, seenMs);
    }

    private DroneSnapshot drone(int sysid, double lat, double lon) {
        DroneSnapshot s = new DroneSnapshot(sysid);
        s.online = true;
        s.lat = lat;
        s.lon = lon;
        return s;
    }

    // ------------------------------------------------------------------
    // 聚合与 upsert
    // ------------------------------------------------------------------

    @Nested
    @DisplayName("聚合")
    class AggregateTest {
        @Test
        void 同trackId更新不重复() {
            long now = System.currentTimeMillis();
            // 同一来源同一 trackId 两次 poll → 一条航迹（seenAgain 更新坐标）
            AerialSensingSource src = new AerialSensingSource() {
                int calls = 0;
                public String sourceType() { return SensingTrack.TYPE_COUNTER_DRONE_RADAR; }
                public String sourceId() { return "R1"; }
                public List<SensingTrack> poll() {
                    calls++;
                    return List.of(track("T1", sourceType(), "R1",
                            22.59 + calls * 0.001, 113.93, now));
                }
            };
            SensingTrackService s = new SensingTrackService(List.of(src), registry, 30_000, 1000);
            s.aggregate();
            s.aggregate();
            assertThat(s.tracks(null, false)).hasSize(1);
        }

        @Test
        void 超时清理移除陈旧航迹() {
            long stale = System.currentTimeMillis() - 60_000;
            AerialSensingSource src = new AerialSensingSource() {
                public String sourceType() { return SensingTrack.TYPE_FIVE_G_SENSING; }
                public String sourceId() { return "G1"; }
                public List<SensingTrack> poll() {
                    return List.of(track("T1", sourceType(), "G1", 22.59, 113.93, stale));
                }
            };
            SensingTrackService s = new SensingTrackService(List.of(src), registry, 30_000, 1000);
            s.aggregate();   // 写入陈旧时间戳
            // 下一次 aggregate 会先 poll（仍是 stale 时间戳）——手工触发清理验证语义
            s.evictStale();
            assertThat(s.tracks(null, false)).isEmpty();
        }

        @Test
        void source过滤() {
            when(registry.all()).thenReturn(List.of());
            long now = System.currentTimeMillis();
            AerialSensingSource radar = fixedSource(SensingTrack.TYPE_COUNTER_DRONE_RADAR, "R1", "T-R");
            AerialSensingSource fiveg = fixedSource(SensingTrack.TYPE_FIVE_G_SENSING, "G1", "T-G");
            SensingTrackService s = new SensingTrackService(List.of(radar, fiveg), registry, 30_000, 1000);
            s.aggregate();

            assertThat(s.tracks("COUNTER_DRONE_RADAR", false)).hasSize(1);
            assertThat(s.tracks("FIVE_G_SENSING", false)).hasSize(1);
            assertThat(s.tracks(null, false)).hasSize(2);
        }

        private AerialSensingSource fixedSource(String type, String sourceId, String trackId) {
            long now = System.currentTimeMillis();
            return new AerialSensingSource() {
                public String sourceType() { return type; }
                public String sourceId() { return sourceId; }
                public List<SensingTrack> poll() {
                    return List.of(track(trackId, type, sourceId, 22.59, 113.93, now));
                }
            };
        }
    }

    // ------------------------------------------------------------------
    // 近域告警三段语义
    // ------------------------------------------------------------------

    @Nested
    @DisplayName("近域告警")
    class ProximityTest {
        @Test
        void 进入告警_离开清除_再进入再告警() {
            when(registry.all()).thenReturn(List.of(drone(9, 22.5907, 113.9345)));
            long now = System.currentTimeMillis();

            // 段1：远（8km 外）→ 无告警
            svc.tracks(null, false);
            svc = inject(svc, track("T1", SensingTrack.TYPE_COUNTER_DRONE_RADAR, "R1",
                    22.66, 113.9345, now));   // ≈7.7km 北
            svc.evaluateProximity();
            assertThat(svc.alertCount()).isZero();

            // 段2：近（~100m）→ 告警
            svc = inject(svc, track("T1", SensingTrack.TYPE_COUNTER_DRONE_RADAR, "R1",
                    22.5908, 113.9345, now));
            svc.evaluateProximity();
            assertThat(svc.alertCount()).isEqualTo(1);
            assertThat(svc.tracks(null, true)).hasSize(1);   // alertOnly 过滤

            // 段3：再远 → 清除；段4：再近 → 重新告警（三段闭环）
            svc = inject(svc, track("T1", SensingTrack.TYPE_COUNTER_DRONE_RADAR, "R1",
                    22.66, 113.9345, now));
            svc.evaluateProximity();
            assertThat(svc.alertCount()).isZero();

            svc = inject(svc, track("T1", SensingTrack.TYPE_COUNTER_DRONE_RADAR, "R1",
                    22.5908, 113.9345, now));
            svc.evaluateProximity();
            assertThat(svc.alertCount()).isEqualTo(1);
        }

        @Test
        void 无在线设备则无告警() {
            when(registry.all()).thenReturn(List.of());
            long now = System.currentTimeMillis();
            svc = inject(svc, track("T1", SensingTrack.TYPE_FIVE_G_SENSING, "G1",
                    22.5907, 113.9345, now));
            svc.evaluateProximity();
            assertThat(svc.alertCount()).isZero();
        }

        /** 测试辅助：把一条航迹塞进服务内部 map（绕过 scheduled poll）。 */
        private SensingTrackService inject(SensingTrackService s, SensingTrack t) {
            try {
                var field = SensingTrackService.class.getDeclaredField("tracks");
                field.setAccessible(true);
                @SuppressWarnings("unchecked")
                Map<String, SensingTrack> map = (Map<String, SensingTrack>) field.get(s);
                map.put(t.sourceId() + ":" + t.trackId(), t);
                return s;
            } catch (ReflectiveOperationException e) {
                throw new IllegalStateException(e);
            }
        }
    }

    // ------------------------------------------------------------------
    // 模拟源契约
    // ------------------------------------------------------------------

    @Nested
    @DisplayName("模拟源")
    class SourceTest {
        @Test
        void 反制雷达源产两条航迹且逼近航迹递减() {
            CounterDroneRadarSource src = new CounterDroneRadarSource(22.5907, 113.9345);
            List<SensingTrack> first = src.poll();
            List<SensingTrack> second = src.poll();
            assertThat(first).hasSize(2);
            assertThat(first.get(0).sourceType()).isEqualTo(SensingTrack.TYPE_COUNTER_DRONE_RADAR);

            // intruder 航迹：每 tick 逼近 250m（到 300m 下限）
            double d1 = dist(src, second.get(1).lat(), second.get(1).lon());
            double d2 = dist(src, first.get(1).lat(), first.get(1).lon());
            assertThat(d2 - d1).isBetween(200.0, 300.0);   // ≈250m/step
        }

        @Test
        void fiveGA源产单航迹含误报形态() {
            FiveGSensingSource src = new FiveGSensingSource(22.5907, 113.9345);
            List<SensingTrack> out = src.poll();
            assertThat(out).hasSize(1);
            assertThat(out.get(0).sourceType()).isEqualTo(SensingTrack.TYPE_FIVE_G_SENSING);
            // rangeMOf 反算语义（接入映射样板）
            assertThat(src.rangeMOf(out.get(0))).isGreaterThan(0);
        }

        private static double dist(CounterDroneRadarSource src, double lat, double lon) {
            return FiveGSensingSource.UspaceGeometry.haversineM(22.5907, 113.9345, lat, lon);
        }
    }
}
