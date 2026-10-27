package io.aerofleet.cloud.health;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

/**
 * 维护记录持久化仓库（V30；审计 §2.1 第 3 条）。
 * <p>
 * 查询按 id 升序（"m-1 ... m-10" 的字符串序对固定前缀是字典序 = 插入序，
 * 与内存时期的 Map 遍历排序语义一致；换生成规则需同步这条口径）。
 */
@Repository
public interface MaintenanceRecordRepository extends JpaRepository<MaintenanceRecordEntity, String> {

    List<MaintenanceRecordEntity> findAllByOrderByIdAsc();

    List<MaintenanceRecordEntity> findBySysidOrderByIdAsc(int sysid);

    List<MaintenanceRecordEntity> findByStatusOrderByIdAsc(MaintenanceRecord.Status status);

    List<MaintenanceRecordEntity> findBySysidAndStatusOrderByIdAsc(int sysid, MaintenanceRecord.Status status);
}
