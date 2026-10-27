package io.aerofleet.cloud.regulator;

import io.aerofleet.cloud.regulator.model.ComplianceState;
import io.aerofleet.cloud.regulator.model.ComplianceStatus;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 合规状态管理器（内存态）。
 * <p>
 * 使用 {@link ConcurrentHashMap} 存储每架无人机的合规状态，提供状态流转、查询与清理功能。
 * 状态流转遵循正向单向规则：{@code UNVERIFIED → VERIFIED → ACTIVATED → OPERATING → CANCELLED}，
 * 任何逆向或跳跃式流转将抛出 {@link IllegalStateException}。
 * <p>
 * 2026-10-27 起为「内存缓存 + 写穿」持久化模式（审计 §2.1 第 7 条）：启动时从库水合，
 * 每次 transition/remove 同步落库；仓库缺失（纯单测构造）时退化为纯内存，语义不变。
 * 重启后已实名/已激活设备不再回 UNVERIFIED——丢的是合规叙事本身，这条优先级最高。
 *
 * @see ComplianceState
 * @see ComplianceStatus
 */
@Component
public class ComplianceStateManager {

    private static final Logger log = LoggerFactory.getLogger(ComplianceStateManager.class);

    /** sysid → ComplianceStatus，内存缓存（写穿模式的读面）。 */
    private final ConcurrentHashMap<Integer, ComplianceStatus> statusMap = new ConcurrentHashMap<>();

    /** 持久化仓库（可选；缺失时纯内存语义）。 */
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private ComplianceStateRepository repository;

    /** 启动水合：库是权威，缓存从中重建（空库/无仓库安全跳过）。 */
    @jakarta.annotation.PostConstruct
    void hydrateFromRepository() {
        if (repository == null) {
            return;
        }
        try {
            int loaded = 0;
            for (ComplianceStateEntity e : repository.findAll()) {
                statusMap.put(e.getSysid(), e.toStatus());
                loaded++;
            }
            if (loaded > 0) {
                log.info("Restored {} compliance states from database", loaded);
            }
        } catch (Exception e) {
            log.warn("Failed to hydrate compliance states from database: {}", e.getMessage());
        }
    }

    /** 写穿：库写失败只告警不抛——合规状态机的主链是内存判定，落库是持久性旁路。 */
    private void persist(ComplianceStatus status) {
        if (repository == null) {
            return;
        }
        try {
            repository.save(ComplianceStateEntity.fromStatus(status));
        } catch (Exception e) {
            log.warn("Failed to persist compliance state (sysid={}): {}",
                    status.sysid(), e.getMessage());
        }
    }

    /**
     * 状态流转（仅允许正向）。
     * <p>
     * 若 sysid 不存在且 {@code newState} 为 {@link ComplianceState#UNVERIFIED}，则初始化该设备的合规状态记录。
     * 若 sysid 不存在且 {@code newState} 非 {@code UNVERIFIED}，抛出 {@link IllegalStateException}（不允许跳过初始状态）。
     * 若 sysid 已存在，校验流转合法性，非法流转抛出 {@link IllegalStateException}。
     * <p>
     * 该方法使用 {@link ConcurrentHashMap#compute} 保证原子性，线程安全。
     *
     * @param sysid    无人机系统标识（MAVLink sysid）
     * @param newState 目标状态
     * @return 变更后的合规状态记录
     * @throws IllegalStateException 若流转不合法（逆向、跳跃或跳过初始状态）
     */
    public ComplianceStatus transition(int sysid, ComplianceState newState) {
        return transition(sysid, newState, null);
    }

