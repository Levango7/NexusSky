package io.aerofleet.sim;

/**
 * Autopilot failsafe layer of the virtual drone - PX4-compatible reactions to
 * link loss, critical battery, and GPS loss. Runs on the tick thread inside
 * VirtualDrone.tickOnce(); every transition is announced once via STATUSTEXT so
 * the cloud/GCS reaction chain can be regression-tested end to end.
 *
 * Reactions (PX4 defaults):
 *  - datalink loss  : RTL after the configured grace period (NAV_DLLC_ACT)
 *  - battery crit   : RTL (BAT_CRIT_THR; warn threshold fires earlier)
 *  - GPS loss       : HOLD (hover in place, COM_GPSLOSS_ACT=Hold); mission
 *                     resumes from the same seq when the fix returns.
 *
 * Precedence when several triggers are active at once (same order PX4 uses):
 *  battery crit > datalink loss > GPS loss. A pilot's explicit command always
 *  overrides the current failsafe until the next trigger edge.
 */
final class FailsafeController {

    /** Heartbeat silence (ms) after which the datalink is considered lost. */
    private final long linkLossAfterMs;
    /** Battery % at which the critical-RTL fires (PX4 BAT_CRIT_THR default). */
    private final int batteryCritPct;
    /** Config: failsafe features can be disabled for A/B comparisons. */
    private final boolean enabled;

    // edge detectors so each failsafe announces exactly once per trigger
    private boolean linkFailActive = false;
    private boolean battFailActive = false;
    private boolean gpsFailActive = false;

    FailsafeController(boolean enabled, long linkLossAfterMs, int batteryCritPct) {
        this.enabled = enabled;
        this.linkLossAfterMs = linkLossAfterMs;
        this.batteryCritPct = batteryCritPct;
    }

    static FailsafeController defaults() {
        // link grace 15s > cloud's 10s offline timeout, so the drone reacts
        // slightly after the GCS has already flagged it offline - PX4-like.
        return new FailsafeController(true, 15_000, 22);
    }

    /** Verdict handed back to the tick loop. */
    enum Action {
        NONE,
        ENTER_RTL,
        ENTER_HOLD,
        RESUME_MISSION
    }

    /** True while the datalink failsafe has fired (announced once per edge). */
    boolean linkFailActive() {
        return linkFailActive;
    }

    /**
     * Evaluate all triggers once per tick.
     *
     * @param state       current flight state
     * @param lastGcsRxMs epoch ms of the last received GCS packet (0 = never)
     * @param nowMs       current epoch ms
     * @param batteryPct  current battery percentage
     * @param gpsLost     scenario GPS-loss flag
     * @return the action to apply this tick (NONE most of the time)
     */
    Action evaluate(FlightState state, long lastGcsRxMs, long nowMs,
                    int batteryPct, boolean gpsLost) {
        if (!enabled) {
            return Action.NONE;
        }
        boolean airborne = state.armed() && state != FlightState.STANDBY;

        // ---- battery critical: highest precedence ----
        boolean battNow = airborne && batteryPct <= batteryCritPct;
        if (battNow && !battFailActive) {
            battFailActive = true;
            if (state != FlightState.RTL) {
                return Action.ENTER_RTL;
            }
        } else if (!battNow) {
            battFailActive = false;
        }

        // ---- datalink loss: RTL after grace (only while armed) ----
        long silence = lastGcsRxMs > 0 ? nowMs - lastGcsRxMs : 0;
        boolean linkNow = airborne && silence > linkLossAfterMs;
        if (linkNow && !linkFailActive) {
            linkFailActive = true;
            if (state != FlightState.RTL) {
                return Action.ENTER_RTL;
            }
        } else if (!linkNow) {
            linkFailActive = false;
        }

        // ---- GPS loss: HOLD while in mission; recover resumes the mission ----
        boolean gpsNow = airborne && gpsLost;
        if (gpsNow && !gpsFailActive) {
            gpsFailActive = true;
            if (state == FlightState.MISSION || state == FlightState.ARMED) {
                return Action.ENTER_HOLD;
            }
        } else if (!gpsNow && gpsFailActive) {
            gpsFailActive = false;
            if (state == FlightState.HOLD) {
                return Action.RESUME_MISSION;
            }
        }

        return Action.NONE;
    }
}
