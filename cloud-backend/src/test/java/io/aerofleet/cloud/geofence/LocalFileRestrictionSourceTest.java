package io.aerofleet.cloud.geofence;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * {@link LocalFileRestrictionSource} 本地文件限飞区数据源测试。
 * <p>
 * 测试覆盖：
 * <ul>
 *   <li>正常文件 → 解析成功</li>
 *   <li>文件不存在 → 返回空列表（不抛异常）</li>
 *   <li>格式错误 → 解析容错处理</li>
 * </ul>
 * <p>
 * 直接实例化（无 Spring 上下文），用 AssertJ 断言。
 */
@DisplayName("LocalFileRestrictionSource 本地文件限飞区数据源")
class LocalFileRestrictionSourceTest {

    @TempDir
    Path tempDir;

    /** 正常 JSON 文件内容：包含圆形和多边形限飞区。 */
    private static final String VALID_JSON = """
            [
              {
                "zoneId": "BJ-PEK-001",
                "name": "北京首都机场禁飞区",
                "type": "CIRCLE",
                "centerLat": 40.0801,
                "centerLon": 116.5846,
                "radiusM": 5000
              },
              {
                "zoneId": "SZ-CITY-001",
                "name": "深圳城区禁飞区",
                "type": "POLYGON",
                "points": [
                  {"lat": 22.54, "lon": 113.92},
                  {"lat": 22.54, "lon": 114.08},
                  {"lat": 22.48, "lon": 114.08},
                  {"lat": 22.48, "lon": 113.92}
                ]
              }
            ]
            """;

    // ------------------------------------------------------------------
    // 正常文件解析
    // ------------------------------------------------------------------

    @Test
    @DisplayName("testParseValidFile: 正常文件 → 解析成功")
    void testParseValidFile() throws Exception {
        Path jsonFile = tempDir.resolve("restrictions.json");
        Files.writeString(jsonFile, VALID_JSON);

        LocalFileRestrictionSource source = new LocalFileRestrictionSource();
        // 通过反射设置 filePath 字段（@Value 注入在测试中不可用）
        setFilePath(source, jsonFile.toString());

        List<RestrictionZone> zones = source.fetch();

        assertThat(zones).hasSize(2);

        // 验证圆形限飞区
        RestrictionZone circle = zones.stream()
                .filter(z -> "BJ-PEK-001".equals(z.getZoneId()))
                .findFirst()
                .orElse(null);
        assertThat(circle).isNotNull();
        assertThat(circle.getName()).isEqualTo("北京首都机场禁飞区");
        assertThat(circle.getType()).isEqualTo(GeofenceZone.Type.CIRCLE);
        assertThat(circle.getFenceType()).isEqualTo(FenceType.KEEP_OUT);
        assertThat(circle.getRadiusM()).isEqualTo(5000.0);

        // 验证多边形限飞区
        RestrictionZone polygon = zones.stream()
                .filter(z -> "SZ-CITY-001".equals(z.getZoneId()))
                .findFirst()
                .orElse(null);
        assertThat(polygon).isNotNull();
        assertThat(polygon.getName()).isEqualTo("深圳城区禁飞区");
        assertThat(polygon.getType()).isEqualTo(GeofenceZone.Type.POLYGON);
        assertThat(polygon.getPoints()).hasSize(4);
        assertThat(polygon.getFenceType()).isEqualTo(FenceType.KEEP_OUT);
    }

    // ------------------------------------------------------------------
    // 文件不存在
    // ------------------------------------------------------------------

    @Test
    @DisplayName("testFileNotFound: 文件不存在 → 返回空列表")
    void testFileNotFound() throws Exception {
        Path nonExistent = tempDir.resolve("nonexistent.json");

        LocalFileRestrictionSource source = new LocalFileRestrictionSource();
        setFilePath(source, nonExistent.toString());

        List<RestrictionZone> zones = source.fetch();

        // 文件不存在时返回空列表，不抛异常
        assertThat(zones).isEmpty();
    }

    // ------------------------------------------------------------------
    // 格式错误
    // ------------------------------------------------------------------

    @Test
    @DisplayName("testMalformedJson: 格式错误的 JSON → 容错处理（跳过无法解析的对象）")
    void testMalformedJson() throws Exception {
        // 包含一个有效对象和一个无效对象（缺少必要字段）
        String malformedJson = """
                [
                  {
                    "zoneId": "VALID-001",
                    "name": "有效限飞区",
                    "type": "CIRCLE",
                    "centerLat": 40.0,
                    "centerLon": 116.0,
                    "radiusM": 3000
                  },
                  {
                    "zoneId": "INVALID-001",
                    "name": "缺少 type 字段"
                  }
                ]
                """;

        Path jsonFile = tempDir.resolve("malformed.json");
        Files.writeString(jsonFile, malformedJson);

        LocalFileRestrictionSource source = new LocalFileRestrictionSource();
        setFilePath(source, jsonFile.toString());

        List<RestrictionZone> zones = source.fetch();

        // 应跳过无效对象，只解析有效对象
        assertThat(zones).hasSize(1);
        assertThat(zones.get(0).getZoneId()).isEqualTo("VALID-001");
    }

    @Test
    @DisplayName("testEmptyFile: 空文件 → 返回空列表")
    void testEmptyFile() throws Exception {
        Path jsonFile = tempDir.resolve("empty.json");
        Files.writeString(jsonFile, "");

        LocalFileRestrictionSource source = new LocalFileRestrictionSource();
        setFilePath(source, jsonFile.toString());

        List<RestrictionZone> zones = source.fetch();

        assertThat(zones).isEmpty();
    }

    @Test
    @DisplayName("testBlankFile: 仅空白字符的文件 → 返回空列表")
    void testBlankFile() throws Exception {
        Path jsonFile = tempDir.resolve("blank.json");
        Files.writeString(jsonFile, "   \n\t  \n  ");

        LocalFileRestrictionSource source = new LocalFileRestrictionSource();
        setFilePath(source, jsonFile.toString());

        List<RestrictionZone> zones = source.fetch();

        assertThat(zones).isEmpty();
    }

    @Test
    @DisplayName("testInvalidTypeValue: 无效的 type 枚举值 → 抛 IllegalArgumentException")
    void testInvalidTypeValue() throws Exception {
        String invalidTypeJson = """
                [
                  {
                    "zoneId": "BAD-TYPE-001",
                    "name": "无效类型",
                    "type": "HEXAGON",
                    "centerLat": 40.0,
                    "centerLon": 116.0,
                    "radiusM": 3000
                  }
                ]
                """;

        Path jsonFile = tempDir.resolve("bad-type.json");
        Files.writeString(jsonFile, invalidTypeJson);

        LocalFileRestrictionSource source = new LocalFileRestrictionSource();
        setFilePath(source, jsonFile.toString());

        // 无效 type 值导致 valueOf 抛 IllegalArgumentException，被 parseZoneObject catch 后跳过
        // 因此应返回空列表（容错处理）
        List<RestrictionZone> zones = source.fetch();
        assertThat(zones).isEmpty();
    }

    // ------------------------------------------------------------------
    // 辅助方法
    // ------------------------------------------------------------------

    /** 通过反射设置 LocalFileRestrictionSource 的 filePath 字段。 */
    private void setFilePath(LocalFileRestrictionSource source, String path) throws Exception {
        var field = LocalFileRestrictionSource.class.getDeclaredField("filePath");
        field.setAccessible(true);
        field.set(source, path);
    }
}