package dev.abu.screener_backend.exchange.book;

import dev.abu.screener_backend.exchange.Instrument;

/**
 * The per-instrument runtime record, held in {@link BookSlotTable} at index
 * {@link Instrument#id()}.
 *
 * <p>Every depth event needs the book to mutate <em>and</em> the instrument that identifies it
 * (for the classifier's feed key and for log lines). Holding them together means one array load
 * and one dereference instead of two independent lookups.
 *
 * <p>Slots are allocated at registration, not lazily on first diff. That is what removes the
 * {@code SNAPSHOT}/{@code DIFF} branch, the {@code computeIfAbsent}, the key concatenation and the
 * null check from {@code OrderBookProcessor}: {@code slots.get(id)} is unconditionally present.
 *
 * <p>The sync context, the sync strategy and the reset flag join this record in later phases.
 */
public record BookSlot(Instrument instrument, OrderBook book) {}
