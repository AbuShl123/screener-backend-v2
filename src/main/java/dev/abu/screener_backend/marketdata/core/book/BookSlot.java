package dev.abu.screener_backend.marketdata.core.book;

import dev.abu.screener_backend.marketdata.Instrument;
import dev.abu.screener_backend.marketdata.spi.BookSyncContext;
import dev.abu.screener_backend.marketdata.spi.DepthSyncStrategy;

/**
 * The per-instrument runtime record, held in {@link BookSlotTable} at index
 * {@link Instrument#id()}.
 *
 * <p>Every depth event needs the book to mutate <em>and</em> the instrument that identifies it
 * (for the classifier's feed key and for log lines). Holding them together means one array load
 * and one dereference instead of two independent lookups.
 *
 * <p>Slots are allocated at registration, not lazily on the first diff.
 * {@code slots.get(id)} must be unconditionally present.
 *
 * <p>The sync context, the sync strategy and the reset flag join this record in later phases.
 */
public record BookSlot(Instrument instrument, OrderBook book, DepthSyncStrategy strategy, BookSyncContext ctx) { }
