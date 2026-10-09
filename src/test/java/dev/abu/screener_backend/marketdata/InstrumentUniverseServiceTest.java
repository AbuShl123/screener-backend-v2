package dev.abu.screener_backend.marketdata;

import dev.abu.screener_backend.config.DiscoveryProperties;
import dev.abu.screener_backend.config.ExchangesProperties;
import dev.abu.screener_backend.config.ExchangesProperties.ExchangeProperties;
import dev.abu.screener_backend.config.ExchangesProperties.VenueProperties;
import dev.abu.screener_backend.config.ExchangesProperties.VenueProperties.RestProperties;
import dev.abu.screener_backend.config.OrderbookProperties;
import dev.abu.screener_backend.marketdata.core.book.BookSlot;
import dev.abu.screener_backend.marketdata.core.book.BookSlotTable;
import dev.abu.screener_backend.marketdata.core.ingress.DepthEvent;
import dev.abu.screener_backend.marketdata.spi.BookSyncContext;
import dev.abu.screener_backend.marketdata.spi.DepthSyncStrategy;
import dev.abu.screener_backend.marketdata.spi.InstrumentCandidate;
import dev.abu.screener_backend.marketdata.spi.InstrumentSource;
import dev.abu.screener_backend.marketdata.spi.SyncStrategyRegistry;
import dev.abu.screener_backend.marketdata.spi.VenueStrategyBinding;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Supplier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The exchange-agnostic merger: source validation, id ordering, the added/removed diff, and
 * per-source failure isolation. Fake sources only — no Spring context, no network.
 *
 * <p>Two-source cases split Binance into a spot-only and a futures-only source. That is not how
 * the Binance adapter ships, but it keeps the fixtures to one exchange's YAML, and core cannot tell
 * the difference.
 */
class InstrumentUniverseServiceTest {

    private static final Set<Venue> BOTH = Set.of(Venue.BINANCE_SPOT, Venue.BINANCE_FUTURES);

    private final InstrumentRegistry registry = new InstrumentRegistry();
    private final List<String> calls = new ArrayList<>();
    private final List<InstrumentUniverseChangedEvent> events = new ArrayList<>();
    private final RecordingSlotTable slots = new RecordingSlotTable(calls);

    // ---------------------------------------------------------------- construction

    @Nested
    @DisplayName("source validation at construction")
    class Construction {

        @Test
        @DisplayName("two sources claiming one venue throw")
        void duplicateClaimThrows() {
            FakeSource a = new FakeSource(Set.of(Venue.BINANCE_SPOT));
            FakeSource b = new FakeSource(BOTH);

            assertThrows(IllegalStateException.class, () -> service(List.of(a, b)));
        }

        @Test
        @DisplayName("a source claiming no venues throws")
        void emptyClaimThrows() {
            assertThrows(IllegalStateException.class, () -> service(List.of(new FakeSource(Set.of()))));
        }

        @Test
        @DisplayName("a source spanning two exchanges throws")
        void multiExchangeClaimThrows() {
            FakeSource source = new FakeSource(Set.of(Venue.BINANCE_FUTURES, Venue.MEXC_FUTURES));

            assertThrows(IllegalStateException.class, () -> service(List.of(source)));
        }

        @Test
        @DisplayName("a source whose venues are all disabled is skipped and never fetched")
        void disabledSourceSkipped() {
            FakeSource source = new FakeSource(BOTH);
            InstrumentUniverseService service = service(List.of(source), exchanges(false, true, true), timeout());

            service.refresh();

            assertEquals(0, source.fetchCount);
            assertTrue(events.isEmpty(), "nothing succeeded, so no event");
        }
    }

    // ---------------------------------------------------------------- per-venue switch

    @Nested
    @DisplayName("a partly enabled source")
    class PartlyEnabled {

        private final FakeSource source = new FakeSource(BOTH)
                .returning(() -> both(candidates("A", "B"), candidates("A", "B", "C")));

        private InstrumentUniverseService spotOnly() {
            return service(List.of(source), exchanges(true, true, false), timeout());
        }

        @Test
        @DisplayName("is fetched in full, but only its enabled venue is registered and announced")
        void onlyEnabledVenueRegistered() {
            spotOnly().refresh();

            assertEquals(1, source.fetchCount);
            assertEquals(List.of("A", "B"), symbols(events.getFirst().getAdded()));
            assertTrue(events.getFirst().getAdded().stream().allMatch(i -> i.venue() == Venue.BINANCE_SPOT));
            assertTrue(registry.find(Venue.BINANCE_FUTURES, "A").isEmpty());
            assertEquals(List.of("allocate", "allocate", "publish", "event"), calls,
                    "no slots for the disabled venue");
        }

