package io.aerofleet.cloud.api.controller;

import io.aerofleet.cloud.gateway.DeviceRegistry;
import io.aerofleet.cloud.gateway.DroneSnapshot;
import io.aerofleet.cloud.vision.ThermalTaskService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.context.SecurityContextHolder;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@code GET /api/v1/thermal/status} 机队热读数口径（P2 #19）。
 *
 * <p>钉住：只列 online 且上报过（thermalUpdatedAtMs > 0）的机——离线机的陈旧读数
 * 和"从未上报"的机都不许混进机队热状态；字段与快照逐项相等（不造数）。
 */
@DisplayName("GET /api/v1/thermal/status 机队热读数口径 (P2 #19)")
class ThermalControllerStatusTest {

    private final DeviceRegistry registry = new DeviceRegistry();
    private final ThermalController controller =
            new ThermalController(new ThermalTaskService(), registry);

    @AfterEach
    void cleanUp() {
        SecurityContextHolder.clearContext();
    }

    private DroneSnapshot drone(int sysid) {
        registry.provision(sysid, null);
        return registry.all().stream()
                .filter(d -> d.sysid == sysid)
                .findFirst()
                .orElseThrow();
    }

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> itemsOf(ResponseEntity<Map<String, Object>> resp) {
        return (List<Map<String, Object>>) resp.getBody().get("items");
    }

    @Test
    @DisplayName("online + 已上报的机：字段与快照逐项相等（不造数）")
    void reportedOnlineDroneListed() {
        DroneSnapshot s = drone(7);
        s.online = true;
        s.thermalTempMean = 30.5;
        s.thermalTempMin = 22.0;
        s.thermalTempMax = 61.0;
        s.thermalTempStdDev = 4.25;
        s.thermalHotspotCount = 2;
        s.thermalUpdatedAtMs = 1234L;

        ResponseEntity<Map<String, Object>> resp = controller.status();

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);
        List<Map<String, Object>> items = itemsOf(resp);
        assertThat(resp.getBody().get("total")).isEqualTo(1);
        assertThat(items).hasSize(1);
        Map<String, Object> item = items.get(0);
        assertThat(item).containsEntry("sysid", 7)
                .containsEntry("tempMean", 30.5)
                .containsEntry("tempMin", 22.0)
                .containsEntry("tempMax", 61.0)
                .containsEntry("tempStdDev", 4.25)
                .containsEntry("hotspotCount", 2)
                .containsEntry("updatedAtMs", 1234L);
    }

    @Test
    @DisplayName("离线机的陈旧读数不列（热状态是实时口径，不是最后一次见到的值）")
    void offlineDroneExcluded() {
        DroneSnapshot s = drone(7);
        s.online = false;
        s.thermalTempMean = 99.0;
        s.thermalUpdatedAtMs = 1234L;

        ResponseEntity<Map<String, Object>> resp = controller.status();

        assertThat(itemsOf(resp)).isEmpty();
        assertThat(resp.getBody().get("total")).isEqualTo(0);
    }

    @Test
    @DisplayName("从未上报热读数的在线机不列（thermalUpdatedAtMs == 0）")
    void neverReportedDroneExcluded() {
        DroneSnapshot s = drone(7);
        s.online = true; // 热像字段全缺省：NaN / -1 / 0

        ResponseEntity<Map<String, Object>> resp = controller.status();

        assertThat(itemsOf(resp)).isEmpty();
    }

    @Test
    @DisplayName("空机队：items 空数组、total 0")
    void emptyFleetYieldsEmptyItems() {
        ResponseEntity<Map<String, Object>> resp = controller.status();

        assertThat(itemsOf(resp)).isEmpty();
        assertThat(resp.getBody().get("total")).isEqualTo(0);
    }
}
