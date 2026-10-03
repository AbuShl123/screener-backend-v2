package dev.abu.screener_backend.exchange.stream;

import dev.abu.screener_backend.config.ExchangesProperties;
import dev.abu.screener_backend.config.ExchangesProperties.ExchangeProperties;
import dev.abu.screener_backend.config.ExchangesProperties.VenueProperties;
import dev.abu.screener_backend.config.ExchangesProperties.VenueProperties.RestProperties;
import dev.abu.screener_backend.config.WebSocketProperties;
import dev.abu.screener_backend.exchange.Exchange;
import dev.abu.screener_backend.exchange.Instrument;
import dev.abu.screener_backend.exchange.InstrumentUniverseChangedEvent;
import dev.abu.screener_backend.exchange.Market;
import dev.abu.screener_backend.exchange.Venue;
import dev.abu.screener_backend.exchange.ingress.DepthEventPublisher;
import dev.abu.screener_backend.exchange.spi.Heartbeat;
import dev.abu.screener_backend.exchange.spi.StreamProtocol;
import dev.abu.screener_backend.exchange.spi.StreamProtocolRegistry;
import dev.abu.screener_backend.exchange.spi.VenueStreamBinding;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Per-venue pool lifecycle in {@link StreamManager}. Pools are recorded, never connected.
 *
 * <p>Only Binance venues exist, so "another exchange streams while one fails to appear" is modelled
 * as "futures appears before spot" — the same code path: a venue first seen in a later event.
 */
class StreamManagerTest {

    private static final RestProperties REST =
            new RestProperties("https://x", 1, Duration.ofSeconds(5), Duration.ofSeconds(10));
    private static final VenueProperties PROPS =
            new VenueProperties("wss://x", REST, "{symbol}@depth", 1024, 1, 1, 400, 120);
    private static final WebSocketProperties WS = new WebSocketProperties(100, 1000);
    private static final DepthEventPublisher NO_OP_PUBLISHER = new DepthEventPublisher() {
        @Override public void publishFrame(int instrumentId, String payload) { }
        @Override public void publishSnapshot(int instrumentId, String payload) { }
        @Override public void publishSnapshotFailure(int instrumentId) { }
    };

    private static final class StubProtocol implements StreamProtocol {
        @Override public String subscribeFrame(List<Instrument> chunk, int requestId) { return ""; }
        @Override public String routingKey(Instrument instrument) { return instrument.nativeSymbol(); }
        @Override public int route(String frame, SubscriptionIndex index) { return IGNORED; }
        @Override public Heartbeat heartbeat() { return new Heartbeat.ProtocolPing(Duration.ofSeconds(1)); }
    }

    /** Records calls instead of opening sockets; {@code shutdown} still releases the real scheduler. */
    private static final class RecordingPool extends ConnectionPool {
        final Venue venue;
        final List<List<Instrument>> starts = new ArrayList<>();
        boolean shutDown;

        RecordingPool(Venue venue, StreamProtocol protocol) {
            super(venue, PROPS, WS, protocol, NO_OP_PUBLISHER);
            this.venue = venue;
        }

        @Override
        public void start(List<Instrument> instruments) {
            starts.add(List.copyOf(instruments));
        }

        @Override
        public void shutdown() {
            shutDown = true;
            super.shutdown();
        }
    }

    private final List<RecordingPool> created = new ArrayList<>();
    private final Set<Venue> failing = new HashSet<>();

    @AfterEach
    void releaseSchedulers() {
        created.forEach(p -> { if (!p.shutDown) p.shutdown(); });
    }

    private StreamManager manager(Market... enabledMarkets) {
        Map<Market, VenueProperties> venues = new EnumMap<>(Market.class);
        for (Market m : enabledMarkets) venues.put(m, PROPS);
        ExchangesProperties exchanges = new ExchangesProperties(
                Map.of(Exchange.BINANCE, new ExchangeProperties(true, venues, null)));
        StreamProtocolRegistry registry = new StreamProtocolRegistry(List.of(
                new VenueStreamBinding(Venue.BINANCE_SPOT, new StubProtocol()),
                new VenueStreamBinding(Venue.BINANCE_FUTURES, new StubProtocol())), exchanges);
        return new StreamManager(exchanges, registry, (venue, protocol) -> {
            if (failing.contains(venue)) throw new IllegalStateException("boom: " + venue);
            RecordingPool pool = new RecordingPool(venue, protocol);
            created.add(pool);
            return pool;
        });
    }

    private static Instrument spot(int id, String symbol) {
        return Instrument.of(id, Venue.BINANCE_SPOT, symbol, symbol.replace("USDT", ""), "USDT");
    }

