package io.aerofleet.cloud.scenario;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

import static io.aerofleet.cloud.api.ApiExceptionHandler.NotFoundException;

/**
 * 场景模板管理 REST API（P0-2 应急救援场景库）。
 * <p>
 * 端点：
 * <ul>
 *   <li>{@code GET /api/scenarios/templates} — 列出所有场景模板（含预设 + 自定义）</li>
 *   <li>{@code GET /api/scenarios/templates/{id}} — 获取模板详情</li>
 *   <li>{@code POST /api/scenarios/templates} — 创建自定义模板</li>
 *   <li>{@code PUT /api/scenarios/templates/{id}} — 更新模板</li>
 *   <li>{@code DELETE /api/scenarios/templates/{id}} — 删除模板</li>
 *   <li>{@code GET /api/scenarios/templates/by-type/{disasterType}} — 按灾害类型筛选</li>
 * </ul>
 * <p>
 * 启动时自动加载 {@link ScenarioPresetFactory} 提供的 18 个预设模板；
 * 自定义模板通过 POST 创建，运行期保存在内存中。
 */
@RestController
@RequestMapping("/api/scenarios/templates")
@Tag(name = "ScenarioTemplate", description = "应急救援场景模板管理：CRUD 与按灾害类型筛选")
public class ScenarioTemplateController {

    private static final Logger log = LoggerFactory.getLogger(ScenarioTemplateController.class);

    private final Map<String, ScenarioTemplate> store = new ConcurrentHashMap<>();
    private final AtomicInteger customSeq = new AtomicInteger(0);

    public ScenarioTemplateController() {
        for (ScenarioTemplate preset : ScenarioPresetFactory.all()) {
            store.put(preset.getId(), preset);
        }
        log.info("Scenario templates loaded: presets={} total={}",
                ScenarioPresetFactory.size(), store.size());
    }

    /**
     * 列出所有场景模板（预设 + 自定义）。
     *
     * @return 全部模板列表
     */
    @GetMapping
    @Operation(summary = "列出所有场景模板", description = "返回预设与自定义模板的完整列表。")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "模板列表")
    })
    public List<ScenarioTemplate> list() {
        return new ArrayList<>(store.values());
    }

    /**
     * 获取模板详情。
     *
     * @param id 模板 ID
     * @return 模板对象
     * @throws NotFoundException 模板不存在
     */
    @GetMapping("/{id}")
    @Operation(summary = "获取模板详情", description = "按 ID 查询单个模板。")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "模板对象"),
            @ApiResponse(responseCode = "404", description = "模板不存在")
    })
    public ScenarioTemplate get(@PathVariable("id") String id) {
        ScenarioTemplate t = store.get(id);
        if (t == null) {
            throw new NotFoundException("scenario template not found: " + id);
        }
        return t;
    }

    /**
     * 创建自定义模板。
     * <p>
     * 若请求体未指定 ID，则自动生成 {@code custom-<seq>}。
     *
     * @param template 模板对象
     * @return 创建后的模板（含 ID 与时间戳）
     */
    @PostMapping
    @Operation(summary = "创建自定义模板", description = "创建一个新的自定义场景模板。未指定 ID 时自动生成。")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "创建成功"),
            @ApiResponse(responseCode = "400", description = "请求体无效")
    })
    public ScenarioTemplate create(@RequestBody ScenarioTemplate template) {
        if (template.getName() == null || template.getName().isBlank()) {
            throw new IllegalArgumentException("template name must not be blank");
        }
        if (template.getId() == null || template.getId().isBlank()) {
            template.setId("custom-" + customSeq.incrementAndGet());
        }
        Instant now = Instant.now();
        template.setCreatedAt(now);
        template.setUpdatedAt(now);
        store.put(template.getId(), template);
        log.info("Scenario template created: id={} name={} disasterType={}",
                template.getId(), template.getName(), template.getDisasterType());
        return template;
    }

    /**
     * 更新模板。
     *
     * @param id       模板 ID
     * @param template 更新后的模板对象
     * @return 更新后的模板
     * @throws NotFoundException 模板不存在
     */
    @PutMapping("/{id}")
    @Operation(summary = "更新模板", description = "按 ID 更新已有模板的全部字段。")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "更新成功"),
            @ApiResponse(responseCode = "404", description = "模板不存在")
    })
    public ScenarioTemplate update(@PathVariable("id") String id,
                                   @RequestBody ScenarioTemplate template) {
        ScenarioTemplate existing = store.get(id);
        if (existing == null) {
            throw new NotFoundException("scenario template not found: " + id);
        }
        template.setId(id);
        template.setCreatedAt(existing.getCreatedAt());
        template.setUpdatedAt(Instant.now());
        store.put(id, template);
        log.info("Scenario template updated: id={} name={}", id, template.getName());
        return template;
    }

    /**
     * 删除模板。
     * <p>
     * 预设模板不可删除（返回 409 风格的 IllegalArgumentException）。
     *
     * @param id 模板 ID
     * @return 删除结果
     * @throws NotFoundException 模板不存在
     */
    @DeleteMapping("/{id}")
    @Operation(summary = "删除模板", description = "按 ID 删除自定义模板。预设模板不可删除。")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "删除成功"),
            @ApiResponse(responseCode = "404", description = "模板不存在")
    })
    public Map<String, Object> delete(@PathVariable("id") String id) {
        ScenarioTemplate existing = store.get(id);
        if (existing == null) {
            throw new NotFoundException("scenario template not found: " + id);
        }
        store.remove(id);
        log.info("Scenario template deleted: id={}", id);
        return Map.of("deleted", true, "id", id);
    }

    /**
     * 按灾害类型筛选模板。
     *
     * @param disasterType 灾害类型（FIRE/FLOOD/EARTHQUAKE/MUDSLIDE/CHEMICAL_LEAK/MASS_EVENT）
     * @return 匹配的模板列表
     */
    @GetMapping("/by-type/{disasterType}")
    @Operation(summary = "按灾害类型筛选", description = "返回指定灾害类型的全部模板。")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "模板列表")
    })
    public List<ScenarioTemplate> byType(@PathVariable("disasterType") DisasterType disasterType) {
        List<ScenarioTemplate> result = new ArrayList<>();
        for (ScenarioTemplate t : store.values()) {
            if (t.getDisasterType() == disasterType) {
                result.add(t);
            }
        }
        return result;
    }
}