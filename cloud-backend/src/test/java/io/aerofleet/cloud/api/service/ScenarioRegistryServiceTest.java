package io.aerofleet.cloud.api.service;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.List;
import java.util.Map;

import static io.aerofleet.cloud.api.exception.ApiExceptionHandler.BadRequestException;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@DisplayName("ScenarioRegistryService（V43 三张场景表读写）")
class ScenarioRegistryServiceTest {

    private final JdbcTemplate jdbc = mock(JdbcTemplate.class);
    private final ScenarioRegistryService service = new ScenarioRegistryService(jdbc);

    @Test
    @DisplayName("创建阳台落点：必填校验 + INSERT 列与值对齐")
    void createBalconyDropPoint() {
        Map<String, Object> body = Map.of(
                "buildingId", "B-1", "floor", 12, "lat", 30.1, "lon", 120.2, "altM", 60.0);
        Map<String, Object> out = service.createBalconyDropPoint(body);
        assertThat(out).containsKey("id");
        assertThat(out.get("created")).isEqualTo(true);

        ArgumentCaptor<Object[]> args = ArgumentCaptor.forClass(Object[].class);
        verify(jdbc).update(anyString(), args.capture());
        assertThat(args.getValue()).hasSize(12);
        assertThat(args.getValue()[2]).isEqualTo("B-1");
        assertThat(args.getValue()[10]).isEqualTo("ACTIVE");
    }

    @Test
    @DisplayName("缺必填字段抛 BadRequestException（不是 500）")
    void rejectsMissingRequiredField() {
        assertThatThrownBy(() -> service.createBalconyDropPoint(Map.of("buildingId", "B-1")))
                .isInstanceOf(BadRequestException.class)
                .hasMessageContaining("floor");
        assertThatThrownBy(() -> service.createEmergencyRole(Map.of("sysid", 7)))
                .isInstanceOf(BadRequestException.class)
                .hasMessageContaining("role");
    }

    @Test
    @DisplayName("查询带可选过滤时 SQL 追加条件，返回行原样透传")
    void queryWithFilter() {
        when(jdbc.queryForList(anyString(), any(Object[].class)))
                .thenReturn(List.of(Map.of("building_id", "B-1")));
        List<Map<String, Object>> rows = service.balconyDropPoints("B-1");
        assertThat(rows).hasSize(1);

        ArgumentCaptor<String> sql = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<Object[]> args = ArgumentCaptor.forClass(Object[].class);
        verify(jdbc).queryForList(sql.capture(), args.capture());
        assertThat(sql.getValue()).contains("building_id=?");
        assertThat(args.getValue()).containsExactly(1, "B-1");
    }

    @Test
    @DisplayName("PUT 只更新提供的字段，DELETE 带租户条件")
    void updateAndDelete() {
        when(jdbc.update(anyString(), any(Object[].class))).thenReturn(1, 1);
        assertThat(service.updateBalconyDropPoint("p1", Map.of("floor", 13)))
                .containsEntry("updated", true);
        assertThat(service.deleteEmergencyRole("e1")).containsEntry("deleted", true);

        ArgumentCaptor<String> sql = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<Object[]> args = ArgumentCaptor.forClass(Object[].class);
        verify(jdbc, times(2)).update(sql.capture(), args.capture());
        assertThat(sql.getAllValues().get(0))
                .contains("update balcony_drop_point set floor=?")
                .contains("where point_id=? and tenant_id=?");
        assertThat(args.getAllValues().get(0)).containsExactly(13, "p1", 1);
        assertThat(sql.getAllValues().get(1))
                .isEqualTo("delete from emergency_node_role where role_id=? and tenant_id=?");
    }

    @Test
    @DisplayName("PUT 未提供任何可更新字段时抛 BadRequestException")
    void rejectsEmptyUpdate() {
        assertThatThrownBy(() -> service.updateBalconyDropPoint("p1", Map.of()))
                .isInstanceOf(BadRequestException.class)
                .hasMessageContaining("no updatable fields");
    }
}
