package dev.abu.screener_backend.marketdata.spi;

import dev.abu.screener_backend.marketdata.core.slot.InstrumentSlot;

import java.nio.ByteBuffer;

/**
 * Parses one venue's trade frames. The adapter side of a {@code TRADE} stream: it only parses, and
 * hands every trade to a core {@link TradeSink}, which owns detection.
 *
 * <p>Called on the instrument's shard consumer thread for every {@code TRADE} event, so it is hot
 * path: no allocation beyond the parser, primitives only. Implementations must be stateless, so
 * one instance can serve several venues (Binance spot and futures share one).
 *
 * <p>A frame may carry 0..n trades. Binance sends one per frame; Bybit and MEXC batch several. A
 * decoder reports them oldest first, whatever order the frame lists them in.
 *
 * <p>The two flags describe what one reported trade is and how a venue's fills arrive. Core reads
 * them once, when it allocates the instrument's slot, and its sweep coalescing follows them.
 */
public interface TradeDecoder {

    /**
     * Decodes one text frame and calls {@code sink.onTrade} once per trade in it, or
     * {@code sink.onFiltered} once per trade the venue's policy drops.
     *
     * @return {@code false} if the frame could not be parsed. A single-trade decoder validates the
     *         whole frame first, so a failed frame reports nothing. A decoder for batched frames
     *         states in its Javadoc whether trades before the bad one were already reported.
     */
    boolean decode(InstrumentSlot slot, String rawJson, TradeSink sink);

    /**
     * Decodes one binary frame, as {@link #decode(InstrumentSlot, String, TradeSink)} does a text
     * one. The default refuses it: only a venue whose trade stream is binary overrides this.
     *
     * <p>The buffer is the one the transport routed, without a copy. Read it with absolute indexes
     * between {@code position()} and {@code limit()}, and never move either.
     */
    default boolean decode(InstrumentSlot slot, ByteBuffer frame, TradeSink sink) {
        return false;
    }

    /**
     * {@code true} when one reported trade is one fill against one resting order (MEXC, Bybit), so
     * one taker order can fill several resting orders at the same price. A sweep may then continue
     * at an equal price, not only at one further in the aggressor's direction. {@code false} when one
     * trade already aggregates a taker order's fills at one price (Binance {@code aggTrade}).
     */
    default boolean reportsMakerFills() {
        return false;
    }

    /**
     * {@code true} when one taker order's fills always arrive in one frame (MEXC spot's 100 ms
     * batches), so the sweep still open after a frame is complete and can be judged there instead of
     * waiting for the instrument's next trade.
     */
    default boolean closesSweepAtFrameEnd() {
        return false;
    }
}