        @Test
        @DisplayName("failing retains the enabled venue's previous universe, with zero removals")
        void failureRetainsEnabledVenue() {
            InstrumentUniverseService service = spotOnly();
            service.refresh();

            source.returning(() -> { throw new IllegalStateException("futures endpoint down"); });
            service.refresh();
            assertEquals(1, events.size(), "the only source failed, so no event");

            source.returning(() -> both(candidates("A"), candidates("A")));
            service.refresh();
            assertEquals(List.of("B"), symbols(events.get(1).getRemoved()),
                    "spot's universe was carried through the failure, so B is removed now and only now");
            assertEquals(Venue.BINANCE_SPOT, events.get(1).getRemoved().getFirst().venue());
        }

        @Test
        @DisplayName("a result missing the disabled venue is still rejected: the contract is unchanged")
        void missingDisabledVenueRejected() {
            source.returning(() -> Map.of(Venue.BINANCE_SPOT, candidates("A")));

            spotOnly().refresh();

            assertTrue(events.isEmpty());
            assertTrue(registry.find(Venue.BINANCE_SPOT, "A").isEmpty());
        }
    }

    // ---------------------------------------------------------------- happy path

    @Nested
    @DisplayName("merging and the diff")
    class Merging {

        @Test
        @DisplayName("ids follow (venue.ordinal, nativeSymbol) whatever order the source reports in")
        void idsAreOrderedByVenueThenSymbol() {
            FakeSource source = new FakeSource(BOTH).returning(() -> Map.of(
                    Venue.BINANCE_FUTURES, candidates("ETHUSDT", "BTCUSDT"),
                    Venue.BINANCE_SPOT, candidates("SOLUSDT", "BTCUSDT")));

            service(List.of(source)).refresh();

            assertEquals(0, id(Venue.BINANCE_SPOT, "BTCUSDT"));
            assertEquals(1, id(Venue.BINANCE_SPOT, "SOLUSDT"));
            assertEquals(2, id(Venue.BINANCE_FUTURES, "BTCUSDT"));
            assertEquals(3, id(Venue.BINANCE_FUTURES, "ETHUSDT"));
            assertEquals(4, events.getFirst().getAdded().size());
        }

        @Test
        @DisplayName("a candidate's quantity multiplier reaches the registered instrument")
        void quantityMultiplierCarried() {
            FakeSource source = new FakeSource(BOTH).returning(() -> both(
                    candidates("BTCUSDT"),
                    List.of(new InstrumentCandidate("ETHUSDT", "ETH", "USDT", 0.01))));

            service(List.of(source)).refresh();

            assertEquals(1.0, registry.find(Venue.BINANCE_SPOT, "BTCUSDT").orElseThrow().quantityMultiplier());
            assertEquals(0.01, registry.find(Venue.BINANCE_FUTURES, "ETHUSDT").orElseThrow().quantityMultiplier());
        }

        @Test
        @DisplayName("refreshing twice with the same data keeps ids and reports no change")
        void idempotentRefresh() {
            FakeSource source = new FakeSource(BOTH).returning(() -> both(candidates("A", "B"), candidates("A")));
            InstrumentUniverseService service = service(List.of(source));

            service.refresh();
            int idB = id(Venue.BINANCE_SPOT, "B");
            service.refresh();

            assertEquals(idB, id(Venue.BINANCE_SPOT, "B"));
            assertEquals(2, events.size());
            assertTrue(events.get(1).getAdded().isEmpty());
            assertTrue(events.get(1).getRemoved().isEmpty());
        }

        @Test
        @DisplayName("a vanished symbol is removed; its id is never handed to another instrument")
        void delisting() {
            FakeSource source = new FakeSource(BOTH).returning(() -> both(candidates("A", "B"), candidates("A")));
            InstrumentUniverseService service = service(List.of(source));
            service.refresh();
            int idB = id(Venue.BINANCE_SPOT, "B");

            source.returning(() -> both(candidates("A"), candidates("A")));
            service.refresh();

            assertEquals(List.of("B"), symbols(events.get(1).getRemoved()));

            source.returning(() -> both(candidates("A", "B", "C"), candidates("A")));
            service.refresh();

            assertEquals(idB, id(Venue.BINANCE_SPOT, "B"), "relisting keeps the durable identity's id");
            assertNotEquals(idB, id(Venue.BINANCE_SPOT, "C"));
        }

        @Test
        @DisplayName("a venue that was empty may stay empty")
        void emptyToEmptyAccepted() {
            FakeSource source = new FakeSource(BOTH).returning(() -> both(List.of(), candidates("A")));

            InstrumentUniverseService service = service(List.of(source));
            service.refresh();
            service.refresh();

            assertEquals(2, events.size(), "both refreshes succeeded");
            assertTrue(events.get(1).getRemoved().isEmpty());
        }

