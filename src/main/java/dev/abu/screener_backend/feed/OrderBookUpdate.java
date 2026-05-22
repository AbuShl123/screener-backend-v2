package dev.abu.screener_backend.feed;

import dev.abu.screener_backend.binance.websocket.Market;

/**
 * Single type for both snapshotMap values and pendingRef values.
 * Arrays use null sentinels beyond the actual level count — iterate until null.
 * Do NOT compare two OrderBookUpdate instances with equals(): record equals() on array
 * fields is reference equality, not deep equality.
 */
public record OrderBookUpdate(
        String symbol,
        Market market,
        FeedEventType type,
        ClassifiedLevel[] bids,
        ClassifiedLevel[] asks
) {}
