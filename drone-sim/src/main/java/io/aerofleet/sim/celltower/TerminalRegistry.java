package io.aerofleet.sim.celltower;

import io.aerofleet.sim.SimLog;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 终端注册表（M6 移动基站载荷抽象，FR-TERM-01 / FR-TERM-04 / FR-TERM-07）。
 * <p>
 * 维护每个已接入终端的 ID、类型、GPS 坐标、接入时间、最后心跳时间。
 * 支持注册、心跳更新、注销、超时扫描、唯一性校验。
 * <p>
 * 线程模型：内部 {@link ConcurrentHashMap}，线程安全。
 */
public final class TerminalRegistry {

    /** Registry 容量保护上限（防止内存溢出，FR-TERM-02 异常场景 3）。 */
    public static final int DEFAULT_CAPACITY_LIMIT = 10_000;

    private final ConcurrentHashMap<Integer, GroundTerminal> terminals = new ConcurrentHashMap<>();
    private final int capacityLimit;

    public TerminalRegistry() {
        this(DEFAULT_CAPACITY_LIMIT);
    }

    public TerminalRegistry(int capacityLimit) {
        this.capacityLimit = capacityLimit;
    }

    /**
     * 新增终端（FR-TERM-01）。
     *
     * @return true 新增成功；false 表示已存在（FR-TERM-07）或容量超限（REGISTRY_FULL）
     */
    public boolean add(GroundTerminal terminal) {
        if (terminals.size() >= capacityLimit) {
            SimLog.warn("REGISTRY_FULL: terminal registry at capacity " + capacityLimit);
            return false;
        }
        return terminals.putIfAbsent(terminal.terminalId, terminal) == null;
    }

    /** 移除终端（FR-TERM-01 / FR-CAP-06）。 */
    public boolean remove(int terminalId) {
        return terminals.remove(terminalId) != null;
    }

    /** 查询终端。 */
    public GroundTerminal get(int terminalId) {
        return terminals.get(terminalId);
    }

    /** 是否已存在（FR-TERM-07 唯一性校验）。 */
    public boolean contains(int terminalId) {
        return terminals.containsKey(terminalId);
    }

    /** 更新最后心跳时间（FR-TERM-01）。 */
    public boolean updateLastSeen(int terminalId, long nowMs) {
        GroundTerminal t = terminals.get(terminalId);
        if (t == null) {
            return false;
        }
        t.updateLastSeen(nowMs);
        return true;
    }

    /** 已接入终端数（FR-CAP-01 / FR-NFR-REL-03 一致性）。 */
    public int size() {
        return terminals.size();
    }

    /** 全部终端快照。 */
    public Collection<GroundTerminal> all() {
        return terminals.values();
    }

    /**
     * 扫描心跳超时终端（FR-TERM-04）。
     *
     * @param nowMs      当前时间戳
     * @param timeoutMs  心跳超时阈值（默认 30s）
     * @return 超时终端 ID 列表（不自动移除，由调用方决定）
     */
    public List<Integer> findExpired(long nowMs, long timeoutMs) {
        List<Integer> expired = new ArrayList<>();
        for (GroundTerminal t : terminals.values()) {
            if (nowMs - t.lastSeenMs > timeoutMs) {
                expired.add(t.terminalId);
            }
        }
        return expired;
    }

    /** 清空（制式切换时批量注销，FR-CT-05）。 */
    public void clear() {
        terminals.clear();
    }
}