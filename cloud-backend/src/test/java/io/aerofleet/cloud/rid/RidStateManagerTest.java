package io.aerofleet.cloud.rid;

import io.aerofleet.cloud.rid.model.BasicIdData;
import io.aerofleet.cloud.rid.model.LocationData;
import io.aerofleet.cloud.rid.model.OperatorIdData;
import io.aerofleet.cloud.rid.model.RidComplianceState;
import io.aerofleet.cloud.rid.model.RidSnapshot;
import io.aerofleet.cloud.rid.model.SelfIdData;
import io.aerofleet.cloud.rid.model.SystemData;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link RidStateManager} 单元测试（直接实例化，无 Spring 上下文）。
 * <p>
 * 验证状态流转（NOT_BROADCASTING → BROADCASTING → BROADCASTING_ERROR → BROADCASTING）、
 * 超时检测、查询与清理功能。
 * <p>
 * 使用较短的超时参数加速测试（timeoutPeriods=1, broadcastInterval=0.001 → 1ms 超时）。
 */
@DisplayName("RidStateManager RID 状态管理器")
class RidStateManagerTest {

    private RidConfig config;
    private RidStateManager manager;

    @BeforeEach
    void setUp() {
        config = new RidConfig();
        // 设置极短超时以加速测试：1ms 超时阈值
        config.setTimeoutPeriods(1);
        config.setBroadcastInterval(0.001);
        manager = new RidStateManager(config);
    }

    // ─── 状态流转 ───

    @Nested
    @DisplayName("状态流转")
    class StateTransition {

        @Test
        @DisplayName("新 sysid 的初始状态为 NOT_BROADCASTING（get 返回 null）")
        void newSysid_initialState_isNotBroadcasting() {
            assertThat(manager.get(1)).isNull();
        }

        @Test
        @DisplayName("updateBasicId 后状态转为 BROADCASTING")
        void updateBasicId_transitionsToBroadcasting() {
            BasicIdData data = new BasicIdData(1, 2, "UAS-001");
            manager.updateBasicId(1, data);

            RidSnapshot snapshot = manager.get(1);
            assertThat(snapshot).isNotNull();
            assertThat(snapshot.ridStatus()).isEqualTo(RidComplianceState.BROADCASTING);
            assertThat(snapshot.basicId()).isEqualTo(data);
        }

        @Test
        @DisplayName("updateLocation 后状态转为 BROADCASTING")
        void updateLocation_transitionsToBroadcasting() {
            LocationData data = new LocationData(2, 180, 0, 0,
                    39.9, 116.3, 100.0f, 120.0f, 0,
                    0.0f, 10, 10, 10, 10, 1.0f);
            manager.updateLocation(1, data);

            RidSnapshot snapshot = manager.get(1);
            assertThat(snapshot).isNotNull();
            assertThat(snapshot.ridStatus()).isEqualTo(RidComplianceState.BROADCASTING);
            assertThat(snapshot.location()).isEqualTo(data);
        }

        @Test
        @DisplayName("超时后状态转为 BROADCASTING_ERROR")
        void checkTimeout_transitionsToBroadcastingError() throws InterruptedException {
            BasicIdData data = new BasicIdData(1, 2, "UAS-001");
            manager.updateBasicId(1, data);

            // 等待超时（1ms 阈值，等待 50ms 确保超时）
            Thread.sleep(50);
            manager.checkTimeout();

            RidSnapshot snapshot = manager.get(1);
            assertThat(snapshot).isNotNull();
            assertThat(snapshot.ridStatus()).isEqualTo(RidComplianceState.BROADCASTING_ERROR);
        }

        @Test
        @DisplayName("从 BROADCASTING_ERROR 收到消息后转回 BROADCASTING")
        void broadcastingError_recoversToBroadcasting() throws InterruptedException {
            // 先进入 BROADCASTING 状态
            manager.updateBasicId(1, new BasicIdData(1, 2, "UAS-001"));

            // 等待超时进入 BROADCASTING_ERROR
            Thread.sleep(50);
            manager.checkTimeout();
            assertThat(manager.get(1).ridStatus()).isEqualTo(RidComplianceState.BROADCASTING_ERROR);

            // 收到新消息，恢复到 BROADCASTING
            manager.updateLocation(1, new LocationData(2, 180, 0, 0,
                    39.9, 116.3, 100.0f, 120.0f, 0,
                    0.0f, 10, 10, 10, 10, 1.0f));

            RidSnapshot snapshot = manager.get(1);
            assertThat(snapshot.ridStatus()).isEqualTo(RidComplianceState.BROADCASTING);
        }
    }

    // ─── 查询功能 ───

    @Nested
    @DisplayName("查询功能")
    class QueryOperations {

