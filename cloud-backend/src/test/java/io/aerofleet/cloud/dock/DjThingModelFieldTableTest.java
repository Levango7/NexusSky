package io.aerofleet.cloud.dock;

import java.util.List;
import java.util.Set;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import static io.aerofleet.cloud.dock.DjThingModelFieldTable.FieldStatus;

/**
 * DjThingModelFieldTable 单测（CI 无 Broker，纯元数据声明）。
 * <p>
 * 判据：帧层/应答层字段 VERIFIED；OSD/flight-task/wayline/livestream/media 子字段 UNVERIFIED；
 * 五大服务齐全；field() 精确查询与缺失字段抛异常。
 */
@DisplayName("DjThingModelFieldTable：DJI 物模型字段表元数据")
class DjThingModelFieldTableTest {

    private static final Set<String> EXPECTED_SERVICES = Set.of(
            "osd", "flight_task", "wayline", "livestream", "media");

    @Test
    @DisplayName("services() 返回五大服务，各自 service 名与描述非空")
    void servicesComplete() {
        List<DjThingModelFieldTable.DjThingModelService> services =
                DjThingModelFieldTable.services();
        assertThat(services).hasSize(5);
        assertThat(services).extracting(DjThingModelFieldTable.DjThingModelService::service)
                .containsExactlyInAnyOrderElementsOf(EXPECTED_SERVICES);
        services.forEach(s -> {
            assertThat(s.description()).isNotBlank();
            assertThat(s.fields()).isNotEmpty();
        });
    }

