package io.aerofleet.cloud.citytwin;

import io.aerofleet.cloud.api.exception.ApiExceptionHandler.NotFoundException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 城市模型管理服务，负责城市三维模型的注册、查询、删除和刷新。
 */
@Service
public class CityModelService {

    private static final Logger log = LoggerFactory.getLogger(CityModelService.class);

    private final Map<String, CityModel> models = new ConcurrentHashMap<>();

    /**
     * 列出所有城市模型。
     */
    public List<CityModel> listModels() {
        return new ArrayList<>(models.values());
    }

    /**
     * 获取模型详情。
     */
    public CityModel getModel(String id) {
        CityModel model = models.get(id);
        if (model == null) {
            throw new NotFoundException("model not found: " + id);
        }
        return model;
    }

    /**
     * 注册新模型。
     */
    public CityModel registerModel(CityModel model) {
        if (model.getId() == null || model.getId().isEmpty()) {
            model.setId(UUID.randomUUID().toString());
        }
        long now = System.currentTimeMillis();
        model.setLoadedAt(now);
        model.setLastUpdatedAt(now);
        if (model.getStatus() == null) {
            model.setStatus(CityModel.ModelStatus.LOADED);
        }
        models.put(model.getId(), model);
        log.info("City model registered: id={} name={} type={}", model.getId(), model.getName(), model.getModelType());
        return model;
    }

    /**
     * 删除模型。
     */
    public void deleteModel(String id) {
        CityModel removed = models.remove(id);
        if (removed == null) {
            throw new NotFoundException("model not found: " + id);
        }
        log.info("City model deleted: id={}", id);
    }

    /**
     * 刷新模型数据（模拟重新加载）。
     */
    public CityModel refreshModel(String id) {
        CityModel model = models.get(id);
        if (model == null) {
            throw new NotFoundException("model not found: " + id);
        }
        model.setLastUpdatedAt(System.currentTimeMillis());
        model.setStatus(CityModel.ModelStatus.LOADED);
        log.info("City model refreshed: id={}", id);
        return model;
    }
}