package io.aerofleet.sim;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * SimConfig 布尔开关解析加固单测（2026-10-04）。
 * <p>
 * 原实现把无 {@code =} 的布尔开关的下一个 token 当 value 吞掉，导致两类静默故障：
 * <ol>
 *   <li>{@code --env --actuators=1}：env 把 {@code --actuators=1} 吃掉当 value
 *       （value 被忽略），actuators 永远不生效；</li>
 *   <li>布尔开关作为末参数：整个开关落进「Ignoring unknown argument」被静默丢弃。</li>
 * </ol>
 * 加固后布尔开关（env/actuators/mesh/sat-relay/terrain-adapt/celltower/rid/
 * reject-unsigned/autonomy-exec）裸写即生效、不吞下一 token、可作末参数。
 * 仓库内脚本与文档均用 {@code =} 形式（如 e2e-rid.ps1 的 {@code --rid=on}），
 * 无既有用法依赖旧的吞 token 行为。
 */
@DisplayName("SimConfig: 布尔开关解析加固（不吞 token / 末参数 / = 形式共存）")
class SimConfigBooleanFlagTest {

    @Test
    @DisplayName("裸布尔开关不吞后续 --flag=（原实现会静默丢掉后者）")
    void bareBooleanFlagDoesNotEatNextFlag() {
        SimConfig cfg = SimConfig.parse(new String[]{
                "--env", "--actuators=1", "--port=24800"});
        assertTrue(cfg.envEnabled, "--env 裸写应生效");
        assertTrue(cfg.actuatorsEnabled,
                "--actuators=1 不应被 --env 吞掉（原实现的静默故障点）");
        assertEquals(24800, cfg.port, "--port=24800 应正常解析");
    }

    @Test
    @DisplayName("九个布尔开关连续裸写全部生效（原实现会隔一个丢一个）")
    void consecutiveBareBooleanFlagsAllTakeEffect() {
        // 原实现：env 吃 actuators、mesh 吃 sat-relay、terrain-adapt 吃 celltower、
        // rid 吃 reject-unsigned、autonomy-exec 吃 --port——一半开关静默失效。
        SimConfig cfg = SimConfig.parse(new String[]{
                "--env", "--actuators", "--mesh", "--sat-relay", "--terrain-adapt",
                "--celltower", "--rid", "--reject-unsigned", "--autonomy-exec",
                "--port=24801"});
        assertTrue(cfg.envEnabled);
        assertTrue(cfg.actuatorsEnabled);
        assertTrue(cfg.meshEnabled);
        assertTrue(cfg.satRelayEnabled);
        assertTrue(cfg.terrainAdaptEnabled);
        assertTrue(cfg.cellTowerConfig.enabled,
                "--celltower 生效于 cellTowerConfig.enabled");
        assertTrue(cfg.ridEnabled);
        assertTrue(cfg.rejectUnsigned);
        assertTrue(cfg.autonomyExecEnabled);
        assertEquals(24801, cfg.port, "末尾 --port=24801 不受影响");
    }

    @Test
    @DisplayName("布尔开关作为末参数生效（原实现整个开关被静默丢弃）")
    void bareBooleanFlagAsLastArgumentTakesEffect() {
        SimConfig cfg = SimConfig.parse(new String[]{"--port=24802", "--mesh"});
        assertTrue(cfg.meshEnabled, "末位裸开关 --mesh 应生效（原实现静默丢弃）");
        assertEquals(24802, cfg.port);
    }

    @Test
    @DisplayName("布尔开关后跟非 flag token：开关生效、token 按未知参数告警丢弃")
    void bareBooleanFlagFollowedByStrayToken() {
        // 加固语义：布尔开关永不吞 token；stray-token 自行走「未知参数」告警。
        SimConfig cfg = SimConfig.parse(new String[]{
                "--env", "stray-token", "--port=24803"});
        assertTrue(cfg.envEnabled);
        assertEquals(24803, cfg.port, "--port 不应受影响");
    }

    @Test
    @DisplayName("值参数仍正常吞下一 token（--env-scenario windy）")
    void valueFlagStillConsumesNextToken() {
        SimConfig cfg = SimConfig.parse(new String[]{
                "--env-scenario", "windy", "--port=24804"});
        assertEquals("windy", cfg.envScenario, "值参数的吞 token 行为不变");
        assertEquals(24804, cfg.port);
    }

    @Test
    @DisplayName("= 形式布尔开关行为不变（--env=1 / --rid=on）")
    void equalsFormStillWorks() {
        SimConfig cfg = SimConfig.parse(new String[]{"--env=1", "--rid=on"});
        assertTrue(cfg.envEnabled);
        assertTrue(cfg.ridEnabled);
    }

    @Test
    @DisplayName("值参数缺值时不崩溃：告警丢弃、保持默认值")
    void valueFlagMissingValueKeepsDefault() {
        SimConfig cfg = SimConfig.parse(new String[]{"--port=24805", "--env-scenario"});
        assertEquals("calm", cfg.envScenario, "缺值应保持默认场景 calm");
        assertEquals(24805, cfg.port);
    }

    @Test
    @DisplayName("无开关时全部布尔默认关闭（既有行为不变）")
    void allBooleanFlagsDefaultOff() {
        SimConfig cfg = SimConfig.parse(new String[]{"--port=24806"});
        assertFalse(cfg.envEnabled);
        assertFalse(cfg.actuatorsEnabled);
        assertFalse(cfg.meshEnabled);
        assertFalse(cfg.satRelayEnabled);
        assertFalse(cfg.terrainAdaptEnabled);
        assertFalse(cfg.cellTowerConfig.enabled);
        assertFalse(cfg.ridEnabled);
        assertFalse(cfg.rejectUnsigned);
        assertFalse(cfg.autonomyExecEnabled);
    }
}
