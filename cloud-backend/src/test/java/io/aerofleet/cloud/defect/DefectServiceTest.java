package io.aerofleet.cloud.defect;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.core.env.Environment;
import org.springframework.data.domain.Sort;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** DefectService：立案/去重（10m 锚点）/自动晋升旁路。 */
@DisplayName("DefectService — 立案与去重")
class DefectServiceTest {

    private DefectRepository defects;
    private DefectService svc;
    private java.util.List<DefectEntity> store;

    @BeforeEach
    void setUp() {
        defects = mock(DefectRepository.class);
        Environment env = mock(Environment.class);
        when(env.getProperty("aerofleet.defect.auto-promote-confidence", Double.class, 0.6))
                .thenReturn(0.6);
        svc = new DefectService(defects, env);
        store = new java.util.ArrayList<>();
        when(defects.save(any(DefectEntity.class))).thenAnswer(inv -> {
            DefectEntity d = inv.getArgument(0);
            if (d.id == null) {
                d.id = (long) (store.size() + 1);
            }
            store.removeIf(x -> x.id.equals(d.id));
            store.add(d);
            return d;
        });
        when(defects.findByStatusInOrderByCreatedAtDesc(any()))
                .thenAnswer(inv -> store.stream()
                        .filter(d -> ((List<String>) inv.getArgument(0)).contains(d.status))
                        .toList());
        when(defects.findAll(any(Sort.class))).thenReturn(store);
    }

    @Test
    @DisplayName("人工立案：note 必填，severity 按 confidence 分档")
    void manualCreate() {
        DefectEntity d = svc.createManual("person", 22.59, 113.93, 0.95, "巡检发现", null);

        assertThat(d.severity).isEqualTo(DefectSeverity.P1);
        assertThat(d.status).isEqualTo("OPEN");
        assertThat(d.source).isEqualTo("manual");
    }

    @Test
    @DisplayName("人工立案缺 note → 400 语义")
    void manualRequiresNote() {
        assertThatThrownBy(() -> svc.createManual("person", 22.59, 113.93, 0.9, " ", null))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("自动晋升：置信度达标立案、低置信度跳过")
    void autoPromoteThreshold() {
        svc.onCapture(1, 9, List.of(
                Map.of("kind", "person", "confidence", 0.95, "lat", 22.591, "lon", 113.934),
                Map.of("kind", "vehicle", "confidence", 0.2, "lat", 22.592, "lon", 113.935)), null);

        assertThat(store).hasSize(1);
        assertThat(store.get(0).kind).isEqualTo("person");
        assertThat(store.get(0).source).isEqualTo("auto");
    }

    @Test
    @DisplayName("去重：同 kind 且 ≤10m 复现只更新、不重复立案；>10m 立新单")
    void dedupByDistance() {
        svc.onCapture(1, 9, List.of(
                Map.of("kind", "person", "confidence", 0.9, "lat", 22.591, "lon", 113.934)), null);
        assertThat(store).hasSize(1);
        long firstId = store.get(0).id;
        Long firstSeen = store.get(0).lastSeenAt;

        // 同位置复现（≈0m）：不新增
        svc.onCapture(2, 9, List.of(
                Map.of("kind", "person", "confidence", 0.88, "lat", 22.591, "lon", 113.934)), null);
        assertThat(store).hasSize(1);
        assertThat(store.get(0).id).isEqualTo(firstId);
        assertThat(store.get(0).lastSeenAt).isGreaterThanOrEqualTo(firstSeen);

        // 同 kind 但 ~30m 外：立新单
        svc.onCapture(3, 9, List.of(
                Map.of("kind", "person", "confidence", 0.9, "lat", 22.5913, "lon", 113.9345)), null);
        assertThat(store).hasSize(2);
    }

    @Test
    @DisplayName("旁路纪律：异常不外抛（拍照主路径不受影响）")
    void bypassSwallowsExceptions() {
        when(defects.findByStatusInOrderByCreatedAtDesc(any()))
                .thenThrow(new RuntimeException("db down"));

        // 不得抛——F1 N3 同款旁路纪律
        svc.onCapture(1, 9, List.of(
                Map.of("kind", "person", "confidence", 0.9, "lat", 22.59, "lon", 113.93)), null);
    }

    @Test
    @DisplayName("严重度映射：0.9→P1、0.75→P2、其余 P3")
    void severityMapping() {
        assertThat(DefectSeverity.classify("person", 0.95)).isEqualTo(DefectSeverity.P1);
        assertThat(DefectSeverity.classify("person", 0.8)).isEqualTo(DefectSeverity.P2);
        assertThat(DefectSeverity.classify("person", 0.6)).isEqualTo(DefectSeverity.P3);
    }
}
