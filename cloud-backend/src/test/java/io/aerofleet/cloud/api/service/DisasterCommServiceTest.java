package io.aerofleet.cloud.api.service;

import io.aerofleet.cloud.gateway.MavlinkMessageEvent;
import io.aerofleet.mavlink.messages.ClusterFormationMsg;
import io.aerofleet.mavlink.messages.DisasterModeStatusMsg;
import io.aerofleet.mavlink.messages.QoSRouteDecisionMsg;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * DisasterCommService 灾害通信监控服务单测（P2 灾害应急通讯组网扩展）。
 * <p>
 * 直接实例化 Service（无 Spring 上下文），覆盖灾害模式状态管理、分簇拓扑、
 * QoS 优先级队列、异构链路桥接、MAVLink 事件监听。
 */
@DisplayName("DisasterCommService 灾害通信监控服务 (P2)")
class DisasterCommServiceTest {

    @Test
    @DisplayName("初始状态：灾害模式 inactive")
    void initialStatusInactive() {
        DisasterCommService svc = new DisasterCommService(null);
        Map<String, Object> status = svc.getDisasterStatus();
        assertThat(status.get("mode")).isEqualTo("inactive");
        assertThat(status.get("triggerReason")).isEqualTo("");
        assertThat(status.get("affectedNodes")).isEqualTo(0);
        assertThat(status.get("recoveryRate")).isEqualTo(0);
    }

    @Test
    @DisplayName("activateDisasterMode 手动激活后状态为 active")
    void activateDisasterMode() {
        DisasterCommService svc = new DisasterCommService(null);
        Map<String, Object> status = svc.activateDisasterMode();
        assertThat(status.get("mode")).isEqualTo("active");
        assertThat(status.get("triggerReason")).isEqualTo("manual");
        // 再次查询确认状态持久
        assertThat(svc.getDisasterStatus().get("mode")).isEqualTo("active");
    }

    @Test
    @DisplayName("deactivateDisasterMode 手动退出后状态为 inactive 且恢复率 100%")
    void deactivateDisasterMode() {
        DisasterCommService svc = new DisasterCommService(null);
        svc.activateDisasterMode();
        Map<String, Object> status = svc.deactivateDisasterMode();
        assertThat(status.get("mode")).isEqualTo("inactive");
        assertThat(status.get("recoveryRate")).isEqualTo(100);
        assertThat(status.get("affectedNodes")).isEqualTo(0);
    }

    @Test
    @DisplayName("currentVersion 每次状态变更递增")
    void versionIncrements() {
        DisasterCommService svc = new DisasterCommService(null);
        long v0 = svc.currentVersion();
        svc.activateDisasterMode();
        assertThat(svc.currentVersion()).isEqualTo(v0 + 1);
        svc.deactivateDisasterMode();
        assertThat(svc.currentVersion()).isEqualTo(v0 + 2);
    }

    @Test
    @DisplayName("getClusterTopology 初始无簇")
    void clusterTopologyInitialEmpty() {
        DisasterCommService svc = new DisasterCommService(null);
        Map<String, Object> topology = svc.getClusterTopology();
        assertThat(topology.get("clusterCount")).isEqualTo(0);
        assertThat((java.util.List<?>) topology.get("clusters")).isEmpty();
    }

    @Test
    @DisplayName("getQoSStatus 初始无队列")
    void qosStatusInitialEmpty() {
        DisasterCommService svc = new DisasterCommService(null);
        Map<String, Object> qos = svc.getQoSStatus();
        assertThat(qos.get("queueCount")).isEqualTo(0);
        assertThat((java.util.List<?>) qos.get("queues")).isEmpty();
    }

    @Test
    @DisplayName("getLinkBridgeStatus 初始无桥接")
    void linkBridgeStatusInitialEmpty() {
        DisasterCommService svc = new DisasterCommService(null);
        Map<String, Object> bridges = svc.getLinkBridgeStatus();
        assertThat(bridges.get("bridgeCount")).isEqualTo(0);
        assertThat((java.util.List<?>) bridges.get("bridges")).isEmpty();
    }

