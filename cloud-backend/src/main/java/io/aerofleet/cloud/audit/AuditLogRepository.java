package io.aerofleet.cloud.audit;

import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

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
}
