package dev.abu.screener_backend.marketdata.core.ingress;

import dev.abu.screener_backend.marketdata.StreamKind;

import dev.abu.screener_backend.analysis.OrderBookClassifier;
import dev.abu.screener_backend.config.OrderbookProperties;
import dev.abu.screener_backend.marketdata.Instrument;
import dev.abu.screener_backend.marketdata.Venue;
import dev.abu.screener_backend.marketdata.core.book.OrderBook;
import dev.abu.screener_backend.marketdata.core.slot.InstrumentSlot;
import dev.abu.screener_backend.marketdata.core.slot.InstrumentSlotTable;
import dev.abu.screener_backend.marketdata.spi.BookSyncContext;
import dev.abu.screener_backend.marketdata.spi.DepthSyncStrategy;
import dev.abu.screener_backend.marketdata.spi.SyncStrategyRegistry;
import dev.abu.screener_backend.marketdata.spi.VenueStrategyBinding;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MarketEventHandlerTest {

    private static final int ID = 0;

    private final RecordingStrategy strategy = new RecordingStrategy();
    private final RecordingClassifier classifier = new RecordingClassifier();
    private final MarketEvent event = new MarketEvent();
    private InstrumentSlotTable slots;
    private MarketEventHandler handler;

    @BeforeEach
    void setUp() {
        slots = new InstrumentSlotTable(
                new OrderbookProperties(0.1),
                new SyncStrategyRegistry(List.of(new VenueStrategyBinding(Venue.BINANCE_SPOT, strategy))));
        slots.allocate(Instrument.of(ID, Venue.BINANCE_SPOT, "BTCUSDT", "BTC", "USDT"));
        slots.publish();
        handler = new MarketEventHandler(0, slots, classifier);
    }

    @Test
    @DisplayName("a DEPTH event reaches the slot's sync strategy, then the classifier, and is cleared")
    void depthEventReachesStrategyAndClassifier() {
        event.source = EventSource.WS_MSG;
        event.stream = StreamKind.DEPTH;
        event.instrumentId = ID;
        event.rawJson = "{}";

        handler.onEvent(event, 0, true);

        InstrumentSlot slot = slots.get(ID);
        assertEquals(1, strategy.calls.size());
        assertSame(slot, strategy.calls.get(0).slot());
        assertEquals(StreamKind.DEPTH, strategy.calls.get(0).stream(), "stream is intact when the strategy runs");
        assertEquals(EventSource.WS_MSG, strategy.calls.get(0).source());
        assertEquals(1, classifier.instruments.size());
        assertSame(slot.instrument(), classifier.instruments.get(0));
        assertSame(slot.book(), classifier.books.get(0));
        assertEquals(1, handler.processed());

        assertNull(event.source);
        assertNull(event.stream);
        assertNull(event.rawJson);
        assertEquals(-1, event.instrumentId);
    }

    @Test
    @DisplayName("a TRADE event is counted and cleared without reaching the sync strategy or the classifier")
    void tradeEventIsCountedAndCleared() {
        event.source = EventSource.WS_MSG;
        event.stream = StreamKind.TRADE;
        event.instrumentId = ID;
        event.rawJson = "{\"e\":\"aggTrade\"}";

        handler.onEvent(event, 0, true);

        assertTrue(strategy.calls.isEmpty(), "trades never touch the book");
        assertTrue(classifier.instruments.isEmpty());
        assertEquals(1, handler.processed());
        assertNull(event.source);
        assertNull(event.stream);
        assertNull(event.rawJson);
        assertEquals(-1, event.instrumentId);
    }

    @Test
    @DisplayName("an event for a missing slot is counted, touches nothing and is cleared")
    void missingSlotIsCountedAndCleared() {
        event.source = EventSource.WS_MSG;
        event.stream = StreamKind.DEPTH;
        event.instrumentId = 99;
        event.rawJson = "{}";

        handler.onEvent(event, 0, true);

        assertTrue(strategy.calls.isEmpty());
        assertTrue(classifier.instruments.isEmpty());
        assertEquals(1, handler.processed(), "a dropped event still counts as consumed");
        assertNull(event.stream);
        assertNull(event.rawJson);
        assertEquals(-1, event.instrumentId);
    }

    // --- Fakes ---------------------------------------------------------------------------------

    private record Call(InstrumentSlot slot, EventSource source, StreamKind stream) { }

    private static final class RecordingStrategy implements DepthSyncStrategy {
        final List<Call> calls = new ArrayList<>();

        @Override
        public BookSyncContext newContext() {
            return new BookSyncContext() { };
        }

        @Override
        public void onEvent(InstrumentSlot slot, MarketEvent event) {
            calls.add(new Call(slot, event.source, event.stream));
        }
    }

    private static final class RecordingClassifier extends OrderBookClassifier {
        final List<Instrument> instruments = new ArrayList<>();
        final List<OrderBook> books = new ArrayList<>();

        RecordingClassifier() {
            super(null, null, Map.of());
        }

        @Override
        public void process(Instrument inst, OrderBook ob) {
            instruments.add(inst);
            books.add(ob);
        }
    }
}
