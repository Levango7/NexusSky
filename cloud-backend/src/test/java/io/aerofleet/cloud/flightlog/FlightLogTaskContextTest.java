package io.aerofleet.cloud.flightlog;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("FlightLogTaskContext（飞行日志任务归因 + 去重序号）")
class FlightLogTaskContextTest {

    @Test
    @DisplayName("bind/unbind 维护 sysid → taskId，未登记返回 null")
    void bindUnbind() {
        FlightLogTaskContext ctx = new FlightLogTaskContext();
        assertThat(ctx.taskIdOf(7)).isNull();
        ctx.bind(7, "show-1200");
        assertThat(ctx.taskIdOf(7)).isEqualTo("show-1200");
        ctx.bind(7, "  ");
        assertThat(ctx.taskIdOf(7)).isNull();
        ctx.bind(7, "task-2");
        ctx.unbind(7);
        assertThat(ctx.taskIdOf(7)).isNull();

        ctx.bind(7, "reassign-me");
        ctx.bind(8, "reassign-me");
        ctx.unbindTask("reassign-me");
        assertThat(ctx.taskIdOf(7)).isNull();
        assertThat(ctx.taskIdOf(8)).isNull();
    }

    @Test
    @DisplayName("同一 sysid 的 seq 单调递增，且不同 sysid 互不干扰")
    void seqMonotonicPerSysid() {
        FlightLogTaskContext ctx = new FlightLogTaskContext();
        long a1 = ctx.nextSeq(1);
        long a2 = ctx.nextSeq(1);
        long b1 = ctx.nextSeq(2);
        assertThat(a2).isGreaterThan(a1);
        assertThat(b1).isGreaterThanOrEqualTo(a1);
        assertThat(ctx.nextSeq(1)).isGreaterThan(a2);
    }

    @Test
    @DisplayName("重建上下文（模拟重启）后 seq 不会退回旧值")
    void seqSurvivesRestart() throws Exception {
        FlightLogTaskContext old = new FlightLogTaskContext();
        long before = old.nextSeq(9);
        Thread.sleep(5);
        FlightLogTaskContext restarted = new FlightLogTaskContext();
        assertThat(restarted.nextSeq(9)).isGreaterThan(before);
    }
}
