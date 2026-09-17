package io.aerofleet.cloud.edge;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * EdgeCoordinationService 边缘-云端协同服务单测（M12）。
 * <p>
 * 直接实例化 Service（无 Spring 上下文），覆盖结果提交/查询/聚合。
 */
@DisplayName("EdgeCoordinationService 边缘-云端协同 (M12)")
class EdgeCoordinationServiceTest {

    private EdgeCoordinationService service;

    @BeforeEach
    void setUp() {
        service = new EdgeCoordinationService();
    }

    @Test
    @DisplayName("submitResult 后 getResults 返回该 sysid 的结果列表")
    void submitResultStoresBySysid() {
        Map<String, Object> payload = Map.of("detected", 3);
        service.submitResult(1, "task-1", "VIDEO_ANALYSIS", payload);

        List<Map<String, Object>> results = service.getResults(1);
        assertThat(results).hasSize(1);
        assertThat(results.get(0).get("taskId")).isEqualTo("task-1");
        assertThat(results.get(0).get("type")).isEqualTo("VIDEO_ANALYSIS");
        assertThat(results.get(0).get("result")).isEqualTo(payload);
        assertThat(results.get(0).get("timestamp")).isInstanceOf(Long.class);
    }

    @Test
    @DisplayName("getResults 未提交的 sysid 返回空列表")
    void getResultsEmptyForUnknownSysid() {
        List<Map<String, Object>> results = service.getResults(99);
        assertThat(results).isEmpty();
    }

    @Test
    @DisplayName("submitResult 同一 sysid 多次提交累积保留")
    void submitResultAccumulatesForSameSysid() {
        service.submitResult(1, "t1", "VIDEO_ANALYSIS", Map.of());
        service.submitResult(1, "t2", "OBJECT_DETECT", Map.of());
        service.submitResult(1, "t3", "SENSOR_FUSION", Map.of());

        List<Map<String, Object>> results = service.getResults(1);
        assertThat(results).hasSize(3);
        assertThat(results).extracting(m -> m.get("taskId"))
                .containsExactly("t1", "t2", "t3");
    }

    @Test
    @DisplayName("submitResult 不同 sysid 独立存储")
    void submitResultSeparatesBySysid() {
        service.submitResult(1, "t1", "VIDEO_ANALYSIS", Map.of());
        service.submitResult(2, "t2", "OBJECT_DETECT", Map.of());

        assertThat(service.getResults(1)).hasSize(1);
        assertThat(service.getResults(2)).hasSize(1);
        assertThat(service.getResults(1).get(0).get("taskId")).isEqualTo("t1");
        assertThat(service.getResults(2).get(0).get("taskId")).isEqualTo("t2");
    }

    @Test
    @DisplayName("getAllResults 返回所有 sysid 的结果映射")
    void getAllResultsReturnsAllSysids() {
        service.submitResult(1, "t1", "VIDEO_ANALYSIS", Map.of());
        service.submitResult(2, "t2", "OBJECT_DETECT", Map.of());

        Map<Integer, List<Map<String, Object>>> all = service.getAllResults();
        assertThat(all).hasSize(2);
        assertThat(all.keySet()).containsExactlyInAnyOrder(1, 2);
    }

    @Test
    @DisplayName("getAllResults 初始为空")
    void getAllResultsEmptyInitially() {
        assertThat(service.getAllResults()).isEmpty();
    }

    @Test
    @DisplayName("getAllResults 返回不可修改视图")
    void getAllResultsIsUnmodifiable() {
        Map<Integer, List<Map<String, Object>>> all = service.getAllResults();
        assertThatThrownBy(() -> all.put(99, List.of()))
                .isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    @DisplayName("submitResult 不同 type 字段正确存储")
    void submitResultStoresTypeField() {
        service.submitResult(1, "t1", "VIDEO_ANALYSIS", Map.of());
        service.submitResult(1, "t2", "SENSOR_FUSION", Map.of());
        service.submitResult(1, "t3", "OBJECT_DETECT", Map.of());

        List<Map<String, Object>> results = service.getResults(1);
        assertThat(results).extracting(m -> m.get("type"))
                .containsExactly("VIDEO_ANALYSIS", "SENSOR_FUSION", "OBJECT_DETECT");
    }
}