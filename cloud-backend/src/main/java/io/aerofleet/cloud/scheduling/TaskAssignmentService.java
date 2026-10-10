package io.aerofleet.cloud.scheduling;

import io.aerofleet.cloud.gateway.DeviceRegistry;
import io.aerofleet.cloud.gateway.DroneSnapshot;
import io.aerofleet.cloud.gateway.MavlinkMessageEvent;
import io.aerofleet.cloud.security.TenantContext;
import io.aerofleet.mavlink.enums.TaskStatusEnum;
import io.aerofleet.mavlink.enums.TaskType;
import io.aerofleet.mavlink.messages.TaskAssignmentMsg;
import io.aerofleet.mavlink.messages.TaskStatusMsg;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.PriorityBlockingQueue;

/**
 * M10 集群智能调度：多机任务分配 + 冲突避免 + 任务队列管理。
 *
 * 分配算法：综合评分 = 能力匹配(40%) + 电量因子(30%) + 距离因子(20%) + 优先级(10%)
 *
 * WS 帧生产（2026-10-05 边界清零；2026-10-10 B4 补齐 FAILED）：分配成功发布
 * TaskAssignmentMsg(30048) + TaskStatusMsg(30050, ASSIGNED)；startTask/pollNextTask
 * 发布 IN_PROGRESS；completeTask 发布 COMPLETED；cancelTask 发布 ABORTED；
 * failTask 发布 FAILED（此前协议值不可达）。
 * <p>
 * <b>ABORTED 与 FAILED 的区分规则</b>：ABORTED = 主动取消（任何阶段，上游/人工
 * 决定不做了——任务记录随之移除）；FAILED = 执行尝试后失败（任务已开始执行、
 * 执行侧报失败——记录保留可查）。两者的重试/放弃策略当前由调用方决定，
 * 调度侧不做自动重试（属后续功能立项）。
 */
@Service
public class TaskAssignmentService {
    private static final Logger log = LoggerFactory.getLogger(TaskAssignmentService.class);

    private final DeviceRegistry registry;
    private final ApplicationEventPublisher eventPublisher;
    private final PriorityBlockingQueue<TaskRequest> taskQueue = new PriorityBlockingQueue<>(100,
            Comparator.comparingInt(TaskRequest::getPriority).reversed());
    private final Map<String, AssignmentResult> assignments = new ConcurrentHashMap<>();
    /** 无人机 → 正在执行的任务ID集合（反向映射，用于负载跟踪） */
    private final Map<Integer, Set<String>> droneToTaskIds = new ConcurrentHashMap<>();
    /** taskId → tenantId（租户隔离映射） */
    private final Map<String, Integer> taskTenantMap = new ConcurrentHashMap<>();

    // --- GA 参数（M10 调度算法优化）---
    /** 任务数低于此值时回退到简单评分，避免 GA 开销无收益 */
    private static final int GA_MIN_TASKS = 4;
    /** 无人机数低于此值时回退到简单评分 */
    private static final int GA_MIN_DRONES = 4;
    private static final int GA_POP_SIZE = 50;
    private static final int GA_GENERATIONS = 100;
    private static final double GA_MUTATION_RATE = 0.1;
    private static final int GA_ELITE = 2;
    private static final int GA_TOURNAMENT_K = 3;

    // --- 评分权重与基础分 ---
    /** 综合评分权重：能力匹配 40%、电量 30%、距离 20%、优先级 10% */
    private static final double WEIGHT_CAPABILITY = 0.4;
    private static final double WEIGHT_BATTERY = 0.3;
    private static final double WEIGHT_DISTANCE = 0.2;
    private static final double WEIGHT_PRIORITY = 0.1;
    /** 基础能力分（0-100） */
    private static final double BASE_CAPABILITY_SCORE = 50.0;
    /** 默认距离满分（无位置数据时） */
    private static final double DEFAULT_DISTANCE_SCORE = 100.0;
    /** 优先级乘数：priority 0-10 → 0-100 */
    private static final double PRIORITY_MULTIPLIER = 10.0;
    /** 距离得分线性递减的基准距离（m），100km 内得分线性递减 */
    private static final double DISTANCE_SCORE_BASE_M = 100_000.0;
    /** 地球半径（m），用于 haversine 距离计算 */
    private static final double EARTH_RADIUS_M = 6371000.0;

