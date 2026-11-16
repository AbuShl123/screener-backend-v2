package dev.abu.screener_backend.marketdata.core.stream;

import dev.abu.screener_backend.config.ExchangesProperties;
import dev.abu.screener_backend.config.ExchangesProperties.ExchangeProperties;
import dev.abu.screener_backend.config.ExchangesProperties.StreamProperties;
import dev.abu.screener_backend.config.ExchangesProperties.VenueProperties;
import dev.abu.screener_backend.config.TestVenueProps;
import dev.abu.screener_backend.config.WebSocketProperties;
import dev.abu.screener_backend.marketdata.Exchange;
import dev.abu.screener_backend.marketdata.Instrument;
import dev.abu.screener_backend.marketdata.InstrumentUniverseChangedEvent;
import dev.abu.screener_backend.marketdata.Market;
import dev.abu.screener_backend.marketdata.StreamKey;
import dev.abu.screener_backend.marketdata.Venue;
import dev.abu.screener_backend.marketdata.core.health.PipelineMetrics;
import dev.abu.screener_backend.marketdata.core.ingress.MarketEventPublisher;
import dev.abu.screener_backend.marketdata.StreamKind;
import dev.abu.screener_backend.marketdata.core.stream.ConnectionPool;
import dev.abu.screener_backend.marketdata.core.stream.StreamManager;
import dev.abu.screener_backend.marketdata.core.stream.SubscriptionIndex;
import dev.abu.screener_backend.marketdata.spi.Heartbeat;
import dev.abu.screener_backend.marketdata.spi.StreamProtocol;
import dev.abu.screener_backend.marketdata.spi.StreamProtocolRegistry;
import dev.abu.screener_backend.marketdata.spi.VenueStreamBinding;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.ByteBuffer;
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
 * Per-stream pool lifecycle in {@link StreamManager}. Pools are recorded, never connected.
 *
 * <p>Only Binance venues exist, so "another exchange streams while one fails to appear" is modelled
 * as "futures appears before spot" — the same code path: a venue first seen in a later event.
 *
 * <p>{@link StreamKind} has only {@code DEPTH}, so each venue here has exactly one stream. A venue
 * with two enabled streams starting two pools needs a second kind; that test belongs with it.
 */
class StreamManagerTest {