    @Test
    @DisplayName("service() 按名查找，未知服务抛异常")
    void serviceLookup() {
        assertThat(DjThingModelFieldTable.service("osd").service()).isEqualTo("osd");
        assertThat(DjThingModelFieldTable.service("media").service()).isEqualTo("media");
        assertThatThrownBy(() -> DjThingModelFieldTable.service("unknown"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("unknown service");
    }

    @Test
    @DisplayName("field() 精确查找字段，未知字段抛异常")
    void fieldLookup() {
        var osd = DjThingModelFieldTable.service("osd");
        assertThat(osd.field("latitude").name()).isEqualTo("latitude");
        assertThat(osd.field("batteryLevel").type()).isEqualTo("int");
        assertThat(osd.field("latitude").required()).isTrue();
        assertThatThrownBy(() -> osd.field("noSuchField"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("unknown field");
    }

    @Test
    @DisplayName("fieldMap() 按字段名建立索引")
    void fieldMap() {
        var wayline = DjThingModelFieldTable.service("wayline");
        assertThat(wayline.fieldMap()).containsKeys("waylineId", "action", "waypoints",
                "repeatTimes", "rtlOnLost", "geoFenceEnabled");
    }

    @Test
    @DisplayName("帧层 + 应答层字段均为 VERIFIED（仓内已实现并测试）")
    void frameAndReplyFieldsVerified() {
        assertThat(DjThingModelFieldTable.FRAME_FIELDS)
                .allMatch(f -> f.status() == DjThingModelFieldTable.FieldStatus.VERIFIED);
        assertThat(DjThingModelFieldTable.REPLY_FIELDS)
                .allMatch(f -> f.status() == DjThingModelFieldTable.FieldStatus.VERIFIED);
        assertThat(DjThingModelFieldTable.FRAME_FIELDS)
                .extracting(DjThingModelFieldTable.DjField::name)
                .containsExactlyInAnyOrder("tid", "bid", "timestamp", "method", "data");
        assertThat(DjThingModelFieldTable.REPLY_FIELDS)
                .extracting(DjThingModelFieldTable.DjField::name)
                .containsExactlyInAnyOrder("tid", "bid", "timestamp", "data.result",
                        "data.message");
        assertThat(DjThingModelFieldTable.verifiedFields())
                .hasSize(DjThingModelFieldTable.FRAME_FIELDS.size()
                        + DjThingModelFieldTable.REPLY_FIELDS.size());
    }

    @Test
    @DisplayName("OSD/flight-task/wayline/livestream/media 子字段按官方 schema 标记 VERIFIED/UNVERIFIED")
    void serviceFieldsVerifiedPerOfficialSchema() {
        // OSD: 官方 device-hms.md + device-manage.md 明确列出的字段 = VERIFIED
        var osd = DjThingModelFieldTable.service("osd");
        assertThat(osd.field("latitude").status()).isEqualTo(FieldStatus.VERIFIED);
        assertThat(osd.field("longitude").status()).isEqualTo(FieldStatus.VERIFIED);
        assertThat(osd.field("temperature").status()).isEqualTo(FieldStatus.VERIFIED);
        assertThat(osd.field("humidity").status()).isEqualTo(FieldStatus.VERIFIED);
        assertThat(osd.field("rainfall").status()).isEqualTo(FieldStatus.VERIFIED);
        assertThat(osd.field("totalFlightSorties").status()).isEqualTo(FieldStatus.VERIFIED);
        assertThat(osd.field("heightLimit").status()).isEqualTo(FieldStatus.VERIFIED);
        assertThat(osd.field("homeDistance").status()).isEqualTo(FieldStatus.VERIFIED);
        assertThat(osd.field("deviceSn").status()).isEqualTo(FieldStatus.VERIFIED);
        assertThat(osd.field("modelKey").status()).isEqualTo(FieldStatus.VERIFIED);
        assertThat(osd.field("callsign").status()).isEqualTo(FieldStatus.VERIFIED);
        assertThat(osd.field("firmwareVersion").status()).isEqualTo(FieldStatus.VERIFIED);
        assertThat(osd.field("isOnline").status()).isEqualTo(FieldStatus.VERIFIED);

        // flight_task: 官方 wayline-task-control.md 明确列出 = VERIFIED
        var ft = DjThingModelFieldTable.service("flight_task");
        assertThat(ft.field("taskId").status()).isEqualTo(FieldStatus.VERIFIED);
        assertThat(ft.field("action").status()).isEqualTo(FieldStatus.VERIFIED);
        assertThat(ft.field("targetLatitude").status()).isEqualTo(FieldStatus.VERIFIED);
        assertThat(ft.field("priority").status()).isEqualTo(FieldStatus.VERIFIED);

        // wayline: 官方 wayline-task-control.md 明确列出 = VERIFIED
        var wl = DjThingModelFieldTable.service("wayline");
        assertThat(wl.field("waylineId").status()).isEqualTo(FieldStatus.VERIFIED);
        assertThat(wl.field("uuid").status()).isEqualTo(FieldStatus.VERIFIED);
        assertThat(wl.field("status").status()).isEqualTo(FieldStatus.VERIFIED);
        assertThat(wl.field("currentWaypointIndex").status()).isEqualTo(FieldStatus.VERIFIED);

        // livestream: 官方 live-stream.md 明确列出 = VERIFIED
        var ls = DjThingModelFieldTable.service("livestream");
        assertThat(ls.field("streamId").status()).isEqualTo(FieldStatus.VERIFIED);
        assertThat(ls.field("deviceSn").status()).isEqualTo(FieldStatus.VERIFIED);
        assertThat(ls.field("videoQuality").status()).isEqualTo(FieldStatus.VERIFIED);

        // media: 官方 media-service.md 明确列出 = VERIFIED
        var media = DjThingModelFieldTable.service("media");
        assertThat(media.field("mediaId").status()).isEqualTo(FieldStatus.VERIFIED);
        assertThat(media.field("fileId").status()).isEqualTo(FieldStatus.VERIFIED);
        assertThat(media.field("bucket").status()).isEqualTo(FieldStatus.VERIFIED);
        assertThat(media.field("panoramaState").status()).isEqualTo(FieldStatus.VERIFIED);
    }

    @Test
    @DisplayName("osd 含 latitude/longitude/batteryLevel，wayline 含 waypoints")
    void sampleFieldsPresent() {
        var osd = DjThingModelFieldTable.service("osd");
        assertThat(osd.fieldMap()).containsKeys("latitude", "longitude", "batteryLevel",
                "rtkStatus", "homeLatitude", "homeLongitude");
        var wayline = DjThingModelFieldTable.service("wayline");
        assertThat(wayline.fieldMap()).containsKeys("waylineId", "waypoints", "rtlOnLost");
        var media = DjThingModelFieldTable.service("media");
        assertThat(media.fieldMap()).containsKeys("mediaId", "fileUrl", "mimeType", "capturedAt");
    }
}