    public TaskAssignmentService(DeviceRegistry registry, ApplicationEventPublisher eventPublisher) {
        this.registry = registry;
        this.eventPublisher = eventPublisher;
    }

    /** 分配任务到最优无人机 */
    public synchronized AssignmentResult assignTask(TaskRequest req) {
        List<DroneSnapshot> drones = registry.all();
        if (drones.isEmpty()) {
            log.warn("No drones available for task {}", req.getTaskId());
            return new AssignmentResult(req.getTaskId(), -1, 0, "无可用无人机", false);
        }

        // 优先选择没有正在执行任务的空闲无人机
        DroneSnapshot best = null;
        double bestScore = -1;
        for (DroneSnapshot d : drones) {
            Set<String> activeTasks = droneToTaskIds.get(d.sysid);
            if (activeTasks != null && !activeTasks.isEmpty()) {
                continue; // 跳过有正在执行任务的无人机
            }
            double score = scoreDrone(d, req);
            if (score > bestScore) {
                bestScore = score;
                best = d;
            }
        }

        // 所有无人机都有正在执行的任务时，回退到选择评分最高的无人机（允许排队）
        if (best == null) {
            for (DroneSnapshot d : drones) {
                double score = scoreDrone(d, req);
                if (score > bestScore) {
                    bestScore = score;
                    best = d;
                }
            }
        }

        if (best == null) {
            return new AssignmentResult(req.getTaskId(), -1, 0, "无合适无人机", false);
        }

        AssignmentResult result = new AssignmentResult(req.getTaskId(), best.sysid, bestScore,
                String.format("sysid=%d score=%.1f", best.sysid, bestScore), true);
        assignments.put(req.getTaskId(), result);
        Integer tenantId = TenantContext.getEffectiveTenantId();
        if (tenantId != null) {
            taskTenantMap.put(req.getTaskId(), tenantId);
        }
        taskQueue.offer(req);
        log.info("Task {} assigned to sysid={} score={}", req.getTaskId(), best.sysid, bestScore);
        publishAssignmentFrames(req, best.sysid);
        return result;
    }

    /**
     * 批量分配多个任务到无人机集群（M10 调度算法优化）。
     * <p>
     * 当任务数 ≥ {@value #GA_MIN_TASKS} 且无人机数 ≥ {@value #GA_MIN_DRONES} 时启用遗传算法，
     * 综合优化距离、电量、能力匹配、任务紧急度与负载均衡；否则回退到逐任务简单评分。
     *
     * @param requests 待分配任务列表
     * @return 与输入顺序对应的分配结果列表
     */
    public synchronized List<AssignmentResult> assignTasks(List<TaskRequest> requests) {
        List<DroneSnapshot> drones = registry.all();
        List<AssignmentResult> results = new ArrayList<>();
        if (drones.isEmpty()) {
            for (TaskRequest req : requests) {
                results.add(new AssignmentResult(req.getTaskId(), -1, 0, "无可用无人机", false));
            }
            return results;
        }
        if (requests.isEmpty()) {
            return results;
        }

        int nTasks = requests.size();
        int nDrones = drones.size();
        boolean useGa = nTasks >= GA_MIN_TASKS && nDrones >= GA_MIN_DRONES;

        // 求解最优分配映射：mapping[i] = 第 i 个任务分配到的无人机索引
        int[] mapping = useGa
                ? runGa(requests, drones, new Random())
                : greedyAssign(requests, drones);

        String algo = useGa ? "GA" : "SCORE";
        Integer tenantId = TenantContext.getEffectiveTenantId();
        for (int i = 0; i < nTasks; i++) {
            int di = mapping[i];
            DroneSnapshot d = drones.get(di);
            double score = scoreDrone(d, requests.get(i));
            AssignmentResult r = new AssignmentResult(requests.get(i).getTaskId(), d.sysid, score,
                    String.format("%s sysid=%d score=%.1f", algo, d.sysid, score), true);
            assignments.put(requests.get(i).getTaskId(), r);
            if (tenantId != null) {
                taskTenantMap.put(requests.get(i).getTaskId(), tenantId);
            }
            taskQueue.offer(requests.get(i));
            publishAssignmentFrames(requests.get(i), d.sysid);
            results.add(r);
        }
        log.info("assignTasks: {} tasks to {} drones via {}", nTasks, nDrones, algo);
        return results;
    }

    // ==================== 遗传算法（GA） ====================

