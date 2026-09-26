package io.aerofleet.cloud.mapping;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link MappingResultService} 单测。
 * <p>
 * 测试正射影像、DEM、三维模型成果生成。
 */
@DisplayName("MappingResultService 成果生成 (P2-2)")
class MappingResultServiceTest {

    private MappingResultService resultService;

    @BeforeEach
    void setUp() {
        resultService = new MappingResultService();
    }

    private List<CapturedPhoto> samplePhotos() {
        List<CapturedPhoto> photos = new ArrayList<>();
        for (int i = 0; i < 10; i++) {
            photos.add(new CapturedPhoto(
                    "photo-" + i,
                    "task-001",
                    1,
                    39.90 + i * 0.001,
                    116.40 + i * 0.001,
                    100.0,
                    90.0,
                    0.0,
                    0.0,
                    java.time.Instant.now(),
                    5_000_000L,
                    "https://storage.mapping/photos/photo-" + i + ".jpg"
            ));
        }
        return photos;
    }

    // ========== 正射影像 ==========

    @Test
    @DisplayName("生成正射影像成果")
    void generateOrthophoto() {
        List<CapturedPhoto> photos = samplePhotos();
        MappingResult result = resultService.generateOrthophoto("task-001", photos);

        assertThat(result).isNotNull();
        assertThat(result.getTaskId()).isEqualTo("task-001");
        assertThat(result.getType()).isEqualTo(MappingType.ORTHO_PHOTO);
        assertThat(result.getStatus()).isEqualTo(MappingResult.Status.COMPLETED);
        assertThat(result.getOrthophotoUrl()).isNotNull();
        assertThat(result.getOrthophotoUrl()).contains("orthophoto");
        assertThat(result.getDemUrl()).isNull();
        assertThat(result.getModelUrl()).isNull();
        assertThat(result.getCoveragePct()).isGreaterThan(0);
        assertThat(result.getCoveragePct()).isLessThanOrEqualTo(100);
        assertThat(result.getResolutionCm()).isGreaterThan(0);
        assertThat(result.getProcessingTimeSec()).isGreaterThan(0);
        assertThat(result.getFileSizeMB()).isGreaterThan(0);
    }

    @Test
    @DisplayName("正射影像成果 URL 包含唯一 ID")
    void orthophotoUrlContainsUniqueId() {
        MappingResult r1 = resultService.generateOrthophoto("task-001", samplePhotos());
        MappingResult r2 = resultService.generateOrthophoto("task-002", samplePhotos());

        assertThat(r1.getOrthophotoUrl()).isNotEqualTo(r2.getOrthophotoUrl());
    }

    // ========== DEM ==========

    @Test
    @DisplayName("生成 DEM 成果")
    void generateDem() {
        List<CapturedPhoto> photos = samplePhotos();
        MappingResult result = resultService.generateDem("task-001", photos);

        assertThat(result).isNotNull();
        assertThat(result.getTaskId()).isEqualTo("task-001");
        assertThat(result.getType()).isEqualTo(MappingType.DEM);
        assertThat(result.getStatus()).isEqualTo(MappingResult.Status.COMPLETED);
        assertThat(result.getDemUrl()).isNotNull();
        assertThat(result.getDemUrl()).contains("dem");
        assertThat(result.getOrthophotoUrl()).isNull();
        assertThat(result.getModelUrl()).isNull();
        assertThat(result.getCoveragePct()).isGreaterThan(0);
        assertThat(result.getCoveragePct()).isLessThanOrEqualTo(100);
    }

    // ========== 三维模型 ==========

    @Test
    @DisplayName("生成三维模型成果")
    void generate3DModel() {
        List<CapturedPhoto> photos = samplePhotos();
        MappingResult result = resultService.generate3DModel("task-001", photos);

        assertThat(result).isNotNull();
        assertThat(result.getTaskId()).isEqualTo("task-001");
        assertThat(result.getType()).isEqualTo(MappingType.THREE_D_MODEL);
        assertThat(result.getStatus()).isEqualTo(MappingResult.Status.COMPLETED);
        assertThat(result.getModelUrl()).isNotNull();
        assertThat(result.getModelUrl()).contains("model");
        assertThat(result.getOrthophotoUrl()).isNull();
        assertThat(result.getDemUrl()).isNull();
        assertThat(result.getCoveragePct()).isGreaterThan(0);
        assertThat(result.getCoveragePct()).isLessThanOrEqualTo(100);
    }

    // ========== 查询 ==========

    @Test
    @DisplayName("按任务 ID 查询成果")
    void getResultsByTask() {
        resultService.generateOrthophoto("task-001", samplePhotos());
        resultService.generateDem("task-001", samplePhotos());
        resultService.generate3DModel("task-002", samplePhotos());

        List<MappingResult> task1Results = resultService.getResultsByTask("task-001");
        List<MappingResult> task2Results = resultService.getResultsByTask("task-002");

        assertThat(task1Results).hasSize(2);
        assertThat(task2Results).hasSize(1);
    }

    @Test
    @DisplayName("查询所有成果")
    void getAllResults() {
        resultService.generateOrthophoto("task-001", samplePhotos());
        resultService.generateDem("task-002", samplePhotos());

        List<MappingResult> all = resultService.getAllResults();
        assertThat(all).hasSize(2);
    }

    @Test
    @DisplayName("按成果 ID 查询")
    void getResultById() {
        MappingResult generated = resultService.generateOrthophoto("task-001", samplePhotos());
        String resultId = generated.getId();

        MappingResult found = resultService.getResult(resultId);
        assertThat(found).isNotNull();
        assertThat(found.getId()).isEqualTo(resultId);
    }

    @Test
    @DisplayName("不存在的成果 ID 返回 null")
    void getResultNotFound() {
        MappingResult found = resultService.getResult("nonexistent");
        assertThat(found).isNull();
    }

    @Test
    @DisplayName("空照片列表也能生成成果")
    void generateWithEmptyPhotos() {
        MappingResult result = resultService.generateOrthophoto("task-empty", new ArrayList<>());

        assertThat(result).isNotNull();
        assertThat(result.getStatus()).isEqualTo(MappingResult.Status.COMPLETED);
    }

    @Test
    @DisplayName("同一任务多次生成成果，按任务查询返回全部")
    void multipleResultsForSameTask() {
        resultService.generateOrthophoto("task-multi", samplePhotos());
        resultService.generateDem("task-multi", samplePhotos());
        resultService.generate3DModel("task-multi", samplePhotos());

        List<MappingResult> results = resultService.getResultsByTask("task-multi");
        assertThat(results).hasSize(3);
        // 验证三种类型都有
        boolean hasOrtho = results.stream().anyMatch(r -> r.getType() == MappingType.ORTHO_PHOTO);
        boolean hasDem = results.stream().anyMatch(r -> r.getType() == MappingType.DEM);
        boolean hasModel = results.stream().anyMatch(r -> r.getType() == MappingType.THREE_D_MODEL);
        assertThat(hasOrtho).isTrue();
        assertThat(hasDem).isTrue();
        assertThat(hasModel).isTrue();
    }
}