    // =====================================================================
    // MAVLink 事件监听测试
    // =====================================================================

    @Test
    @DisplayName("onQoSRouteDecision (msgId=480) 更新 QoS 队列状态")
    void onQoSRouteDecisionUpdatesQueue() {
        DisasterCommService svc = new DisasterCommService(null);
        QoSRouteDecisionMsg msg = new QoSRouteDecisionMsg(
                System.currentTimeMillis(), 500, 1, 0, 1, 2);
        MavlinkMessageEvent event = new MavlinkMessageEvent(this, 1, QoSRouteDecisionMsg.ID, msg, msg.timestamp);
        svc.onQoSRouteDecision(event);

        Map<String, Object> qos = svc.getQoSStatus();
        assertThat(qos.get("queueCount")).isEqualTo(1);
        java.util.List<?> queues = (java.util.List<?>) qos.get("queues");
        assertThat(queues).hasSize(1);
        Map<?, ?> queue = (Map<?, ?>) queues.get(0);
        assertThat(queue.get("priorityClass")).isEqualTo("EMERGENCY");
        assertThat(queue.get("routeId")).isEqualTo(1);
        assertThat(queue.get("bandwidthAlloc")).isEqualTo(500);
    }

    @Test
    @DisplayName("onClusterFormation (msgId=481) 更新分簇拓扑")
    void onClusterFormationUpdatesCluster() {
        DisasterCommService svc = new DisasterCommService(null);
        int[] members = {1, 2, 3, 0, 0, 0, 0, 0};
        ClusterFormationMsg msg = new ClusterFormationMsg(
                System.currentTimeMillis(), 500, 1, 1, members, 3);
        MavlinkMessageEvent event = new MavlinkMessageEvent(this, 1, ClusterFormationMsg.ID, msg, msg.timestamp);
        svc.onClusterFormation(event);

        Map<String, Object> topology = svc.getClusterTopology();
        assertThat(topology.get("clusterCount")).isEqualTo(1);
        java.util.List<?> clusters = (java.util.List<?>) topology.get("clusters");
        assertThat(clusters).hasSize(1);
        Map<?, ?> cluster = (Map<?, ?>) clusters.get(0);
        assertThat(cluster.get("clusterId")).isEqualTo(1);
        assertThat(cluster.get("clusterHead")).isEqualTo(1);
        assertThat(cluster.get("memberCount")).isEqualTo(3);
        assertThat(cluster.get("clusterRadius")).isEqualTo(500);
        java.util.List<?> memberList = (java.util.List<?>) cluster.get("members");
        assertThat(memberList).hasSize(3);
        assertThat(memberList.get(0)).isEqualTo(1);
        assertThat(memberList.get(1)).isEqualTo(2);
        assertThat(memberList.get(2)).isEqualTo(3);
    }

    @Test
    @DisplayName("onDisasterModeStatus (msgId=482) 更新灾害模式状态为 active")
    void onDisasterModeStatusActive() {
        DisasterCommService svc = new DisasterCommService(null);
        DisasterModeStatusMsg msg = new DisasterModeStatusMsg(
                System.currentTimeMillis(),
                DisasterModeStatusMsg.MODE_ACTIVE,
                DisasterModeStatusMsg.REASON_HEARTBEAT_TIMEOUT,
                15, 30);
        MavlinkMessageEvent event = new MavlinkMessageEvent(this, 1, DisasterModeStatusMsg.ID, msg, msg.timestamp);
        svc.onDisasterModeStatus(event);

        Map<String, Object> status = svc.getDisasterStatus();
        assertThat(status.get("mode")).isEqualTo("active");
        assertThat(status.get("triggerReason")).isEqualTo("heartbeat_timeout");
        assertThat(status.get("affectedNodes")).isEqualTo(15);
        assertThat(status.get("recoveryRate")).isEqualTo(30);
    }

