package io.aerofleet.cloud.show;

import io.aerofleet.cloud.api.ApiExceptionHandler.BadRequestException;
import io.aerofleet.cloud.api.ApiExceptionHandler.NotFoundException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;

/**
 * 表演任务管理服务。
 * <p>
 * 通过 {@link ShowTaskRepository} 持久化任务数据，承载任务创建 / 查询 / 启动 / 中止；
 * 队形定义委托 {@link FormationService} 查询。
 */
@Service
public class ShowTaskService {

    private static final Logger log = LoggerFactory.getLogger(ShowTaskService.class);

    private final ShowTaskRepository repository;
    private final FormationService formationService;

    @Autowired
    public ShowTaskService(ShowTaskRepository repository, FormationService formationService) {
        this.repository = repository;
        this.formationService = formationService;
    }

    /**
     * 创建表演任务。
     *
     * @param name        任务名称
     * @param formationId 队形定义 ID
     * @param droneSysids 参与表演的无人机 sysid 列表
     * @param durationSec 表演持续时间（秒）
     * @param altitudeM   表演高度（米）
     * @param centerLat   队形中心纬度
     * @param centerLon   队形中心经度
     * @return 创建的表演任务
     * @throws NotFoundException 队形不存在
     * @throws BadRequestException 参数非法
     */
    @Transactional
    public ShowTask createTask(String name, String formationId,
                               List<Integer> droneSysids, int durationSec,
                               double altitudeM, double centerLat, double centerLon) {
        if (name == null || name.isBlank()) {
            throw new BadRequestException("name is required");
        }
        if (formationId == null || formationId.isBlank()) {
            throw new BadRequestException("formationId is required");
        }
        // 校验队形存在
        FormationDefinition formation = formationService.getFormation(formationId);
        if (droneSysids == null || droneSysids.isEmpty()) {
            throw new BadRequestException("droneSysids must not be empty");
        }
        if (durationSec <= 0) {
            throw new BadRequestException("durationSec must be positive");
        }
        if (altitudeM <= 0) {
            throw new BadRequestException("altitudeM must be positive");
        }
        // 校验无人机数量与队形定义匹配
        if (droneSysids.size() != formation.getDroneCount()) {
            throw new BadRequestException(
                    "drone count mismatch: formation requires " + formation.getDroneCount()
                            + " but got " + droneSysids.size());
        }
        String taskId = UUID.randomUUID().toString();
        ShowTask task = new ShowTask(taskId, name, formationId,
                droneSysids, durationSec, altitudeM, centerLat, centerLon);
        repository.save(task);
        log.info("Show task created: id={} name={} formation={} drones={}",
                taskId, name, formationId, droneSysids.size());
        return task;
    }

    /** 列出所有表演任务（可按状态筛选）。 */
    public List<ShowTask> listTasks(ShowStatus status) {
        if (status == null) {
            return repository.findAll();
        }
        return repository.findAll().stream()
                .filter(t -> t.getStatus() == status)
                .toList();
    }

    /** 获取任务详情。 */
    public ShowTask getTask(String taskId) {
        ShowTask task = repository.findById(taskId).orElse(null);
        if (task == null) {
            throw new NotFoundException("show task not found: " + taskId);
        }
        return task;
    }

    /** 启动表演任务：CREATED → DEPLOYING → PERFORMING。 */
    @Transactional
    public ShowTask startTask(String taskId) {
        ShowTask task = getTask(taskId);
        task.deploy();
        task.perform();
        repository.save(task);
        log.info("Show task started: id={}", taskId);
        return task;
    }

    /** 中止表演任务。 */
    @Transactional
    public ShowTask abortTask(String taskId) {
        ShowTask task = getTask(taskId);
        task.abort();
        repository.save(task);
        log.info("Show task aborted: id={}", taskId);
        return task;
    }
}
