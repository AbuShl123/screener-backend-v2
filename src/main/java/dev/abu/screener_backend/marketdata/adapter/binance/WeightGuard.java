package dev.abu.screener_backend.marketdata.adapter.binance;

import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpHeaders;

/**
 * Binance request-weight and ban state for one venue. Owned by that venue's
 * {@link BinanceSnapshotFetcher} and shared with nothing else; it only records, it never delays.
 *
 * <p><b>The window.</b> Binance resets {@code x-mbx-used-weight-1m} at the server's wall-clock
 * minute, not on a rolling 60s window. The window counts as rolled once the <em>local time elapsed
 * since the response was received</em> covers what was left of its server minute according to its
 * {@code Date} header. Only durations are compared, so clock skew cancels out — comparing local
 * {@code now} against a server-minute boundary instead let a local clock running ahead send a full
 * batch into the old window (seen live: 2.3s of skew, a 429 one second into each minute). Both
 * error terms err on the safe side: {@code Date} is truncated to the second and receipt trails the
 * send by the network latency, so the roll is seen up to about a second late, never early.
 *
 * <p><b>Thread safety.</b> {@link #remaining} and {@link #isBanned} run on shard consumer threads
 * (through {@code isAcceptingRequests()}), so they are a volatile read each — no lock, no
 * allocation. Writers come from HTTP threads and are rare, so they synchronize; an observation is
 * one immutable record so a reader never pairs one response's weight with another's timestamp.
 */
@Slf4j
public class WeightGuard {

    static final String WEIGHT_HEADER = "x-mbx-used-weight-1m";

    /**
     * @param atMs         server send time, from {@code Date}
     * @param receivedAtMs local time the response was received
     */
    private record Observation(long atMs, long receivedAtMs, long weight) {

        boolean windowRolled(long nowMs) {
            return nowMs - receivedAtMs >= nextMinuteBoundary(atMs) - atMs;
        }
    }

    /** {@code null} until the first response with a weight header. */
    private volatile Observation last;
    private volatile long bannedUntilMs;

    /**
     * Records the weight counter from a Binance response, success or error. Headers without a
     * weight are ignored; an absent or unparseable {@code Date} falls back to local time.
     *
     * @param receivedAtMs local time the response was received
     */
    public void observe(HttpHeaders headers, long receivedAtMs) {
        String raw = headers == null ? null : headers.getFirst(WEIGHT_HEADER);
        if (raw == null) return;
        long weight;
        try {
            weight = Long.parseLong(raw.trim());
        } catch (NumberFormatException e) {
            log.warn("Unparseable {} header value: '{}'", WEIGHT_HEADER, raw);
            return;
        }
        observe(serverTimeMs(headers, receivedAtMs), receivedAtMs, weight);
    }

    /**
     * Within one server minute Binance's counter never decreases, so a lighter reading from the
     * same minute — or any reading from an earlier one — is a response that arrived out of order,
     * and is discarded. A batch's responses come back in any order; without this the last one to
     * land, not the heaviest, would set the budget.
     */
    synchronized void observe(long sentAtMs, long receivedAtMs, long weight) {
        Observation prev = last;
        if (prev != null) {
            long minute = sentAtMs / 60_000L;
            long prevMinute = prev.atMs / 60_000L;
            if (minute < prevMinute || (minute == prevMinute && weight < prev.weight)) return;
        }
        last = new Observation(sentAtMs, receivedAtMs, weight);
    }

    /**
     * @return {@code limit} minus the last observed weight while its server minute is current;
     *         the whole {@code limit} once that minute has rolled or before any observation. May be
     *         negative when usage overshot the limit.
     */
    public long remaining(long limit, long nowMs) {
        Observation obs = last;
        if (obs == null || obs.windowRolled(nowMs)) return limit;
        return limit - obs.weight;
    }

    /** Extends the ban to {@code untilMs} (local clock). Never shortens a ban already in force. */
    public synchronized void ban(long untilMs) {
        if (untilMs > bannedUntilMs) bannedUntilMs = untilMs;
    }

    public boolean isBanned(long nowMs) {
        return nowMs < bannedUntilMs;
    }

    /** Local-clock end of the current or last ban; 0 if never banned. For logging. */
    long bannedUntilMs() {
        return bannedUntilMs;
    }

    static long nextMinuteBoundary(long ms) {
        return (ms / 60_000L + 1L) * 60_000L;
    }

    /** The server's send time from the {@code Date} header, or {@code fallbackMs} if absent or malformed. */
    static long serverTimeMs(HttpHeaders headers, long fallbackMs) {
        try {
            long date = headers.getFirstDate(HttpHeaders.DATE);
            return date == -1 ? fallbackMs : date;
        } catch (IllegalArgumentException e) {
            return fallbackMs;
        }
    }
}
