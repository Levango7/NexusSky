package io.aerofleet.cloud.defect;

import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.Set;

/**
 * 工单状态机（spec §1，唯一裁决点）。
 * <pre>
 *              ┌──────────── REOPENED ────────────┐
 *              ▼                                  │（复检命中回 DISPATCHED）
 * OPEN → DISPATCHED → IN_PROGRESS → RESOLVED → VERIFIED
 *   └──────────────┴──────────────┴───→ CANCELLED（任意未终态可取消，须记原因）
 * </pre>
 * VERIFIED/CANCELLED 是终态（复检语义上 VERIFIED 后新证据应另立新单，不复活旧单——
 * 与"同一缺陷复检"的 10m 去重锚点配套）。
 */
@Component
public class WorkOrderStateMachine {

    public enum Transition {
        DISPATCH, START, RESOLVE, VERIFY_PASS, VERIFY_FAIL, CANCEL
    }

    private static final Map<String, Set<Transition>> ALLOWED = Map.of(
            "OPEN", Set.of(Transition.DISPATCH, Transition.CANCEL),
            "DISPATCHED", Set.of(Transition.START, Transition.CANCEL),
            "IN_PROGRESS", Set.of(Transition.RESOLVE, Transition.CANCEL),
            "RESOLVED", Set.of(Transition.VERIFY_PASS, Transition.VERIFY_FAIL, Transition.CANCEL),
            // REOPENED 语义 = 回到 DISPATCHED 待再处置
            "REOPENED", Set.of(Transition.START, Transition.CANCEL));

    private static final Map<Transition, String> TARGET = Map.of(
            Transition.DISPATCH, "DISPATCHED",
            Transition.START, "IN_PROGRESS",
            Transition.RESOLVE, "RESOLVED",
            Transition.VERIFY_PASS, "VERIFIED",
            // VERIFY_FAIL 落到 REOPENED（流程回起点等再处置；不直接回 DISPATCHED，
            // 让"复检失败"这一事实在状态里可见，处置时再推进）
            Transition.VERIFY_FAIL, "REOPENED",
            Transition.CANCEL, "CANCELLED");

    public String apply(String current, Transition t) {
        Set<Transition> allowed = ALLOWED.get(current);
        if (allowed == null || !allowed.contains(t)) {
            throw new IllegalWorkOrderTransitionException(current, t);
        }
        return TARGET.get(t);
    }

    public boolean isTerminal(String status) {
        return "VERIFIED".equals(status) || "CANCELLED".equals(status);
    }
}