    /**
     * 运行遗传算法求解任务-无人机分配。
     * <p>
     * 染色体编码：int[nTasks]，chromosome[i] ∈ [0, nDrones) 表示第 i 个任务分配到的无人机索引。
     *
     * @return 最优染色体的分配映射
     */
    private int[] runGa(List<TaskRequest> tasks, List<DroneSnapshot> drones, Random rnd) {
        final int nTasks = tasks.size();
        final int nDrones = drones.size();
        final int popSize = GA_POP_SIZE;

        // 初始化种群（随机分配）
        int[][] pop = new int[popSize][nTasks];
        double[] fit = new double[popSize];
        for (int i = 0; i < popSize; i++) {
            for (int j = 0; j < nTasks; j++) {
                pop[i][j] = rnd.nextInt(nDrones);
            }
            fit[i] = fitness(pop[i], tasks, drones);
        }

        // 进化
        for (int gen = 0; gen < GA_GENERATIONS; gen++) {
            // 精英保留：按适应度降序取前 GA_ELITE 个直接进入下一代
            final double[] fitRef = fit;
            Integer[] order = new Integer[popSize];
            for (int i = 0; i < popSize; i++) {
                order[i] = i;
            }
            Arrays.sort(order, Comparator.comparingDouble((Integer a) -> fitRef[a]).reversed());

            int[][] newPop = new int[popSize][nTasks];
            double[] newFit = new double[popSize];
            for (int e = 0; e < GA_ELITE && e < popSize; e++) {
                newPop[e] = pop[order[e]].clone();
                newFit[e] = fit[order[e]];
            }
            // 交叉 + 变异填充剩余个体
            for (int i = GA_ELITE; i < popSize; i++) {
                int[] parent1 = tournamentSelect(pop, fit, rnd);
                int[] parent2 = tournamentSelect(pop, fit, rnd);
                int[] child = pmxCrossover(parent1, parent2, rnd);
                if (rnd.nextDouble() < GA_MUTATION_RATE) {
                    mutate(child, rnd);
                }
                newPop[i] = child;
                newFit[i] = fitness(child, tasks, drones);
            }
            pop = newPop;
            fit = newFit;
        }

        // 返回最优个体
        int best = 0;
        for (int i = 1; i < popSize; i++) {
            if (fit[i] > fit[best]) {
                best = i;
            }
        }
        return pop[best];
    }

    /**
     * 适应度函数：综合考虑距离、电量、能力匹配、任务紧急度与负载均衡。
     * <p>
     * 个体适应度 = 平均( scoreDrone×0.6 + capabilityMatch×0.4 ) + 负载均衡奖励
     *
     * @param chrom  染色体（任务->无人机索引映射）
     * @param tasks  任务列表
     * @param drones 无人机列表
     * @return 适应度值，越大越优
     */
    private double fitness(int[] chrom, List<TaskRequest> tasks, List<DroneSnapshot> drones) {
        final int nDrones = drones.size();
        double sum = 0;
        int[] loadCount = new int[nDrones];
        for (int i = 0; i < tasks.size(); i++) {
            int di = chrom[i];
            if (di < 0 || di >= nDrones) {
                di = 0; // 防护：越界回退到首台
            }
            DroneSnapshot d = drones.get(di);
            if (d.online) {
                sum += scoreDrone(d, tasks.get(i)) * 0.6 + capabilityMatch(d, tasks.get(i)) * 0.4;
            }
            // 离线无人机贡献为 0，GA 自然避免分配（均衡奖励不足以补偿）
            loadCount[di]++;
        }
        double avg = sum / tasks.size();

        // 负载均衡奖励：分配任务数的标准差越小（越均衡）奖励越高
        double meanLoad = (double) tasks.size() / nDrones;
        double variance = 0;
        for (int c : loadCount) {
            variance += (c - meanLoad) * (c - meanLoad);
        }
        variance /= nDrones;
        double balanceBonus = Math.max(0, 10.0 - Math.sqrt(variance));

        return avg + balanceBonus;
    }

