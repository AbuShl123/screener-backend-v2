package dev.abu.screener_backend.marketdata.spi;

import dev.abu.screener_backend.marketdata.core.slot.InstrumentSlot;

/**
 * Receives the executed trades a {@link TradeDecoder} finds in one frame. Implemented by core
 * ({@code core/trade/TradeDetector}); adapters only call it.
 *
 * <p>Called on the instrument's shard consumer thread, once per trade, in frame order. An
 * instrument's trades all reach one thread in order, so an implementation can keep per-instrument
 * state in the slot without locks.
 */
public interface TradeSink {

    /**
     * One executed trade.
     *
     * @param slot          the instrument the trade belongs to
     * @param price         execution price, {@code > 0}
     * @param qty           executed base quantity, {@code > 0}
     * @param aggressorBuy  {@code true} if the taker bought (lifted the ask), {@code false} if the taker sold
     * @param tradeTimeMs   venue trade time, epoch millis
     * @param firstTradeId  first venue trade id in this trade, or {@code -1} when the venue has no
     *                      numeric trade id (Bybit's are strings). Core must not depend on it.
     * @param lastTradeId   last venue trade id in this trade, or {@code -1}; equal to
     *                      {@code firstTradeId} for a single fill
     */
    void onTrade(InstrumentSlot slot, double price, double qty, boolean aggressorBuy,
                 long tradeTimeMs, long firstTradeId, long lastTradeId);
}
