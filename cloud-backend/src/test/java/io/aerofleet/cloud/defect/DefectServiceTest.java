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
        // setStatus / get 要走 findById——本类原先只测 onCapture 没 stub 它，
        // 于是任何"按 id 取缺陷"的用例都会撞上 Optional.empty() 的
        // "unknown defect 1"（夹具缺桩，不是被测行为）。按 store 真查。
        org.mockito.Mockito.when(defects.findById(org.mockito.ArgumentMatchers.anyLong()))
                .thenAnswer(inv -> store.stream()
                        .filter(d -> d.id.equals(inv.getArgument(0)))
                        .findFirst());
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
    @DisplayName("RECTIFIED 必须带 note（人工关闭没说明等于没关闭）")
    void rectifiedRequiresNote() {
        DefectEntity d = svc.createManual("person", 22.60, 113.94, 0.9, "巡检", null);
        assertThatThrownBy(() -> svc.setStatus(d.id, DefectService.RECTIFIED, null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("requires a note");
        assertThatThrownBy(() -> svc.setStatus(d.id, DefectService.RECTIFIED, "   "))
                .isInstanceOf(IllegalArgumentException.class);

        DefectEntity closed = svc.setStatus(d.id, DefectService.RECTIFIED, "现场班组 11-09 更换");
        assertThat(closed.status).isEqualTo("RECTIFIED");
        // 消缺口径下 lastSeenAt 记"关闭时间"，不是"最后看到缺陷的时间"
        assertThat(closed.lastSeenAt).isNotNull();
    }

    @Test
    @DisplayName("未知状态仍被拒（状态词表封闭）")
    void unknownStatusRejected() {
        DefectEntity d = svc.createManual("person", 22.601, 113.941, 0.9, "巡检", null);
        assertThatThrownBy(() -> svc.setStatus(d.id, "FIXED", "x"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("invalid defect status");
    }

    @Test
    @DisplayName("已消缺缺陷不再被去重折叠：同点位复发立新单，而不是刷新旧缺陷")
    void rectifiedDefectIsNotFoldedInto() {
        // 立案 → 消缺
        svc.onCapture(10, 9, List.of(
                Map.of("kind", "person", "confidence", 0.9, "lat", 22.61, "lon", 113.95)), null);
        DefectEntity first = store.get(0);
        svc.setStatus(first.id, DefectService.RECTIFIED, "已更换");

        // 同一位置再次被拍到
        svc.onCapture(11, 9, List.of(
                Map.of("kind", "person", "confidence", 0.92, "lat", 22.61, "lon", 113.95)), null);

        assertThat(store)
                .as("消缺后复发必须产生新缺陷；若仍是 1 条，说明复发被旧缺陷静默吞并")
                .hasSize(2);
        assertThat(store.get(1).id).isNotEqualTo(first.id);
        assertThat(store.get(1).status).isEqualTo("OPEN");
        assertThat(store.get(1).source).isEqualTo("auto");
        // 旧缺陷保持已消缺（点位修过几次，审计可查）
        assertThat(store.get(0).status).isEqualTo("RECTIFIED");
    }

    @Test
    @DisplayName("严重度映射：0.9→P1、0.75→P2、其余 P3")
    void severityMapping() {
        assertThat(DefectSeverity.classify("person", 0.95)).isEqualTo(DefectSeverity.P1);
        assertThat(DefectSeverity.classify("person", 0.8)).isEqualTo(DefectSeverity.P2);
        assertThat(DefectSeverity.classify("person", 0.6)).isEqualTo(DefectSeverity.P3);
    }
}