    /**
     * 能力匹配评分（0-100）：基于在线状态、任务类型电量门槛与当前负载推断载荷/传感器能力。
     * <p>
     * 不同任务类型对电量要求不同：RESCUE > SPRAY > RELAY > SURVEY。
     */
    private double capabilityMatch(DroneSnapshot d, TaskRequest req) {
        if (!d.online) {
            return 0;
        }
        double score = 50.0; // 基础能力分
        int threshold;
        switch (req.getTaskType()) {
            case "RESCUE": threshold = 60; break;
            case "SPRAY":  threshold = 50; break;
            case "RELAY":  threshold = 40; break;
            default:       threshold = 30; break; // SURVEY
        }
        int battery = d.battery > 0 ? d.battery : 0;
        score += (battery >= threshold) ? 30 : 30.0 * battery / Math.max(1, threshold);
        // 当前负载越轻，可用能力越强（load 为千分比，-1 表示未知）
        if (d.load >= 0) {
            score += Math.max(0, 20 - d.load / 50.0);
        } else {
            score += 20;
        }
        return Math.min(100, score);
    }

    /** 锦标赛选择：随机取 k 个个体，返回适应度最高者。 */
    private int[] tournamentSelect(int[][] pop, double[] fit, Random rnd) {
        int best = rnd.nextInt(pop.length);
        for (int i = 1; i < GA_TOURNAMENT_K; i++) {
            int candidate = rnd.nextInt(pop.length);
            if (fit[candidate] > fit[best]) {
                best = candidate;
            }
        }
        return pop[best];
    }

    /**
     * 部分映射交叉（PMX）：在两个交叉点之间交换基因，并用映射关系修复段外冲突。
     * <p>
     * 对分配编码（无人机索引可重复），修复仍产生合法解（值域 [0, nDrones)）。
     */
    private int[] pmxCrossover(int[] p1, int[] p2, Random rnd) {
        int n = p1.length;
        int[] child = p1.clone();
        int c1 = rnd.nextInt(n);
        int c2 = rnd.nextInt(n);
        if (c1 > c2) {
            int t = c1; c1 = c2; c2 = t;
        }
        // 段 [c1, c2] 从 p2 拷贝，并建立 p2[i] -> p1[i] 映射
        Map<Integer, Integer> segMap = new HashMap<>();
        for (int i = c1; i <= c2; i++) {
            child[i] = p2[i];
            segMap.put(p2[i], p1[i]);
        }
        // 修复段外：跟随映射链直到值不在段映射中
        for (int i = 0; i < n; i++) {
            if (i >= c1 && i <= c2) {
                continue;
            }
            int v = child[i];
            Integer mapped = segMap.get(v);
            int guard = 0;
            while (mapped != null && guard < n) {
                v = mapped;
                mapped = segMap.get(v);
                guard++;
            }
            child[i] = v;
        }
        return child;
    }

    /** 变异：随机交换两个任务的无人机分配。 */
    private void mutate(int[] chrom, Random rnd) {
        if (chrom.length < 2) {
            return;
        }
        int i = rnd.nextInt(chrom.length);
        int j = rnd.nextInt(chrom.length);
        if (i == j) {
            j = (j + 1) % chrom.length;
        }
        int tmp = chrom[i];
        chrom[i] = chrom[j];
        chrom[j] = tmp;
    }

    /** 贪心分配（fallback）：逐任务选择评分最高的无人机。 */
    private int[] greedyAssign(List<TaskRequest> tasks, List<DroneSnapshot> drones) {
        int[] mapping = new int[tasks.size()];
        for (int i = 0; i < tasks.size(); i++) {
            int best = 0;
            double bestScore = -1;
            for (int j = 0; j < drones.size(); j++) {
                double s = scoreDrone(drones.get(j), tasks.get(i));
                if (s > bestScore) {
                    bestScore = s;
                    best = j;
                }
            }
            mapping[i] = best;
        }
        return mapping;
    }

    /** 综合评分：能力(40%) + 电量(30%) + 距离(20%) + 优先级(10%) */
    private double scoreDrone(DroneSnapshot d, TaskRequest req) {
        double capabilityScore = BASE_CAPABILITY_SCORE;
        double batteryScore = d.battery > 0 ? d.battery : 0;
        double distanceScore = DEFAULT_DISTANCE_SCORE;
        if (!Double.isNaN(d.lat) && !Double.isNaN(d.lon) && d.lat != 0 && d.lon != 0) {
            double dist = haversine(d.lat, d.lon, req.getTargetLat(), req.getTargetLon());
            distanceScore = Math.max(0, 100 - dist / (DISTANCE_SCORE_BASE_M / 100));
        }
        double priorityScore = req.getPriority() * PRIORITY_MULTIPLIER;

        return capabilityScore * WEIGHT_CAPABILITY + batteryScore * WEIGHT_BATTERY
                + distanceScore * WEIGHT_DISTANCE + priorityScore * WEIGHT_PRIORITY;
    }

