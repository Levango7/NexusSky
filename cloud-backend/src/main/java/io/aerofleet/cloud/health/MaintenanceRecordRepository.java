package io.aerofleet.cloud.health;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
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

    /**
     * 保留策略用：删除 {@code created_at} 早于 {@code cutoff} 的全部行。
     * <p>
     * 判据用创建时刻（V30 起由应用写入；存量行可能为 NULL——无时间基准不裁）。
     * 维护记录有适航/合规属性，默认保留期关闭（{@code retention-days<=0}），
     * 数值由运营/合规拍板后再开。单条 bulk DELETE；事务标在方法上（调用方是定时任务）。
     *
     * @param cutoff 截止时刻，早于此的记录被删除
     * @return 删除行数
     */
    @Modifying
    @Transactional
    @Query("delete from MaintenanceRecordEntity e where e.createdAt < :cutoff")
    int deleteOlderThan(@Param("cutoff") Instant cutoff);
}
