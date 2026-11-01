package io.aerofleet.cloud.mapping;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * mapping 域两处进程内只增结构的**有界性**回归测试。
 *
 * <h2>被钉住的缺陷</h2>
 *
 * <ul>
 *   <li>{@link PhotoCaptureService#photosByTask}：按任务 ID 存照片，
 *       任务数单调增长且**全仓无 remove/clear**；</li>
 *   <li>{@link MappingResultService#resultsById} + {@code resultsByTask}：
 *       两个协同 map 同样零清理，而成果是每次生成 3 种（正射/DEM/三维）之一。</li>
 * </ul>
 *
 * <h2>本类证明的不变式</h2>
 *
 * 任务数与成果数各自有界；淘汰必须是**联动**的——只删主表会让索引留下孤儿，
 * 任务列表空槽后还留着 key 则 key 本身继续泄漏。读路径不改变淘汰顺序
 * （否则回看老任务会让它续命、上限形同虚设）。
 */
@DisplayName("mapping 域：照片与成果的内存有界")
class MappingInmemoryBoundTest {

    private PhotoCaptureService photoCapture;
    private MappingResultService mappingResult;

    @BeforeEach
    void setUp() {
        photoCapture = new PhotoCaptureService();
        mappingResult = new MappingResultService();
    }

    private MappingWaypoint waypoint() {
        return new MappingWaypoint(1, 39.9, 116.3, 100.0, 0.0, 0.0,
                MappingWaypoint.Action.PHOTO, 1);
    }

    private String captureForTask(String taskId) {
        return photoCapture.capture(1, waypoint(), taskId).getId();
    }

    @Test
    @DisplayName("照片按任务限定：超过上限淘汰最旧任务，新任务不受影响")
    void photosBoundedByTaskCount() {
        List<String> taskIds = new ArrayList<>();
        for (int i = 0; i < PhotoCaptureService.MAX_TASKS + 5; i++) {
            String taskId = "task-" + i;
            taskIds.add(taskId);
            captureForTask(taskId);
        }

        // 最老的 5 个任务被淘汰，最新的都在
        for (int i = 0; i < 5; i++) {
            assertThat(photoCapture.getPhotos(taskIds.get(i)))
                    .as("最老任务应被淘汰")
                    .isEmpty();
        }
        for (int i = 5; i < PhotoCaptureService.MAX_TASKS + 5; i++) {
            assertThat(photoCapture.getPhotos(taskIds.get(i)))
                    .as("最近任务必须有照片")
                    .isNotEmpty();
        }
    }

    @Test
    @DisplayName("读路径不续命：读了老任务也不会让它逃过淘汰")
    void readsDoNotRefreshEvictionOrder() {
        for (int i = 0; i < PhotoCaptureService.MAX_TASKS; i++) {
            captureForTask("task-" + i);
        }
        // 反复读最老任务，之后再来一个新任务
        for (int r = 0; r < 10; r++) {
            photoCapture.getPhotos("task-0");
        }
        captureForTask("newcomer");

        assertThat(photoCapture.getPhotos("task-0"))
                .as("只被读取不该让老任务续命")
                .isEmpty();
        assertThat(photoCapture.getPhotos("newcomer")).isNotEmpty();
    }

    @Test
    @DisplayName("成果上限内保留全部，且 resultsById 与 resultsByTask 一致")
    void resultsBoundedAndIndexesConsistent() {
        List<String> ids = new ArrayList<>();
        for (int i = 0; i < MappingResultService.MAX_RESULTS; i++) {
            ids.add(mappingResult.generateOrthophoto("task-a", List.of(waypointPhoto())).getId());
        }
        assertThat(mappingResult.getAllResults()).hasSize(MappingResultService.MAX_RESULTS);
        assertThat(mappingResult.getResultsByTask("task-a")).hasSize(MappingResultService.MAX_RESULTS);

        // 再加 3 条 → 淘汰最旧 3 条，两个 map 必须同步
        List<String> tail = new ArrayList<>();
        for (int i = 0; i < 3; i++) {
            tail.add(mappingResult.generateOrthophoto("task-a", List.of(waypointPhoto())).getId());
        }

        assertThat(mappingResult.getAllResults())
                .as("成果总数必须有界")
                .hasSize(MappingResultService.MAX_RESULTS);
        for (String oldId : ids.subList(0, 3)) {
            assertThat(mappingResult.getResult(oldId))
                    .as("主表淘汰后不能还能按 ID 查到")
                    .isNull();
        }
        assertThat(mappingResult.getResultsByTask("task-a"))
                .as("索引不能残留指向已淘汰成果的孤儿条目")
                .hasSize(MappingResultService.MAX_RESULTS);
    }

    private CapturedPhoto waypointPhoto() {
        return photoCapture.capture(1, waypoint());
    }

    @Test
    @DisplayName("任务被淘汰清空后，Map key 本身也要移除（否则 key 继续泄漏）")
    void taskIndexKeyRemovedWhenEmptied() {
        // 单任务产出一个成果后就淘汰它
        String expired = mappingResult.generateOrthophoto("solo-task", List.of(waypointPhoto())).getId();
        assertThat(mappingResult.getResult(expired)).isNotNull();

        for (int i = 0; i < MappingResultService.MAX_RESULTS; i++) {
            mappingResult.generateOrthophoto("filler", List.of(waypointPhoto()));
        }
        assertThat(mappingResult.getResultsByTask("solo-task"))
                .as("solo-task 的最后一条被淘汰后，key 必须一并移除")
                .isEmpty();
    }
}
