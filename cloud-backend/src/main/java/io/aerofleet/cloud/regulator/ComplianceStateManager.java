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
 * C1 阶段以内存态管理，C4 持久化后切换为 Repository 实现。
 *
 * @see ComplianceState
 * @see ComplianceStatus
 */
@Component
public class ComplianceStateManager {

    private static final Logger log = LoggerFactory.getLogger(ComplianceStateManager.class);

    /** sysid → ComplianceStatus，内存态存储。 */
    private final ConcurrentHashMap<Integer, ComplianceStatus> statusMap = new ConcurrentHashMap<>();

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
        return statusMap.compute(sysid, (key, existing) -> {
            long now = System.currentTimeMillis();

            if (existing == null) {
                if (newState != ComplianceState.UNVERIFIED) {
                    throw new IllegalStateException(
                            "非法状态流转: sysid=" + sysid + " 不存在，不能直接初始化为 " + newState
                                    + "（必须从 UNVERIFIED 开始）");
                }
                ComplianceStatus initial = new ComplianceStatus(
                        sysid, null, newState, null, null, 0, 0, 0);
                log.info("合规状态初始化: sysid={}, status={}, timestamp={}", sysid, newState, now);
                return initial;
            }

            ComplianceState oldState = existing.status();
            if (!isValidTransition(oldState, newState)) {
                throw new IllegalStateException(
                        "非法状态流转: sysid=" + sysid + ", from=" + oldState + ", to=" + newState);
            }

            ComplianceStatus updated = new ComplianceStatus(
                    sysid,
                    existing.productSerialNo(),
                    newState,
                    existing.activationId(),
                    existing.cancellationId(),
                    existing.lastVerifyTime(),
                    existing.lastActivationTime(),
                    existing.lastTelemetryReportTime()
            );

            log.info("合规状态变更: sysid={}, from={}, to={}, timestamp={}", sysid, oldState, newState, now);
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