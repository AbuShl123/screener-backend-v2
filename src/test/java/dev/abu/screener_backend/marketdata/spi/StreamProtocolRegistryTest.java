package dev.abu.screener_backend.marketdata.spi;

import dev.abu.screener_backend.config.ExchangesProperties;
import dev.abu.screener_backend.config.ExchangesProperties.ExchangeProperties;
import dev.abu.screener_backend.config.ExchangesProperties.VenueProperties;
import dev.abu.screener_backend.config.TestVenueProps;
import dev.abu.screener_backend.marketdata.Exchange;
import dev.abu.screener_backend.marketdata.Instrument;
import dev.abu.screener_backend.marketdata.Market;
import dev.abu.screener_backend.marketdata.StreamKey;
import dev.abu.screener_backend.marketdata.StreamKind;
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

    private static final StreamKey SPOT_DEPTH = new StreamKey(Venue.BINANCE_SPOT, StreamKind.DEPTH);
    private static final StreamKey FUTURES_DEPTH = new StreamKey(Venue.BINANCE_FUTURES, StreamKind.DEPTH);

    private static final class StubProtocol implements StreamProtocol {
        @Override public String subscribeFrame(List<Instrument> chunk, int requestId) { return ""; }
        @Override public String routingKey(Instrument instrument) { return instrument.nativeSymbol(); }
        @Override public int route(String frame, SubscriptionIndex index) { return IGNORED; }
        @Override public Heartbeat heartbeat() { return new Heartbeat.ProtocolPing(Duration.ofSeconds(1)); }
    }

    private static ExchangesProperties enabled(Market... markets) {
        Map<Market, VenueProperties> venues = new EnumMap<>(Market.class);
        for (Market m : markets) {
            venues.put(m, TestVenueProps.venue().build());
        }
        return new ExchangesProperties(Map.of(Exchange.BINANCE, new ExchangeProperties(true, venues, null)));
    }

    @Test
    @DisplayName("resolves each bound stream to its protocol")
    void resolves() {
        StreamProtocol spot = new StubProtocol();
        StreamProtocol futures = new StubProtocol();
        StreamProtocolRegistry registry = new StreamProtocolRegistry(List.of(
                new VenueStreamBinding(SPOT_DEPTH, spot),
                new VenueStreamBinding(FUTURES_DEPTH, futures)), enabled(Market.SPOT, Market.FUTURES));

        assertSame(spot, registry.forStream(SPOT_DEPTH));
        assertSame(futures, registry.forStream(FUTURES_DEPTH));
    }

    @Test
    @DisplayName("two bindings for one stream fail at construction — the stray-@Component guard")
    void duplicateThrows() {
        assertThrows(IllegalStateException.class, () -> new StreamProtocolRegistry(List.of(
                new VenueStreamBinding(SPOT_DEPTH, new StubProtocol()),
                new VenueStreamBinding(SPOT_DEPTH, new StubProtocol())), enabled(Market.SPOT)));
    }

    @Test
    @DisplayName("an enabled stream without a binding fails the boot")
    void enabledWithoutBindingThrows() {
        assertThrows(IllegalStateException.class, () -> new StreamProtocolRegistry(List.of(
                new VenueStreamBinding(SPOT_DEPTH, new StubProtocol())), enabled(Market.SPOT, Market.FUTURES)));
    }

    @Test
    @DisplayName("a stream not configured needs no binding, but asking for it throws")
    void unconfiguredWithoutBindingIsFine() {
        StreamProtocolRegistry registry = assertDoesNotThrow(() -> new StreamProtocolRegistry(List.of(
                new VenueStreamBinding(SPOT_DEPTH, new StubProtocol())), enabled(Market.SPOT)));

        assertThrows(IllegalStateException.class, () -> registry.forStream(FUTURES_DEPTH));
    }

    @Test
    @DisplayName("a binding for a disabled or unconfigured stream is accepted — an adapter can ship a kind dark")
    void bindingForDisabledStreamIsFine() {
        Map<Market, VenueProperties> venues = new EnumMap<>(Market.class);
        venues.put(Market.SPOT, TestVenueProps.venue().build());
        venues.put(Market.FUTURES, TestVenueProps.venue().enabled(false).build());
        ExchangesProperties exchanges = new ExchangesProperties(
                Map.of(Exchange.BINANCE, new ExchangeProperties(true, venues, null)));
        StreamProtocol bybit = new StubProtocol();

        StreamProtocolRegistry registry = assertDoesNotThrow(() -> new StreamProtocolRegistry(List.of(
                new VenueStreamBinding(SPOT_DEPTH, new StubProtocol()),
                new VenueStreamBinding(FUTURES_DEPTH, new StubProtocol()),                      // venue off
                new VenueStreamBinding(new StreamKey(Venue.BYBIT_SPOT, StreamKind.DEPTH), bybit)), // not configured
                exchanges));

        assertSame(bybit, registry.forStream(new StreamKey(Venue.BYBIT_SPOT, StreamKind.DEPTH)));
    }
}
