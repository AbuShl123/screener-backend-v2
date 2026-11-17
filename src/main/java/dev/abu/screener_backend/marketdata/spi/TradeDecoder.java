package dev.abu.screener_backend.marketdata.spi;

import dev.abu.screener_backend.marketdata.core.slot.InstrumentSlot;

/**
 * Parses one venue's trade frames. The adapter side of a {@code TRADE} stream: it only parses, and
 * hands every trade to a core {@link TradeSink}, which owns detection.
 *
 * <p>Called on the instrument's shard consumer thread for every {@code TRADE} event, so it is hot
 * path: no allocation beyond the parser, primitives only. Implementations must be stateless, so
 * one instance can serve several venues (Binance spot and futures share one).
 *
 * <p>A frame may carry 0..n trades. Binance sends one per frame; Bybit and MEXC batch several.
 */
public interface TradeDecoder {

    /**
     * Decodes one frame and calls {@code sink.onTrade} once per trade in it.
     *
     * @return {@code false} if the frame could not be parsed. A single-trade decoder validates the
     *         whole frame first, so a failed frame reports nothing. A decoder for batched frames
     *         states in its Javadoc whether trades before the bad one were already reported.
     */
    boolean decode(InstrumentSlot slot, String rawJson, TradeSink sink);
}
