package dev.abu.screener_backend.binance.orderbook;

import dev.abu.screener_backend.binance.websocket.Market;

/**
 * Immutable snapshot of one {@link OrderBook}'s cheap, O(1) observable state.
 *
 * <p>This is the <b>only</b> way anything outside the owning shard's Disruptor consumer thread is
 * allowed to look at an orderbook. The consumer constructs one of these on a slow cadence (see
 * {@link OrderBook#publishStats(long)}) and stores it into a {@code volatile} field; readers on
 * Tomcat / scheduler threads load that field. The single volatile store of an immutable record is a
 * release and the reader's load is the matching acquire, so a reader always sees <b>one coherent
 * instant</b> — never a mix of fields sampled at different times, and never a torn {@code long}.
 *
 * <p>Never copy the live {@code TreeMap}s across threads to obtain any of this; that is a data race.
 *
 * @param symbol            ticker symbol
 * @param market            SPOT or FUTURES
 * @param state             sync state at publish time
 * @param bids              bid level count at publish time
 * @param asks              ask level count at publish time
 * @param diffsApplied      cumulative successful live diffs applied since JVM start
 * @param resyncs           cumulative resyncs since JVM start
 * @param publishedAtMillis wall clock at publish; {@code 0} means "never published yet"
 */
public record BookStats(
        String symbol,
        Market market,
        OrderBookState state,
        int bids,
        int asks,
        long diffsApplied,
        int resyncs,
        long publishedAtMillis
) {

    /** Total level count — the project-wide definition of "orderbook size" (never per side). */
    public int size() {
        return bids + asks;
    }

    /** True until the owning consumer thread has published at least once. */
    public boolean neverPublished() {
        return publishedAtMillis == 0L;
    }
}
