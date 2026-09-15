package io.aerofleet.sim;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * SimulatedEnvSource 单测（FR-02/03/05）：确定性、场景基线差异、状态钳位。
 */
class SimulatedEnvSourceTest {

    private static final double DT = 0.05;

    @Test
    void sameSeedSameSequence() {
        SimulatedEnvSource a = new SimulatedEnvSource(EnvScenario.WINDY, 42);
        SimulatedEnvSource b = new SimulatedEnvSource(EnvScenario.WINDY, 42);
        for (int i = 0; i < 100; i++) {
            a.evolve(DT);
            b.evolve(DT);
            assertEquals(a.snapshot(), b.snapshot(),
                    "same seed+scenario should produce identical sequence at tick " + i);
        }
    }

    @Test
    void differentSeedDifferentSequence() {
        SimulatedEnvSource a = new SimulatedEnvSource(EnvScenario.WINDY, 42);
        SimulatedEnvSource b = new SimulatedEnvSource(EnvScenario.WINDY, 99);
        boolean diff = false;
        for (int i = 0; i < 100; i++) {
            a.evolve(DT);
            b.evolve(DT);
            if (!a.snapshot().equals(b.snapshot())) {
                diff = true;
                break;
            }
        }
        assertTrue(diff, "different seeds should produce different sequences");
    }

    @Test
    void scenarioBaselineDifference() {
        // storm 风速基线 > calm
        EnvironmentState stormBase = EnvScenario.STORM.baseline();
        EnvironmentState calmBase = EnvScenario.CALM.baseline();
        assertTrue(stormBase.windSpeed() > calmBase.windSpeed(),
                "storm wind baseline should exceed calm");
        // rainy 默认天气 = RAIN
        assertEquals(Weather.RAIN, EnvScenario.RAINY.baseline().weather());
        // foggy 默认天气 = FOG
        assertEquals(Weather.FOG, EnvScenario.FOGGY.baseline().weather());
    }

    @Test
    void stateClampedToDomain() {
        SimulatedEnvSource s = new SimulatedEnvSource(EnvScenario.STORM, 1);
        for (int i = 0; i < 1000; i++) {
            s.evolve(DT);
            EnvironmentState st = s.snapshot();
            assertTrue(st.temperature() >= -40 && st.temperature() <= 55,
                    "temperature out of domain: " + st.temperature());
            assertTrue(st.humidity() >= 0 && st.humidity() <= 100,
                    "humidity out of domain: " + st.humidity());
            assertTrue(st.windSpeed() >= 0 && st.windSpeed() <= 50,
                    "windSpeed out of domain: " + st.windSpeed());
            assertTrue(st.windDirection() >= 0 && st.windDirection() < 360,
                    "windDirection out of domain: " + st.windDirection());
            assertTrue(st.rainRate() >= 0 && st.rainRate() <= 255,
                    "rainRate out of domain: " + st.rainRate());
        }
    }

    @Test
    void unknownScenarioFallsBackCalm() {
        assertEquals(EnvScenario.CALM, EnvScenario.of("nonexistent"));
        assertEquals(EnvScenario.CALM, EnvScenario.of(null));
        assertEquals(EnvScenario.WINDY, EnvScenario.of("WINDY"));  // 大小写不敏感
    }
}