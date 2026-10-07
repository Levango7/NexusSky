package io.aerofleet.cloud.mission.spray;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * SprayTask 持久化回归测试（FR-12 落库通路）。
 * <p>
 * UDP 端口沿用仓内既有约定（{@code aerofleet.udp-port=0} + {@code drone-port=14549}），
 * 否则 {@link io.aerofleet.cloud.gateway.UdpGateway} 会去抢 14550，
 * 与真实运行中的后端 / 同机并行测试撞端口 → ApplicationContext 加载失败。
 * <p>
 * 背景：{@link WaypointListConverter#convertToEntityAttribute} 原实现从最外层 '['
 * 直接切数字串，把内层 '[' 一起切进 substring，得到 {@code "[22.5907"} 并抛
 * NumberFormatException；Hibernate 包装为 "Error attempting to apply AttributeConverter"，
 * 而 {@link SprayTaskService#create} 的 catch 只记 warn 就继续，事务已被标记
 * rollback-only，最终以 {@code UnexpectedRollbackException} 让 POST /api/v1/spray 返回 500。
 * <p>
 * 该缺陷此前无任何测试覆盖（仓内没有 SprayController/SprayTaskService 的持久化测试），
 * 因此单测全绿但 REST 通路实际不可用 —— 本类补上这一层。
 */
@SpringBootTest(properties = {
        "aerofleet.udp-port=0",
        "aerofleet.drone-port=14549"
})
class SprayTaskPersistenceTest {

    @Autowired
    private SprayTaskRepository repository;

    private static List<double[]> threeWaypoints() {
        return List.of(
                new double[]{22.5907, 113.9345},
                new double[]{22.5917, 113.9345},
                new double[]{22.5917, 113.9355});
    }

    /** 核心回归：3 航点任务必须能真正落库（此前必抛 AttributeConverter 失败）。 */
    @Test
    void createPersistsWithoutConverterFailure() {
        SprayTask task = new SprayTask(9101, 1, threeWaypoints(), 2500, 50000, 4.0);
        SprayTaskEntity entity = SprayTaskEntity.fromTask(task, null);

        assertDoesNotThrow(() -> repository.saveAndFlush(entity),
                "WaypointListConverter 反序列化不得抛 NumberFormatException");

        repository.deleteById(9101);
    }

    /** 落库后能读回，且 waypoints 往返无损（3 航点 = 3 对坐标）。 */
    @Test
    void waypointsRoundTripThroughDatabase() {
        SprayTask task = new SprayTask(9102, 1, threeWaypoints(), 2500, 50000, 4.0);
        repository.saveAndFlush(SprayTaskEntity.fromTask(task, null));

        SprayTaskEntity reloaded = repository.findById(9102).orElseThrow();
        assertEquals(3, reloaded.getWaypoints().size(), "3 航点应往返为 3 对坐标");
        assertEquals(22.5907, reloaded.getWaypoints().get(0)[0], 1e-9);
        assertEquals(113.9345, reloaded.getWaypoints().get(0)[1], 1e-9);
        assertEquals(113.9355, reloaded.getWaypoints().get(2)[1], 1e-9);

        repository.deleteById(9102);
    }

    /** 单航点：长度 1 的数组必须被容错处理，不得抛异常。 */
    @Test
    void singleWaypointPersists() {
        SprayTask task = new SprayTask(9103, 1,
                List.of(new double[]{30.0, 120.0}), 100, 1000, 5.0);
        assertDoesNotThrow(() -> repository.saveAndFlush(SprayTaskEntity.fromTask(task, null)));
        repository.deleteById(9103);
    }
}
