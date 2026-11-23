package io.aerofleet.cloud.flightlog;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.aerofleet.cloud.gateway.DroneSnapshot;
import io.aerofleet.cloud.mission.formation.FormationService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.lang.reflect.Field;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

@DisplayName("FlightLogService 写出任务归因与序号（V42 写入侧闭环）")
class FlightLogTaskEnrichmentTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @TempDir
    Path tmp;

    private static DroneSnapshot snap(int sysid, double lat) {
        DroneSnapshot s = new DroneSnapshot(sysid);
        s.lat = lat;
        s.lon = 120.0;
        s.relativeAlt = 50.0;
        s.battery = 80;
        s.voltage = 15000;
        s.mode = "GUIDED";
        s.armed = true;
        s.online = true;
        return s;
    }

    private static void inject(FlightLogService svc, FlightLogTaskContext ctx) throws Exception {
        Field f = FlightLogService.class.getDeclaredField("taskContext");
        f.setAccessible(true);
        f.set(svc, ctx);
    }

    private static void injectFormation(FlightLogService svc, FormationService formation) throws Exception {
        Field f = FlightLogService.class.getDeclaredField("formationService");
        f.setAccessible(true);
        f.set(svc, formation);
    }

    @Test
    @DisplayName("登记任务后 JSONL 行带 taskId/formationId/seq；未登记时只有 seq")
    void writesTaskIdAndSeq() throws Exception {
        FlightLogTaskContext ctx = new FlightLogTaskContext();
        ctx.bind(1, "show-1200");
        FormationService formation = mock(FormationService.class);
        when(formation.formationIdFor(1)).thenReturn(7);
        FlightLogService svc = new FlightLogService(tmp.toString(), 0, MAPPER);
        inject(svc, ctx);
        injectFormation(svc, formation);

        svc.telemetry(snap(1, 30.0));
        Map<String, Object> first = svc.query(LocalDate.now(), "telemetry", 1, 0).get(0);
        assertThat(first).containsEntry("taskId", "show-1200")
                .containsEntry("formationId", "7");
        assertThat(first.get("seq")).isNotNull();

        ctx.unbind(1);
        svc.telemetry(snap(1, 30.1));
        var rows = svc.query(LocalDate.now(), "telemetry", 1, 0);
        assertThat(rows).hasSize(2);
        Map<String, Object> second = rows.stream()
                .filter(r -> Double.compare(30.1, ((Number) r.get("lat")).doubleValue()) == 0)
                .findFirst().orElseThrow();
        assertThat(second).doesNotContainKey("taskId")
                .containsEntry("formationId", "7");
        assertThat(second.get("seq")).isNotNull();
        assertThat(((Number) second.get("seq")).longValue())
                .isGreaterThan(((Number) first.get("seq")).longValue());
    }

    @Test
    @DisplayName("未注入上下文时行为与改造前一致（不新增键）")
    void unchangedWithoutContext() throws Exception {
        FlightLogService svc = new FlightLogService(tmp.toString(), 0, MAPPER);
        svc.telemetry(snap(2, 31.0));
        assertThat(svc.query(LocalDate.now(), "telemetry", 2, 0).get(0))
                .doesNotContainKeys("taskId", "seq");
    }
}
