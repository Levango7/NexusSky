package io.aerofleet.cloud.api.controller;

import io.aerofleet.cloud.api.service.ScenarioRegistryService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@DisplayName("ScenarioRegistryController REST 端点（V43 场景表）")
class ScenarioRegistryControllerTest {

    private ScenarioRegistryService service;
    private ScenarioRegistryController controller;

    @BeforeEach
    void setUp() {
        service = mock(ScenarioRegistryService.class);
        controller = new ScenarioRegistryController(service);
    }

    @Test
    @DisplayName("阳台落点：GET 透传过滤，POST 交给服务层")
    void balcony() {
        when(service.balconyDropPoints("B-1")).thenReturn(List.of(Map.of("floor", 12)));
        assertThat(controller.balconyDropPoints("B-1")).hasSize(1);
        verify(service).balconyDropPoints("B-1");

        when(service.createBalconyDropPoint(any())).thenReturn(Map.of("id", "p1", "created", true));
        assertThat(controller.createBalconyDropPoint(Map.of("buildingId", "B-1"))).containsKey("id");
        verify(service).createBalconyDropPoint(any());
    }

    @Test
    @DisplayName("农资流水：GET/POST 委托")
    void agri() {
        when(service.agriSupply("t1")).thenReturn(List.of(Map.of("action", "USE")));
        assertThat(controller.agriSupply("t1").get(0).get("action")).isEqualTo("USE");
        when(service.createAgriSupply(any())).thenReturn(Map.of("id", "a1"));
        assertThat(controller.createAgriSupply(Map.of("action", "USE"))).containsKey("id");
    }

    @Test
    @DisplayName("应急节点角色：GET/POST 委托")
    void emergency() {
        when(service.emergencyRoles("plan-1")).thenReturn(List.of(Map.of("role", "RELAY")));
        assertThat(controller.emergencyRoles("plan-1").get(0).get("role")).isEqualTo("RELAY");
        when(service.createEmergencyRole(any())).thenReturn(Map.of("id", "e1"));
        assertThat(controller.createEmergencyRole(Map.of("sysid", 9, "role", "RELAY"))).containsKey("id");
    }

    @Test
    @DisplayName("PUT/DELETE 委托服务层并返回结果")
    void updateAndDelete() {
        when(service.updateBalconyDropPoint("p1", Map.of("floor", 13)))
                .thenReturn(Map.of("id", "p1", "updated", true));
        assertThat(controller.updateBalconyDropPoint("p1", Map.of("floor", 13)))
                .containsEntry("updated", true);
        when(service.deleteAgriSupply("a1")).thenReturn(Map.of("id", "a1", "deleted", true));
        assertThat(controller.deleteAgriSupply("a1")).containsEntry("deleted", true);
    }
}
