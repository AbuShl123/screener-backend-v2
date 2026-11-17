package dev.abu.screener_backend.marketdata.core.slot;

import dev.abu.screener_backend.marketdata.Instrument;
import dev.abu.screener_backend.marketdata.core.book.OrderBook;
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
 * {@code syncCtx}) and, for venues that stream trades, the {@code tradeDecoder}; it is
 * {@code null} for a venue without one. State for other stream kinds joins this record as those
 * kinds arrive.
 */
public record InstrumentSlot(
        Instrument instrument,
        OrderBook book,
        DepthSyncStrategy syncStrategy,
        BookSyncContext syncCtx,
        TradeDecoder tradeDecoder) {

    /** A slot without a trade decoder: a venue that streams depth only. */
    public InstrumentSlot(Instrument instrument, OrderBook book, DepthSyncStrategy syncStrategy,
                          BookSyncContext syncCtx) {
        this(instrument, book, syncStrategy, syncCtx, null);
    }
}
