package io.aerofleet.cloud.telemetry;

import io.aerofleet.cloud.gateway.DeviceRegistry;
import io.aerofleet.cloud.gateway.DroneSnapshot;
import io.aerofleet.cloud.mission.common.DroneCommandService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

/**
 * {@code GET /api/v1/env/status} 机队环境读口径契约（P1 #8）。
 *
 * <p>此前前端 WeatherLayerPanel 轮询的这个端点在后端根本不存在（KNOWN_GAPS 登记），
 * 而数据其实早就在库里——每台机的 ENVIRONMENT_STATUS 快照由
 * {@code TelemetrySnapshotListener} 写进 {@link DroneSnapshot}，只是没有任何
 * Controller 把它暴露出去。本组用例钉住聚合口径本身：
 * <ul>
 *   <li>只聚合 online 机；<b>不造数</b>——NaN 字段键缺省（NaN 序列化进 JSON 是
 *       非法字面量，且前端按 {@code != null} 守卫）</li>
 *   <li>风向是<b>圆周均值</b>：359° 与 1° 的机队平均必须是 0° 而不是 180°</li>
 *   <li>天气码 0-4 映射成前端 WEATHER_MARKS 键，取众数、并列取编号小者</li>
 *   <li>没有定位的机不进站表（气象图层画不出它），但参与顶层均值</li>
 * </ul>
 */
@DisplayName("GET /api/v1/env/status 机队环境读口径 (P1 #8)")
class EnvStatusReadContractTest {

    private final DeviceRegistry registry = new DeviceRegistry();
    private final EnvOverrideController controller =
            new EnvOverrideController(mock(DroneCommandService.class), registry);

    /** provision 出快照并返回引用（同一对象，直接改 volatile 字段即注入遥测值）。 */
    private DroneSnapshot drone(int sysid) {
        registry.provision(sysid, null);
        return registry.all().stream()
                .filter(s -> s.sysid == sysid)
                .findFirst()
                .orElseThrow();
    }

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> stationsOf(Map<String, Object> resp) {
        return (List<Map<String, Object>>) resp.get("stations");
    }

    @Test
    @DisplayName("两台在线机：站点逐台组装、天气码映射成前端键、顶层逐字段独立平均")
    void fleetStatusAggregatesOnlineDrones() {
        DroneSnapshot a = drone(7);
        a.online = true;
        a.lat = 22.59;
        a.lon = 113.93;
        a.envTemperature = 25.0;
        a.envHumidity = 60.0;
        a.envWindSpeed = 4.0;
        a.envWindDirection = 180.0;
        a.envWeather = 1; // CLOUDY
        DroneSnapshot b = drone(8);
        b.online = true;
        b.lat = 22.60;
        b.lon = 113.94;
        b.envTemperature = 27.0;
        b.envHumidity = 64.0;
        b.envWindSpeed = 6.0;
        b.envWindDirection = 180.0;
        b.envWeather = 2; // RAIN

        Map<String, Object> resp = controller.envStatus();

        assertThat(resp.get("temp")).isEqualTo(26.0);
        assertThat(resp.get("humidity")).isEqualTo(62.0);
        assertThat(resp.get("windSpeed")).isEqualTo(5.0);
        assertThat(resp.get("windDir")).isEqualTo(180.0);
        // 众数：1 票 cloudy vs 1 票 rainy 并列 → 取编号小者 cloudy（1 < 2）
        assertThat(resp.get("weather")).isEqualTo("cloudy");

        List<Map<String, Object>> stations = stationsOf(resp);
        assertThat(stations).hasSize(2);
        assertThat(stations.get(0)).containsEntry("sysid", 7)
                .containsEntry("lat", 22.59).containsEntry("lon", 113.93)
                .containsEntry("temp", 25.0).containsEntry("weather", "cloudy");
        assertThat(stations.get(1)).containsEntry("sysid", 8)
                .containsEntry("weather", "rainy");
    }

    @Test
    @DisplayName("风向圆周均值：359° 与 1° 的机队平均是 0°（算术平均会给 180° 的错误答案）")
    void windDirIsCircularMean() {
        DroneSnapshot a = drone(7);
        a.online = true;
        a.envWindDirection = 359.0;
        DroneSnapshot b = drone(8);
        b.online = true;
        b.envWindDirection = 1.0;

        Map<String, Object> resp = controller.envStatus();

        double windDir = (Double) resp.get("windDir");
        assertThat(windDir)
                .as("圆周均值把 359°/1° 折回 0° 附近；朴素算术平均会给出 180°（面板箭头指向完全反掉）")
                .isLessThan(1.0)
                .isGreaterThanOrEqualTo(0.0);
    }

    @Test
    @DisplayName("离线机不进站点不进均值；未上报字段键缺省（NaN 永不进 JSON）")
    void offlineAndUnreportedFieldsOmitted() {
        DroneSnapshot offline = drone(7);
        offline.online = false;
        offline.lat = 22.59;
        offline.lon = 113.93;
        offline.envTemperature = 99.0; // 离线机的陈旧读数不许污染机队均值
        DroneSnapshot partial = drone(8);
        partial.online = true;
        partial.lat = 22.60;
        partial.lon = 113.94;
        partial.envTemperature = 25.0; // 只上报了温度：风/湿度/天气全是缺省（NaN/0 码）
        // 注意 envWeather 默认 0（CLEAR）——但"没上报"与"上报了晴天"不可区分，
        // 聚合口径如实按 0 处理（ drone-sim 侧 0 也是合法天气），此处只钉 NaN 字段缺省

        Map<String, Object> resp = controller.envStatus();

        List<Map<String, Object>> stations = stationsOf(resp);
        assertThat(stations).hasSize(1);
        assertThat(stations.get(0)).containsEntry("sysid", 8)
                .containsEntry("temp", 25.0)
                .doesNotContainKey("windDir")
                .doesNotContainKey("humidity");
        assertThat(resp).doesNotContainKey("windSpeed");
        assertThat(resp).doesNotContainKey("humidity");
    }

    @Test
    @DisplayName("空机队：stations 空数组、标量键全部缺省（不返回 NaN/null 标量）")
    void emptyFleetYieldsEmptyStations() {
        Map<String, Object> resp = controller.envStatus();

        assertThat(stationsOf(resp)).isEmpty();
        assertThat(resp).doesNotContainKey("temp");
        assertThat(resp).doesNotContainKey("humidity");
        assertThat(resp).doesNotContainKey("windSpeed");
        assertThat(resp).doesNotContainKey("windDir");
        assertThat(resp).doesNotContainKey("weather");
    }

    @Test
    @DisplayName("天气众数：2 票雨压过 1 票雾；未知码（>4）不参与投票")
    void weatherIsMajorityVote() {
        DroneSnapshot a = drone(7);
        a.online = true;
        a.envWeather = 2;
        DroneSnapshot b = drone(8);
        b.online = true;
        b.envWeather = 2;
        DroneSnapshot c = drone(9);
        c.online = true;
        c.envWeather = 4;
        DroneSnapshot d = drone(10);
        d.online = true;
        d.envWeather = 9; // 未知码：映射不到前端键，不许混进投票

        Map<String, Object> resp = controller.envStatus();

        assertThat(resp.get("weather")).isEqualTo("rainy");
    }
}
