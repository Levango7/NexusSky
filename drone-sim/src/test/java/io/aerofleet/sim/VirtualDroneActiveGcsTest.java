package io.aerofleet.sim;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.SocketTimeoutException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * --gcs-host 主动注册钉测：drone-sim 主动向指定地址发心跳。
 *
 * <p><b>背景（实跑验收发现的真实缺口）</b>：drone-sim 原本只能被动学习对端地址，
 * 导致中继学不到 droneAddr、后端 discovery 经中继转发不到 drone-sim，
 * e2e-mesh 完整版「远端飞机经中继注册」FAIL。--gcs-host 让 drone-sim 额外向指定地址
 * 发心跳，中继据此学习 droneAddr，后端 discovery 才能经中继到达 drone-sim。
 *
 * <p><b>判别力</b>：配了 --gcs-host 时假中继收到心跳（主动）；未配时假中继收不到（被动），
 * 一正一反证明主动注册确实由 --gcs-host 驱动，而非原本就有的行为。
 */
@DisplayName("VirtualDrone: --gcs-host 主动注册心跳")
class VirtualDroneActiveGcsTest {

    @Test
    @DisplayName("配 --gcs-host：drone-sim 主动向目标地址发心跳（假中继收到）")
    void activeGcsHost_sendsHeartbeatToTarget() throws Exception {
        try (DatagramSocket relay = new DatagramSocket(0, InetAddress.getByName("127.0.0.1"))) {
            relay.setSoTimeout(15_000);
            int relayPort = relay.getLocalPort();
            SimConfig config = SimConfig.parse(new String[]{
                    "--port=0", "--sysid=2", "--gcs-host", "127.0.0.1:" + relayPort});
            try (VirtualDrone drone = new VirtualDrone(config)) {
                drone.start();
                // 心跳 1Hz：阻塞等待假中继收到主动注册包（超时 15s 则说明主动注册未生效）
                byte[] buf = new byte[64];
                DatagramPacket pkt = new DatagramPacket(buf, buf.length);
                relay.receive(pkt);
                assertThat(pkt.getLength())
                        .as("drone-sim 应主动向 --gcs-host 目标发心跳")
                        .isGreaterThan(0);
            }
        }
    }

    @Test
    @DisplayName("未配 --gcs-host：保持被动，不向无关目标发包（对照组）")
    void noGcsHost_staysPassive_noPacketToArbitraryTarget() throws Exception {
        try (DatagramSocket relay = new DatagramSocket(0, InetAddress.getByName("127.0.0.1"))) {
            relay.setSoTimeout(2_500);
            SimConfig config = SimConfig.parse(new String[]{"--port=0", "--sysid=2"});
            try (VirtualDrone drone = new VirtualDrone(config)) {
                drone.start();
                byte[] buf = new byte[64];
                DatagramPacket pkt = new DatagramPacket(buf, buf.length);
                // 被动时 getLastPeer()=null，send() 不发 ⇒ 假中继收不到任何包
                assertThrows(SocketTimeoutException.class, () -> relay.receive(pkt),
                        "未配 --gcs-host 时不应向无关目标发包（被动）");
            }
        }
    }

    @Test
    @DisplayName("SimConfig：--gcs-host 解析与默认值")
    void parseGcsHost_roundtrip() {
        SimConfig cfg = SimConfig.parse(new String[]{"--gcs-host", "127.0.0.1:14543"});
        assertEquals("127.0.0.1:14543", cfg.gcsHost);
        assertNull(SimConfig.defaults().gcsHost, "默认应仅被动（gcsHost=null）");
    }
}
