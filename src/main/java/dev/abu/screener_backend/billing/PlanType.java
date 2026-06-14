package dev.abu.screener_backend.billing;

/**
 * The two kinds of catalog plan.
 *
 * <ul>
 *   <li>{@code FIXED} — a pre-priced bundle of a fixed number of days (weekly/monthly/yearly);
 *       {@code duration_days} is non-null.</li>
 *   <li>{@code PER_DAY} — pay-by-days; the price row is the price of ONE day and
 *       {@code duration_days} is null. The number of days bought is computed at purchase time
 *       (payment plan), not stored on the plan.</li>
 * </ul>
 */
public enum PlanType {
    FIXED,
    PER_DAY
}
