package io.aerofleet.cloud.delivery2;

import io.aerofleet.cloud.api.ApiExceptionHandler.BadRequestException;
import io.aerofleet.cloud.api.ApiExceptionHandler.NotFoundException;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 无人机物流配送 REST API（P4-1）。
 * <p>
 * 端点：
 * <ul>
 *   <li>POST /api/delivery2/tasks — 创建配送任务</li>
 *   <li>GET /api/delivery2/tasks — 列出配送任务</li>
 *   <li>GET /api/delivery2/tasks/{id} — 获取任务详情</li>
 *   <li>POST /api/delivery2/tasks/{id}/start — 启动配送</li>
 *   <li>POST /api/delivery2/tasks/{id}/abort — 中止配送</li>
 *   <li>GET /api/delivery2/tasks/{id}/route — 获取优化路线</li>
 *   <li>POST /api/delivery2/tasks/{id}/deliver — 执行投放</li>
 *   <li>GET /api/delivery2/tasks/{id}/status — 配送状态</li>
 *   <li>POST /api/delivery2/tasks/{id}/confirm — 确认签收</li>
 *   <li>GET /api/delivery2/landing-sites — 搜索降落点</li>
 * </ul>
 */
@RestController
@RequestMapping("/api/v1/delivery2")
@Tag(name = "Delivery2", description = "无人机物流配送 REST API：应急物资空投、医疗样本运输、偏远地区配送")
public class DeliveryController2 {

    private static final Logger log = LoggerFactory.getLogger(DeliveryController2.class);

    private final RouteOptimizer routeOptimizer;
    private final LandingSiteSelector landingSiteSelector;
    private final DeliveryStatusTracker statusTracker;

    @Autowired
    private DeliveryTask2Repository repository;

    private final AtomicInteger taskCounter = new AtomicInteger(0);

    public DeliveryController2(RouteOptimizer routeOptimizer,
                               LandingSiteSelector landingSiteSelector,
                               DeliveryStatusTracker statusTracker) {
        this.routeOptimizer = routeOptimizer;
        this.landingSiteSelector = landingSiteSelector;
        this.statusTracker = statusTracker;
    }

    /** 创建配送任务。 */
    @PostMapping("/tasks")
    @Transactional
    @Operation(summary = "创建配送任务", description = "创建一个新的无人机配送任务")
    public DeliveryTask2 createTask(@RequestBody DeliveryTask2 task) {
        if (task == null) {
            throw new BadRequestException("task body is required");
        }
        if (task.getType() == null) {
            throw new BadRequestException("type is required");
        }
        if (task.getSenderLat() == 0.0 || task.getSenderLon() == 0.0) {
            throw new BadRequestException("senderLat and senderLon are required");
        }
        if (task.getReceiverLat() == 0.0 || task.getReceiverLon() == 0.0) {
            throw new BadRequestException("receiverLat and receiverLon are required");
        }
        if (task.getPayload() == null) {
            throw new BadRequestException("payload is required");
        }
        if (task.getPayload().getWeightKg() <= 0) {
            throw new BadRequestException("payload weightKg must be > 0");
        }
        // 在 id 中引入时间戳分量，避免服务重启后 taskCounter 归零导致与已持久化主键冲突；
        // taskCounter 仍负责同一毫秒内并发创建时的序号区分。
        String id = "DT-" + System.currentTimeMillis() + "-" + String.format("%04d", taskCounter.incrementAndGet());
        task.setId(id);
        if (task.getStatus() == null) {
            task.setStatus(DeliveryTask2.Status.PENDING);
        }
        repository.save(task);
        statusTracker.initStatus(id);
        log.info("配送任务创建：id={} type={} priority={}", id, task.getType(), task.getPriority());
        return task;
    }

    /** 列出配送任务。 */
    @GetMapping("/tasks")
    @Operation(summary = "列出配送任务", description = "返回所有配送任务列表")
    public List<DeliveryTask2> listTasks() {
        return repository.findAll();
    }

    /** 获取任务详情。 */
    @GetMapping("/tasks/{id}")
    @Operation(summary = "获取任务详情", description = "根据任务 ID 获取配送任务详细信息")
    public DeliveryTask2 getTask(@PathVariable("id") String id) {
        DeliveryTask2 task = repository.findById(id).orElse(null);
        if (task == null) {
            throw new NotFoundException("delivery task " + id + " not found");
        }
        return task;
    }

    /** 启动配送。 */
    @PostMapping("/tasks/{id}/start")
    @Transactional
    @Operation(summary = "启动配送", description = "启动指定配送任务，状态从 PENDING 转为 IN_PROGRESS")
    public DeliveryTask2 startTask(@PathVariable("id") String id) {
        DeliveryTask2 task = repository.findById(id).orElse(null);
        if (task == null) {
            throw new NotFoundException("delivery task " + id + " not found");
        }
        if (task.getStatus() != DeliveryTask2.Status.PENDING) {
            throw new BadRequestException("task " + id + " is not in PENDING state");
        }
        task.setStatus(DeliveryTask2.Status.IN_PROGRESS);
        task.setStartTime(Instant.now());
        repository.save(task);
        statusTracker.advancePhase(id);
        statusTracker.advancePhase(id);
        log.info("配送任务启动：id={}", id);
        return task;
    }

