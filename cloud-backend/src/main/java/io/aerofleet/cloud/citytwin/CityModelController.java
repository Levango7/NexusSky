package io.aerofleet.cloud.citytwin;

import io.swagger.v3.oas.annotations.Operation;
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

import java.util.List;

/**
 * 城市模型管理 REST API。
 * <p>
 * 端点：
 * <ul>
 *   <li>{@code GET /api/city-twin/models} — 列出所有城市模型</li>
 *   <li>{@code GET /api/city-twin/models/{id}} — 获取模型详情</li>
 *   <li>{@code POST /api/city-twin/models} — 上传/注册新模型</li>
 *   <li>{@code DELETE /api/city-twin/models/{id}} — 删除模型</li>
 *   <li>{@code PUT /api/city-twin/models/{id}/refresh} — 刷新模型数据</li>
 * </ul>
 */
@RestController
@RequestMapping("/api/city-twin/models")
@Tag(name = "CityTwin-Models", description = "城市三维模型管理：模型注册、查询、删除、刷新")
public class CityModelController {

    private static final Logger log = LoggerFactory.getLogger(CityModelController.class);

    private final CityModelService cityModelService;

    public CityModelController(CityModelService cityModelService) {
        this.cityModelService = cityModelService;
    }

    @GetMapping
    @Operation(summary = "列出所有城市模型", description = "返回当前注册的所有城市三维模型列表")
    public List<CityModel> listModels() {
        log.debug("Listing all city models");
        return cityModelService.listModels();
    }

    @GetMapping("/{id}")
    @Operation(summary = "获取模型详情", description = "根据模型 ID 获取城市模型的详细信息")
    public CityModel getModel(@PathVariable("id") String id) {
        log.debug("Getting city model: id={}", id);
        return cityModelService.getModel(id);
    }

    @PostMapping
    @Operation(summary = "上传/注册新模型", description = "注册一个新的城市三维模型")
    public CityModel registerModel(@RequestBody CityModel model) {
        log.info("Registering city model: name={} type={}", model.getName(), model.getModelType());
        return cityModelService.registerModel(model);
    }

    @DeleteMapping("/{id}")
    @Operation(summary = "删除模型", description = "根据模型 ID 删除城市模型")
    public void deleteModel(@PathVariable("id") String id) {
        log.info("Deleting city model: id={}", id);
        cityModelService.deleteModel(id);
    }

    @PutMapping("/{id}/refresh")
    @Operation(summary = "刷新模型数据", description = "刷新指定城市模型的数据，更新时间戳")
    public CityModel refreshModel(@PathVariable("id") String id) {
        log.info("Refreshing city model: id={}", id);
        return cityModelService.refreshModel(id);
    }
}