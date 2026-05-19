package dev.abu.screener_backend.binance.api;

/**
 * Tracks Binance API weight usage for one market (spot or futures) and calculates
 * how long to delay the next request when the budget is nearly exhausted.
 *
 * <p>Binance resets the weight counter at the wall-clock minute boundary, not on a
 * rolling 60-second window. The delay formula exploits this: once we know the server
 * send time we can compute the exact reset instant without relying on local clock skew.
 *
 * <p>Thread-safe via {@code volatile} — sufficient for a safety-net guard where an
 * occasional torn read under heavy concurrency is preferable to lock overhead.
 */
public class WeightGuard {

    private volatile long lastObservedWeight = 0;
    private volatile long lastObservedAtMs = 0;
    private final long threshold;

    public WeightGuard(long threshold) {
        this.threshold = threshold;
    }

    /**
     * Returns the number of milliseconds the caller should delay before sending
     * the next request, or {@code 0} if the request may proceed immediately.
     *
     * <p>Algorithm:
     * <ol>
     *   <li>If the wall-clock minute has already flipped since the last observation,
     *       Binance has reset the counter — no delay needed.</li>
     *   <li>If the last observed weight is below the threshold — no delay needed.</li>
     *   <li>Otherwise wait until the next minute boundary plus a 1-second safety buffer.</li>
     * </ol>
     */
    public long delayMillisRequired() {
        long now = System.currentTimeMillis();
        long nextMinuteBoundaryMs = (lastObservedAtMs / 60_000L + 1L) * 60_000L;
        if (now >= nextMinuteBoundaryMs) return 0L;
        if (lastObservedWeight < threshold) return 0L;
        return (nextMinuteBoundaryMs - now) + 1_000L;
    }

    /**
     * Records the weight value from a Binance response.
     *
     * <p>{@code sentTimeMs} must be the server-side send time from the HTTP {@code Date}
     * header — not {@code System.currentTimeMillis()} — so that minute boundary detection
     * is anchored to Binance's clock rather than local receive time.
     *
     * <p>Out-of-order responses are discarded when both conditions hold:
     * the response was sent earlier than the last recorded observation AND its weight
     * is lower. Within a single minute window Binance's counter is strictly non-decreasing,
     * so a response that is both older and lighter is genuinely stale and carries no new
     * information.
     */
    public void observe(long sentTimeMs, long weight) {
        if (sentTimeMs < lastObservedAtMs && weight < lastObservedWeight) {
            return;
        }
        lastObservedAtMs = sentTimeMs;
        lastObservedWeight = weight;
    }
}
