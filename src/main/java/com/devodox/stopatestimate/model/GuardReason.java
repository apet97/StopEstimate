package com.devodox.stopatestimate.model;

/**
 * Why a guard event fired (or, for {@link #BELOW_CAPS} / {@link #NO_ACTIVE_CAPS}, why it did not).
 *
 * <p>Stays an {@code enum} rather than a sealed interface or sealed type with subclasses. Plan
 * item C3 considered sealing this for compile-time exhaustive-switch checking, but every consumer
 * either:
 * <ul>
 *     <li>passes the value as a constructor argument (e.g. {@code Assessment} record fields),</li>
 *     <li>compares against a constant ({@code reason == GuardReason.TIME_CAP_REACHED}) for the
 *     two-cap exceeded check in {@code EstimateGuardService}, or</li>
 *     <li>calls {@link #name()} for persistence to the {@code guard_events.guard_reason} column
 *     and the {@code ProjectGuardSummary.reason} payload.</li>
 * </ul>
 *
 * No call site does an exhaustive {@code switch (reason)} that would benefit from sealing.
 * Adding a sealed hierarchy plus per-variant subclass would cost ergonomics ({@code .name()}
 * stops being a one-liner; the JPA column mapper grows) without adding a single compile-time
 * guarantee. Re-evaluate if a future change introduces such a switch.
 */
public enum GuardReason {
    TIME_CAP_REACHED,
    BUDGET_CAP_REACHED,
    BELOW_CAPS,
    NO_ACTIVE_CAPS
}
