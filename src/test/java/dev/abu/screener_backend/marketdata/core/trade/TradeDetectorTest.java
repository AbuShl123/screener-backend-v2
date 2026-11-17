package dev.abu.screener_backend.marketdata.core.trade;

import dev.abu.screener_backend.config.TradeAlertProperties;
import dev.abu.screener_backend.marketdata.Instrument;
import dev.abu.screener_backend.marketdata.Venue;
import dev.abu.screener_backend.marketdata.core.health.PipelineMetrics;
import dev.abu.screener_backend.marketdata.core.slot.InstrumentSlot;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TradeDetectorTest {

    private static final double MIN = 10_000;

    private final PipelineMetrics metrics = new PipelineMetrics();
    private final TradeAlertProperties props = new TradeAlertProperties(MIN,
            Map.of(Venue.BINANCE_FUTURES, Map.of("ETHUSDT", 50_000.0)), 100);
    private final TradeAlertStore store = new TradeAlertStore(props);
    private final TradeDetector detector = new TradeDetector(metrics, new TradeThresholds(props), store);

    private final InstrumentSlot spot = slot(0, Venue.BINANCE_SPOT, "BTCUSDT");
    private final InstrumentSlot futures = slot(1, Venue.BINANCE_FUTURES, "BTCUSDT");

    @Test
    @DisplayName("every trade is counted as decoded for its instrument's venue")
    void countsDecodedPerVenue() {
        detector.onTrade(spot, 100, 1, true, 1L, 10, 10);
        detector.onTrade(spot, 100, 1, false, 2L, 11, 11);
        detector.onTrade(futures, 100, 1, true, 3L, -1, -1);

        assertEquals(2, metrics.tradesDecoded(Venue.BINANCE_SPOT));
        assertEquals(1, metrics.tradesDecoded(Venue.BINANCE_FUTURES));
        assertEquals(0, metrics.tradesDecoded(Venue.BYBIT_SPOT));
    }

    @Test
    @DisplayName("a trade below the minimum notional raises no alert")
    void belowThreshold() {
        detector.onTrade(spot, 100, 99.99, true, 1L, 10, 10);

        assertTrue(store.recent(null, null, 10).isEmpty());
        assertEquals(0, metrics.tradeAlerts(Venue.BINANCE_SPOT));
        assertEquals(1, metrics.tradesDecoded(Venue.BINANCE_SPOT), "still counted as decoded");
    }

    @Test
    @DisplayName("a trade exactly at the minimum notional alerts")
    void atThreshold() {
        detector.onTrade(spot, 100, 100, true, 1L, 10, 10);

        assertEquals(1, store.recent(null, null, 10).size());
        assertEquals(1, metrics.tradeAlerts(Venue.BINANCE_SPOT));
    }

    @Test
    @DisplayName("an alert carries the trade: side, price, qty, notional, ids, times, one fill")
    void aboveThresholdCarriesTrade() {
        long before = System.currentTimeMillis();
        detector.onTrade(futures, 65_000, 0.5, false, 1_794_860_803_450L, 8_285_985_083L, 8_285_985_090L);
        long after = System.currentTimeMillis();

        TradeAlert a = only();
        assertSame(futures.instrument(), a.instrument());
        assertFalse(a.aggressorBuy());
        assertEquals(65_000, a.vwap());
        assertEquals(65_000, a.firstPrice());
        assertEquals(65_000, a.lastPrice());
        assertEquals(0.5, a.qty());
        assertEquals(32_500, a.notional());
        assertEquals(1, a.fills());
        assertEquals(8_285_985_083L, a.firstTradeId());
        assertEquals(8_285_985_090L, a.lastTradeId());
        assertEquals(1_794_860_803_450L, a.tradeTimeMs());
        assertTrue(a.detectedAtMs() >= before && a.detectedAtMs() <= after);
        assertEquals(1, metrics.tradeAlerts(Venue.BINANCE_FUTURES));
        assertEquals(0, metrics.tradeAlerts(Venue.BINANCE_SPOT));
    }

    @Test
    @DisplayName("a taker buy alerts as a buy")
    void buySide() {
        detector.onTrade(spot, 100, 200, true, 1L, 10, 10);

        assertTrue(only().aggressorBuy());
    }

    @Test
    @DisplayName("a per-symbol override replaces the default for that instrument only")
    void override() {
        InstrumentSlot ethFutures = slot(2, Venue.BINANCE_FUTURES, "ETHUSDT");
        InstrumentSlot ethSpot = slot(3, Venue.BINANCE_SPOT, "ETHUSDT");

        detector.onTrade(ethFutures, 3_000, 10, true, 1L, 1, 1);   // 30K < 50K override
        detector.onTrade(ethSpot, 3_000, 10, true, 1L, 1, 1);      // 30K ≥ 10K default

        List<TradeAlert> alerts = store.recent(null, null, 10);
        assertEquals(1, alerts.size());
        assertSame(ethSpot.instrument(), alerts.getFirst().instrument());
    }

    private TradeAlert only() {
        List<TradeAlert> alerts = store.recent(null, null, 10);
        assertEquals(1, alerts.size(), alerts.toString());
        return alerts.getFirst();
    }

    private static InstrumentSlot slot(int id, Venue venue, String symbol) {
        return new InstrumentSlot(Instrument.of(id, venue, symbol, symbol.replace("USDT", ""), "USDT"),
                null, null, null);
    }
}