    private static Instrument futures(int id, String symbol) {
        return Instrument.of(id, Venue.BINANCE_FUTURES, symbol, symbol.replace("USDT", ""), "USDT");
    }

    private static InstrumentUniverseChangedEvent event(List<Instrument> added, List<Instrument> removed) {
        return new InstrumentUniverseChangedEvent(new Object(), added, removed);
    }

    private RecordingPool pool(Venue venue) {
        return created.stream().filter(p -> p.venue == venue).findFirst().orElseThrow();
    }

    @Test
    @DisplayName("first event with both venues starts two pools, each with only its venue's instruments in id order")
    void bothVenues() {
        StreamManager manager = manager(Market.SPOT, Market.FUTURES);
        Instrument s0 = spot(0, "BTCUSDT"), f1 = futures(1, "BTCUSDT"), s2 = spot(2, "ETHUSDT"), f3 = futures(3, "ETHUSDT");

        manager.onUniverseChanged(event(List.of(s0, f1, s2, f3), List.of()));

        assertEquals(2, created.size());
        assertEquals(List.of(List.of(s0, s2)), pool(Venue.BINANCE_SPOT).starts);
        assertEquals(List.of(List.of(f1, f3)), pool(Venue.BINANCE_FUTURES).starts);
    }

    @Test
    @DisplayName("a venue first seen in a later event still gets a pool, with exactly that event's instruments")
    void futuresBeforeSpot() {
        StreamManager manager = manager(Market.SPOT, Market.FUTURES);
        Instrument f0 = futures(0, "BTCUSDT"), s1 = spot(1, "BTCUSDT"), s2 = spot(2, "ETHUSDT");

        manager.onUniverseChanged(event(List.of(f0), List.of()));
        assertEquals(1, created.size());
        assertEquals(Venue.BINANCE_FUTURES, created.getFirst().venue);

        manager.onUniverseChanged(event(List.of(s1, s2), List.of()));
        assertEquals(2, created.size());
        assertEquals(List.of(List.of(s1, s2)), pool(Venue.BINANCE_SPOT).starts);
        assertEquals(1, pool(Venue.BINANCE_FUTURES).starts.size(), "the futures pool must not restart");
    }

    @Test
    @DisplayName("an event for an already-started venue neither creates a pool nor restarts one")
    void alreadyStarted() {
        StreamManager manager = manager(Market.SPOT, Market.FUTURES);

        manager.onUniverseChanged(event(List.of(spot(0, "BTCUSDT")), List.of()));
        manager.onUniverseChanged(event(List.of(spot(1, "ETHUSDT")), List.of()));

        assertEquals(1, created.size());
        assertEquals(1, pool(Venue.BINANCE_SPOT).starts.size());
    }

    @Test
    @DisplayName("an event with only removals creates no pool")
    void onlyRemovals() {
        StreamManager manager = manager(Market.SPOT, Market.FUTURES);

        manager.onUniverseChanged(event(List.of(), List.of(spot(0, "BTCUSDT"))));

        assertTrue(created.isEmpty());
    }

    @Test
    @DisplayName("a venue disabled in config is skipped")
    void disabledVenue() {
        StreamManager manager = manager(Market.FUTURES);

        manager.onUniverseChanged(event(List.of(spot(0, "BTCUSDT"), futures(1, "BTCUSDT")), List.of()));

        assertEquals(1, created.size());
        assertEquals(Venue.BINANCE_FUTURES, created.getFirst().venue);
    }

    @Test
    @DisplayName("one venue failing to start does not stop the other, and nothing escapes into discovery")
    void failureIsolated() {
        StreamManager manager = manager(Market.SPOT, Market.FUTURES);
        failing.add(Venue.BINANCE_SPOT);
        Instrument f1 = futures(1, "BTCUSDT");

        assertDoesNotThrow(() -> manager.onUniverseChanged(event(List.of(spot(0, "BTCUSDT"), f1), List.of())));

        assertEquals(1, created.size());
        assertEquals(List.of(List.of(f1)), pool(Venue.BINANCE_FUTURES).starts);
    }

    @Test
    @DisplayName("shutdown stops every pool, and a later event starts nothing")
    void shutdownThenEvent() {
        StreamManager manager = manager(Market.SPOT, Market.FUTURES);
        manager.onUniverseChanged(event(List.of(futures(0, "BTCUSDT")), List.of()));

        manager.shutdown();
        assertTrue(created.stream().allMatch(p -> p.shutDown));

        // Spot has no pool yet, so only the shutDown flag can stop this one from starting.
        manager.onUniverseChanged(event(List.of(spot(1, "BTCUSDT")), List.of()));
        assertEquals(1, created.size());
    }
}
