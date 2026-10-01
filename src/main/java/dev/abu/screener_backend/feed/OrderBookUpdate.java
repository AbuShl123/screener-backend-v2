package dev.abu.screener_backend.feed;

import dev.abu.screener_backend.exchange.Instrument;

/**
 * Single type for both snapshotMap values and pendingRef values.
 * Arrays use null sentinels beyond the actual level count — iterate until null.
 * Do NOT compare two OrderBookUpdate instances with equals(): record equals() on array
 * fields is reference equality, not deep equality.
 *
 * <p>Carries the whole {@link Instrument} so the broadcaster can read its precomputed
 * {@code ruleKey} (custom-rule filtering) and payload fields without re-deriving them.
 */
public record OrderBookUpdate(
        Instrument instrument,
        FeedEventType type,
        ClassifiedLevel[] bids,
        ClassifiedLevel[] asks
) {}
