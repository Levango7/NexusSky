package io.aerofleet.cloud.mission.emergency;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

/**
 * 空地协同记录归档仓库（V33；审计 §2.1 第 6 条）。
 * <p>
 * 读面刻意极小：内存命中为主，未命中（总结后清理/重启）才读穿归档——避免把
 * 归档表当热路径查询面；不做启动批量水合（低频事件 + 内存有界是既有设计）。
 */
@Repository
public interface CoordinationRecordRepository extends JpaRepository<CoordinationRecordEntity, String> {
}