        @Test
        @DisplayName("ordering invariant: allocate → publish → event")
        void publishPrecedesEvent() {
            FakeSource source = new FakeSource(BOTH).returning(() -> both(candidates("A"), candidates("A")));

            service(List.of(source)).refresh();

            assertEquals(List.of("allocate", "allocate", "publish", "event"), calls);
        }

        @Test
        @DisplayName("excluded symbols are matched on base + quote, whatever the native spelling")
        void exclusionMatchesNormalizedSymbol() {
            FakeSource source = new FakeSource(BOTH).returning(() -> both(
                    List.of(new InstrumentCandidate("USDCUSDT", "USDC", "USDT"),
                            new InstrumentCandidate("BTCUSDT", "BTC", "USDT")),
                    List.of(new InstrumentCandidate("USDC_USDT", "USDC", "USDT"),
                            new InstrumentCandidate("BTC_USDT", "BTC", "USDT"))));

            service(List.of(source), Set.of("USDCUSDT")).refresh();

            assertEquals(List.of("BTCUSDT", "BTC_USDT"), symbols(events.getFirst().getAdded()));
            assertTrue(registry.find(Venue.BINANCE_FUTURES, "USDC_USDT").isEmpty());
        }
    }

    // ---------------------------------------------------------------- failure isolation

    @Nested
    @DisplayName("per-source failure isolation")
    class FailureIsolation {

        private final FakeSource spot = new FakeSource(Set.of(Venue.BINANCE_SPOT))
                .returning(() -> Map.of(Venue.BINANCE_SPOT, candidates("A", "B")));
        private final FakeSource futures = new FakeSource(Set.of(Venue.BINANCE_FUTURES))
                .returning(() -> Map.of(Venue.BINANCE_FUTURES, candidates("A", "B")));

        @Test
        @DisplayName("one source throwing does not block the other, and produces zero removals")
        void throwingSourceIsIsolated() {
            InstrumentUniverseService service = service(List.of(spot, futures));
            service.refresh();

            spot.returning(() -> { throw new IllegalStateException("exchange down"); });
            futures.returning(() -> Map.of(Venue.BINANCE_FUTURES, candidates("A", "C")));
            service.refresh();

            InstrumentUniverseChangedEvent event = events.get(1);
            assertEquals(List.of("C"), symbols(event.getAdded()));
            assertEquals(List.of("B"), symbols(event.getRemoved()), "only the healthy source's delisting");
            assertEquals(Venue.BINANCE_FUTURES, event.getRemoved().getFirst().venue());
        }

        @Test
        @DisplayName("a timed-out source is treated exactly like a throwing one")
        void timeoutIsIsolated() {
            InstrumentUniverseService service = service(List.of(spot, futures), allEnabled(), Duration.ofMillis(200));
            service.refresh();

            spot.returning(() -> {
                try {
                    Thread.sleep(10_000);
                } catch (InterruptedException e) {
                    throw new IllegalStateException("interrupted", e);
                }
                return Map.of(Venue.BINANCE_SPOT, List.of());
            });
            long start = System.nanoTime();
            service.refresh();

            assertTrue(Duration.ofNanos(System.nanoTime() - start).toSeconds() < 5,
                    "refresh must not wait for the stuck source");
            assertTrue(events.get(1).getRemoved().isEmpty());
        }

        @Test
        @DisplayName("a result whose keys differ from venues() is a failure, retained")
        void wrongKeysRetained() {
            FakeSource source = new FakeSource(BOTH).returning(() -> both(candidates("A"), candidates("A")));
            InstrumentUniverseService service = service(List.of(source));
            service.refresh();

            source.returning(() -> Map.of(Venue.BINANCE_SPOT, List.of()));
            service.refresh();
            assertEquals(1, events.size(), "the only source failed, so no event");

            source.returning(() -> both(candidates("A"), candidates("A")));
            service.refresh();
            assertTrue(events.get(1).getRemoved().isEmpty(), "the previous universe was carried forward");
        }

        @Test
        @DisplayName("a venue going from N to empty trips the guard: retained, zero removals")
        void emptyGuard() {
            InstrumentUniverseService service = service(List.of(spot, futures));
            service.refresh();

            spot.returning(() -> Map.of(Venue.BINANCE_SPOT, List.of()));
            service.refresh();

            assertEquals(2, events.size(), "the healthy source still produced an event");
            assertTrue(events.get(1).getRemoved().isEmpty());

            spot.returning(() -> Map.of(Venue.BINANCE_SPOT, candidates("A")));
            service.refresh();
            assertEquals(List.of("B"), symbols(events.get(2).getRemoved()),
                    "spot's pre-guard universe was retained, so B is removed now and only now");
        }
    }

