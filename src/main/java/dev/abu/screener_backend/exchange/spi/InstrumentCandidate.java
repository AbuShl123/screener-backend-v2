package dev.abu.screener_backend.exchange.spi;

/**
 * One discovered instrument, as reported by an {@link InstrumentSource}.
 *
 * <p>Venue-less on purpose: core attaches the venue from the key of the map
 * {@link InstrumentSource#fetch()} returns, so a source cannot file a candidate under the wrong
 * venue. Deliberately minimal — {@code tickSize} / {@code stepSize} arrive with the first venue
 * that needs them.
 */
public record InstrumentCandidate(String nativeSymbol, String base, String quote) { }
