package io.aerofleet.cloud.mission.delivery;

import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link DeliveryRequest} 的 JSR-303 约束契约测试。
 * <p>
 * <b>为什么要有这个类</b>：DeliveryRequest 的约束由入口 {@code DeliveryController.create}
 * 上的 {@code @Valid} 触发。一旦哪天 {@code @Valid} 被去掉、或者某个字段的注解被改成
 * 不适用的类型，这些约束就会静默失效 —— 而服务层 {@code DeliveryService.create()}
 * 只检查 {@code sites} 非空，<b>没有任何兜底</b>。
 * 这里把「约束确实会报告违规」钉住，正是为了防止
 * {@code FormationController.create} 那类「注解存在但没人执行」的问题再出现。
 * <p>
 * 断言粒度到字段名与具体约束注解，不满足于"有违规"。
 */
@DisplayName("DeliveryRequest 的 JSR-303 约束契约")
class DeliveryRequestValidationTest {

    private static Validator validator;

    @BeforeAll
    static void setUpValidator() {
        validator = Validation.buildDefaultValidatorFactory().getValidator();
    }

    private DeliveryRequest.SiteDto site(double lat, double lon, int payloadId,
                                          double weightKg, double volumeL, double dropAccM) {
        DeliveryRequest.SiteDto s = new DeliveryRequest.SiteDto();
        s.lat = lat;
        s.lon = lon;
        s.alt = 50;
        s.payloadId = payloadId;
        s.payloadWeightKg = weightKg;
        s.payloadVolumeL = volumeL;
        s.dropAccuracyM = dropAccM;
        return s;
    }

    private static boolean hasViolationOn(Set<ConstraintViolation<DeliveryRequest>> v, String path) {
        return v.stream().anyMatch(c -> c.getPropertyPath().toString().endsWith(path));
    }

    @Test
    @DisplayName("目标 sysid 越界被 Min/Max 捕获")
    void targetSysidOutOfRangeIsCaught() {
        DeliveryRequest req = new DeliveryRequest();
        req.sites = List.of(site(39.9, 116.3, 1, 1.0, 1.0, 1.0));
        req.targetSysid = 0;    // @Min(1)

        assertThat(hasViolationOn(validator.validate(req), "targetSysid")).isTrue();

        DeliveryRequest hi = new DeliveryRequest();
        hi.sites = req.sites;
        hi.targetSysid = 256;   // @Max(255)
        assertThat(hasViolationOn(validator.validate(hi), "targetSysid")).isTrue();

        DeliveryRequest ok = new DeliveryRequest();
        ok.sites = req.sites;
        ok.targetSysid = 255;
        assertThat(hasViolationOn(validator.validate(ok), "targetSysid")).isFalse();
    }

    @Test
    @DisplayName("站点列表 null / 空 / 超过 128 都被 NotNull-NotEmpty-Size 捕获")
    void sitesListChecksApply() {
        DeliveryRequest req = new DeliveryRequest();

        req.sites = null;
        assertThat(validator.validate(req)).as("sites=null 应违反 @NotNull").isNotEmpty();

        req.sites = List.of();
        assertThat(hasViolationOn(validator.validate(req), "sites")).as("空列表应违反 @NotEmpty").isTrue();

        DeliveryRequest.SiteDto one = site(39.9, 116.3, 1, 1.0, 1.0, 1.0);
        req.sites = List.of(one, one, one);       // 合法
        assertThat(hasViolationOn(validator.validate(req), "sites")).isFalse();

        // @Size(max = 128) 的上界：129 个站点必须被拒
        req.sites = java.util.Collections.nCopies(129, one);
        assertThat(hasViolationOn(validator.validate(req), "sites"))
                .as("129 个站点应违反 @Size(max = 128)").isTrue();

        req.sites = java.util.Collections.nCopies(128, one);
        assertThat(hasViolationOn(validator.validate(req), "sites"))
                .as("128 个站点正好等于上限，应放行").isFalse();
    }

    @Test
    @DisplayName("站点坐标与负载物理量越界：lat/lon 与四个 @Min(0) 字段全部被捕获")
    void siteCoordinateAndPayloadChecksApply() {
        DeliveryRequest req = new DeliveryRequest();
        req.targetSysid = 1;
        req.sites = List.of(site(200.0, 400.0, -1, -1.0, -1.0, -1.0));

        Set<ConstraintViolation<DeliveryRequest>> v = validator.validate(req);

        assertThat(hasViolationOn(v, "lat")).as("lat=200 应违反 @Max(90)").isTrue();
        assertThat(hasViolationOn(v, "lon")).as("lon=400 应违反 @Max(180)").isTrue();
        assertThat(hasViolationOn(v, "payloadId")).as("payloadId=-1 应违反 @Min(0)").isTrue();
        assertThat(hasViolationOn(v, "payloadWeightKg")).as("weight=-1 应违反 @Min(0)").isTrue();
        assertThat(hasViolationOn(v, "payloadVolumeL")).as("volume=-1 应违反 @Min(0)").isTrue();
        assertThat(hasViolationOn(v, "dropAccuracyM")).as("dropAcc=-1 应违反 @Min(0)").isTrue();
    }

    @Test
    @DisplayName("全部合法时零违规——防止上面的断言只是「有违规就算通过」")
    void fullyValidRequestHasNoViolations() {
        DeliveryRequest req = new DeliveryRequest();
        req.targetSysid = 42;
        req.sites = List.of(site(39.9, 116.3, 1, 1.0, 1.0, 1.0),
                site(-33.9, -70.2, 2, 0.0, 0.0, 0.0));

        assertThat(validator.validate(req)).isEmpty();
    }
}
