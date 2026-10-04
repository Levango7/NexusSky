package io.aerofleet.sim;

import io.aerofleet.mavlink.MavlinkFrame;
import io.aerofleet.mavlink.enums.MavEnums;
import io.aerofleet.mavlink.messages.CommandAck;
import io.aerofleet.mavlink.messages.CommandLong;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * NexusSky 自定义命令（MAV_CMD 30080-30087）机载派发守卫。
 * <p>
 * 背景：2026-10-04 命令 ID 整带搬迁（原 310-312/320-322/420/421 → 私有区
 * 30000-30007，见 {@link MavEnums}）。云端常量与机载 switch case 是**两处独立
 * 改动**——任何一边漏改，命令就会静默落进 default 分支返回 UNSUPPORTED，
 * 功能失效但无任何报错。本测试走**真实 UDP 协议路径**（编码 CommandLong →
 * 发给运行中的 VirtualDrone → 收 COMMAND_ACK），把 8 条新 ID 的派发路由
 * 逐一钉死：**case 标签没跟上常量搬迁，构建会红。**
 * <p>
 * 断言语义只到「路由已命中且硬件已装配」（ACK.command 回显且
 * result != UNSUPPORTED），不钉各 handler 的业务结果（ACCEPTED/DENIED 随
 * 参数与飞行状态合理变化）。为此必须把被短路的硬件装配齐：
 * <ul>
 *   <li>{@code --env}：否则 envModel==null，30000-30002 恒 UNSUPPORTED；</li>
 *   <li>{@code --actuators}：否则 sprayPump/gripper==null，30003-30005 恒 UNSUPPORTED；</li>
 *   <li>{@code setRadar}/{@code setRotorAero}：雷达与气动默认不装配（null），
 *       30006/30007 恒 UNSUPPORTED。</li>
 * </ul>
 * 各命令参数按 handler javadoc 给合法值（如喷洒选 DISABLE、夹爪选 RESET，
 * 避免改动机外可见的执行机构状态）。
 * <p>
 * 模式来源：VirtualDroneLifecycleTest（独立高端口 + try-with-resources）。
 */
@DisplayName("NexusSky MAV_CMD 30080-30087：机载 switch 派发真实 UDP 往返守卫")
class NexusCommandDispatchTest {

    /** 独立端口，避开 Lifecycle(24700+) 与 Budget(24590+) 测试段。 */
    private static final int DRONE_PORT = 24750;
    /** 机载组件 ID（MAVLink 惯例自动驾驶仪=1）。 */
    private static final int AUTOPILOT_COMP = 1;
    /** 每条命令等待 ACK 的最长时间。 */
    private static final long ACK_TIMEOUT_MS = 5_000;

    /** 一条待派发命令：id + 7 个参数（含义见各 handler javadoc）。 */
    private record Pending(String name, int id,
                           float p1, float p2, float p3, float p4,
                           float p5, float p6, float p7) {
    }

    /** 8 条 NEXUS 命令（保持迁移次序，参数全部合法以绕开业务层 DENIED 之外的分支）。 */
    private static final Map<String, Pending> NEXUS_COMMANDS = new LinkedHashMap<>();

    static {
        NEXUS_COMMANDS.put("ENV_SET_WIND(原310)", new Pending("ENV_SET_WIND",
                MavEnums.MAV_CMD_NEXUS_ENV_SET_WIND, 5, 90, 0, 0, 0, 0, 0));
        NEXUS_COMMANDS.put("ENV_SET_WEATHER(原311)", new Pending("ENV_SET_WEATHER",
                MavEnums.MAV_CMD_NEXUS_ENV_SET_WEATHER, 2, 30, 0, 0, 0, 0, 0));
        NEXUS_COMMANDS.put("ENV_SET_THRESHOLDS(原312)", new Pending("ENV_SET_THRESHOLDS",
                MavEnums.MAV_CMD_NEXUS_ENV_SET_THRESHOLDS, 8, 15, 0, 0, 0, 0, 0));
        // 喷洒选子命令 1=DISABLE：验证派发又不真正开泵。
        NEXUS_COMMANDS.put("SPRAY_CONTROL(原320)", new Pending("SPRAY_CONTROL",
                MavEnums.MAV_CMD_NEXUS_SPRAY_CONTROL, 1, 0, 0, 0, 0, 0, 0));
        // 夹爪选子命令 2=RESET：无抓取/释放副作用。
        NEXUS_COMMANDS.put("GRIPPER_CONTROL(原321)", new Pending("GRIPPER_CONTROL",
                MavEnums.MAV_CMD_NEXUS_GRIPPER_CONTROL, 2, 0, 0, 0, 0, 0, 0));
        NEXUS_COMMANDS.put("PAYLOAD_QUERY(原322)", new Pending("PAYLOAD_QUERY",
                MavEnums.MAV_CMD_NEXUS_PAYLOAD_QUERY, 0, 0, 0, 0, 0, 0, 0));
        // 雷达：mode=0，range=5000m，scanPeriod=1000ms。
        NEXUS_COMMANDS.put("RADAR_CONFIG(原420)", new Pending("RADAR_CONFIG",
                MavEnums.MAV_CMD_NEXUS_RADAR_CONFIG, 0, 0, 0, 0, 0, 5000, 1000));
        // 旋翼：4 桨、直径 0.5m、桨距 0.1、最大转速 6000rpm、海平面空气密度。
        NEXUS_COMMANDS.put("ROTOR_CONFIG(原421)", new Pending("ROTOR_CONFIG",
                MavEnums.MAV_CMD_NEXUS_ROTOR_CONFIG, 4, 0.5f, 0.1f, 6000, 1.225f, 0, 0));
    }

