package io.aerofleet.sim.weather;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

@DisplayName("天气模型测试：湍流/微下击暴流/空气密度")
class TurbulenceModelTest {

    @Test
    @DisplayName("湍流强度等级：light=0.5, moderate=1.5, severe=3.0, extreme=5.0")
    void testSeverityLevels() {
        assertThat(new TurbulenceModel("light").turbulenceIntensity).isCloseTo(0.5, within(0.001));
        assertThat(new TurbulenceModel("moderate").turbulenceIntensity).isCloseTo(1.5, within(0.001));
        assertThat(new TurbulenceModel("severe").turbulenceIntensity).isCloseTo(3.0, within(0.001));
        assertThat(new TurbulenceModel("extreme").turbulenceIntensity).isCloseTo(5.0, within(0.001));
    }

    @Test
    @DisplayName("湍流强度等级：未知severity应回退为moderate=1.0")
    void testUnknownSeverityFallback() {
        TurbulenceModel model = new TurbulenceModel("unknown");
        assertThat(model.turbulenceIntensity).isCloseTo(1.0, within(0.001));
        assertThat(model.severity).isEqualTo("moderate");
    }

    @Test
    @DisplayName("三轴湍流输出：应返回3个分量且在合理范围内")
    void testTurbulenceOutputRange() {
        TurbulenceModel model = new TurbulenceModel("moderate");
        for (int i = 0; i < 100; i++) {
            double[] t = model.getTurbulence(500, 30, 0.01);
            assertThat(t).hasSize(3);
            assertThat(Math.abs(t[0])).isLessThan(20);
            assertThat(Math.abs(t[1])).isLessThan(20);
            assertThat(Math.abs(t[2])).isLessThan(20);
        }
    }

    @Test
    @DisplayName("湍流输出：零空速应返回全零")
    void testTurbulenceZeroAirspeed() {
        TurbulenceModel model = new TurbulenceModel("severe");
        double[] t = model.getTurbulence(500, 0, 0.01);
        assertThat(t[0]).isCloseTo(0, within(0.001));
        assertThat(t[1]).isCloseTo(0, within(0.001));
        assertThat(t[2]).isCloseTo(0, within(0.001));
    }

    @Test
    @DisplayName("湍流输出：extreme等级平均幅度应大于light等级")
    void testTurbulenceExtremeGreaterThanLight() {
        TurbulenceModel light = new TurbulenceModel("light");
        TurbulenceModel extreme = new TurbulenceModel("extreme");
        double lightSum = 0;
        double extremeSum = 0;
        for (int i = 0; i < 200; i++) {
            double[] lt = light.getTurbulence(500, 30, 0.01);
            double[] et = extreme.getTurbulence(500, 30, 0.01);
            lightSum += Math.abs(lt[0]) + Math.abs(lt[1]) + Math.abs(lt[2]);
            extremeSum += Math.abs(et[0]) + Math.abs(et[1]) + Math.abs(et[2]);
        }
        assertThat(extremeSum).isGreaterThan(lightSum);
    }

    @Test
    @DisplayName("微下击暴流风场：应返回3分量且径向方向有水平风")
    void testMicroburstWindField() {
        MicroburstModel mb = new MicroburstModel(0, 0, 0.05, 20, 60);
        double[] wind = mb.getWindField(0.01, 0.01, 100);
        assertThat(wind).hasSize(3);
        assertThat(Math.abs(wind[0]) + Math.abs(wind[1])).isGreaterThan(0);
    }

    @Test
    @DisplayName("微下击暴流风场：超出半径应返回全零")
    void testMicroburstWindFieldOutsideRadius() {
        MicroburstModel mb = new MicroburstModel(0, 0, 0.05, 20, 60);
        double[] wind = mb.getWindField(0.1, 0.1, 100);
        assertThat(wind[0]).isCloseTo(0, within(0.001));
        assertThat(wind[1]).isCloseTo(0, within(0.001));
        assertThat(wind[2]).isCloseTo(0, within(0.001));
    }

    @Test
    @DisplayName("微下击暴流下沉气流：中心应大于0，边缘应接近0")
    void testMicroburstDowndraft() {
        MicroburstModel mb = new MicroburstModel(0, 0, 0.05, 20, 60);
        double ddCenter = mb.getDowndraft(0, 0);
        assertThat(ddCenter).isGreaterThan(0);
        double ddEdge = mb.getDowndraft(0.06, 0);
        assertThat(ddEdge).isCloseTo(0, within(0.001));
    }

