package io.aerofleet.sim;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;

import static org.assertj.core.api.Assertions.assertThat;

/** F6 RTK：SimConfig 的 --rtk 参数解析（none/fixed/float；非法值降级 none+WARN）。 */
@DisplayName("SimConfig --rtk 解析")
class SimConfigRtkTest {

    @Test
    @DisplayName("缺省 none（既有行为不变：单点 3D_FIX）")
    void defaultIsNone() {
        SimConfig c = SimConfig.parse(new String[]{});
        assertThat(c.rtkMode).isEqualTo("none");
    }

    @Test
    @DisplayName("--rtk fixed / float 正确解析")
    void fixedAndFloat() {
        assertThat(SimConfig.parse(new String[]{"--rtk", "fixed"}).rtkMode).isEqualTo("fixed");
        assertThat(SimConfig.parse(new String[]{"--rtk", "float"}).rtkMode).isEqualTo("float");
    }

    @Test
    @DisplayName("非法值降级 none 并 WARN（不静默吞，与数值参数同纪律）")
    void invalidFallsBackToNone() {
        PrintStream oldErr = System.err;
        ByteArrayOutputStream sink = new ByteArrayOutputStream();
        System.setErr(new PrintStream(sink, true));
        try {
            SimConfig c = SimConfig.parse(new String[]{"--rtk", "bogus"});
            assertThat(c.rtkMode).isEqualTo("none");
            assertThat(sink.toString()).contains("--rtk");
        } finally {
            System.setErr(oldErr);
        }
    }
}
