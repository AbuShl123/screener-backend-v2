package dev.abu.screener_backend.marketdata.spi;

import dev.abu.screener_backend.config.ExchangesProperties;
import dev.abu.screener_backend.config.ExchangesProperties.ExchangeProperties;
import dev.abu.screener_backend.config.ExchangesProperties.VenueProperties;
import dev.abu.screener_backend.config.ExchangesProperties.VenueProperties.RestProperties;
import dev.abu.screener_backend.marketdata.Exchange;
import dev.abu.screener_backend.marketdata.Instrument;
import dev.abu.screener_backend.marketdata.Market;
import dev.abu.screener_backend.marketdata.Venue;
import dev.abu.screener_backend.marketdata.core.stream.SubscriptionIndex;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

class StreamProtocolRegistryTest {

    private static final class StubProtocol implements StreamProtocol {
        @Override public String subscribeFrame(List<Instrument> chunk, int requestId) { return ""; }
        @Override public String routingKey(Instrument instrument) { return instrument.nativeSymbol(); }
        @Override public int route(String frame, SubscriptionIndex index) { return IGNORED; }
        @Override public Heartbeat heartbeat() { return new Heartbeat.ProtocolPing(Duration.ofSeconds(1)); }
    }

    private static final RestProperties REST =
            new RestProperties("https://x", 1, Duration.ofSeconds(5), Duration.ofSeconds(10));

    private static ExchangesProperties enabled(Market... markets) {
        Map<Market, VenueProperties> venues = new EnumMap<>(Market.class);
        for (Market m : markets) {
            venues.put(m, new VenueProperties("wss://x", REST, 1024, 1, 1, 400, 120, null));
        }
        return new ExchangesProperties(Map.of(Exchange.BINANCE, new ExchangeProperties(true, venues, null)));
    }

    @Test
    @DisplayName("resolves each bound venue to its protocol")
    void resolves() {
        StreamProtocol spot = new StubProtocol();
        StreamProtocol futures = new StubProtocol();
        StreamProtocolRegistry registry = new StreamProtocolRegistry(List.of(
                new VenueStreamBinding(Venue.BINANCE_SPOT, spot),
                new VenueStreamBinding(Venue.BINANCE_FUTURES, futures)), enabled(Market.SPOT, Market.FUTURES));

        assertSame(spot, registry.forVenue(Venue.BINANCE_SPOT));
        assertSame(futures, registry.forVenue(Venue.BINANCE_FUTURES));
    }

    @Test
    @DisplayName("two bindings for one venue fail at construction — the stray-@Component guard")
    void duplicateThrows() {
        assertThrows(IllegalStateException.class, () -> new StreamProtocolRegistry(List.of(
                new VenueStreamBinding(Venue.BINANCE_SPOT, new StubProtocol()),
                new VenueStreamBinding(Venue.BINANCE_SPOT, new StubProtocol())), enabled(Market.SPOT)));
    }

    @Test
    @DisplayName("an enabled venue without a binding fails the boot")
    void enabledWithoutBindingThrows() {
        assertThrows(IllegalStateException.class, () -> new StreamProtocolRegistry(List.of(
                new VenueStreamBinding(Venue.BINANCE_SPOT, new StubProtocol())), enabled(Market.SPOT, Market.FUTURES)));
    }

    @Test
    @DisplayName("a disabled venue without a binding is fine, but asking for it throws")
    void disabledWithoutBindingIsFine() {
        StreamProtocolRegistry registry = assertDoesNotThrow(() -> new StreamProtocolRegistry(List.of(
                new VenueStreamBinding(Venue.BINANCE_SPOT, new StubProtocol())), enabled(Market.SPOT)));

        assertThrows(IllegalStateException.class, () -> registry.forVenue(Venue.BINANCE_FUTURES));
    }
}
