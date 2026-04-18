package com.devodox.stopatestimate.model;

/**
 * Outcome recorded in {@code guard_events}. Written by {@code EstimateGuardService} at the four
 * decision points that produce user-visible side effects: a lock/unlock, a scheduled cutoff, or a
 * stopped timer.
 */
public enum GuardEventType {
    LOCKED,
    UNLOCKED,
    CUTOFF_SCHEDULED,
    TIMER_STOPPED
}
