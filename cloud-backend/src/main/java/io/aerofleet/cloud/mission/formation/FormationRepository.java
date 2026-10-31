package io.aerofleet.cloud.mission.formation;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;

/**
 * 编队持久化 Repository（Spring Data JPA）。
 *
 * 提供 FormationEntity 的标准 CRUD 操作，供 FormationService 在创建/解散/变换时同步写入。
 */
@Repository
public interface FormationRepository extends JpaRepository<FormationEntity, Integer> {

    /** 按租户 ID 查询编队列表（租户隔离）。 */
    List<FormationEntity> findByTenantId(Integer tenantId);

    /**
     * 取当前最大编队 ID（**含已解散行**）。
     * <p>
     * 启动恢复用它续接 {@code nextFormationId}：编队表是只增的（解散只把状态改成
     * {@code DISSOLVED}，见 {@code FormationService.dissolve}），而 {@code formation_id}
     * 是主键——若 maxId 只统计未解散行，重启后新编队会复用已解散行的 ID，撞主键。
     * 单独取 MAX 只回 1 行，替代旧实现为算 maxId 而 {@code findAll()} 整表读堆。
     */
    @Query("select max(e.formationId) from FormationEntity e")
    Integer findMaxFormationId();

    /**
     * 启动恢复：取**除 DISSOLVED 外**的编队进运行时 map（DISSOLVED 行保留在库里供历史查询）。
     * <p>
     * 与旧实现（{@code findAll()} 后逐行跳过 DISSOLVED）逐条对齐，包括脏数据口径：
     * 派生查询 {@code findByStateNot} 生成的 {@code state <> 'DISSOLVED'} 因 SQL 三值逻辑
     * 会**连 state IS NULL 的脏行一起排除**，而旧代码只跳过 DISSOLVED、null 状态行照样恢复。
     * 这里显式写出 {@code state is null or state <> :state} 保持旧行为不变——
     * 本改动只应改变"取多少行"，不应改变"哪些行算活跃"。
     */
    @Query("select e from FormationEntity e where e.state is null or e.state <> :state")
    List<FormationEntity> findRestorable(@Param("state") Formation.FormationState state);
}