    private static final VenueProperties VENUE = TestVenueProps.venue().build();
    private static final StreamProperties STREAM = TestVenueProps.stream().build();
    private static final WebSocketProperties WS = new WebSocketProperties(100, 1000);
    private static final StreamKey SPOT_DEPTH = new StreamKey(Venue.BINANCE_SPOT, StreamKind.DEPTH);
    private static final StreamKey FUTURES_DEPTH = new StreamKey(Venue.BINANCE_FUTURES, StreamKind.DEPTH);
    private static final MarketEventPublisher NO_OP_PUBLISHER = new MarketEventPublisher() {
        @Override public void publishFrame(StreamKind stream, int instrumentId, String payload) { }
        @Override public void publishFrame(StreamKind stream, int instrumentId, ByteBuffer payload) { }
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
        final StreamKey stream;
        final List<List<Instrument>> starts = new ArrayList<>();
        boolean shutDown;

        RecordingPool(StreamKey stream, StreamProtocol protocol) {
            super(stream, STREAM, WS, protocol, NO_OP_PUBLISHER, new PipelineMetrics());
            this.stream = stream;
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
    private final Set<StreamKey> failing = new HashSet<>();

    @AfterEach
    void releaseSchedulers() {
        created.forEach(p -> { if (!p.shutDown) p.shutdown(); });
    }

    private StreamManager manager(Market... enabledMarkets) {
        Map<Market, VenueProperties> venues = new EnumMap<>(Market.class);
        for (Market m : enabledMarkets) venues.put(m, VENUE);
        ExchangesProperties exchanges = new ExchangesProperties(
                Map.of(Exchange.BINANCE, new ExchangeProperties(true, venues, null)));
        StreamProtocolRegistry registry = new StreamProtocolRegistry(List.of(
                new VenueStreamBinding(SPOT_DEPTH, new StubProtocol()),
                new VenueStreamBinding(FUTURES_DEPTH, new StubProtocol())), exchanges);
        return new StreamManager(exchanges, registry, (stream, protocol) -> {
            if (failing.contains(stream)) throw new IllegalStateException("boom: " + stream);
            RecordingPool pool = new RecordingPool(stream, protocol);
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

    private RecordingPool pool(StreamKey stream) {
        return created.stream().filter(p -> p.stream.equals(stream)).findFirst().orElseThrow();
    }

    @Test
    @DisplayName("first event with both venues starts one pool per stream, each with its venue's full list in id order")
    void bothVenues() {
        StreamManager manager = manager(Market.SPOT, Market.FUTURES);
        Instrument s0 = spot(0, "BTCUSDT"), f1 = futures(1, "BTCUSDT"), s2 = spot(2, "ETHUSDT"), f3 = futures(3, "ETHUSDT");

        manager.onUniverseChanged(event(List.of(s0, f1, s2, f3), List.of()));

        assertEquals(2, created.size());
        assertEquals(List.of(List.of(s0, s2)), pool(SPOT_DEPTH).starts);
        assertEquals(List.of(List.of(f1, f3)), pool(FUTURES_DEPTH).starts);
    }

    @Test
    @DisplayName("a venue first seen in a later event still gets a pool, with exactly that event's instruments")
    void futuresBeforeSpot() {
        StreamManager manager = manager(Market.SPOT, Market.FUTURES);
        Instrument f0 = futures(0, "BTCUSDT"), s1 = spot(1, "BTCUSDT"), s2 = spot(2, "ETHUSDT");

        manager.onUniverseChanged(event(List.of(f0), List.of()));
        assertEquals(1, created.size());
        assertEquals(FUTURES_DEPTH, created.getFirst().stream);

        manager.onUniverseChanged(event(List.of(s1, s2), List.of()));
        assertEquals(2, created.size());
        assertEquals(List.of(List.of(s1, s2)), pool(SPOT_DEPTH).starts);
        assertEquals(1, pool(FUTURES_DEPTH).starts.size(), "the futures pool must not restart");
    }

    @Test
    @DisplayName("an event for an already-started venue neither creates a pool nor restarts one")
    void alreadyStarted() {
        StreamManager manager = manager(Market.SPOT, Market.FUTURES);

        manager.onUniverseChanged(event(List.of(spot(0, "BTCUSDT")), List.of()));
        manager.onUniverseChanged(event(List.of(spot(1, "ETHUSDT")), List.of()));

        assertEquals(1, created.size());
        assertEquals(1, pool(SPOT_DEPTH).starts.size());
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
        assertEquals(FUTURES_DEPTH, created.getFirst().stream);
    }

    @Test
    @DisplayName("one stream failing to start does not stop the others, and nothing escapes into discovery")
    void failureIsolated() {
        StreamManager manager = manager(Market.SPOT, Market.FUTURES);
        failing.add(SPOT_DEPTH);
        Instrument f1 = futures(1, "BTCUSDT");

        assertDoesNotThrow(() -> manager.onUniverseChanged(event(List.of(spot(0, "BTCUSDT"), f1), List.of())));

        assertEquals(1, created.size());
        assertEquals(List.of(List.of(f1)), pool(FUTURES_DEPTH).starts);
    }

    @Test
    @DisplayName("a stream that failed to start is not retried by a later event, which would give it a partial list")
    void failedStreamNotRetried() {
        StreamManager manager = manager(Market.SPOT, Market.FUTURES);
        failing.add(SPOT_DEPTH);
        manager.onUniverseChanged(event(List.of(spot(0, "BTCUSDT")), List.of()));
        failing.clear();

        manager.onUniverseChanged(event(List.of(spot(1, "ETHUSDT")), List.of()));

        assertTrue(created.isEmpty());
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
