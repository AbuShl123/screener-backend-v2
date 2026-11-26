package dev.abu.screener_backend.marketdata.spi;

import dev.abu.screener_backend.marketdata.core.slot.InstrumentSlot;

/**
 * Receives the candle updates a {@link KlineDecoder} finds in one frame. Implemented by core
 * ({@code core/spike/SpikeDetector}); adapters only call it.
 *
 * <p>Called on the instrument's shard consumer thread, so an implementation can keep
 * per-instrument state in the slot without locks.
 */
public interface KlineSink {

    /**
     * The current state of one instrument's candle. A venue pushes the same candle many times while
     * it forms; each push carries its full state so far, not a delta.
     *
     * @param slot        the instrument the candle belongs to
     * @param openTimeMs  candle open time, epoch millis, aligned to the interval in UTC: identifies
     *                    the candle
     * @param high        highest trade price in the candle so far
     * @param low         lowest trade price in the candle so far
     * @param close       last trade price in the candle so far: the current price while it forms
     * @param quoteVolume traded quote volume (USDT) in the candle so far
     * @param eventTimeMs venue event time of the push, epoch millis
     */
    void onKline(InstrumentSlot slot, long openTimeMs, double high, double low, double close,
                 double quoteVolume, long eventTimeMs);
}