    // ---------------------------------------------------------------- fixtures

    private InstrumentUniverseService service(List<InstrumentSource> sources) {
        return service(sources, allEnabled(), timeout(), Set.of());
    }

    private InstrumentUniverseService service(List<InstrumentSource> sources, Set<String> excludedSymbols) {
        return service(sources, allEnabled(), timeout(), excludedSymbols);
    }

    private InstrumentUniverseService service(List<InstrumentSource> sources,
                                              ExchangesProperties exchanges, Duration timeout) {
        return service(sources, exchanges, timeout, Set.of());
    }

    private InstrumentUniverseService service(List<InstrumentSource> sources, ExchangesProperties exchanges,
                                              Duration timeout, Set<String> excludedSymbols) {
        return new InstrumentUniverseService(sources, registry, slots, exchanges,
                new DiscoveryProperties(timeout, excludedSymbols),
                event -> {
                    calls.add("event");
                    events.add((InstrumentUniverseChangedEvent) event);
                });
    }

    private static Duration timeout() {
        return Duration.ofSeconds(5);
    }

    private static ExchangesProperties allEnabled() {
        return exchanges(true, true, true);
    }

    /** Binance with both venue blocks present: the exchange's master switch and each venue's own. */
    private static ExchangesProperties exchanges(boolean enabled, boolean spot, boolean futures) {
        Map<Market, VenueProperties> venues = new EnumMap<>(Market.class);
        venues.put(Market.SPOT, venueProps(spot));
        venues.put(Market.FUTURES, venueProps(futures));
        return new ExchangesProperties(Map.of(Exchange.BINANCE, new ExchangeProperties(enabled, venues, null)));
    }

    private static VenueProperties venueProps(boolean enabled) {
        RestProperties rest = new RestProperties("https://x", 1, Duration.ofSeconds(5), Duration.ofSeconds(10));
        return new VenueProperties(enabled, "wss://x", rest, 1024, 1, 1, 100, 120, null);
    }

    private static Map<Venue, List<InstrumentCandidate>> both(List<InstrumentCandidate> spot,
                                                              List<InstrumentCandidate> futures) {
        return Map.of(Venue.BINANCE_SPOT, spot, Venue.BINANCE_FUTURES, futures);
    }

    private static List<InstrumentCandidate> candidates(String... symbols) {
        List<InstrumentCandidate> list = new ArrayList<>();
        for (String s : symbols) list.add(new InstrumentCandidate(s, s, "USDT"));
        return list;
    }

    private int id(Venue venue, String symbol) {
        return registry.find(venue, symbol).orElseThrow().id();
    }

    private static List<String> symbols(List<Instrument> instruments) {
        return instruments.stream().map(Instrument::nativeSymbol).sorted().toList();
    }

    /** A source whose behaviour a test can swap between refreshes. */
    private static final class FakeSource implements InstrumentSource {
        private final Set<Venue> venues;
        private volatile Supplier<Map<Venue, List<InstrumentCandidate>>> behaviour = Map::of;
        private volatile int fetchCount;

        FakeSource(Set<Venue> venues) {
            this.venues = venues;
        }

        FakeSource returning(Supplier<Map<Venue, List<InstrumentCandidate>>> behaviour) {
            this.behaviour = behaviour;
            return this;
        }

        @Override
        public Set<Venue> venues() {
            return venues;
        }

        @Override
        public Map<Venue, List<InstrumentCandidate>> fetch() {
            fetchCount++;
            return behaviour.get();
        }
    }

    /** A real slot table that records allocate/publish into the shared call log. */
    private static final class RecordingSlotTable extends BookSlotTable {
        private final List<String> calls;

        RecordingSlotTable(List<String> calls) {
            super(new OrderbookProperties(0.1), new SyncStrategyRegistry(List.of(
                    new VenueStrategyBinding(Venue.BINANCE_SPOT, new NoopStrategy()),
                    new VenueStrategyBinding(Venue.BINANCE_FUTURES, new NoopStrategy()))));
            this.calls = calls;
        }

        @Override
        public void allocate(Instrument instrument) {
            calls.add("allocate");
            super.allocate(instrument);
        }

        @Override
        public void publish() {
            calls.add("publish");
            super.publish();
        }
    }

    private static final class NoopStrategy implements DepthSyncStrategy {
        @Override
        public BookSyncContext newContext() {
            return new BookSyncContext() { };
        }

        @Override
        public void onEvent(BookSlot slot, DepthEvent event) { }
    }
}
