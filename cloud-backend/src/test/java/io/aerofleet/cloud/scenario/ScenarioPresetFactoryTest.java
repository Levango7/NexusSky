package io.aerofleet.cloud.scenario;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.EnumMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link ScenarioPresetFactory} 预设模板工厂单测（P0-2）。
 * <p>
 * 验证：
 * <ul>
 *   <li>18 个预设模板全部创建成功</li>
 *   <li>每种灾害类型至少有 1 个模板</li>
 *   <li>模板参数合理性（droneCount &gt; 0, radiusKm &gt; 0）</li>
 * </ul>
 */
@DisplayName("ScenarioPresetFactory 预设模板 (P0-2)")
class ScenarioPresetFactoryTest {

    @Test
    @DisplayName("全部 18 个预设模板创建成功")
    void allPresetsCreated() {
        List<ScenarioTemplate> all = ScenarioPresetFactory.all();
        assertThat(all).hasSize(18);
    }

    @Test
    @DisplayName("size() 返回 18")
    void sizeReturns18() {
        assertThat(ScenarioPresetFactory.size()).isEqualTo(18);
    }

    @Test
    @DisplayName("每种灾害类型至少有 1 个模板")
    void eachDisasterTypeHasAtLeastOneTemplate() {
        for (DisasterType type : DisasterType.values()) {
            List<ScenarioTemplate> templates = ScenarioPresetFactory.byDisasterType(type);
            assertThat(templates)
                    .as("disaster type %s should have at least 1 template", type)
                    .isNotEmpty();
        }
    }

    @Test
    @DisplayName("每种灾害类型恰好有 3 个模板（SMALL/MEDIUM/LARGE）")
    void eachDisasterTypeHasThreeTemplates() {
        for (DisasterType type : DisasterType.values()) {
            List<ScenarioTemplate> templates = ScenarioPresetFactory.byDisasterType(type);
            assertThat(templates)
                    .as("disaster type %s should have exactly 3 templates", type)
                    .hasSize(3);
        }
    }

    @Test
    @DisplayName("所有模板参数合理性：droneCount > 0, radiusKm > 0, hoverAltitudeM > 0, durationMin > 0")
    void allTemplatesHaveValidParameters() {
        for (ScenarioTemplate t : ScenarioPresetFactory.all()) {
            assertThat(t.getDroneCount())
                    .as("template %s droneCount > 0", t.getId())
                    .isGreaterThan(0);
            assertThat(t.getRadiusKm())
                    .as("template %s radiusKm > 0", t.getId())
                    .isGreaterThan(0);
            assertThat(t.getHoverAltitudeM())
                    .as("template %s hoverAltitudeM > 0", t.getId())
                    .isGreaterThan(0);
            assertThat(t.getDurationMin())
                    .as("template %s durationMin > 0", t.getId())
                    .isGreaterThan(0);
        }
    }

    @Test
    @DisplayName("所有模板 ID 唯一")
    void allTemplateIdsUnique() {
        List<ScenarioTemplate> all = ScenarioPresetFactory.all();
        long uniqueIds = all.stream().map(ScenarioTemplate::getId).distinct().count();
        assertThat(uniqueIds).isEqualTo(18);
    }

    @Test
    @DisplayName("所有模板名称非空")
    void allTemplateNamesNonBlank() {
        for (ScenarioTemplate t : ScenarioPresetFactory.all()) {
            assertThat(t.getName())
                    .as("template %s name should not be blank", t.getId())
                    .isNotBlank();
        }
    }

    @Test
    @DisplayName("所有模板描述非空")
    void allTemplateDescriptionsNonBlank() {
        for (ScenarioTemplate t : ScenarioPresetFactory.all()) {
            assertThat(t.getDescription())
                    .as("template %s description should not be blank", t.getId())
                    .isNotBlank();
        }
    }

    @Test
    @DisplayName("byId 查找存在的模板返回非 null")
    void byIdReturnsExistingTemplate() {
        ScenarioTemplate t = ScenarioPresetFactory.byId("preset-FIRE-SMALL");
        assertThat(t).isNotNull();
        assertThat(t.getDisasterType()).isEqualTo(DisasterType.FIRE);
        assertThat(t.getSeverityLevel()).isEqualTo(ScenarioTemplate.SeverityLevel.SMALL);
    }

    @Test
    @DisplayName("byId 查找不存在的模板返回 null")
    void byIdReturnsNullForNonExistent() {
        assertThat(ScenarioPresetFactory.byId("non-existent")).isNull();
    }

    @Test
    @DisplayName("火灾 SMALL: 2 架, 1km, 侦察优先")
    void fireSmallPreset() {
        ScenarioTemplate t = ScenarioPresetFactory.byId("preset-FIRE-SMALL");
        assertThat(t).isNotNull();
        assertThat(t.getDroneCount()).isEqualTo(2);
        assertThat(t.getRadiusKm()).isEqualTo(1.0);
        assertThat(t.getCollaborationStrategy())
                .isEqualTo(ScenarioTemplate.CollaborationStrategy.RECON_ONLY);
    }

