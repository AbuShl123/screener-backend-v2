package dev.abu.screener_backend.marketdata.core.slot;

import dev.abu.screener_backend.marketdata.Instrument;
import dev.abu.screener_backend.marketdata.core.book.OrderBook;
import dev.abu.screener_backend.marketdata.core.trade.TradeState;
import dev.abu.screener_backend.marketdata.spi.BookSyncContext;
import dev.abu.screener_backend.marketdata.spi.DepthSyncStrategy;
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
 * {@code syncCtx}) and, for venues that stream trades, the {@code tradeDecoder} and the
 * {@code trade} detection state; both are {@code null} for a venue without a decoder. State for
 * other stream kinds joins this record as those kinds arrive.
 */
public record InstrumentSlot(
        Instrument instrument,
        OrderBook book,
        DepthSyncStrategy syncStrategy,
        BookSyncContext syncCtx,
        TradeDecoder tradeDecoder,
        TradeState trade) {

    /** A slot without a trade decoder: a venue that streams depth only. */
    public InstrumentSlot(Instrument instrument, OrderBook book, DepthSyncStrategy syncStrategy,
                          BookSyncContext syncCtx) {
        this(instrument, book, syncStrategy, syncCtx, null, null);
    }

    /**
     * A slot for a venue that streams trades: a fresh {@link TradeState} goes with the decoder,
     * carrying the decoder's {@link TradeDecoder#reportsMakerFills} and
     * {@link TradeDecoder#closesSweepAtFrameEnd} so the detector reads them from the state.
     */
    public InstrumentSlot(Instrument instrument, OrderBook book, DepthSyncStrategy syncStrategy,
                          BookSyncContext syncCtx, TradeDecoder tradeDecoder) {
        this(instrument, book, syncStrategy, syncCtx, tradeDecoder, tradeDecoder == null ? null
                : new TradeState(tradeDecoder.reportsMakerFills(), tradeDecoder.closesSweepAtFrameEnd()));
    }
}
