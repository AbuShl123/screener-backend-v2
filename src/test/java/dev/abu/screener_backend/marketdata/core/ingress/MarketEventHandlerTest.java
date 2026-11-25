package dev.abu.screener_backend.marketdata.core.ingress;

import dev.abu.screener_backend.marketdata.StreamKind;

import dev.abu.screener_backend.analysis.OrderBookClassifier;
import dev.abu.screener_backend.analysis.cluster.OrderClusterDetector;
import dev.abu.screener_backend.analysis.cluster.OrderClusterDetectorTest;
import dev.abu.screener_backend.config.OrderbookProperties;
import dev.abu.screener_backend.config.TradeAlertProperties;
import dev.abu.screener_backend.marketdata.Instrument;
import dev.abu.screener_backend.marketdata.Venue;
import dev.abu.screener_backend.marketdata.core.book.OrderBook;
import dev.abu.screener_backend.marketdata.core.health.PipelineMetrics;
import dev.abu.screener_backend.marketdata.core.slot.InstrumentSlot;
import dev.abu.screener_backend.marketdata.core.slot.InstrumentSlotTable;
import dev.abu.screener_backend.marketdata.core.trade.TradeAlertStore;
import dev.abu.screener_backend.marketdata.core.trade.TradeDetector;
import dev.abu.screener_backend.marketdata.core.trade.TradeThresholds;
import dev.abu.screener_backend.marketdata.spi.BookSyncContext;
import dev.abu.screener_backend.marketdata.spi.DepthSyncStrategy;
import dev.abu.screener_backend.marketdata.spi.SyncStrategyRegistry;
import dev.abu.screener_backend.marketdata.spi.TradeDecoder;
import dev.abu.screener_backend.marketdata.spi.TradeDecoderRegistry;
import dev.abu.screener_backend.marketdata.spi.TradeSink;
import dev.abu.screener_backend.marketdata.spi.VenueStrategyBinding;
import dev.abu.screener_backend.marketdata.spi.VenueTradeDecoderBinding;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MarketEventHandlerTest {

    /** A Binance spot instrument: its venue has a trade decoder. */
    private static final int ID = 0;
    /** A Binance futures instrument: no decoder is bound for its venue in this test. */
    private static final int NO_DECODER_ID = 1;

    private final RecordingStrategy strategy = new RecordingStrategy();
    private final RecordingClassifier classifier = new RecordingClassifier();
    /** Decoder calls and frame-end calls, in the order they happened. */
    private final List<String> trace = new ArrayList<>();
    private final RecordingDecoder decoder = new RecordingDecoder(trace);
    private final PipelineMetrics metrics = new PipelineMetrics();
    private final RecordingDetector detector = new RecordingDetector(metrics, trace);
    private final MarketEvent event = new MarketEvent();
    private InstrumentSlotTable slots;
    private MarketEventHandler handler;

    @BeforeEach
    void setUp() {
        slots = new InstrumentSlotTable(
                new OrderbookProperties(0.1),
                new SyncStrategyRegistry(List.of(
                        new VenueStrategyBinding(Venue.BINANCE_SPOT, strategy),
                        new VenueStrategyBinding(Venue.BINANCE_FUTURES, strategy))),
                new TradeDecoderRegistry(List.of(new VenueTradeDecoderBinding(Venue.BINANCE_SPOT, decoder))));
        slots.allocate(Instrument.of(ID, Venue.BINANCE_SPOT, "BTCUSDT", "BTC", "USDT"));
        slots.allocate(Instrument.of(NO_DECODER_ID, Venue.BINANCE_FUTURES, "BTCUSDT", "BTC", "USDT"));
        slots.publish();
        handler = new MarketEventHandler(0, 1, slots, classifier, detector, metrics);
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
        assertTrue(decoder.calls.isEmpty(), "depth never reaches the trade decoder");
        assertEquals(1, handler.processed());

        assertCleared();
    }

    @Test
    @DisplayName("a throwing cluster detector does not escape onEvent: counted, its state discarded, the event cleared")
    void clusterFailureIsContained() {
        List<Integer> discarded = new ArrayList<>();
        OrderClusterDetector failing = new OrderClusterDetector(OrderClusterDetectorTest.props(), null, null, metrics, () -> 0L) {
            @Override
            public void process(Instrument inst, OrderBook ob) {
                throw new IllegalStateException("boom");
            }

            @Override
            public void discard(Instrument inst) {
                discarded.add(inst.id());
            }
        };
        MarketEventHandler withClusters = new MarketEventHandler(0, 1, slots, classifier, failing, detector, metrics);
        event.source = EventSource.WS_MSG;
        event.stream = StreamKind.DEPTH;
        event.instrumentId = ID;
        event.rawJson = "{}";

        withClusters.onEvent(event, 0, true);

        assertEquals(1, metrics.clusterFailures());
        assertEquals(List.of(ID), discarded);
        assertEquals(1, classifier.instruments.size(), "classification still ran");
        assertCleared();
    }

    @Test
    @DisplayName("a DEPTH event reaches the cluster detector after the classifier; a TRADE event does not")
    void depthEventReachesClusterDetectorAfterClassifier() {
        List<String> order = new ArrayList<>();
        OrderBookClassifier orderedClassifier = new OrderBookClassifier(null, null, Map.of()) {
            @Override
            public void process(Instrument inst, OrderBook ob) {
                order.add("classifier");
            }
        };
        OrderClusterDetector clusters = new OrderClusterDetector(OrderClusterDetectorTest.props(), null, null, metrics, () -> 0L) {
            @Override
            public void process(Instrument inst, OrderBook ob) {
                order.add("clusters:" + inst.id());
            }
        };
        MarketEventHandler withClusters = new MarketEventHandler(0, 1, slots, orderedClassifier, clusters, detector, metrics);

        event.source = EventSource.WS_MSG;
        event.stream = StreamKind.DEPTH;
        event.instrumentId = ID;
        event.rawJson = "{}";
        withClusters.onEvent(event, 0, true);
        tradeEvent(ID, "{\"e\":\"aggTrade\"}");
        withClusters.onEvent(event, 1, true);

        assertEquals(List.of("classifier", "clusters:" + ID), order);
    }

    @Test
    @DisplayName("a TRADE event reaches the slot's decoder with the shared detector, not the sync strategy or the classifier")
    void tradeEventReachesDecoder() {
        String frame = "{\"e\":\"aggTrade\"}";
        tradeEvent(ID, frame);

        handler.onEvent(event, 0, true);

        assertEquals(1, decoder.calls.size());
        DecodeCall call = decoder.calls.get(0);
        assertSame(slots.get(ID), call.slot());
        assertSame(frame, call.rawJson());
        assertSame(detector, call.sink());
        assertTrue(strategy.calls.isEmpty(), "trades never touch the book");
        assertTrue(classifier.instruments.isEmpty());
        assertEquals(0, metrics.tradeParseFailures(Venue.BINANCE_SPOT));
        assertEquals(0, handler.unhandledKinds());
        assertEquals(1, handler.processed());

        assertCleared();
    }

    @Test
    @DisplayName("a text TRADE event takes the String overload, a binary one the ByteBuffer overload; the frame end follows each decode")
    void payloadPicksOverloadThenFrameEnd() {
        tradeEvent(ID, "{\"e\":\"aggTrade\"}");
        handler.onEvent(event, 0, true);

        ByteBuffer frame = ByteBuffer.wrap("binary".getBytes(StandardCharsets.UTF_8));
        event.source = EventSource.WS_MSG;
        event.stream = StreamKind.TRADE;
        event.instrumentId = ID;
        event.rawBytes = frame;
        handler.onEvent(event, 1, true);

        assertEquals(List.of("decode text", "frame end", "decode binary", "frame end"), trace);
        assertSame(frame, decoder.calls.get(1).rawBytes(), "the routed buffer itself, not a copy");
        assertNull(event.rawBytes, "cleared");
    }

    @Test
    @DisplayName("a TRADE frame the decoder rejects is counted as a parse failure for its venue, and still ends the frame")
    void tradeParseFailureIsCounted() {
        decoder.result = false;
        tradeEvent(ID, "garbage");

        handler.onEvent(event, 0, true);

        assertEquals(1, metrics.tradeParseFailures(Venue.BINANCE_SPOT));
        assertEquals(0, metrics.tradeParseFailures(Venue.BINANCE_FUTURES));
        assertEquals(0, handler.unhandledKinds());
        assertEquals(List.of("decode text", "frame end"), trace);
        assertCleared();
    }

    @Test
    @DisplayName("a TRADE event for a venue without a decoder is counted as unhandled and cleared")
    void tradeWithoutDecoderIsUnhandled() {
        tradeEvent(NO_DECODER_ID, "{\"e\":\"aggTrade\"}");

        handler.onEvent(event, 0, true);

        assertTrue(decoder.calls.isEmpty());
        assertTrue(strategy.calls.isEmpty());
        assertTrue(trace.isEmpty(), "no decode, so no frame end");
        assertEquals(1, handler.unhandledKinds());
        assertEquals(0, metrics.tradeParseFailures(Venue.BINANCE_FUTURES), "no decoder is not a parse failure");
        assertEquals(1, handler.processed());
        assertCleared();
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

    @Test
    @DisplayName("a TICK visits exactly this shard's slots, without the slot lookup, and is not counted as processed")
    void tickVisitsOwnShard() {
        slots.allocate(Instrument.of(2, Venue.BINANCE_SPOT, "ETHUSDT", "ETH", "USDT"));
        slots.allocate(Instrument.of(3, Venue.BINANCE_SPOT, "SOLUSDT", "SOL", "USDT"));
        slots.publish();
        MarketEventHandler shard1 = new MarketEventHandler(1, 2, slots, classifier, detector, metrics);
        event.source = EventSource.TICK;
        event.instrumentId = -1;

        shard1.onEvent(event, 0, true);

        assertEquals(List.of("idle 1", "idle 3"), trace, "ids 1 and 3 belong to shard 1 of 2");
        assertTrue(strategy.calls.isEmpty());
        assertTrue(decoder.calls.isEmpty());
        assertTrue(classifier.instruments.isEmpty());
        assertEquals(0, shard1.processed());
        assertEquals(0, shard1.unhandledKinds(), "no stream kind, and none expected");
        assertCleared();
    }

    private void tradeEvent(int instrumentId, String rawJson) {
        event.source = EventSource.WS_MSG;
        event.stream = StreamKind.TRADE;
        event.instrumentId = instrumentId;
        event.rawJson = rawJson;
    }

    private void assertCleared() {
        assertNull(event.source);
        assertNull(event.stream);
        assertNull(event.rawJson);
        assertEquals(-1, event.instrumentId);
    }

    // --- Fakes ---------------------------------------------------------------------------------

    private record Call(InstrumentSlot slot, EventSource source, StreamKind stream) { }

    private record DecodeCall(InstrumentSlot slot, String rawJson, ByteBuffer rawBytes, TradeSink sink) { }

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

    private static final class RecordingDecoder implements TradeDecoder {
        final List<DecodeCall> calls = new ArrayList<>();
        final List<String> trace;
        boolean result = true;

        RecordingDecoder(List<String> trace) {
            this.trace = trace;
        }

        @Override
        public boolean decode(InstrumentSlot slot, String rawJson, TradeSink sink) {
            calls.add(new DecodeCall(slot, rawJson, null, sink));
            trace.add("decode text");
            return result;
        }

        @Override
        public boolean decode(InstrumentSlot slot, ByteBuffer frame, TradeSink sink) {
            calls.add(new DecodeCall(slot, null, frame, sink));
            trace.add("decode binary");
            return result;
        }
    }

    private static final class RecordingDetector extends TradeDetector {
        final List<String> trace;

        RecordingDetector(PipelineMetrics metrics, List<String> trace) {
            this(metrics, new TradeAlertProperties(1, null, null, 10, 10, null, 250), trace);
        }

        private RecordingDetector(PipelineMetrics metrics, TradeAlertProperties props, List<String> trace) {
            super(metrics, props, new TradeThresholds(props), new TradeAlertStore(props));
            this.trace = trace;
        }

        @Override
        public void onFrameEnd(InstrumentSlot slot) {
            trace.add("frame end");
            super.onFrameEnd(slot);
        }

        @Override
        public void closeIfIdle(InstrumentSlot slot) {
            trace.add("idle " + slot.instrument().id());
            super.closeIfIdle(slot);
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
