package io.aerofleet.cloud.api.dto;

import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link LoRaAlarmDto} 的 JSR-303 约束契约测试。
 * <p>
 * <b>为什么要有这个类</b>：入口 {@code LoRaRelayController.receiveLoRaAlarm} 有 {@code @Valid}，
 * 它是这几条约束的<b>唯一防线</b> —— {@code LoRaRelayService.receiveLoRaAlarm()} 只查
 * {@code dto == null}，{@code convertToAlarmEvent()} 只查 {@code alarmType == null}；
 * deviceId、经纬度、severity 全都靠注解拦。
 * <p>
 * 顺带记一笔：这个 DTO 的 lat/lon 用 {@code @DecimalMin/@DecimalMax}，其余四个 mission DTO
 * 用 {@code @Min/@Max}。两种写法在当前依赖（Hibernate Validator 8.0.3）下<b>都生效</b>，
 * 不存在"double 只能用 DecimalMin"这种限制 —— 这里是刻意验证过才这么写的。
 */
@DisplayName("LoRaAlarmDto 的 JSR-303 约束契约")
class LoRaAlarmDtoValidationTest {

    private static Validator validator;

    @BeforeAll
    static void setUpValidator() {
        validator = Validation.buildDefaultValidatorFactory().getValidator();
    }

    private LoRaAlarmDto valid() {
        return new LoRaAlarmDto(101, "FIRE", 39.9, 116.3, 1_700_000_000_000L, 3, 1, -70.0);
    }

    private static boolean hasViolationOn(Set<ConstraintViolation<LoRaAlarmDto>> v, String path) {
        return v.stream().anyMatch(c -> c.getPropertyPath().toString().endsWith(path));
    }

    private LoRaAlarmDto mutate(java.util.function.Consumer<LoRaAlarmDto> m) {
        LoRaAlarmDto d = valid();
        m.accept(d);
        return d;
    }

    @Test
    @DisplayName("deviceId 必须为正（LoRaRelayService 侧不查）")
    void deviceIdMustBePositive() {
        assertThat(hasViolationOn(validator.validate(mutate(d -> d.setDeviceId(0))), "deviceId")).isTrue();
        assertThat(hasViolationOn(validator.validate(mutate(d -> d.setDeviceId(-1))), "deviceId")).isTrue();
        assertThat(hasViolationOn(validator.validate(mutate(d -> d.setDeviceId(1))), "deviceId")).isFalse();
    }

    @Test
    @DisplayName("alarmType 非空白（服务层只查 null，不查空白）")
    void alarmTypeNotBlank() {
        assertThat(hasViolationOn(validator.validate(mutate(d -> d.setAlarmType(null))), "alarmType")).isTrue();
        assertThat(hasViolationOn(validator.validate(mutate(d -> d.setAlarmType("   "))), "alarmType"))
                .as("全空白也应被拦 —— convertToAlarmEvent 只查 null").isTrue();
        // 注意：@NotBlank 只管空白，不校验取值集合；未知类型由 service 的映射逻辑兜
        assertThat(hasViolationOn(validator.validate(mutate(d -> d.setAlarmType("WEIRD"))), "alarmType")).isFalse();
    }

    @Test
    @DisplayName("lat/lon 地理范围由 DecimalMin/DecimalMax 捕获")
    void latLonRangesUseDecimalBounds() {
        assertThat(hasViolationOn(validator.validate(mutate(d -> d.setLat(-90.0001))), "lat")).isTrue();
        assertThat(hasViolationOn(validator.validate(mutate(d -> d.setLat(90.0001))), "lat")).isTrue();
        assertThat(hasViolationOn(validator.validate(mutate(d -> d.setLat(-90.0))), "lat")).isFalse();

        assertThat(hasViolationOn(validator.validate(mutate(d -> d.setLon(-180.0001))), "lon")).isTrue();
        assertThat(hasViolationOn(validator.validate(mutate(d -> d.setLon(180.0001))), "lon")).isTrue();
        assertThat(hasViolationOn(validator.validate(mutate(d -> d.setLon(180.0))), "lon")).isFalse();
    }

    @Test
    @DisplayName("severity 1~5（severity=0 是缺省值，必须被拦下而不是当成最轻微）")
    void severityRangeApplies() {
        assertThat(hasViolationOn(validator.validate(mutate(d -> d.setSeverity(0))), "severity"))
                .as("severity=0 是 int 缺省值，不能当作合法输入").isTrue();
        assertThat(hasViolationOn(validator.validate(mutate(d -> d.setSeverity(6))), "severity")).isTrue();
        assertThat(hasViolationOn(validator.validate(mutate(d -> d.setSeverity(1))), "severity")).isFalse();
        assertThat(hasViolationOn(validator.validate(mutate(d -> d.setSeverity(5))), "severity")).isFalse();
    }

    @Test
    @DisplayName("合法请求零违规")
    void validRequestHasNoViolations() {
        assertThat(validator.validate(valid())).isEmpty();
    }
}
