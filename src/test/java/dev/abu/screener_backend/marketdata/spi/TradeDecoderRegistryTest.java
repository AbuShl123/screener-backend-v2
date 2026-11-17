package dev.abu.screener_backend.marketdata.spi;

import dev.abu.screener_backend.marketdata.Venue;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

class TradeDecoderRegistryTest {

    private static final TradeDecoder SPOT = (slot, rawJson, sink) -> true;
    private static final TradeDecoder FUTURES = (slot, rawJson, sink) -> true;

    @Test
    @DisplayName("each venue resolves to the decoder bound for it")
    void lookupByVenue() {
        TradeDecoderRegistry registry = new TradeDecoderRegistry(List.of(
                new VenueTradeDecoderBinding(Venue.BINANCE_SPOT, SPOT),
                new VenueTradeDecoderBinding(Venue.BINANCE_FUTURES, FUTURES)));

        assertSame(SPOT, registry.forVenue(Venue.BINANCE_SPOT));
        assertSame(FUTURES, registry.forVenue(Venue.BINANCE_FUTURES));
    }

    @Test
    @DisplayName("one decoder may serve several venues")
    void sharedDecoder() {
        TradeDecoderRegistry registry = new TradeDecoderRegistry(List.of(
                new VenueTradeDecoderBinding(Venue.BINANCE_SPOT, SPOT),
                new VenueTradeDecoderBinding(Venue.BINANCE_FUTURES, SPOT)));

        assertSame(registry.forVenue(Venue.BINANCE_SPOT), registry.forVenue(Venue.BINANCE_FUTURES));
    }

    @Test
    @DisplayName("a venue without a binding resolves to null: not every venue streams trades")
    void unknownVenueIsNull() {
        TradeDecoderRegistry registry = new TradeDecoderRegistry(List.of(
                new VenueTradeDecoderBinding(Venue.BINANCE_SPOT, SPOT)));

        assertNull(registry.forVenue(Venue.BYBIT_SPOT));
    }

    @Test
    @DisplayName("two bindings for one venue fail at construction")
    void duplicateVenueThrows() {
        assertThrows(IllegalStateException.class, () -> new TradeDecoderRegistry(List.of(
                new VenueTradeDecoderBinding(Venue.BINANCE_SPOT, SPOT),
                new VenueTradeDecoderBinding(Venue.BINANCE_SPOT, FUTURES))));
    }
}
