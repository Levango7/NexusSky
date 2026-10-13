package io.aerofleet.cloud.flightlog;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.sql.Types;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 飞行日志显式批量插入的**类型口径**守卫。
 *
 * <p><b>为什么钉这一条</b>（缺陷全文见 {@code V29__flight_log_timestamp_tz.sql} 与
 * {@code .coord/GAPS.md} §七）：{@code flight_log.timestamp} 过去是 {@code timestamp}
 * （无时区）列，而批量写路径用 {@code setObject(i, value)} **不带目标类型**绑定，
 * 值又是 {@code java.sql.Timestamp}——PG 驱动于是按 JVM 默认时区把绝对时刻摊成墙钟写进去。
 * 结果是 +08 部署上"当天的飞行历史"读到空、{@code day=明天} 才看得见，
 * 而 CI 的 runner 是 UTC，写读两侧恰好抵消 ⇒ 这条缺陷在 CI 上结构性不可见。
 *
 * <p><b>本类的定位要诚实</b>：H2（测试用的方言）那列本来就带时区，所以"写进去再读回来"
 * 在修复前后都成立——拿它当回归证据是个会自我表扬的假测试。这里只钉**能被静态判定的契约**
 * （列类型口径 + 绑定值类型），真正的端到端证据在 IT Pass C：
 * 真 PostgreSQL + 一条把时区钉成非 UTC 的腿（见 {@code .github/workflows/ci.yml} 与
 * {@code scripts/ci-integration-test.sh}）。
 */
@DisplayName("flight_log 批量写的时区口径（V29 配套守卫）")
class FlightLogTimestampTypeTest {

    /** 找到 `timestamp` 那一列在插入元数据里的下标（列名来自 @Column，重命名会转红——正是要的效果）。 */
    private static int timestampColumnIndex() {
        String[] names = FlightLogEntity.insertColumnNames();
        for (int i = 0; i < names.length; i++) {
            if ("timestamp".equals(names[i])) {
                return i;
            }
        }
        throw new AssertionError("插入列清单里找不到 timestamp 列；实际=" + String.join(",", names));
    }

    @Test
    @DisplayName("1. Instant 列必须按 TIMESTAMP_WITH_TIMEZONE 声明：写 Types.TIMESTAMP 就是本次缺陷的一半")
    void instantColumnIsDeclaredWithTimeZone() {
        int idx = timestampColumnIndex();
        int[] types = FlightLogEntity.insertSqlTypes();

        assertThat(types[idx])
                .as("timestamp 列的 java.sql.Types——必须是带时区的，否则驱动会挑一个时区来摊平它")
                .isEqualTo(Types.TIMESTAMP_WITH_TIMEZONE);
        // 其余非空列也应各有其类型（这条顺带钉住"类型数组与列清单同序"）
        assertThat(types).hasSize(FlightLogEntity.insertColumnNames().length);
    }

    @Test
    @DisplayName("2. 绑定值是 UTC 偏移的 OffsetDateTime：Instant 会被 pgjdbc 拒、Timestamp 会招它自己挑时区")
    void boundValueIsUtcOffset() throws IllegalAccessException {
        Instant moment = Instant.parse("2026-10-12T16:30:00Z");
        FlightLogEntity e = new FlightLogEntity();
        e.setTimestamp(moment);

        Object bound = FlightLogEntity.insertValues(e)[timestampColumnIndex()];

        assertThat(bound)
                .as("交给 setObject 的值——必须自带偏移，驱动才没有'按机器时区摊平'的空间")
                .isInstanceOf(java.time.OffsetDateTime.class)
                .isNotInstanceOf(java.sql.Timestamp.class);
        java.time.OffsetDateTime odt = (java.time.OffsetDateTime) bound;
        assertThat(odt.getOffset()).isEqualTo(java.time.ZoneOffset.UTC);
        assertThat(odt.toInstant()).isEqualTo(moment);
        // 这一条是"为什么不能用 Instant + TIMESTAMP_WITH_TIMEZONE"的留档：
        // pgjdbc 42.7.3 对那个组合直接抛"不能转换"，实测过（本机 +08 连真 PG）。
        assertThat(odt.toInstant().toString()).isEqualTo("2026-10-12T16:30:00Z");
    }

    @Test
    @DisplayName("3. 元数据反射取的是 @Column 名：列名被改动时这两条守卫要一起转红，不允许悄悄失配")
    void columnMetadataIsConsistent() {
        String[] names = FlightLogEntity.insertColumnNames();
        int[] types = FlightLogEntity.insertSqlTypes();
        assertThat(names).contains("timestamp");
        assertThat(types).hasSameSizeAs(names);
    }

    /** 反射拿字段类型，确保上面的断言测的是真映射而不是我造的第二份表。 */
    @Test
    @DisplayName("4. 实体字段确实是 java.time.Instant（否则上面的口径断言在守一个不存在的东西）")
    void entityFieldIsInstant() throws NoSuchFieldException {
        Field f = FlightLogEntity.class.getDeclaredField("timestamp");
        assertThat(f.getType()).isEqualTo(Instant.class);
    }
}