        @Test
        @DisplayName("getAll 返回所有 sysid 的快照（按 sysid 升序排列）")
        void getAll_returnsAllSortedBySysid() {
            manager.updateBasicId(3, new BasicIdData(1, 2, "UAS-003"));
            manager.updateBasicId(1, new BasicIdData(1, 2, "UAS-001"));
            manager.updateBasicId(2, new BasicIdData(1, 2, "UAS-002"));

            List<RidSnapshot> all = manager.getAll();

            assertThat(all).hasSize(3);
            assertThat(all.get(0).sysid()).isEqualTo(1);
            assertThat(all.get(1).sysid()).isEqualTo(2);
            assertThat(all.get(2).sysid()).isEqualTo(3);
        }

        @Test
        @DisplayName("getAll 空管理器返回空列表")
        void getAll_empty_returnsEmptyList() {
            assertThat(manager.getAll()).isEmpty();
        }

        @Test
        @DisplayName("get 不存在的 sysid 返回 null")
        void get_nonExistent_returnsNull() {
            assertThat(manager.get(99)).isNull();
        }
    }

    // ─── 移除功能 ───

    @Nested
    @DisplayName("移除功能")
    class RemoveOperations {

        @Test
        @DisplayName("remove 后 get 返回 null")
        void remove_thenGetReturnsNull() {
            manager.updateBasicId(1, new BasicIdData(1, 2, "UAS-001"));

            RidSnapshot removed = manager.remove(1);

            assertThat(removed).isNotNull();
            assertThat(removed.sysid()).isEqualTo(1);
            assertThat(manager.get(1)).isNull();
        }

        @Test
        @DisplayName("remove 不存在的 sysid 返回 null")
        void remove_nonExistent_returnsNull() {
            assertThat(manager.remove(99)).isNull();
        }
    }

    // ─── 数据保持 ───

    @Nested
    @DisplayName("数据保持")
    class DataPreservation {

        @Test
        @DisplayName("updateLocation 后 basicId 数据保持不变")
        void updateLocation_preservesBasicId() {
            BasicIdData basicId = new BasicIdData(1, 2, "UAS-001");
            manager.updateBasicId(1, basicId);

            LocationData location = new LocationData(2, 180, 0, 0,
                    39.9, 116.3, 100.0f, 120.0f, 0,
                    0.0f, 10, 10, 10, 10, 1.0f);
            manager.updateLocation(1, location);

            RidSnapshot snapshot = manager.get(1);
            assertThat(snapshot.basicId()).isEqualTo(basicId);
            assertThat(snapshot.location()).isEqualTo(location);
        }

        @Test
        @DisplayName("updateOperatorId 后其他数据保持不变")
        void updateOperatorId_preservesOtherData() {
            BasicIdData basicId = new BasicIdData(1, 2, "UAS-001");
            LocationData location = new LocationData(2, 180, 0, 0,
                    39.9, 116.3, 100.0f, 120.0f, 0,
                    0.0f, 10, 10, 10, 10, 1.0f);
            manager.updateBasicId(1, basicId);
            manager.updateLocation(1, location);

            OperatorIdData operatorId = new OperatorIdData(0, "OPERATOR-123456");
            manager.updateOperatorId(1, operatorId);

            RidSnapshot snapshot = manager.get(1);
            assertThat(snapshot.basicId()).isEqualTo(basicId);
            assertThat(snapshot.location()).isEqualTo(location);
            assertThat(snapshot.operatorId()).isEqualTo(operatorId);
        }

        @Test
        @DisplayName("updateSystem 后其他数据保持不变")
        void updateSystem_preservesOtherData() {
            BasicIdData basicId = new BasicIdData(1, 2, "UAS-001");
            manager.updateBasicId(1, basicId);

            SystemData system = new SystemData(1, 39.9, 116.3, 1, 500, 200.0f, 50.0f);
            manager.updateSystem(1, system);

            RidSnapshot snapshot = manager.get(1);
            assertThat(snapshot.basicId()).isEqualTo(basicId);
            assertThat(snapshot.system()).isEqualTo(system);
        }

        @Test
        @DisplayName("updateSelfId 后其他数据保持不变")
        void updateSelfId_preservesOtherData() {
            BasicIdData basicId = new BasicIdData(1, 2, "UAS-001");
            manager.updateBasicId(1, basicId);

            SelfIdData selfId = new SelfIdData(0, "NexusSky drone");
            manager.updateSelfId(1, selfId);

            RidSnapshot snapshot = manager.get(1);
            assertThat(snapshot.basicId()).isEqualTo(basicId);
            assertThat(snapshot.selfId()).isEqualTo(selfId);
        }
    }
}