    @Test
    @DisplayName("8 条私有区命令全部被机载 switch 命中（回显 ACK 且非 UNSUPPORTED）")
    void allNexusCommandsDispatched() throws Exception {
        // 注意：SimConfig.parse 的无值开关会吞掉下一个 token 当 value，
        // 因此布尔开关必须写成 --env=1 / --actuators=1 形式，不能裸写。
        SimConfig config = SimConfig.parse(new String[]{
                "--port=" + DRONE_PORT, "--failsafe=off", "--env=1", "--actuators=1"});
        try (VirtualDrone drone = new VirtualDrone(config);
             DatagramSocket gcs = new DatagramSocket()) {
            // 雷达/气动默认不装配，handler 会以 UNSUPPORTED 短路——先注入模拟实现。
            drone.setRadar(new SimulatedRadar());
            drone.setRotorAero(new SimulatedRotorAerodynamics());
            drone.start();
            InetAddress droneAddr = InetAddress.getLoopbackAddress();
            int seq = 0;
            Map<String, CommandAck> failures = new LinkedHashMap<>();

            for (Pending p : NEXUS_COMMANDS.values()) {
                // 无人机 SYS_ID 默认 1（SimConfig 注释），GCS 用 255（VirtualDrone.GCS_SYSID）。
                CommandLong cmd = new CommandLong(1, AUTOPILOT_COMP, p.id(), 0,
                        p.p1(), p.p2(), p.p3(), p.p4(), p.p5(), p.p6(), p.p7());
                byte[] frame = cmd.toFrame(VirtualDrone.GCS_SYSID,
                        AUTOPILOT_COMP, seq++).encodeV2();
                gcs.send(new DatagramPacket(frame, frame.length, droneAddr, DRONE_PORT));

                CommandAck ack = awaitAckFor(gcs, p.id());
                if (ack == null || ack.result == MavEnums.MAV_RESULT_UNSUPPORTED) {
                    failures.put(p.name(), ack);
                }
            }

            assertThat(failures)
                    .as("8 条 NEXUS 命令应全部命中机载派发（ack=null 表示未收到 ACK，"
                            + "UNSUPPORTED 表示落进 default 分支或硬件未装配；逐一列出坏命令）")
                    .isEmpty();
        }
    }

    /**
     * 在 {@link #ACK_TIMEOUT_MS} 内扫描本机收到的帧，找到回显指定命令 ID 的
     * COMMAND_ACK 则返回；超时返回 null（调用方按「路由未命中」记失败）。
     * 帧损坏（截断/CRC 不符）按丢弃处理——心跳等常规广播与 ACK 混在同一条
     * socket 上，扫描必须能跳过它们。
     */
    private CommandAck awaitAckFor(DatagramSocket gcs, int cmdId) throws Exception {
        long deadline = System.currentTimeMillis() + ACK_TIMEOUT_MS;
        byte[] buf = new byte[2048];
        while (System.currentTimeMillis() < deadline) {
            DatagramPacket packet = new DatagramPacket(buf, buf.length);
            try {
                gcs.setSoTimeout(200);
                gcs.receive(packet);
            } catch (java.net.SocketTimeoutException ste) {
                continue;
            }
            byte[] raw = Arrays.copyOf(packet.getData(), packet.getLength());
            MavlinkFrame frame;
            try {
                frame = MavlinkFrame.decodeV2(raw);
            } catch (Exception ignored) {
                continue;
            }
            if (frame.getMessageId() == CommandAck.ID) {
                CommandAck ack = CommandAck.decode(frame);
                if (ack.command == cmdId) {
                    return ack;
                }
            }
        }
        return null;
    }
}