    @Test
    @DisplayName("onDisasterModeStatus (msgId=482) 更新灾害模式状态为 inactive")
    void onDisasterModeStatusInactive() {
        DisasterCommService svc = new DisasterCommService(null);
        // 先激活
        svc.activateDisasterMode();
        // 收到 inactive 消息
        DisasterModeStatusMsg msg = new DisasterModeStatusMsg(
                System.currentTimeMillis(),
                DisasterModeStatusMsg.MODE_INACTIVE,
                DisasterModeStatusMsg.REASON_AUTO,
                0, 85);
        MavlinkMessageEvent event = new MavlinkMessageEvent(this, 1, DisasterModeStatusMsg.ID, msg, msg.timestamp);
        svc.onDisasterModeStatus(event);

        Map<String, Object> status = svc.getDisasterStatus();
        assertThat(status.get("mode")).isEqualTo("inactive");
        assertThat(status.get("triggerReason")).isEqualTo("auto");
        assertThat(status.get("recoveryRate")).isEqualTo(85);
    }

    @Test
    @DisplayName("QoS 4 个优先级类别名称正确映射")
    void priorityClassNameMapping() {
        DisasterCommService svc = new DisasterCommService(null);
        // EMERGENCY (0)
        QoSRouteDecisionMsg msg0 = new QoSRouteDecisionMsg(0, 600, 1, 0, 1, 0);
        svc.onQoSRouteDecision(new MavlinkMessageEvent(this, 1, QoSRouteDecisionMsg.ID, msg0, 0));
        // COMMAND (1)
        QoSRouteDecisionMsg msg1 = new QoSRouteDecisionMsg(0, 250, 2, 1, 1, 0);
        svc.onQoSRouteDecision(new MavlinkMessageEvent(this, 1, QoSRouteDecisionMsg.ID, msg1, 0));
        // MAPPING (2)
        QoSRouteDecisionMsg msg2 = new QoSRouteDecisionMsg(0, 100, 3, 2, 1, 0);
        svc.onQoSRouteDecision(new MavlinkMessageEvent(this, 1, QoSRouteDecisionMsg.ID, msg2, 0));
        // ROUTINE (3)
        QoSRouteDecisionMsg msg3 = new QoSRouteDecisionMsg(0, 50, 4, 3, 1, 0);
        svc.onQoSRouteDecision(new MavlinkMessageEvent(this, 1, QoSRouteDecisionMsg.ID, msg3, 0));

        Map<String, Object> qos = svc.getQoSStatus();
        assertThat(qos.get("queueCount")).isEqualTo(4);
        java.util.List<?> queues = (java.util.List<?>) qos.get("queues");
        assertThat(queues).hasSize(4);
        // 验证名称映射
        Map<?, ?> q0 = (Map<?, ?>) queues.get(0);
        assertThat(q0.get("priorityClass")).isEqualTo("EMERGENCY");
        Map<?, ?> q1 = (Map<?, ?>) queues.get(1);
        assertThat(q1.get("priorityClass")).isEqualTo("COMMAND");
        Map<?, ?> q2 = (Map<?, ?>) queues.get(2);
        assertThat(q2.get("priorityClass")).isEqualTo("MAPPING");
        Map<?, ?> q3 = (Map<?, ?>) queues.get(3);
        assertThat(q3.get("priorityClass")).isEqualTo("ROUTINE");
    }

