package io.aerofleet.cloud.defect;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.core.env.Environment;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * {@link DefectService#onCapture} 的**单条隔离**回归测试。
 *
 * <h2>被钉住的缺陷</h2>
 *
 * {@code onCapture} 的旁路纪律（spec §4 / F1 N3）只要求「立案失败不影响拍照主路径」。
 * 但原实现把 {@code try} 包在**整个 for 循环外面**：
 *
 * <pre>
 * try {
 *     for (detection : detections) { ... defects.save(...) ... }
 * } catch (Exception e) {
 *     log.warn("defect auto-promote failed (ignored, frame {})", ...);
 * }
 * </pre>
 *
 * 于是**同帧某一条 detection 触发的 save 抛异常，会让本帧余下所有 detection 全部不被
 * 处理**——缺陷静默消失，而日志只有一行 warn，从日志看不出丢了多少条。
 *
 * <h2>本类证明的不变式</h2>
 *
 * 单条失败只跳过该条：同帧余下检测照常立案，且主路径（本方法）不抛异常。
 */
@DisplayName("DefectService.onCapture 单条失败隔离")
class DefectOnCaptureIsolationTest {

    private DefectRepository defects;
    private DefectService svc;
    private final List<DefectEntity> store = new ArrayList<>();

    @BeforeEach
    void setUp() {
        defects = Mockito.mock(DefectRepository.class);
        Environment env = Mockito.mock(Environment.class);
        when(env.getProperty("aerofleet.defect.auto-promote-confidence", Double.class, 0.6))
                .thenReturn(0.6);
        svc = new DefectService(defects, env);
        store.clear();
        when(defects.save(any(DefectEntity.class))).thenAnswer(inv -> {
            DefectEntity d = inv.getArgument(0);
            if (d.id == null) {
                d.id = (long) (store.size() + 1);
            }
            store.removeIf(x -> x.id.equals(d.id));
            store.add(d);
            return d;
        });
        when(defects.findByStatusInOrderByCreatedAtDesc(any())).thenReturn(List.of());
    }

    private Map<String, Object> detection(String kind, double conf, double lat, double lon) {
        return Map.of("kind", kind, "confidence", conf, "lat", lat, "lon", lon);
    }

    @Test
    @DisplayName("同帧第 1 条 save 抛异常 ⇒ 余下 2 条仍被持久化（不被整帧吞掉）")
    void oneFailureDoesNotSwallowTheRestOfTheFrame() {
        // 第一条 save 抛，其余正常
        when(defects.save(any(DefectEntity.class)))
                .thenThrow(new RuntimeException("db down on first save"))
                .thenAnswer(inv -> {
                    DefectEntity d = inv.getArgument(0);
                    if (d.id == null) {
                        d.id = (long) (store.size() + 1);
                    }
                    store.add(d);
                    return d;
                });

        List<Map<String, Object>> frame = List.of(
                detection("CRACK", 0.9, 39.9, 116.3),
                detection("CRACK", 0.9, 39.91, 116.31),
                detection("CRACK", 0.9, 39.92, 116.32)
        );

        // 主路径不得抛（旁路纪律）
        svc.onCapture(1001, 1, frame, "tenant-1");

        // 三条都尝试过 save
        verify(defects, times(3)).save(any(DefectEntity.class));
        // 后两条成功落进 store（store 此时有 2 条）
        assertThat(store)
                .as("后两条应已落库，第一条失败不得连带丢掉它们")
                .hasSize(2);
    }

    @Test
    @DisplayName("全部成功时不产生异常路径（不误伤正常帧）")
    void allSuccessPathUnchanged() {
        List<Map<String, Object>> frame = List.of(
                detection("CRACK", 0.9, 39.9, 116.3),
                detection("DENT", 0.95, 39.91, 116.31)
        );

        svc.onCapture(1002, 1, frame, "tenant-1");

        verify(defects, times(2)).save(any(DefectEntity.class));
        assertThat(store).hasSize(2);
    }

    @Test
    @DisplayName("置信度不足的检测不触发持久化（原门槛语义不变）")
    void belowThresholdStillSkipped() {
        List<Map<String, Object>> frame = List.of(
                detection("CRACK", 0.3, 39.9, 116.3),
                detection("CRACK", 0.5, 39.91, 116.31)
        );

        svc.onCapture(1003, 1, frame, "tenant-1");

        verify(defects, never()).save(any(DefectEntity.class));
        assertThat(store).isEmpty();
    }
}
