package io.aerofleet.cloud.scheduling;

import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link TaskRequest} 的 JSR-303 约束契约测试。
 * <p>
 * <b>为什么要有这个类</b>：入口 {@code SchedulingController.createTask} 有 {@code @Valid}，
 * 而这些约束是<b>唯一防线</b> —— {@code TaskAssignmentService} 里
 * {@code capabilityMatch()} 对未知 taskType 走 {@code default} 分支、
 * {@code priorityToProtocol()} 对超范围 priority 也只会落到"高/中/低"某一档，
 * <b>都不拒绝</b>。@Valid 一旦掉了，非法 taskType 与越界坐标会直接进入评审计算。
 */
@DisplayName("TaskRequest 的 JSR-303 约束契约")
class TaskRequestValidationTest {

    private static Validator validator;

    @BeforeAll
    static void setUpValidator() {
        validator = Validation.buildDefaultValidatorFactory().getValidator();
    }

    private TaskRequest valid() {
        return new TaskRequest("task-1", "SURVEY", 5, 22.59, 113.93, 50.0);
    }

    private static boolean hasViolationOn(Set<ConstraintViolation<TaskRequest>> v, String path) {
        return v.stream().anyMatch(c -> c.getPropertyPath().toString().endsWith(path));
    }

    @Test
    @DisplayName("taskId：非空白且长度 1~64")
    void taskIdConstraintsApply() {
        assertThat(hasViolationOn(validator.validate(req(r -> r.setTaskId(null))), "taskId")).isTrue();
        assertThat(hasViolationOn(validator.validate(req(r -> r.setTaskId(""))), "taskId")).isTrue();
        assertThat(hasViolationOn(validator.validate(req(r -> r.setTaskId("   "))), "taskId"))
                .as("全空白应违反 @NotBlank").isTrue();
        assertThat(hasViolationOn(validator.validate(req(r -> r.setTaskId("x".repeat(65)))), "taskId"))
                .as("65 个字符应违反 @Size(max = 64)").isTrue();
        assertThat(hasViolationOn(validator.validate(req(r -> r.setTaskId("x".repeat(64)))), "taskId"))
                .as("64 个字符正好等于上限，应放行").isFalse();
    }

    @Test
    @DisplayName("taskType：四个白名单值放行，其他全部拒绝")
    void taskTypeWhitelistApplies() {
        for (String ok : new String[]{"SURVEY", "SPRAY", "RELAY", "RESCUE"}) {
            assertThat(hasViolationOn(validator.validate(req(r -> r.setTaskType(ok))), "taskType"))
                    .as(ok + " 应放行").isFalse();
        }
        assertThat(hasViolationOn(validator.validate(req(r -> r.setTaskType(null))), "taskType")).isTrue();
        // @Pattern 是全串匹配，子串或拼错都不行
        assertThat(hasViolationOn(validator.validate(req(r -> r.setTaskType("SURVEYX"))), "taskType"))
                .as("SURVEYX 不在白名单").isTrue();
        assertThat(hasViolationOn(validator.validate(req(r -> r.setTaskType("survey"))), "taskType"))
                .as("小写 survey 不在白名单").isTrue();
        assertThat(hasViolationOn(validator.validate(req(r -> r.setTaskType(""))), "taskType")).isTrue();
    }

    @Test
    @DisplayName("priority：0~10，越界两侧都拒绝")
    void priorityBoundsApply() {
        assertThat(hasViolationOn(validator.validate(req(r -> r.setPriority(-1))), "priority")).isTrue();
        assertThat(hasViolationOn(validator.validate(req(r -> r.setPriority(11))), "priority")).isTrue();
        assertThat(hasViolationOn(validator.validate(req(r -> r.setPriority(0))), "priority")).isFalse();
        assertThat(hasViolationOn(validator.validate(req(r -> r.setPriority(10))), "priority")).isFalse();
    }

    @Test
    @DisplayName("targetLat/targetLon：地理范围越界被拒（TaskAssignmentService 不查）")
    void geoCoordinateBoundsApply() {
        assertThat(hasViolationOn(validator.validate(req(r -> r.setTargetLat(-90.0001))), "targetLat")).isTrue();
        assertThat(hasViolationOn(validator.validate(req(r -> r.setTargetLat(90.0001))), "targetLat")).isTrue();
        assertThat(hasViolationOn(validator.validate(req(r -> r.setTargetLat(90.0))), "targetLat")).isFalse();

        assertThat(hasViolationOn(validator.validate(req(r -> r.setTargetLon(-180.0001))), "targetLon")).isTrue();
        assertThat(hasViolationOn(validator.validate(req(r -> r.setTargetLon(180.0001))), "targetLon")).isTrue();
        assertThat(hasViolationOn(validator.validate(req(r -> r.setTargetLon(-180.0))), "targetLon")).isFalse();

        // targetAlt 无约束注解 —— 服务层也不查，负高度在这里是放行的
        assertThat(hasViolationOn(validator.validate(req(r -> r.setTargetAlt(-5000.0))), "targetAlt")).isFalse();
    }

    @Test
    @DisplayName("合法请求零违规")
    void validRequestHasNoViolations() {
        assertThat(validator.validate(valid())).isEmpty();
    }

    /** 对合法请求施加一处改动，其余字段保持合法。 */
    private TaskRequest req(java.util.function.Consumer<TaskRequest> mutate) {
        TaskRequest r = valid();
        mutate.accept(r);
        return r;
    }
}