    /**
     * 状态流转，并在同一次原子更新里绑定/保留产品序列号。
     * <p>
     * 为什么要带 serialNo：遥测上报（{@code TelemetryReportService}）是从合规状态里
     * 取 {@code productSerialNo} 拼上报体的，而原实现从初始化起就写死 null——于是
     * "已实名、已激活"的设备，遥测上报反而丢了身份标识，监管平台无法把上报关联到
     * 激活记录（sim 侧表现为 NOT_IDENTIFIED 拒绝）。
     * <p>
     * 绑定语义：新值非空则覆盖（以最新一次实名/激活请求为准），新值为空则保留原值
     * （避免一次不带序列号的调用把已绑定的身份擦掉）。
     *
     * @param sysid           无人机系统标识（MAVLink sysid）
     * @param newState        目标状态
     * @param productSerialNo 产品序列号（可空）
     * @return 变更后的合规状态记录
     * @throws IllegalStateException 若流转不合法（逆向、跳跃或跳过初始状态）
     */
    public ComplianceStatus transition(int sysid, ComplianceState newState, String productSerialNo) {
        return statusMap.compute(sysid, (key, existing) -> {
            long now = System.currentTimeMillis();

            if (existing == null) {
                if (newState != ComplianceState.UNVERIFIED) {
                    throw new IllegalStateException(
                            "非法状态流转: sysid=" + sysid + " 不存在，不能直接初始化为 " + newState
                                    + "（必须从 UNVERIFIED 开始）");
                }
                ComplianceStatus initial = new ComplianceStatus(
                        sysid, productSerialNo, newState, null, null,
                        newState == ComplianceState.VERIFIED ? now : 0, 0, 0);
                log.info("合规状态初始化: sysid={}, status={}, serialNo={}, timestamp={}",
                        sysid, newState, productSerialNo, now);
                persist(initial);
                return initial;
            }

            ComplianceState oldState = existing.status();
            if (!isValidTransition(oldState, newState)) {
                throw new IllegalStateException(
                        "非法状态流转: sysid=" + sysid + ", from=" + oldState + ", to=" + newState);
            }

            String serialNo = productSerialNo != null ? productSerialNo : existing.productSerialNo();
            // 三个 last* 时间戳按语义落点写入（此前声明了却从未写入——只初始化 0 再原样
            // 复制，RegulatorController 上报的永远是 0；本笔顺带修掉，见 CHANGELOG）：
            //   →VERIFIED 记 lastVerifyTime；→ACTIVATED 记 lastActivationTime。
            long lastVerifyTime = newState == ComplianceState.VERIFIED
                    ? now : existing.lastVerifyTime();
            long lastActivationTime = newState == ComplianceState.ACTIVATED
                    ? now : existing.lastActivationTime();
            ComplianceStatus updated = new ComplianceStatus(
                    sysid,
                    serialNo,
                    newState,
                    existing.activationId(),
                    existing.cancellationId(),
                    lastVerifyTime,
                    lastActivationTime,
                    existing.lastTelemetryReportTime()
            );

            log.info("合规状态变更: sysid={}, from={}, to={}, serialNo={}, timestamp={}",
                    sysid, oldState, newState, serialNo, now);
            persist(updated);
            return updated;
        });
    }

    /**
     * 查询单架无人机的合规状态。
     *
     * @param sysid 无人机系统标识
     * @return 合规状态记录，若不存在返回 {@code null}
     */
    public ComplianceStatus get(int sysid) {
        return statusMap.get(sysid);
    }

    /**
     * 查询全部无人机的合规状态。
     *
     * @return 合规状态记录列表（按 sysid 升序排列）
     */
    public List<ComplianceStatus> getAll() {
        List<ComplianceStatus> list = new ArrayList<>(statusMap.values());
        list.sort((a, b) -> Integer.compare(a.sysid(), b.sysid()));
        return list;
    }

    /**
     * 移除单架无人机的合规状态记录（设备离线清理）。
     *
     * @param sysid 无人机系统标识
     * @return 被移除的合规状态记录，若不存在返回 {@code null}
     */
    public ComplianceStatus remove(int sysid) {
        ComplianceStatus removed = statusMap.remove(sysid);
        if (removed != null) {
            if (repository != null) {
                try {
                    repository.deleteById(sysid);
                } catch (Exception e) {
                    log.warn("Failed to delete compliance state (sysid={}): {}", sysid, e.getMessage());
                }
            }
            log.info("合规状态移除: sysid={}, status={}, timestamp={}",
                    sysid, removed.status(), System.currentTimeMillis());
        }
        return removed;
    }

    /**
     * 校验状态流转是否合法。
     * <p>
     * 合法流转规则（仅正向）：
     * <ul>
     *   <li>{@code UNVERIFIED → VERIFIED}</li>
     *   <li>{@code VERIFIED → ACTIVATED}</li>
     *   <li>{@code ACTIVATED → OPERATING} 或 {@code ACTIVATED → CANCELLED}</li>
     *   <li>{@code OPERATING → CANCELLED}</li>
     * </ul>
     * {@code CANCELLED} 为终态，不允许任何后续流转。不允许逆向回退。
     *
     * @param from 当前状态
     * @param to   目标状态
     * @return {@code true} 若流转合法，{@code false} 若非法
     */
    private boolean isValidTransition(ComplianceState from, ComplianceState to) {
        return switch (from) {
            case UNVERIFIED -> to == ComplianceState.VERIFIED;
            case VERIFIED -> to == ComplianceState.ACTIVATED;
            case ACTIVATED -> to == ComplianceState.OPERATING || to == ComplianceState.CANCELLED;
            case OPERATING -> to == ComplianceState.CANCELLED;
            case CANCELLED -> false;
        };
    }
}