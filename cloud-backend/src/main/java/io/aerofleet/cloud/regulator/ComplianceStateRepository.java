package io.aerofleet.cloud.regulator;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

/**
 * 合规状态持久化仓库（V32；审计 §2.1 第 7 条）。
 * <p>
 * 查询面极小：启动时 findAll 水合内存缓存，其余读写由 ComplianceStateManager
 * 以"缓存 + 写穿"模式承担（合规状态是状态机，不是查询密集实体）。
 */
@Repository
public interface ComplianceStateRepository extends JpaRepository<ComplianceStateEntity, Integer> {
}
