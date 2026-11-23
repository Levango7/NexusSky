package io.aerofleet.cloud.flightlog;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("FlightLogEntity 任务/编队/序号维度（V42）")
class FlightLogTaskSeqTest {

    @Test
    @DisplayName("from() 映射 taskId/formationId/seq，缺省为 null")
    void fromMapsTaskDimensions() {
        FlightLogEntity e = FlightLogEntity.from(Map.of(
                "type", "telemetry", "sysid", 9, "tenantId", 1,
                "taskId", "show-1200", "formationId", "g-07", "seq", 42L));
        assertThat(e.getTaskId()).isEqualTo("show-1200");
        assertThat(e.getFormationId()).isEqualTo("g-07");
        assertThat(e.getSeq()).isEqualTo(42L);

        FlightLogEntity bare = FlightLogEntity.from(Map.of("type", "telemetry", "sysid", 9));
        assertThat(bare.getTaskId()).isNull();
        assertThat(bare.getFormationId()).isNull();
        assertThat(bare.getSeq()).isNull();
    }

    @Test
    @DisplayName("批插列清单包含新列且与值同源")
    void insertColumnsIncludeTaskDimensions() {
        List<String> cols = List.of(FlightLogEntity.insertColumnNames());
        assertThat(cols).contains("task_id", "formation_id", "seq");
        FlightLogEntity e = FlightLogEntity.from(Map.of(
                "type", "telemetry", "sysid", 9, "taskId", "t", "seq", 7L));
        Object[] values = FlightLogEntity.insertValues(e);
        assertThat(values).hasSize(cols.size());
        assertThat(values[cols.indexOf("task_id")]).isEqualTo("t");
        assertThat(values[cols.indexOf("seq")]).isEqualTo(7L);
    }
}
