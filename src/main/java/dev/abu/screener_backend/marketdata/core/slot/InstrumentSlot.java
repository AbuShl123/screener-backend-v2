package dev.abu.screener_backend.marketdata.core.slot;

import dev.abu.screener_backend.config.SpikeAlertProperties;
import dev.abu.screener_backend.marketdata.Instrument;
import dev.abu.screener_backend.marketdata.core.book.OrderBook;
import dev.abu.screener_backend.marketdata.core.candle.CandleStore;
import dev.abu.screener_backend.marketdata.core.spike.SpikeState;
import dev.abu.screener_backend.marketdata.core.trade.TradeState;
import dev.abu.screener_backend.marketdata.spi.BookSyncContext;
import dev.abu.screener_backend.marketdata.spi.DepthSyncStrategy;
import dev.abu.screener_backend.marketdata.spi.KlineDecoder;
import dev.abu.screener_backend.marketdata.spi.TradeDecoder;

/**
 * The per-instrument runtime record, held in {@link InstrumentSlotTable} at index
 * {@link Instrument#id()}.
 *
 * <p>Every event needs the state to mutate <em>and</em> the instrument that identifies it (for the
 * classifier's feed key and for log lines). Holding them together means one array load and one
 * dereference instead of two independent lookups.
 *
 * <p>Slots are allocated at registration, not lazily on the first event.
 * {@code slots.get(id)} must be unconditionally present.
 *
 * <p>The slot carries the depth state (the {@code book} and its {@code syncStrategy} /
 * {@code syncCtx}); for venues that stream trades, the {@code tradeDecoder} and the {@code trade}
 * detection state; and for venues that stream klines, the {@code klineDecoder}, the
 * {@code candles} and the {@code spike} re-arm state. Each group is {@code null} for a venue
 * without that decoder.
 */
public record InstrumentSlot(
        Instrument instrument,
        OrderBook book,
        DepthSyncStrategy syncStrategy,
        BookSyncContext syncCtx,
        TradeDecoder tradeDecoder,
        TradeState trade,
        KlineDecoder klineDecoder,
        CandleStore candles,
        SpikeState spike) {

    /** A slot without a trade or kline decoder: a venue that streams depth only. */
    public InstrumentSlot(Instrument instrument, OrderBook book, DepthSyncStrategy syncStrategy,
                          BookSyncContext syncCtx) {
        this(instrument, book, syncStrategy, syncCtx, null, null, null, null, null);
    }

    /**
     * A slot for a venue that streams trades but no klines: a fresh {@link TradeState} goes with the
     * decoder, carrying the decoder's {@link TradeDecoder#reportsMakerFills} and
     * {@link TradeDecoder#closesSweepAtFrameEnd} so the detector reads them from the state.
     */
    public InstrumentSlot(Instrument instrument, OrderBook book, DepthSyncStrategy syncStrategy,
                          BookSyncContext syncCtx, TradeDecoder tradeDecoder) {
        this(instrument, book, syncStrategy, syncCtx, tradeDecoder, null, 0);
    }

    /**
     * A slot with every decoder its venue binds. A kline decoder gets a fresh {@link CandleStore}
     * of {@code candleRingSize} closed 1m candles and a fresh {@link SpikeState}.
     *
     * @param tradeDecoder   {@code null} if the venue does not stream trades
     * @param klineDecoder   {@code null} if the venue does not stream klines
     * @param candleRingSize closed candles kept per instrument; ignored without a kline decoder
     */
    public InstrumentSlot(Instrument instrument, OrderBook book, DepthSyncStrategy syncStrategy,
                          BookSyncContext syncCtx, TradeDecoder tradeDecoder, KlineDecoder klineDecoder,
                          int candleRingSize) {
        this(instrument, book, syncStrategy, syncCtx,
                tradeDecoder,
                tradeDecoder == null ? null
                        : new TradeState(tradeDecoder.reportsMakerFills(), tradeDecoder.closesSweepAtFrameEnd()),
                klineDecoder,
                klineDecoder == null ? null : new CandleStore(candleRingSize, SpikeAlertProperties.CANDLE_INTERVAL_MS),
                klineDecoder == null ? null : new SpikeState());
    }
}
