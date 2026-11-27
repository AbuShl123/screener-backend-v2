package dev.abu.screener_backend.marketdata.core.ingress;

import dev.abu.screener_backend.config.OrderbookProperties;
import dev.abu.screener_backend.config.SpikeAlertProperties;
import dev.abu.screener_backend.marketdata.Instrument;
import dev.abu.screener_backend.marketdata.StreamKind;
import dev.abu.screener_backend.marketdata.Venue;
import dev.abu.screener_backend.marketdata.adapter.binance.BinanceKlineDecoder;
import dev.abu.screener_backend.marketdata.core.health.PipelineMetrics;
import dev.abu.screener_backend.marketdata.core.slot.InstrumentSlot;
import dev.abu.screener_backend.marketdata.core.slot.InstrumentSlotTable;
import dev.abu.screener_backend.marketdata.core.spike.SpikeAlertStore;
import dev.abu.screener_backend.marketdata.core.spike.SpikeDetector;
import dev.abu.screener_backend.marketdata.spi.BookSyncContext;
import dev.abu.screener_backend.marketdata.spi.DepthSyncStrategy;
import dev.abu.screener_backend.marketdata.spi.KlineDecoderRegistry;
import dev.abu.screener_backend.marketdata.spi.SyncStrategyRegistry;
import dev.abu.screener_backend.marketdata.spi.TradeDecoderRegistry;
import dev.abu.screener_backend.marketdata.spi.VenueKlineDecoderBinding;
import dev.abu.screener_backend.marketdata.spi.VenueStrategyBinding;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/** The handler's KLINE arm, wired to the real Binance decoder and the real spike detector. */
class MarketEventHandlerKlineTest {

    private static final int FUT = 0;
    private static final int SPOT = 1;
    private static final String FRAME = "{\"e\":\"kline\",\"E\":1795689455628,\"s\":\"BTCUSDT\",\"k\":{\"t\":1795689420000,"
            + "\"o\":\"1\",\"c\":\"60659.80\",\"h\":\"60760.00\",\"l\":\"60654.40\",\"q\":\"18359538.7\",\"x\":false}}";

    private final PipelineMetrics metrics = new PipelineMetrics();
    private final SpikeAlertProperties props = new SpikeAlertProperties(true, true, 10, 10,
            new SpikeAlertProperties.Price(5, 5, 1), new SpikeAlertProperties.Volume(5, 200, 70_000));
    private final SpikeDetector spikes = new SpikeDetector(props, new SpikeAlertStore(props), metrics);
    private final MarketEvent event = new MarketEvent();
    private InstrumentSlotTable slots;
    private MarketEventHandler handler;

    @BeforeEach
    void setUp() {
        DepthSyncStrategy noSync = new DepthSyncStrategy() {
            @Override public BookSyncContext newContext() { return null; }
            @Override public void onEvent(InstrumentSlot slot, MarketEvent e) { }
        };
        slots = new InstrumentSlotTable(new OrderbookProperties(0.1),
                new SyncStrategyRegistry(List.of(
                        new VenueStrategyBinding(Venue.BINANCE_FUTURES, noSync),
                        new VenueStrategyBinding(Venue.BINANCE_SPOT, noSync))),
                new TradeDecoderRegistry(List.of()),
                new KlineDecoderRegistry(List.of(
                        new VenueKlineDecoderBinding(Venue.BINANCE_FUTURES, new BinanceKlineDecoder()))),
                props);
        slots.allocate(Instrument.of(FUT, Venue.BINANCE_FUTURES, "BTCUSDT", "BTC", "USDT"));
        slots.allocate(Instrument.of(SPOT, Venue.BINANCE_SPOT, "BTCUSDT", "BTC", "USDT"));
        slots.publish();
        handler = new MarketEventHandler(0, 1, slots, null, null, null, spikes, metrics);
    }

    private void kline(int id, String json) {
        event.source = EventSource.WS_MSG;
        event.stream = StreamKind.KLINE;
        event.instrumentId = id;
        event.rawJson = json;
    }

    @Test
    @DisplayName("a KLINE frame is decoded into the slot's candle store and the event is cleared")
    void klineReachesCandles() {
        kline(FUT, FRAME);
        handler.onEvent(event, 0, true);

        InstrumentSlot slot = slots.get(FUT);
        assertEquals(1795689420000L, slot.candles().formingOpenTime());
        assertEquals(60659.80, slot.candles().formingClose());
        assertEquals(props.candleRingSize(), slot.candles().capacity());
        assertEquals(1, metrics.klines(Venue.BINANCE_FUTURES));
        assertEquals(0, handler.unhandledKinds());
        assertNull(event.rawJson);
    }

    @Test
    @DisplayName("a KLINE frame the decoder rejects is counted as a parse failure for its venue")
    void parseFailureCounted() {
        kline(FUT, "{\"e\":\"kline\"}");
        handler.onEvent(event, 0, true);

        assertEquals(1, metrics.klineParseFailures(Venue.BINANCE_FUTURES));
        assertEquals(0, metrics.klines(Venue.BINANCE_FUTURES));
    }

    @Test
    @DisplayName("a KLINE event for a venue without a decoder is counted as unhandled; its slot has no candles")
    void venueWithoutDecoder() {
        kline(SPOT, FRAME);
        handler.onEvent(event, 0, true);

        assertNull(slots.get(SPOT).candles());
        assertEquals(1, handler.unhandledKinds());
    }
}