    private double haversine(double lat1, double lon1, double lat2, double lon2) {
        double R = EARTH_RADIUS_M;
        double dLat = Math.toRadians(lat2 - lat1);
        double dLon = Math.toRadians(lon2 - lon1);
        double a = Math.sin(dLat / 2) * Math.sin(dLat / 2) +
                Math.cos(Math.toRadians(lat1)) * Math.cos(Math.toRadians(lat2)) *
                        Math.sin(dLon / 2) * Math.sin(dLon / 2);
        return R * 2 * Math.atan2(Math.sqrt(a), Math.sqrt(1 - a)); // 米
    }

    /** 查询所有分配，自动按当前租户过滤。 */
    public Map<String, AssignmentResult> getAllAssignments() {
        Integer tenantId = TenantContext.getEffectiveTenantId();
        if (tenantId == null) {
            return Collections.unmodifiableMap(assignments);
        }
        Map<String, AssignmentResult> filtered = new LinkedHashMap<>();
        for (Map.Entry<String, AssignmentResult> entry : assignments.entrySet()) {
            Integer taskTenant = taskTenantMap.get(entry.getKey());
            if (taskTenant == null || tenantId.equals(taskTenant)) {
                filtered.put(entry.getKey(), entry.getValue());
            }
        }
        return Collections.unmodifiableMap(filtered);
    }

    /** 取消任务（验证租户归属）。 */
    public synchronized boolean cancelTask(String taskId) {
        Integer tenantId = TenantContext.getEffectiveTenantId();
        if (tenantId != null) {
            Integer taskTenant = taskTenantMap.get(taskId);
            if (taskTenant != null && !tenantId.equals(taskTenant)) {
                return false;
            }
        }
        AssignmentResult removed = assignments.remove(taskId);
        if (removed != null) {
            taskTenantMap.remove(taskId);
            // 从正在执行映射中移除（任务可能已被 poll 开始执行）
            Set<String> activeTasks = droneToTaskIds.get(removed.getAssignedSysid());
            if (activeTasks != null) {
                activeTasks.remove(taskId);
                if (activeTasks.isEmpty()) {
                    droneToTaskIds.remove(removed.getAssignedSysid());
                }
            }
            // 同步从任务队列移除，避免队列只增不减
            taskQueue.removeIf(req -> req.getTaskId().equals(taskId));
            publishTaskStatus(taskId, removed.getAssignedSysid(), TaskStatusEnum.ABORTED.ordinal(), 0);
        }
        return removed != null;
    }

    /** 从任务队列取出下一个待执行任务（消费队列，避免只增不减）。 */
    public synchronized TaskRequest pollNextTask() {
        TaskRequest task = taskQueue.poll();
        if (task != null) {
            // 标记任务为正在执行，加入无人机负载映射
            AssignmentResult assignment = assignments.get(task.getTaskId());
            if (assignment != null && assignment.isSuccess()) {
                droneToTaskIds.computeIfAbsent(assignment.getAssignedSysid(), k -> ConcurrentHashMap.newKeySet())
                        .add(task.getTaskId());
                publishTaskStatus(task.getTaskId(), assignment.getAssignedSysid(),
                        TaskStatusEnum.IN_PROGRESS.ordinal(), 0);
            }
        }
        return task;
    }

    /**
     * 显式启动指定任务（REST 生命周期沿）：标记为正在执行并发布 IN_PROGRESS 帧。
     *
     * @return true 若任务存在且分配成功
     */
    public synchronized boolean startTask(String taskId) {
        AssignmentResult assignment = assignments.get(taskId);
        if (assignment == null || !assignment.isSuccess()) {
            return false;
        }
        droneToTaskIds.computeIfAbsent(assignment.getAssignedSysid(), k -> ConcurrentHashMap.newKeySet())
                .add(taskId);
        taskQueue.removeIf(req -> req.getTaskId().equals(taskId));
        publishTaskStatus(taskId, assignment.getAssignedSysid(), TaskStatusEnum.IN_PROGRESS.ordinal(), 0);
        return true;
    }

