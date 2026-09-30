package io.aerofleet.cloud.audit;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HexFormat;
import java.util.List;
import java.util.concurrent.ConcurrentLinkedDeque;

/**
 * 审计日志服务：记录和查询操作审计日志。
 * <p>
 * 双模式存储：
 * <ul>
 *   <li><b>内存缓冲</b>（始终维护）——{@link ConcurrentLinkedDeque}，默认保留最近 1000 条
 *       （{@code aerofleet.audit.capacity}），提供 DB 不可用时的查询回退；</li>
 *   <li><b>数据库持久化</b>（{@code aerofleet.audit.persist-to-db=true} 且
 *       Repository 可用时）——每条例记录同时写入 {@code audit_log} 表（V21），
 *       查询与计数走数据库。</li>
 * </ul>
 * <p>
 * <b>哈希链</b>：每条记录带 {@code prevHash}（上一条的 {@code entryHash}，首条为
 * 64 个 0）与 {@code entryHash} = SHA-256(prevHash + 时间戳毫秒 + 各字段)，
 * 两种模式都维护。{@link #verifyChain()} 重算全链定位首处断链——任何对历史
 * 记录内容的改动都会暴露。落库失败的行会推进内存链尾但不进库，下一次成功落库
 * 的行会因 prev_hash 指向前一条（未落库）哈希而被校验判为断链，使缺口可见。
 * <p>
 * 当 {@code aerofleet.audit.enabled=false} 时，{@link #record} 直接返回。
 */
@Service
public class AuditService {

    private static final Logger log = LoggerFactory.getLogger(AuditService.class);

    /** 默认内存日志上限，超出后丢弃最旧记录。 */
    private static final int DEFAULT_CAPACITY = 1000;

    /** 创世哈希：链首记录的 prevHash。 */
    static final String GENESIS_HASH = "0".repeat(64);

    /** 规范串字段分隔符（单元分隔符，避免字段拼接歧义）。 */
    private static final char SEP = '\u001F';

    private final boolean enabled;
    private final int capacity;
    private final ConcurrentLinkedDeque<AuditLog> logs = new ConcurrentLinkedDeque<>();

    /** 是否将审计日志持久化到数据库（默认 false，保持纯内存行为）。 */
    @Value("${aerofleet.audit.persist-to-db:false}")
    private boolean persistToDb;

    /** JPA Repository（可选注入，数据库不可用时回退纯内存路径）。 */
    @Autowired(required = false)
    private AuditLogRepository auditLogRepository;

    /** 内存缓冲条数（避免 ConcurrentLinkedDeque.size() 的 O(n) 遍历）。 */
    private int memoryCount;

    /** 链尾哈希；null = 未初始化（首条写入时从库尾恢复，空库则取创世值）。 */
    private String lastHash;

    /** 链操作串行锁：哈希链必须按序生成，审计写入频率低，串行代价可接受。 */
    private final Object chainLock = new Object();

    public AuditService(@Value("${aerofleet.audit.enabled:true}") boolean enabled,
                        @Value("${aerofleet.audit.capacity:1000}") int capacity) {
        this.enabled = enabled;
        this.capacity = capacity > 0 ? capacity : DEFAULT_CAPACITY;
    }

    /**
     * 记录一条审计日志（内存 + 可选落库，维护哈希链）。
     *
     * @param userId 操作者标识
     * @param action HTTP 方法
     * @param target 请求路径
     * @param detail 操作详情（可为 null）
     * @param ip     客户端 IP
     */
    public void record(String userId, String action, String target, String detail, String ip) {
        if (!enabled) {
            return;
        }
        Instant ts = Instant.now();
        AuditLog entry;
        synchronized (chainLock) {
            String prevHash = currentChainHead();
            String entryHash = computeHash(prevHash, ts, userId, action, target, detail, ip);
            lastHash = entryHash;
            entry = new AuditLog(ts, userId, action, target, detail, ip, prevHash, entryHash);

            logs.addLast(entry);
            // 超出容量时丢弃最旧记录
            while (memoryCount >= capacity && !logs.isEmpty()) {
                logs.pollFirst();
                memoryCount--;
            }
            memoryCount++;

            if (persistToDb && auditLogRepository != null) {
                try {
                    auditLogRepository.save(AuditLogEntity.from(entry, prevHash, entryHash));
                } catch (Exception e) {
                    // 内存链尾仍已推进：下一次成功落库的行会因 prev_hash 不匹配被 verifyChain 判为断链，缺口可见
                    log.warn("审计日志落库失败（本条仅存内存，链缺口将在 /api/v1/audit/verify 暴露）: {}", e.getMessage());
                }
            }
        }
        log.debug("审计日志: {}", entry);
    }

    /**
     * 查询全部审计日志（按时间倒序，最新的在前）。
     *
     * @return 不可修改的审计日志列表
     */
    public List<AuditLog> findAll() {
        if (persistToDb && auditLogRepository != null) {
            try {
                return auditLogRepository.findAllByOrderByIdDesc().stream()
                        .map(AuditLogEntity::toAuditLog)
                        .toList();
            } catch (Exception e) {
                log.warn("审计日志查询失败，回退内存缓冲: {}", e.getMessage());
            }
        }
        return memorySnapshot();
    }

    /**
     * 查询最近 N 条审计日志。
     *
     * @param limit 最大返回条数
     * @return 不可修改的审计日志列表
     */
    public List<AuditLog> findRecent(int limit) {
        if (persistToDb && auditLogRepository != null) {
            try {
                if (limit <= 0) {
                    return findAll();
                }
                return auditLogRepository.findAllByOrderByIdDesc(PageRequest.of(0, limit)).stream()
                        .map(AuditLogEntity::toAuditLog)
                        .toList();
            } catch (Exception e) {
                log.warn("审计日志查询失败，回退内存缓冲: {}", e.getMessage());
            }
        }
        List<AuditLog> all = memorySnapshot();
        if (limit <= 0 || limit >= all.size()) {
            return all;
        }
        return all.subList(0, limit);
    }

