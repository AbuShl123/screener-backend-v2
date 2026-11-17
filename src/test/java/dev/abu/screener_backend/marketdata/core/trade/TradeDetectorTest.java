package dev.abu.screener_backend.marketdata.core.trade;

import dev.abu.screener_backend.config.TradeAlertProperties;
import dev.abu.screener_backend.marketdata.Instrument;
import dev.abu.screener_backend.marketdata.Venue;
import dev.abu.screener_backend.marketdata.core.health.PipelineMetrics;
import dev.abu.screener_backend.marketdata.core.slot.InstrumentSlot;
import dev.abu.screener_backend.marketdata.spi.TradeDecoder;
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
    private static final long T = 1_794_860_803_450L;
    private static final TradeDecoder NO_DECODER = (slot, rawJson, sink) -> true;

    private final PipelineMetrics metrics = new PipelineMetrics();
    private final TradeAlertProperties props = new TradeAlertProperties(MIN,
            Map.of(Venue.BINANCE_FUTURES, Map.of("ETHUSDT", 50_000.0)), 100);
    private final TradeAlertStore store = new TradeAlertStore(props);
    private final TradeDetector detector = new TradeDetector(metrics, new TradeThresholds(props), store);

    private final InstrumentSlot spot = slot(0, Venue.BINANCE_SPOT, "BTCUSDT");
    private final InstrumentSlot futures = slot(1, Venue.BINANCE_FUTURES, "BTCUSDT");

    // ---- counting ----

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

    // ---- one fill ----

    @Test
    @DisplayName("a large trade is judged only once the instrument's next trade closes its sweep")
    void judgedOnClose() {
        detector.onTrade(spot, 100, 200, true, T, 10, 10);
        assertTrue(store.recent(null, null, 10).isEmpty(), "still open");

        closeWith(spot);
        assertEquals(1, only().fills());
    }

    @Test
    @DisplayName("another instrument's trade does not close the sweep")
    void otherInstrumentDoesNotClose() {
        detector.onTrade(spot, 100, 200, true, T, 10, 10);
        closeWith(futures);

        assertTrue(store.recent(null, null, 10).isEmpty());
    }

    @Test
    @DisplayName("a sweep below the minimum notional raises no alert")
    void belowThreshold() {
        detector.onTrade(spot, 100, 99.99, true, T, 10, 10);
        closeWith(spot);

        assertTrue(store.recent(null, null, 10).isEmpty());
        assertEquals(0, metrics.tradeAlerts(Venue.BINANCE_SPOT));
    }

    @Test
    @DisplayName("a sweep exactly at the minimum notional alerts")
    void atThreshold() {
        detector.onTrade(spot, 100, 100, true, T, 10, 10);
        closeWith(spot);

        assertEquals(1, store.recent(null, null, 10).size());
        assertEquals(1, metrics.tradeAlerts(Venue.BINANCE_SPOT));
    }

    @Test
    @DisplayName("a one-fill alert carries the trade: side, price, qty, notional, ids, times, id")
    void oneFillCarriesTrade() {
        long before = System.currentTimeMillis();
        detector.onTrade(futures, 65_000, 0.5, false, T, 8_285_985_083L, 8_285_985_090L);
        closeWith(futures);
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
        assertFalse(a.coalescedOnly(), "one fill can never be coalescing-only");
        assertEquals(8_285_985_083L, a.firstTradeId());
        assertEquals(8_285_985_090L, a.lastTradeId());
        assertEquals(T, a.tradeTimeMs());
        assertTrue(a.detectedAtMs() >= before && a.detectedAtMs() <= after);
        assertEquals("BINANCE_FUTURES:BTCUSDT:8285985083", a.id());
        assertEquals(1, metrics.tradeAlerts(Venue.BINANCE_FUTURES));
        assertEquals(0, metrics.tradeAlerts(Venue.BINANCE_SPOT));
    }

    @Test
    @DisplayName("a per-symbol override replaces the default for that instrument only")
    void override() {
        InstrumentSlot ethFutures = slot(2, Venue.BINANCE_FUTURES, "ETHUSDT");
        InstrumentSlot ethSpot = slot(3, Venue.BINANCE_SPOT, "ETHUSDT");

        detector.onTrade(ethFutures, 3_000, 10, true, T, 1, 1);   // 30K < 50K override
        detector.onTrade(ethSpot, 3_000, 10, true, T, 1, 1);      // 30K ≥ 10K default
        closeWith(ethFutures);
        closeWith(ethSpot);

        List<TradeAlert> alerts = store.recent(null, null, 10);
        assertEquals(1, alerts.size());
        assertSame(ethSpot.instrument(), alerts.getFirst().instrument());
    }

    // ---- sweeps ----

    @Test
    @DisplayName("ten contiguous fills walking up, each below the minimum, alert once as one sweep")
    void tenFillBuySweep() {
        for (int i = 0; i < 10; i++) {
            detector.onTrade(spot, 100 + i, 10, true, T, 100 + i, 100 + i);   // 1,000–1,090 each
        }
        assertTrue(store.recent(null, null, 10).isEmpty(), "still open");
        closeWith(spot);

        TradeAlert a = only();
        assertTrue(a.aggressorBuy());
        assertEquals(10, a.fills());
        assertEquals(100, a.qty(), 1e-9);
        assertEquals(10_450, a.notional(), 1e-9);
        assertEquals(104.5, a.vwap(), 1e-9);
        assertEquals(100, a.firstPrice());
        assertEquals(109, a.lastPrice());
        assertEquals(100, a.firstTradeId());
        assertEquals(109, a.lastTradeId());
        assertEquals(T, a.tradeTimeMs());
        assertTrue(a.coalescedOnly(), "no single fill reached 10K");
        assertEquals("BINANCE_SPOT:BTCUSDT:100", a.id());
    }

    @Test
    @DisplayName("a sell sweep walks down")
    void sellSweep() {
        detector.onTrade(spot, 100, 60, false, T, 1, 1);
        detector.onTrade(spot, 99, 60, false, T, 2, 2);
        closeWith(spot);

        TradeAlert a = only();
        assertFalse(a.aggressorBuy());
        assertEquals(2, a.fills());
        assertEquals(11_940, a.notional(), 1e-9);
    }

    @Test
    @DisplayName("fills summing below the minimum raise no alert")
    void sweepBelowThreshold() {
        for (int i = 0; i < 10; i++) {
            detector.onTrade(spot, 100 + i, 9, true, T, 100 + i, 100 + i);   // 9,405 in all
        }
        closeWith(spot);

        assertTrue(store.recent(null, null, 10).isEmpty());
    }

    @Test
    @DisplayName("a sweep with one fill at the minimum on its own is not coalescing-only")
    void notCoalescedOnly() {
        detector.onTrade(spot, 100, 100, true, T, 1, 1);    // 10,000 alone
        detector.onTrade(spot, 101, 10, true, T, 2, 2);
        closeWith(spot);

        TradeAlert a = only();
        assertEquals(2, a.fills());
        assertFalse(a.coalescedOnly());
    }

    // Each case: two 6K fills (12K together, 6K apart against a 10K minimum). One alert if the
    // second continues the first, none if it opens a sweep of its own.

    @Test
    @DisplayName("control: two fills that continue alert together")
    void controlContinues() {
        detector.onTrade(spot, 100, 60, true, T, 1, 1);
        detector.onTrade(spot, 101, 60, true, T, 2, 2);
        closeWith(spot);

        assertEquals(2, only().fills());
    }

    @Test
    @DisplayName("a side change closes the sweep")
    void sideChangeCloses() {
        detector.onTrade(spot, 100, 60, true, T, 1, 1);
        detector.onTrade(spot, 99, 60, false, T, 2, 2);
        assertSplit();
    }

    @Test
    @DisplayName("a trade-time change closes the sweep")
    void timeChangeCloses() {
        detector.onTrade(spot, 100, 60, true, T, 1, 1);
        detector.onTrade(spot, 101, 60, true, T + 1, 2, 2);
        assertSplit();
    }

    @Test
    @DisplayName("a gap in trade ids closes the sweep")
    void idGapCloses() {
        detector.onTrade(spot, 100, 60, true, T, 1, 1);
        detector.onTrade(spot, 101, 60, true, T, 3, 3);
        assertSplit();
    }

    @Test
    @DisplayName("an equal price closes the sweep: a second order in the same millisecond")
    void equalPriceCloses() {
        detector.onTrade(spot, 100, 60, true, T, 1, 1);
        detector.onTrade(spot, 100, 60, true, T, 2, 2);
        assertSplit();
    }

    @Test
    @DisplayName("a price moving against the aggressor closes the sweep")
    void reversalCloses() {
        detector.onTrade(spot, 100, 60, true, T, 1, 1);
        detector.onTrade(spot, 99, 60, true, T, 2, 2);
        assertSplit();
    }

    @Test
    @DisplayName("an aggTrade spanning several ids continues when the next starts right after its last")
    void multiIdFill() {
        detector.onTrade(spot, 100, 60, true, T, 1, 5);
        detector.onTrade(spot, 101, 60, true, T, 6, 9);
        closeWith(spot);

        TradeAlert a = only();
        assertEquals(1, a.firstTradeId());
        assertEquals(9, a.lastTradeId());
    }

    @Test
    @DisplayName("without trade ids, side, time and price decide alone")
    void noIds() {
        detector.onTrade(spot, 100, 60, true, T, -1, -1);
        detector.onTrade(spot, 101, 60, true, T, -1, -1);
        detector.onTrade(spot, 101, 60, true, T, -1, -1);   // equal price: closes, 6K alone
        closeWith(spot);

        TradeAlert a = only();
        assertEquals(2, a.fills());
        assertEquals(-1, a.firstTradeId());
        assertEquals("BINANCE_SPOT:BTCUSDT:t" + T + "B", a.id());
    }

    @Test
    @DisplayName("the closing trade opens the next sweep, which is judged in turn")
    void closerOpensNext() {
        detector.onTrade(spot, 100, 200, true, T, 1, 1);
        detector.onTrade(spot, 100, 300, false, T + 5, 2, 2);
        detector.onTrade(spot, 100, 1, true, T + 9, 3, 3);

        List<TradeAlert> alerts = store.recent(null, null, 10);
        assertEquals(2, alerts.size());
        assertFalse(alerts.get(0).aggressorBuy(), "newest first");
        assertEquals(30_000, alerts.get(0).notional(), 1e-9);
        assertEquals(20_000, alerts.get(1).notional(), 1e-9);
    }

    @Test
    @DisplayName("alerts are counted by fills per sweep and as coalescing-only")
    void alertMetrics() {
        detector.onTrade(spot, 100, 200, true, T, 1, 1);                    // 1 fill
        for (int i = 0; i < 4; i++) detector.onTrade(spot, 100 + i, 30, true, T + 1, 2 + i, 2 + i);  // 4 fills
        closeWith(spot);

        assertEquals(2, metrics.tradeAlerts(Venue.BINANCE_SPOT));
        assertEquals(1, metrics.tradeAlertsCoalescedOnly(Venue.BINANCE_SPOT));
        assertEquals(1, metrics.tradeAlertFills(Venue.BINANCE_SPOT, 0));
        assertEquals(0, metrics.tradeAlertFills(Venue.BINANCE_SPOT, 1));
        assertEquals(1, metrics.tradeAlertFills(Venue.BINANCE_SPOT, 2));
        assertEquals(0, metrics.tradeAlertFills(Venue.BINANCE_SPOT, 3));
        assertEquals(0, metrics.tradeAlerts(Venue.BINANCE_FUTURES));
    }

    // ---- helpers ----

    /** The two 6K fills were judged apart: no alert. */
    private void assertSplit() {
        closeWith(spot);
        assertTrue(store.recent(null, null, 10).isEmpty(), () -> store.recent(null, null, 10).toString());
    }

    /** A tiny trade much later, on its own id range: closes whatever sweep is open, alerts on nothing. */
    private void closeWith(InstrumentSlot slot) {
        detector.onTrade(slot, 1, 0.001, true, T + 60_000, 1_000_000_000L, 1_000_000_000L);
    }

    private TradeAlert only() {
        List<TradeAlert> alerts = store.recent(null, null, 10);
        assertEquals(1, alerts.size(), alerts.toString());
        return alerts.getFirst();
    }

    private static InstrumentSlot slot(int id, Venue venue, String symbol) {
        return new InstrumentSlot(Instrument.of(id, venue, symbol, symbol.replace("USDT", ""), "USDT"),
                null, null, null, NO_DECODER);
    }
}