    /** 中止配送。 */
    @PostMapping("/tasks/{id}/abort")
    @Transactional
    @Operation(summary = "中止配送", description = "中止指定配送任务，状态转为 ABORTED")
    public DeliveryTask2 abortTask(@PathVariable("id") String id) {
        DeliveryTask2 task = repository.findById(id).orElse(null);
        if (task == null) {
            throw new NotFoundException("delivery task " + id + " not found");
        }
        if (task.getStatus() != DeliveryTask2.Status.PENDING
                && task.getStatus() != DeliveryTask2.Status.IN_PROGRESS) {
            throw new BadRequestException(
                    "task " + id + " cannot be aborted from state " + task.getStatus());
        }
        task.setStatus(DeliveryTask2.Status.ABORTED);
        repository.save(task);
        log.info("配送任务中止：id={}", id);
        return task;
    }

    /** 获取优化路线。 */
    @GetMapping("/tasks/{id}/route")
    @Operation(summary = "获取优化路线", description = "为指定配送任务计算优化路线")
    public OptimizedRoute getRoute(@PathVariable("id") String id) {
        DeliveryTask2 task = repository.findById(id).orElse(null);
        if (task == null) {
            throw new NotFoundException("delivery task " + id + " not found");
        }
        OptimizedRoute route = routeOptimizer.optimizeRoute(List.of(task));
        route.setTaskId(id);
        return route;
    }

    /** 执行投放。 */
    @PostMapping("/tasks/{id}/deliver")
    @Transactional
    @Operation(summary = "执行投放", description = "执行配送投放操作（空投/着陆交付/绳索降下）")
    public Map<String, Object> deliver(@PathVariable("id") String id,
                                       @RequestBody DeliverRequest body) {
        DeliveryTask2 task = repository.findById(id).orElse(null);
        if (task == null) {
            throw new NotFoundException("delivery task " + id + " not found");
        }
        if (body == null || body.method == null) {
            throw new BadRequestException("delivery method is required");
        }
        if (task.getStatus() != DeliveryTask2.Status.IN_PROGRESS) {
            throw new BadRequestException("task " + id + " is not IN_PROGRESS");
        }

        // 根据投放方式选择降落点（LAND_DELIVER 需要降落点）
        LandingSite landingSite = null;
        if (body.method == DeliveryMethod.LAND_DELIVER) {
            landingSite = landingSiteSelector.selectLandingSite(
                    task.getReceiverLat(), task.getReceiverLon());
            if (landingSite == null) {
                throw new BadRequestException(
                        "no suitable landing site found for task " + id);
            }
            boolean verified = landingSiteSelector.verifyLandingSite(landingSite);
            if (!verified) {
                throw new BadRequestException(
                        "landing site " + landingSite.getId() + " verification failed");
            }
        }

        statusTracker.advancePhase(id);
        statusTracker.advancePhase(id);

        task.setStatus(DeliveryTask2.Status.DELIVERED);
        task.setActualDeliveryTime(Instant.now());
        repository.save(task);

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("taskId", id);
        result.put("method", body.method.name());
        result.put("status", task.getStatus().name());
        result.put("landingSite", landingSite);
        log.info("配送投放执行：id={} method={}", id, body.method);
        return result;
    }

    /** 配送状态。 */
    @GetMapping("/tasks/{id}/status")
    @Operation(summary = "配送状态", description = "获取配送任务的实时状态追踪信息")
    public DeliveryStatus getStatus(@PathVariable("id") String id) {
        DeliveryStatus status = statusTracker.getStatus(id);
        if (status == null) {
            throw new NotFoundException("no status for task " + id);
        }
        return status;
    }

    /** 确认签收。 */
    @PostMapping("/tasks/{id}/confirm")
    @Transactional
    @Operation(summary = "确认签收", description = "确认配送任务已签收完成")
    public Map<String, Object> confirm(@PathVariable("id") String id) {
        DeliveryTask2 task = repository.findById(id).orElse(null);
        if (task == null) {
            throw new NotFoundException("delivery task " + id + " not found");
        }
        if (task.getStatus() != DeliveryTask2.Status.DELIVERED) {
            throw new BadRequestException("task " + id + " is not DELIVERED");
        }
        repository.save(task);
        statusTracker.advancePhase(id);

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("taskId", id);
        result.put("confirmed", true);
        result.put("status", task.getStatus().name());
        log.info("配送签收确认：id={}", id);
        return result;
    }

    /** 搜索降落点。 */
    @GetMapping("/landing-sites")
    @Operation(summary = "搜索降落点", description = "根据坐标和半径搜索附近可用降落点")
    public List<LandingSite> searchLandingSites(
            @RequestParam("lat") double lat,
            @RequestParam("lon") double lon,
            @RequestParam(value = "radius", defaultValue = "5.0") double radius) {
        return landingSiteSelector.searchLandingSites(lat, lon, radius);
    }

    /** 投放请求体 DTO。 */
    public static final class DeliverRequest {
        public DeliveryMethod method;
    }
}