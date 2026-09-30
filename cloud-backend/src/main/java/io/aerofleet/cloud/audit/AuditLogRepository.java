package io.aerofleet.cloud.audit;

import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * 审计日志 JPA Repository。
 *
 * 查询一律按主键 id 排序（自增主键即写入顺序），避免依赖 timestamp 的
 * 同毫秒并列顺序；limit 通过 {@link Pageable} 下推到 SQL。
 */
@Repository
public interface AuditLogRepository extends JpaRepository<AuditLogEntity, Long> {

    /** 全部记录，最新的在前。 */
    List<AuditLogEntity> findAllByOrderByIdDesc();

    /** 最近 N 条（Pageable 限定），最新的在前。 */
    List<AuditLogEntity> findAllByOrderByIdDesc(Pageable pageable);

    /** 链尾一行（哈希链续接用）。 */
    Optional<AuditLogEntity> findFirstByOrderByIdDesc();

    /** 全部记录，最旧的在前（哈希链校验用）。 */
    List<AuditLogEntity> findAllByOrderByIdAsc();

    /**
     * 保留策略用：删除 {@code cutoff} 之前的记录（链的<b>前缀</b>）。
     * <p>
     * 删前缀会让 {@code verifyChain} 的链首不再是创世哈希，因此该方法只在
     * {@code aerofleet.audit.retention-days > 0} 时被 {@link AuditRetentionJob} 调用，
     * 且校验逻辑据同一配置把该态识别为"截断"而非"断链"。
     * 链尾（最新行）不受影响，续接照常。
     *
     * @param cutoff 截止时间，早于此的记录被删除
     * @return 删除行数
     */
    @Modifying
    @Transactional
    @Query("delete from AuditLogEntity e where e.timestamp < :cutoff")
    int deleteOlderThan(@Param("cutoff") Instant cutoff);
}