    @Test
    @DisplayName("触发原因 4 种类型名称正确映射")
    void triggerReasonNameMapping() {
        DisasterCommService svc = new DisasterCommService(null);

        // manual (0)
        DisasterModeStatusMsg msg0 = new DisasterModeStatusMsg(0,
                DisasterModeStatusMsg.MODE_ACTIVE, DisasterModeStatusMsg.REASON_MANUAL, 5, 10);
        svc.onDisasterModeStatus(new MavlinkMessageEvent(this, 1, DisasterModeStatusMsg.ID, msg0, 0));
        assertThat(svc.getDisasterStatus().get("triggerReason")).isEqualTo("manual");

        // heartbeat_timeout (1)
        DisasterModeStatusMsg msg1 = new DisasterModeStatusMsg(0,
                DisasterModeStatusMsg.MODE_ACTIVE, DisasterModeStatusMsg.REASON_HEARTBEAT_TIMEOUT, 5, 10);
        svc.onDisasterModeStatus(new MavlinkMessageEvent(this, 1, DisasterModeStatusMsg.ID, msg1, 0));
        assertThat(svc.getDisasterStatus().get("triggerReason")).isEqualTo("heartbeat_timeout");

        // terrain_change (2)
        DisasterModeStatusMsg msg2 = new DisasterModeStatusMsg(0,
                DisasterModeStatusMsg.MODE_ACTIVE, DisasterModeStatusMsg.REASON_TERRAIN_CHANGE, 5, 10);
        svc.onDisasterModeStatus(new MavlinkMessageEvent(this, 1, DisasterModeStatusMsg.ID, msg2, 0));
        assertThat(svc.getDisasterStatus().get("triggerReason")).isEqualTo("terrain_change");

        // auto (3)
        DisasterModeStatusMsg msg3 = new DisasterModeStatusMsg(0,
                DisasterModeStatusMsg.MODE_ACTIVE, DisasterModeStatusMsg.REASON_AUTO, 5, 10);
        svc.onDisasterModeStatus(new MavlinkMessageEvent(this, 1, DisasterModeStatusMsg.ID, msg3, 0));
        assertThat(svc.getDisasterStatus().get("triggerReason")).isEqualTo("auto");
    }

    @Test
    @DisplayName("多个分簇同时存在时 getClusterTopology 返回全部")
    void multipleClusters() {
        DisasterCommService svc = new DisasterCommService(null);

        // 簇 1
        int[] members1 = {1, 2, 3, 0, 0, 0, 0, 0};
        ClusterFormationMsg msg1 = new ClusterFormationMsg(0, 300, 1, 1, members1, 3);
        svc.onClusterFormation(new MavlinkMessageEvent(this, 1, ClusterFormationMsg.ID, msg1, 0));

        // 簇 2
        int[] members2 = {4, 5, 6, 7, 0, 0, 0, 0};
        ClusterFormationMsg msg2 = new ClusterFormationMsg(0, 400, 2, 4, members2, 4);
        svc.onClusterFormation(new MavlinkMessageEvent(this, 1, ClusterFormationMsg.ID, msg2, 0));

        Map<String, Object> topology = svc.getClusterTopology();
        assertThat(topology.get("clusterCount")).isEqualTo(2);
        java.util.List<?> clusters = (java.util.List<?>) topology.get("clusters");
        assertThat(clusters).hasSize(2);
    }

    @Test
    @DisplayName("同一簇 ID 收到新消息时覆盖旧信息")
    void clusterIdOverwrite() {
        DisasterCommService svc = new DisasterCommService(null);

        // 簇 1 初始 3 个成员
        int[] members1 = {1, 2, 3, 0, 0, 0, 0, 0};
        ClusterFormationMsg msg1 = new ClusterFormationMsg(0, 300, 1, 1, members1, 3);
        svc.onClusterFormation(new MavlinkMessageEvent(this, 1, ClusterFormationMsg.ID, msg1, 0));

        // 簇 1 更新为 5 个成员
        int[] members2 = {1, 2, 3, 4, 5, 0, 0, 0};
        ClusterFormationMsg msg2 = new ClusterFormationMsg(0, 300, 1, 1, members2, 5);
        svc.onClusterFormation(new MavlinkMessageEvent(this, 1, ClusterFormationMsg.ID, msg2, 0));

        Map<String, Object> topology = svc.getClusterTopology();
        assertThat(topology.get("clusterCount")).isEqualTo(1); // 仍然只有一个簇
        java.util.List<?> clusters = (java.util.List<?>) topology.get("clusters");
        Map<?, ?> cluster = (Map<?, ?>) clusters.get(0);
        assertThat(cluster.get("memberCount")).isEqualTo(5);
    }
}