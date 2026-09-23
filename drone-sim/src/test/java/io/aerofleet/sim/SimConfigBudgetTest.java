package io.aerofleet.sim;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * SimConfig 丐版模式（--budget）参数解析单测。
 * <p>
 * 验证 --budget=toy|standard|advanced|emergency-toy|emergency-standard 五档丐版模式解析，
 * 以及 budgetMode=null 时既有行为完全不变（DFX 4.5 向下兼容）。
 */
class SimConfigBudgetTest {

    @Test
    void budget_toy_parsedCorrectly() {
        // --budget=toy 解析为 BudgetMode.TOY
        SimConfig cfg = SimConfig.parse(new String[]{"--budget=toy"});
        assertEquals(BudgetMode.TOY, cfg.budgetMode);
    }

    @Test
    void budget_standard_parsedCorrectly() {
        // --budget=standard 解析为 BudgetMode.STANDARD
        SimConfig cfg = SimConfig.parse(new String[]{"--budget=standard"});
        assertEquals(BudgetMode.STANDARD, cfg.budgetMode);
    }

    @Test
    void budget_advanced_parsedCorrectly() {
        // --budget=advanced 解析为 BudgetMode.ADVANCED
        SimConfig cfg = SimConfig.parse(new String[]{"--budget=advanced"});
        assertEquals(BudgetMode.ADVANCED, cfg.budgetMode);
    }

    @Test
    void budget_absent_returnsNull() {
        // 不指定 --budget 时 budgetMode 为 null（完整版，既有行为不变）
        SimConfig cfg = SimConfig.parse(new String[]{});
        assertNull(cfg.budgetMode);
    }

    @Test
    void budget_null_preservesExistingBehavior() {
        // budgetMode=null 时其他参数正常解析（DFX 4.5 向下兼容）
        SimConfig cfg = SimConfig.parse(new String[]{
                "--port=14541", "--sysid=2", "--lat=22.0", "--lon=114.0",
                "--speed=10.0", "--name=AF-TEST"
        });
        assertNull(cfg.budgetMode);
        assertEquals(14541, cfg.port);
        assertEquals(2, cfg.sysid);
        assertEquals(22.0, cfg.lat, 0.0001);
        assertEquals(114.0, cfg.lon, 0.0001);
        assertEquals(10.0, cfg.speed, 0.0001);
        assertEquals("AF-TEST", cfg.name);
    }

    @Test
    void budget_toy_withOtherArgs_allParsed() {
        // --budget=toy 与其他参数共存，全部正确解析
        SimConfig cfg = SimConfig.parse(new String[]{
                "--budget=toy", "--port=14542", "--sysid=3", "--speed=5.0"
        });
        assertEquals(BudgetMode.TOY, cfg.budgetMode);
        assertEquals(14542, cfg.port);
        assertEquals(3, cfg.sysid);
        assertEquals(5.0, cfg.speed, 0.0001);
    }

    @Test
    void budget_invalid_value_ignored() {
        // --budget=invalid 无效值被忽略，budgetMode 保持 null
        SimConfig cfg = SimConfig.parse(new String[]{"--budget=invalid"});
        assertNull(cfg.budgetMode);
    }

    @Test
    void budget_toy_space_form() {
        // --budget toy（空格形式）也正确解析为 BudgetMode.TOY
        SimConfig cfg = SimConfig.parse(new String[]{"--budget", "toy"});
        assertEquals(BudgetMode.TOY, cfg.budgetMode);
    }

    @Test
    void budget_defaults_returnsNull() {
        // SimConfig.defaults() 返回 budgetMode=null
        SimConfig cfg = SimConfig.defaults();
        assertNull(cfg.budgetMode);
    }

    @Test
    void budget_standard_with_env_and_actuators() {
        // --budget=standard 与 --env、--actuators 等开关共存，全部正确解析
        // 开关参数用 =on 形式避免被 --key value 解析逻辑消耗下一个参数
        SimConfig cfg = SimConfig.parse(new String[]{
                "--budget=standard", "--env=on", "--actuators=on", "--mesh=on"
        });
        assertEquals(BudgetMode.STANDARD, cfg.budgetMode);
        assertTrue(cfg.envEnabled);
        assertTrue(cfg.actuatorsEnabled);
        assertTrue(cfg.meshEnabled);
    }
}