    /**
     * 标记任务完成，从无人机负载映射中移除，并发布 COMPLETED 帧。
     *
     * @return true 若任务存在且分配成功
     */
    public synchronized boolean completeTask(String taskId) {
        AssignmentResult assignment = assignments.get(taskId);
        if (assignment != null && assignment.isSuccess()) {
            Set<String> activeTasks = droneToTaskIds.get(assignment.getAssignedSysid());
            if (activeTasks != null) {
                activeTasks.remove(taskId);
                if (activeTasks.isEmpty()) {
                    droneToTaskIds.remove(assignment.getAssignedSysid());
                }
            }
            publishTaskStatus(taskId, assignment.getAssignedSysid(),
                    TaskStatusEnum.COMPLETED.ordinal(), 100);
            log.info("Task {} completed, removed from drone {} load tracking",
                    taskId, assignment.getAssignedSysid());
            return true;
        }
        return false;
    }

    /**
     * 标记任务执行失败，从无人机负载映射中移除，并发布 FAILED 帧（B4）。
     * <p>
     * 与 {@link #cancelTask} 的区分：本方法是「执行尝试后失败」（任务已开始执行、
     * 执行侧报失败），**任务记录保留**（失败任务可查）；主动取消走 cancelTask
     * （ABORTED，记录移除）。两路都清负载映射与队列，避免"在飞任务"引用悬挂。
     *
     * @return true 若任务存在且分配成功
     */
    public synchronized boolean failTask(String taskId) {
        AssignmentResult assignment = assignments.get(taskId);
        if (assignment != null && assignment.isSuccess()) {
            Set<String> activeTasks = droneToTaskIds.get(assignment.getAssignedSysid());
            if (activeTasks != null) {
                activeTasks.remove(taskId);
                if (activeTasks.isEmpty()) {
                    droneToTaskIds.remove(assignment.getAssignedSysid());
                }
            }
            taskQueue.removeIf(req -> req.getTaskId().equals(taskId));
            publishTaskStatus(taskId, assignment.getAssignedSysid(),
                    TaskStatusEnum.FAILED.ordinal(), 0);
            log.warn("Task {} failed, removed from drone {} load tracking",
                    taskId, assignment.getAssignedSysid());
            return true;
        }
        return false;
    }

    /**
     * 全量重新分配（无人机损毁后触发）。
     * <p>
     * 线程安全说明：使用 synchronized 与 assignTask/assignTasks/cancelTask/pollNextTask 对共享状态
     * （taskQueue、assignments）的访问保持互斥，消除 drainTo 与并发 offer/poll 之间的竞态。
     * 重分配策略：仅重分配 taskQueue 中待执行的任务；正在执行中的任务（已从队列消费、
     * 仅有 assignments 记录）不受影响，其分配记录被保留。
     */
    public synchronized void reassignAll() {
        log.info("Reassigning all tasks, pendingQueueCount={}", taskQueue.size());
        // 从队列取出所有待重分配任务（不加 assignments.clear()，保留执行中任务的分配记录）
        List<TaskRequest> pending = new ArrayList<>();
        taskQueue.drainTo(pending);
        // 重新分配：仅对在线无人机分配（损毁无人机已离线）
        List<DroneSnapshot> drones = registry.all();
        for (TaskRequest req : pending) {
            DroneSnapshot best = null;
            double bestScore = -1;
            // 优先选择没有正在执行任务的在线无人机
            for (DroneSnapshot d : drones) {
                if (!d.online) {
                    continue; // 跳过离线无人机
                }
                Set<String> activeTasks = droneToTaskIds.get(d.sysid);
                if (activeTasks != null && !activeTasks.isEmpty()) {
                    continue; // 跳过有正在执行任务的无人机
                }
                double score = scoreDrone(d, req);
                if (score > bestScore) {
                    bestScore = score;
                    best = d;
                }
            }
            // 所有在线无人机都有正在执行的任务时，回退到选择评分最高的在线无人机
            if (best == null) {
                for (DroneSnapshot d : drones) {
                    if (!d.online) {
                        continue;
                    }
                    double score = scoreDrone(d, req);
                    if (score > bestScore) {
                        bestScore = score;
                        best = d;
                    }
                }
            }
            if (best != null) {
                AssignmentResult result = new AssignmentResult(req.getTaskId(), best.sysid, bestScore,
                        String.format("sysid=%d score=%.1f", best.sysid, bestScore), true);
                assignments.put(req.getTaskId(), result); // 覆盖旧分配记录
                taskQueue.offer(req);
                log.info("Task {} reassigned to sysid={} score={}", req.getTaskId(), best.sysid, bestScore);
                publishAssignmentFrames(req, best.sysid);
            } else {
                // 无在线无人机时将任务放回队列，等待下次有无人机上线时再分配。
                taskQueue.offer(req);
                log.warn("Task {} cannot be reassigned: no online drone available, re-queued", req.getTaskId());
            }
        }
    }