    /**
     * 校验哈希链完整性，返回首个断链位置。
     * <p>
     * 数据库模式下从创世哈希起逐行重算（行按 id 升序）：内容被改 → entry_hash 不符；
     * 历史行缺失 → 后继行的 prev_hash 不符。内存模式校验保留窗口内的链（容量裁剪
     * 导致窗口首条之前没有记录，从窗口首条的 prev_hash 起算）。
     *
     * @return 校验结果
     */
    public ChainVerification verifyChain() {
        if (persistToDb && auditLogRepository != null) {
            try {
                List<AuditLogEntity> rows = auditLogRepository.findAllByOrderByIdAsc();
                String prev = GENESIS_HASH;
                int checked = 0;
                for (AuditLogEntity r : rows) {
                    if (!prev.equals(r.getPrevHash())) {
                        return new ChainVerification(false, checked, r.getId(),
                                "prev_hash 与前一条 entry_hash 不一致（历史行缺失或被改动）");
                    }
                    String expected = computeHash(prev, r.getTimestamp(), r.getUserId(),
                            r.getAction(), r.getTarget(), r.getDetail(), r.getIp());
                    if (!expected.equals(r.getEntryHash())) {
                        return new ChainVerification(false, checked, r.getId(),
                                "entry_hash 与记录内容不符（内容被改动）");
                    }
                    prev = r.getEntryHash();
                    checked++;
                }
                return new ChainVerification(true, checked, null, null);
            } catch (Exception e) {
                return new ChainVerification(false, 0, null, "校验失败（查询异常）: " + e.getMessage());
            }
        }

        List<AuditLog> mem;
        synchronized (chainLock) {
            mem = new ArrayList<>(logs);
        }
        String prev = mem.isEmpty() ? GENESIS_HASH : mem.get(0).getPrevHash();
        int checked = 0;
        for (AuditLog e : mem) {
            if (!prev.equals(e.getPrevHash())) {
                return new ChainVerification(false, checked, null, "第 " + (checked + 1) + " 条 prev_hash 不匹配");
            }
            String expected = computeHash(prev, e.getTimestamp(), e.getUserId(), e.getAction(),
                    e.getTarget(), e.getDetail(), e.getIp());
            if (!expected.equals(e.getEntryHash())) {
                return new ChainVerification(false, checked, null, "第 " + (checked + 1) + " 条 entry_hash 校验失败");
            }
            prev = e.getEntryHash();
            checked++;
        }
        return new ChainVerification(true, checked, null, null);
    }

    /**
     * 清空内存缓冲。
     * <p>
     * 只清内存窗口，不删除数据库中的历史行（审计记录落库后不可由应用侧删除）。
     */
    public void clear() {
        synchronized (chainLock) {
            logs.clear();
            memoryCount = 0;
        }
    }

    /**
     * 返回当前审计日志条数（数据库模式为库中总行数）。
     *
     * @return 日志条数
     */
    public int size() {
        if (persistToDb && auditLogRepository != null) {
            try {
                return (int) auditLogRepository.count();
            } catch (Exception e) {
                log.warn("审计日志计数失败，回退内存缓冲: {}", e.getMessage());
            }
        }
        synchronized (chainLock) {
            return memoryCount;
        }
    }

    /** 内存窗口快照（最新在前）。 */
    private List<AuditLog> memorySnapshot() {
        synchronized (chainLock) {
            List<AuditLog> snapshot = new ArrayList<>(logs);
            Collections.reverse(snapshot); // 最新的在前
            return Collections.unmodifiableList(snapshot);
        }
    }

    /** 链尾：首次调用时从库尾恢复（空库取创世值，库不可用按创世值继续并告警）。 */
    private String currentChainHead() {
        if (lastHash != null) {
            return lastHash;
        }
        if (persistToDb && auditLogRepository != null) {
            try {
                lastHash = auditLogRepository.findFirstByOrderByIdDesc()
                        .map(AuditLogEntity::getEntryHash)
                        .orElse(GENESIS_HASH);
                log.info("审计哈希链已恢复，链尾={}", lastHash);
                return lastHash;
            } catch (Exception e) {
                log.warn("审计链尾恢复失败，按创世哈希继续（可能产生链断裂告警）: {}", e.getMessage());
            }
        }
        lastHash = GENESIS_HASH;
        return lastHash;
    }

    /** SHA-256(prevHash + 时间戳毫秒 + 各字段)，UTF-8。 */
    private static String computeHash(String prevHash, Instant ts, String userId, String action,
                                      String target, String detail, String ip) {
        String canonical = prevHash + SEP
                + (ts == null ? "" : Long.toString(ts.toEpochMilli())) + SEP
                + nz(userId) + SEP + nz(action) + SEP + nz(target) + SEP + nz(detail) + SEP + nz(ip);
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(md.digest(canonical.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 不可用", e);
        }
    }

    private static String nz(String s) {
        return s == null ? "" : s;
    }

    /**
     * 哈希链校验结果。
     *
     * @param ok          整条链是否完好
     * @param checked     已通过校验的记录数
     * @param brokenAtId  首个断链记录的数据库 id（内存模式或无法定位时为 null）
     * @param reason      断链原因（完好时为 null）
     */
    public record ChainVerification(boolean ok, int checked, Long brokenAtId, String reason) {
    }
}
