package io.aerofleet.cloud.mission.formation;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.TestPropertySource;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link FormationRepository} 启动恢复两个查询的 H2 真库验证。
 * <p>
 * 单测（{@code FormationStartupRestoreTest}）用 mock 钉的是"服务怎么用查询"；
 * 这里钉的是"查询本身在真数据库上算什么"——JPQL/枚举映射只有真库能证：
 * ① {@code findMaxFormationId()} 必须**含已解散行**（PK 续接的依据）；
 * ② {@code findRestorable(DISSOLVED)} 排除已解散、但**保留 state IS NULL 的脏行**
 *    （与旧实现"只跳过 DISSOLVED"逐条对齐；派生查询 {@code findByStateNot} 的三值逻辑
 *    会把 NULL 行一起排除，故仓库层显式写了 {@code state is null or state <> :state}）。
 */
@SpringBootTest
@TestPropertySource(properties = {
        "spring.datasource.url=jdbc:h2:mem:formation-restore-test;MODE=PostgreSQL;DB_CLOSE_DELAY=-1;DATABASE_TO_LOWER=TRUE",
        "spring.datasource.driver-class-name=org.h2.Driver",
        "spring.datasource.username=sa",
        "spring.datasource.password=",
        "spring.jpa.hibernate.ddl-auto=create-drop",
        "spring.flyway.enabled=false",
        "aerofleet.device-registry.persist=false",
        "spring.cache.type=none",
        "aerofleet.udp-port=0",
        "aerofleet.drone-port=14551",
        "spring.autoconfigure.exclude=org.springframework.boot.autoconfigure.data.redis.RedisAutoConfiguration,org.springframework.boot.autoconfigure.data.redis.RedisRepositoriesAutoConfiguration",
})
@DisplayName("FormationRepository 启动恢复查询（H2 真库）")
class FormationRestoreQueryTest {

    @Autowired
    private FormationRepository repository;

    /** H2 是 DB_CLOSE_DELAY=-1 的共享内存库，两个用例共用；各自先清空保证独立性。 */
    @org.junit.jupiter.api.BeforeEach
    void clean() {
        repository.deleteAll();
    }

    private static FormationEntity row(Integer id, Formation.FormationState state, Integer tenantId) {
        FormationEntity e = new FormationEntity();
        if (id != null) {
            e.setFormationId(id);
        }
        e.setShape(FormationGeometry.Shape.LINE);
        e.setSpacing(10.0);
        e.setHeading(0);
        e.setRefLat(22.5907);
        e.setRefLon(113.9345);
        e.setRefAlt(50.0);
        e.setLeaderSysid(1);
        e.setState(state);
        e.setTenantId(tenantId);
        e.setMembers(new ArrayList<>(List.of(1, 2)));
        return e;
    }

    @Test
    @DisplayName("findMaxFormationId 含已解散行；findRestorable 排除已解散但保留 state NULL 脏行")
    void startupQueriesMatchOldAllScanSemantics() {
        repository.save(row(5, Formation.FormationState.STABLE, 1));
        repository.save(row(6, null, 1));                                   // 历史脏行：state 为 NULL
        repository.save(row(7, Formation.FormationState.DISSOLVED, 2));    // 已解散：更晚，ID 最大

        assertThat(repository.findMaxFormationId())
                .as("maxId 若不含已解散行，重启后新编队会复用 id=7 撞主键")
                .isEqualTo(7);

        assertThat(repository.findRestorable(Formation.FormationState.DISSOLVED))
                .extracting(FormationEntity::getFormationId)
                .as("排除 DISSOLVED；state IS NULL 的脏行保留（与旧实现逐条对齐）")
                .containsExactlyInAnyOrder(5, 6);
    }

    @Test
    @DisplayName("空库：findMaxFormationId 返回 null，findRestorable 返回空——启动不得抛异常")
    void emptyDatabaseIsSafe() {
        assertThat(repository.findMaxFormationId()).isNull();
        assertThat(repository.findRestorable(Formation.FormationState.DISSOLVED)).isEmpty();
    }
}