    @Test
    @DisplayName("微下击暴流风切变：半径内应返回非负值")
    void testMicroburstWindShear() {
        MicroburstModel mb = new MicroburstModel(0, 0, 0.05, 20, 60);
        double shear = mb.getWindShear(0.025, 0);
        assertThat(shear).isGreaterThanOrEqualTo(0);
    }

    @Test
    @DisplayName("微下击暴流tick：elapsed应随dt累加")
    void testMicroburstTick() {
        MicroburstModel mb = new MicroburstModel(0, 0, 0.05, 20, 60);
        assertThat(mb.elapsed).isCloseTo(0, within(0.001));
        mb.tick(1.0);
        assertThat(mb.elapsed).isCloseTo(1.0, within(0.001));
        mb.tick(2.0);
        assertThat(mb.elapsed).isCloseTo(3.0, within(0.001));
    }

    @Test
    @DisplayName("微下击暴流强度因子：生命周期中期应最强")
    void testMicroburstIntensityLifecycle() {
        MicroburstModel mb = new MicroburstModel(0, 0, 0.05, 20, 60);
        double ddEarly = mb.getDowndraft(0, 0);
        mb.tick(30);
        double ddMid = mb.getDowndraft(0, 0);
        mb.tick(28);
        double ddLate = mb.getDowndraft(0, 0);
        assertThat(ddMid).isGreaterThanOrEqualTo(ddEarly);
        assertThat(ddMid).isGreaterThanOrEqualTo(ddLate);
    }

    @Test
    @DisplayName("空气密度：海平面温度应为288.15K")
    void testAirDensityAtSeaLevel() {
        AirDensityModel model = new AirDensityModel();
        assertThat(model.getTemperature(0)).isCloseTo(288.15, within(0.001));
        assertThat(model.getPressure(0)).isCloseTo(101325.0, within(1.0));
        assertThat(model.getDensity(0)).isCloseTo(1.225, within(0.01));
    }

    @Test
    @DisplayName("空气密度：随高度递减")
    void testAirDensityDecreasesWithAltitude() {
        AirDensityModel model = new AirDensityModel();
        double density0 = model.getDensity(0);
        double density1000 = model.getDensity(1000);
        double density5000 = model.getDensity(5000);
        assertThat(density0).isGreaterThan(density1000);
        assertThat(density1000).isGreaterThan(density5000);
    }

    @Test
    @DisplayName("推力衰减系数：海平面接近1.0，高空递减")
    void testThrustFactorDecreasesWithAltitude() {
        AirDensityModel model = new AirDensityModel();
        double tf0 = model.getThrustFactor(0);
        double tf5000 = model.getThrustFactor(5000);
        assertThat(tf0).isCloseTo(1.0, within(0.05));
        assertThat(tf5000).isLessThan(tf0);
    }

    @Test
    @DisplayName("旋翼效率系数：海平面接近1.0，高空递减")
    void testRotorEfficiencyDecreasesWithAltitude() {
        AirDensityModel model = new AirDensityModel();
        double re0 = model.getRotorEfficiency(0);
        double re5000 = model.getRotorEfficiency(5000);
        assertThat(re0).isCloseTo(1.0, within(0.05));
        assertThat(re5000).isLessThan(re0);
    }

    @Test
    @DisplayName("ISA温度：对流层内随高度线性递减")
    void testTemperatureLapseRate() {
        AirDensityModel model = new AirDensityModel();
        double t0 = model.getTemperature(0);
        double t1000 = model.getTemperature(1000);
        assertThat(t0 - t1000).isCloseTo(6.5, within(0.1));
    }

    @Test
    @DisplayName("ISA温度：对流层顶以上温度恒定")
    void testTemperatureAboveTropopause() {
        AirDensityModel model = new AirDensityModel();
        double t11km = model.getTemperature(11000);
        double t15km = model.getTemperature(15000);
        assertThat(t11km).isCloseTo(t15km, within(0.001));
        assertThat(t11km).isCloseTo(216.65, within(0.01));
    }
}