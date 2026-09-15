package io.aerofleet.linksim;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * RainAttenuation + EnvAwareImpairmentEngine 单测（FR-15/16，数据约束 6.4）：
 * 雨衰叠加、雾衰常数、CLEAR 无叠加、丢包概率上限、不修改既有引擎统计语义。
 */
class RainAttenuationTest {

    @Test
    void rainAddsExtraDelay() {
        // RAIN R=10mm/h → attenDb = K * 10^α * 1km = 0.0001 * 10 * 1 = 0.001 dB
        // 额外延迟 = 0.001 * 0.1 * baseDelayMs
        double atten = RainAttenuation.attenuationDb(2, 10);
        assertTrue(atten > 0, "RAIN should add positive attenuation");
        double extraDelay = RainAttenuation.extraDelayMs(atten, 100);
        assertTrue(extraDelay > 0, "RAIN should add positive extra delay");
    }

    @Test
    void fogAddsConstantAttenuation() {
        double atten = RainAttenuation.attenuationDb(4, 0);  // FOG
        assertEquals(RainAttenuation.FOG_ATTEN_DB, atten, 1e-9, "FOG -> 3dB constant");
        double extraDelay = RainAttenuation.extraDelayMs(atten, 100);
        assertEquals(30, extraDelay, 1e-9, "3dB * 0.1 * 100ms = 30ms extra delay");
    }

    @Test
    void clearNoExtraAttenuation() {
        assertEquals(0, RainAttenuation.attenuationDb(0, 0), 1e-9, "CLEAR -> 0dB");
        assertEquals(0, RainAttenuation.attenuationDb(1, 0), 1e-9, "CLOUDY -> 0dB");
        assertEquals(0, RainAttenuation.extraDelayMs(0, 100), 1e-9);
        assertEquals(0, RainAttenuation.extraDropProb(0), 1e-9);
    }

    @Test
    void rainDropProbBounded() {
        // 极大降雨率 → 丢包概率仍 ≤ 5%
        double hugeAtten = 1000;  // 假设极大衰减
        double prob = RainAttenuation.extraDropProb(hugeAtten);
        assertTrue(prob <= 0.05, "drop probability must be bounded at 5%, got " + prob);
        // FOG 3dB → 3 * 0.005 = 0.015 = 1.5%
        double fogProb = RainAttenuation.extraDropProb(RainAttenuation.FOG_ATTEN_DB);
        assertEquals(0.015, fogProb, 1e-9, "FOG 3dB -> 1.5% drop prob");
    }

    @Test
    void envAwareEngineClearEqualsBase() {
        // CLEAR 天气 → EnvAwareImpairmentEngine.verdict == base.verdict（无叠加）
        ImpairmentEngine base = new ImpairmentEngine(10, 0, 0, 0, 0, 0, 0, 0, 0, 0);
        EnvAwareImpairmentEngine env = new EnvAwareImpairmentEngine(base, 10);
        env.updateEnvironment(0, 0);  // CLEAR
        // 多次采样：CLEAR 时两者应一致（base 不丢包，env 也不丢包）
        for (int i = 0; i < 100; i++) {
            long baseDelay = base.verdict(100);
            long envDelay = env.verdict(100);
            assertEquals(baseDelay, envDelay,
                    "CLEAR: env verdict should equal base verdict");
        }
    }

    @Test
    void envAwareEngineFogMayDropOrDelay() {
        // FOG → 额外延迟或丢包（概率性，验证不抛异常 + 统计可读）
        ImpairmentEngine base = new ImpairmentEngine(10, 0, 0, 0, 0, 0, 0, 0, 0, 0);
        EnvAwareImpairmentEngine env = new EnvAwareImpairmentEngine(base, 10);
        env.updateEnvironment(4, 0);  // FOG
        for (int i = 0; i < 1000; i++) {
            long delay = env.verdict(100);
            assertTrue(delay == -1 || delay >= 10, "FOG: verdict is drop or delayed");
        }
        String stats = env.rainStats();
        assertNotNull(stats);
        assertTrue(stats.contains("rainFwd=") && stats.contains("rainDrop="));
    }

    @Test
    void baseEngineUnchanged() {
        // FR-16: ImpairmentEngine.verdict() 源码与统计语义不变
        // 验证 base 引用仍可独立工作（EnvAwareImpairmentEngine 不修改 base 内部状态）
        ImpairmentEngine base = new ImpairmentEngine(10, 0, 0, 0, 0, 0, 0, 0, 0, 0);
        EnvAwareImpairmentEngine env = new EnvAwareImpairmentEngine(base, 10);
        env.updateEnvironment(2, 50);  // RAIN
        // 调 env.verdict 不应破坏 base.stats 语义
        env.verdict(100);
        String baseStats = base.stats();
        assertTrue(baseStats.contains("fwd=") && baseStats.contains("drop="),
                "base engine stats format unchanged");
        // base 引用可独立调用
        long d = base.verdict(100);
        assertTrue(d >= 0, "base engine still works independently");
    }
}