package io.aerofleet.sim;

import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * 机巢状态机核心（设备侧语义单源）。
 *
 * <p>HTTP 回环对端（{@link DockSim}）与 MQTT 对端（{@link DockSimMqttPeer}）共用这一份
 * 状态机：两边的差异只在**传输**（帧形状/topic），语义必须一致——否则两条路的 e2e
 * 断言会互相打架（一路说门开、另一路说门关）。
 *
 * <p>状态取值沿用既有 sim 口径：IDLE / OPENING / OPEN / CLOSING / CHARGING /
 * EXCHANGING / FAULT；命令语义（door_open / door_close / battery_swap / reboot）
 * 是 NexusSky 侧语义，不是 DJI 官方 method——详见 cloud-backend 的 {@code DockCommand}。
 */
public final class DockSimCore implements AutoCloseable {

    /**
     * 命令执行结果（DJI {@code data.result} 口径）。
     *
     * @param result  0=成功，1=机巢侧非法态拒绝，2=未知 method
     * @param message 设备自建消息（可为 null；进 {@code data.output.message}）
     */
    public record Result(int result, String message) {
    }

    private volatile String state = "IDLE";
    private volatile double temperature = 35.0;
    private volatile int batteryPct = 80;
    private volatile String faultReason;

    private final int droneSysid;
    private final Double tempFixedC;

    private final ScheduledExecutorService timer = Executors.newScheduledThreadPool(2, r -> {
        Thread t = new Thread(r, "dock-sim-core-timer");
        t.setDaemon(true);
        return t;
    });

    public DockSimCore(int droneSysid, Double tempFixedC) {
        this.droneSysid = droneSysid;
        this.tempFixedC = tempFixedC;
    }

    /** 执行一条 services 命令（语义见类注释）。 */
    public Result apply(String method) {
        String cur = state;
        switch (method) {
            case "door_open" -> {
                if (!"IDLE".equals(cur) && !"CHARGING".equals(cur)) {
                    return rejected(cur);
                }
                state = "OPENING";
                timer.schedule(() -> state = "OPEN", 3000, TimeUnit.MILLISECONDS);
                return new Result(0, null);
            }
            case "door_close" -> {
                if (!"OPEN".equals(cur)) {
                    return rejected(cur);
                }
                state = "CLOSING";
                timer.schedule(() -> state = batteryPct < 100 ? "CHARGING" : "IDLE",
                        3000, TimeUnit.MILLISECONDS);
                return new Result(0, null);
            }
            case "battery_swap" -> {
                if (!"IDLE".equals(cur) && !"CHARGING".equals(cur)) {
                    return rejected(cur);
                }
                state = "EXCHANGING";
                timer.schedule(() -> {
                    batteryPct = 20;
                    state = "CHARGING";
                }, 5000, TimeUnit.MILLISECONDS);
                return new Result(0, null);
            }
            case "reboot" -> {
                state = "FAULT";
                faultReason = "manual reboot";
                timer.schedule(() -> {
                    state = "IDLE";
                    faultReason = null;
                }, 8000, TimeUnit.MILLISECONDS);
                return new Result(0, null);
            }
            default -> {
                return new Result(2, "unknown method " + method);
            }
        }
    }

    private Result rejected(String cur) {
        return new Result(1, "state=" + cur);
    }

    /** 周期推进：温度游走 + 充电推进（**不含上报**——上报归各传输）。 */
    public void tick() {
        if (tempFixedC != null) {
            temperature = tempFixedC;
        } else {
            double delta = (Math.random() - 0.5) * 1.0;
            temperature = Math.max(28.0, Math.min(50.0, temperature + delta));
        }
        if ("CHARGING".equals(state) && batteryPct < 100) {
            batteryPct = Math.min(100, batteryPct + 1);
        }
    }

    /**
     * DJI 物模型 OSD 属性子集（**官方属性名**，见 dji-sdk/Cloud-API-Doc v1.18
     * {@code 60.api-reference/20.dock-to-cloud/00.mqtt/20.dock/10.dock2/00.properties.md}）：
     * <ul>
     *   <li>{@code mode_code} 机场状态：0 空闲 / 4 作业中</li>
     *   <li>{@code environment_temperature} 环境温度 ℃</li>
     *   <li>{@code drone_charge_state.capacity_percent} 飞行器电量 %</li>
     *   <li>{@code drone_charge_state.state} 充电状态：0 空闲 / 1 充电中</li>
     *   <li>{@code drone_in_dock} 飞行器是否在舱：0 舱外 / 1 舱内</li>
     *   <li>{@code putter_state} 推杆（舱盖）状态：0 关闭 / 1 打开 / 2 半开</li>
     *   <li>{@code alarm_state} 声光报警：0 关闭 / 1 开启</li>
     * </ul>
     *
     * <p>映射口径（NexusSky 状态 → DJI 属性）写在各分支注释里；官方这几个属性全是
     * {@code accessMode=r}（只读上报），云端不能反向设置它们。
     */
    public Map<String, Object> osdProperties() {
        String cur = state;
        Map<String, Object> m = new LinkedHashMap<>();
        // mode_code：换电属设备作业 → 4 作业中；其余（含 FAULT）→ 0 空闲
        // （FAULT 的诚实信号是 alarm_state=1，不用 mode_code=3 谎称"固件升级中"）
        m.put("mode_code", "EXCHANGING".equals(cur) ? 4 : 0);
        m.put("environment_temperature", Math.round(temperature * 10.0) / 10.0);

        Map<String, Object> charge = new LinkedHashMap<>();
        charge.put("capacity_percent", batteryPct);
        charge.put("state", "CHARGING".equals(cur) ? 1 : 0);
        m.put("drone_charge_state", charge);

        // 充电/换电时机巢内有无人机
        m.put("drone_in_dock", ("CHARGING".equals(cur) || "EXCHANGING".equals(cur)) ? 1 : 0);

        int putter = switch (cur) {
            case "OPEN" -> 1;                       // 打开
            case "OPENING", "CLOSING" -> 2;         // 半开（过渡态）
            default -> 0;                           // 关闭
        };
        m.put("putter_state", putter);
        m.put("alarm_state", "FAULT".equals(cur) ? 1 : 0);
        return m;
    }

    /** 本 sim 口径的 OSD JSON（HTTP 回环对端用；MQTT 侧走 {@link #osdProperties()}）。 */
    public String osdJson() {
        return "{\"sn\":\"" + snForOsd() + "\",\"state\":\"" + state + "\","
                + "\"temperature\":" + String.format(Locale.ROOT, "%.1f", temperature) + ","
                + "\"batteryPct\":" + batteryPct + ","
                + "\"droneSysid\":" + droneSysid + "}";
    }

    private String snForOsd() {
        return snHolder;
    }

    /** OSD JSON 里的 sn（HTTP 对端从构造参数拿；核心本身不持有 sn）。 */
    private volatile String snHolder = "";

    /** 设置 OSD JSON 用的 sn（由各对端在构造时填）。 */
    public void bindSn(String sn) {
        this.snHolder = sn == null ? "" : sn;
    }

    public String state() {
        return state;
    }

    public String faultReason() {
        return faultReason;
    }

    public int batteryPct() {
        return batteryPct;
    }

    public double temperature() {
        return temperature;
    }

    public int droneSysid() {
        return droneSysid;
    }

    /** 测试与故障注入入口。 */
    public void forceState(String s) {
        this.state = s;
    }

    @Override
    public void close() {
        timer.shutdownNow();
    }
}
