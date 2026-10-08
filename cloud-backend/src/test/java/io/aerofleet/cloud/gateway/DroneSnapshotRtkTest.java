package io.aerofleet.cloud.gateway;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/** F6 RTK：rtkStatus() 派生标签——MAVLink GPS_FIX_TYPE 口径（5=FLOAT/6=FIXED/3=3D）。 */
@DisplayName("DroneSnapshot.rtkStatus — GPS_FIX_TYPE 派生")
class DroneSnapshotRtkTest {

    @Test
    @DisplayName("fixType=6 → RTK_FIXED（RTK 固定解，cm 级）")
    void fixed() {
        DroneSnapshot s = new DroneSnapshot(9);
        s.fixType = 6;
        assertThat(s.rtkStatus()).isEqualTo("RTK_FIXED");
    }

    @Test
    @DisplayName("fixType=5 → RTK_FLOAT（RTK 浮点解）")
    void floatRtk() {
        DroneSnapshot s = new DroneSnapshot(9);
        s.fixType = 5;
        assertThat(s.rtkStatus()).isEqualTo("RTK_FLOAT");
    }

    @Test
    @DisplayName("fixType=3 → STANDALONE（单点定位，既有缺省）")
    void standalone() {
        DroneSnapshot s = new DroneSnapshot(9);
        s.fixType = 3;
        assertThat(s.rtkStatus()).isEqualTo("STANDALONE");
    }

    @Test
    @DisplayName("fixType<3（NO_FIX/2D）→ NONE")
    void none() {
        DroneSnapshot s = new DroneSnapshot(9);
        s.fixType = 0;
        assertThat(s.rtkStatus()).isEqualTo("NONE");
        s.fixType = 2;
        assertThat(s.rtkStatus()).isEqualTo("NONE");
    }
}