    // ==================== WS 帧发布（30048 TaskAssignment / 30050 TaskStatus） ====================

    /** 云端调度器作为帧发送方的系统 ID（与 UdpGateway.GCS_SYSID 同源）。 */
    private static final int SENDER_SYSID = 255;

    /**
     * taskId 字符串 → u32 稳定哈希。
     * <p>
     * REST 面 taskId 为 1-64 字符字符串，协议字段 u32 无法无损承载；
     * 以 {@code String.hashCode()} 无符号化映射，同一 taskId 稳定映射同一 u32
     * （理论碰撞存在，REST 面以字符串 taskId 为准）。
     */
    static long taskIdToU32(String taskId) {
        return taskId.hashCode() & 0xFFFFFFFFL;
    }

    /**
     * REST 优先级 0-10 → 协议优先级 1-4（等宽分档）。
     * <p>
     * [0,2]→1（低），[3,5]→2（中），[6,8]→3（高），[9,10]→4（紧急）。
     */
    static int priorityToProtocol(int priority) {
        if (priority <= 2) {
            return 1;
        }
        if (priority <= 5) {
            return 2;
        }
        if (priority <= 8) {
            return 3;
        }
        return 4;
    }

    /** i16 高度钳位（协议 targetAlt i16，米）。 */
    private static int clampAltI16(double altM) {
        long v = Math.round(altM);
        if (v > Short.MAX_VALUE) {
            return Short.MAX_VALUE;
        }
        if (v < Short.MIN_VALUE) {
            return Short.MIN_VALUE;
        }
        return (int) v;
    }

    /**
     * 分配成功后发布 30048（任务分配）+ 30050（ASSIGNED, progress 0）。
     * <p>
     * 发布失败仅记日志，不影响分配主流程（WS 转发是旁路关注点）。
     */
    private void publishAssignmentFrames(TaskRequest req, int assignedSysid) {
        try {
            long now = System.currentTimeMillis();
            TaskAssignmentMsg assignmentMsg = new TaskAssignmentMsg(
                    taskIdToU32(req.getTaskId()),
                    (int) Math.round(req.getTargetLat() * 1e7),
                    (int) Math.round(req.getTargetLon() * 1e7),
                    clampAltI16(req.getTargetAlt()),
                    SENDER_SYSID,
                    TaskType.valueOf(req.getTaskType()).ordinal(),
                    priorityToProtocol(req.getPriority()),
                    assignedSysid);
            eventPublisher.publishEvent(
                    new MavlinkMessageEvent(this, assignedSysid, TaskAssignmentMsg.ID, assignmentMsg, now));
            publishTaskStatus(req.getTaskId(), assignedSysid, TaskStatusEnum.ASSIGNED.ordinal(), 0);
        } catch (Exception e) {
            log.warn("task assignment frame publish failed: taskId={}: {}", req.getTaskId(), e.getMessage());
        }
    }

    /**
     * 发布 30050（任务状态）。事件 sysid 用任务所属无人机（租户路由按其归属推导）；
     * 帧内 sysId 字段为任务所属机（云端代报生命周期沿，非机载自报）。
     */
    private void publishTaskStatus(String taskId, int assignedSysid, int statusOrdinal, int progressPercent) {
        try {
            TaskStatusMsg msg = new TaskStatusMsg(
                    taskIdToU32(taskId),
                    System.currentTimeMillis(),
                    assignedSysid,
                    statusOrdinal,
                    progressPercent);
            eventPublisher.publishEvent(
                    new MavlinkMessageEvent(this, assignedSysid, TaskStatusMsg.ID, msg, msg.timestamp));
        } catch (Exception e) {
            log.warn("task status frame publish failed: taskId={} status={}: {}",
                    taskId, statusOrdinal, e.getMessage());
        }
    }
}
