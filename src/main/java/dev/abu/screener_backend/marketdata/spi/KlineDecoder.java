package dev.abu.screener_backend.marketdata.spi;

import dev.abu.screener_backend.marketdata.core.slot.InstrumentSlot;

/**
 * Parses one venue's candlestick frames. The adapter side of a {@code KLINE} stream: it only
 * parses, and hands each candle update to a core {@link KlineSink}, which owns the candle state
 * and detection.
 *
 * <p>Called on the instrument's shard consumer thread for every {@code KLINE} event, so it is hot
 * path: no allocation beyond the parser, primitives only. Implementations must be stateless.
 */
public interface KlineDecoder {

    /**
     * Decodes one text frame and calls {@code sink.onKline} for the candle in it.
     *
     * @return {@code false} if the frame could not be parsed; nothing is reported then
     */
    boolean decode(InstrumentSlot slot, String rawJson, KlineSink sink);
}