    @Test
    @DisplayName("火灾 LARGE: 6 架, 3km, 侦察+中继+执行")
    void fireLargePreset() {
        ScenarioTemplate t = ScenarioPresetFactory.byId("preset-FIRE-LARGE");
        assertThat(t).isNotNull();
        assertThat(t.getDroneCount()).isEqualTo(6);
        assertThat(t.getRadiusKm()).isEqualTo(3.0);
        assertThat(t.getCollaborationStrategy())
                .isEqualTo(ScenarioTemplate.CollaborationStrategy.RECON_RELAY_EXEC);
    }

    @Test
    @DisplayName("洪水 SMALL: 3 架, 2km, 侦察+搜救")
    void floodSmallPreset() {
        ScenarioTemplate t = ScenarioPresetFactory.byId("preset-FLOOD-SMALL");
        assertThat(t).isNotNull();
        assertThat(t.getDroneCount()).isEqualTo(3);
        assertThat(t.getRadiusKm()).isEqualTo(2.0);
        assertThat(t.getCollaborationStrategy())
                .isEqualTo(ScenarioTemplate.CollaborationStrategy.RECON_ONLY);
    }

    @Test
    @DisplayName("地震 LARGE: 12 架, 5km, 全角色协同")
    void earthquakeLargePreset() {
        ScenarioTemplate t = ScenarioPresetFactory.byId("preset-EARTHQUAKE-LARGE");
        assertThat(t).isNotNull();
        assertThat(t.getDroneCount()).isEqualTo(12);
        assertThat(t.getRadiusKm()).isEqualTo(5.0);
        assertThat(t.getCollaborationStrategy())
                .isEqualTo(ScenarioTemplate.CollaborationStrategy.RECON_RELAY_EXEC);
    }

    @Test
    @DisplayName("泥石流 MEDIUM: 6 架, 3km, 侦察+中继")
    void mudslideMediumPreset() {
        ScenarioTemplate t = ScenarioPresetFactory.byId("preset-MUDSLIDE-MEDIUM");
        assertThat(t).isNotNull();
        assertThat(t.getDroneCount()).isEqualTo(6);
        assertThat(t.getRadiusKm()).isEqualTo(3.0);
        assertThat(t.getCollaborationStrategy())
                .isEqualTo(ScenarioTemplate.CollaborationStrategy.RECON_RELAY);
    }

    @Test
    @DisplayName("化工厂泄漏 LARGE: 8 架, 2km, 侦察+监测（悬停时间长）")
    void chemicalLeakLargePreset() {
        ScenarioTemplate t = ScenarioPresetFactory.byId("preset-CHEMICAL_LEAK-LARGE");
        assertThat(t).isNotNull();
        assertThat(t.getDroneCount()).isEqualTo(8);
        assertThat(t.getRadiusKm()).isEqualTo(2.0);
        assertThat(t.getCollaborationStrategy())
                .isEqualTo(ScenarioTemplate.CollaborationStrategy.RECON_RELAY_EXEC);
        // 悬停时间长：durationMin >= 120
        assertThat(t.getDurationMin()).isGreaterThanOrEqualTo(120);
    }

    @Test
    @DisplayName("群体性事件 MEDIUM: 4 架, 1km, 侦察+中继")
    void massEventMediumPreset() {
        ScenarioTemplate t = ScenarioPresetFactory.byId("preset-MASS_EVENT-MEDIUM");
        assertThat(t).isNotNull();
        assertThat(t.getDroneCount()).isEqualTo(4);
        assertThat(t.getRadiusKm()).isEqualTo(1.0);
        assertThat(t.getCollaborationStrategy())
                .isEqualTo(ScenarioTemplate.CollaborationStrategy.RECON_RELAY);
    }

    @Test
    @DisplayName("每种灾害类型覆盖 SMALL/MEDIUM/LARGE 三种规模")
    void eachDisasterTypeCoversAllSeverityLevels() {
        for (DisasterType type : DisasterType.values()) {
            List<ScenarioTemplate> templates = ScenarioPresetFactory.byDisasterType(type);
            Map<ScenarioTemplate.SeverityLevel, ScenarioTemplate> bySeverity = new EnumMap<>(ScenarioTemplate.SeverityLevel.class);
            for (ScenarioTemplate t : templates) {
                bySeverity.put(t.getSeverityLevel(), t);
            }
            assertThat(bySeverity.keySet())
                    .as("disaster type %s should cover all severity levels", type)
                    .containsExactlyInAnyOrder(
                            ScenarioTemplate.SeverityLevel.SMALL,
                            ScenarioTemplate.SeverityLevel.MEDIUM,
                            ScenarioTemplate.SeverityLevel.LARGE);
        }
    }
}