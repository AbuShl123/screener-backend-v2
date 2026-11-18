package dev.abu.screener_backend.marketdata.core.trade;

import dev.abu.screener_backend.config.TradeAlertProperties;
import dev.abu.screener_backend.marketdata.Instrument;
import dev.abu.screener_backend.marketdata.Venue;
import dev.abu.screener_backend.marketdata.core.health.PipelineMetrics;
import dev.abu.screener_backend.marketdata.core.slot.InstrumentSlot;
import dev.abu.screener_backend.marketdata.spi.TradeDecoder;
import dev.abu.screener_backend.marketdata.spi.TradeSink;
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
    /** MEXC spot's flags: one fill per resting order, sweeps close at the end of the frame. */
    private static final TradeDecoder SPOT_LIKE = new FlagDecoder(true, true);
    /** MEXC futures' flags: one fill per resting order, sweeps close on the next trade. */
    private static final TradeDecoder FUTURES_LIKE = new FlagDecoder(true, false);

    private final PipelineMetrics metrics = new PipelineMetrics();
    private final TradeAlertProperties props = new TradeAlertProperties(MIN,
            Map.of(Venue.BINANCE_FUTURES, Map.of("ETHUSDT", 50_000.0)), 100, 100);
    private final TradeAlertStore store = new TradeAlertStore(props);
    private final TradeDetector detector = new TradeDetector(metrics, new TradeThresholds(props), store);

    private final InstrumentSlot spot = slot(0, Venue.BINANCE_SPOT, "BTCUSDT");
    private final InstrumentSlot futures = slot(1, Venue.BINANCE_FUTURES, "BTCUSDT");
    private final InstrumentSlot mexcSpot = slot(10, Venue.MEXC_SPOT, "BTCUSDT", SPOT_LIKE);
    private final InstrumentSlot mexcFutures = slot(11, Venue.MEXC_FUTURES, "BTC_USDT", FUTURES_LIKE);

    // ---- counting ----

    @Test
    @DisplayName("every trade is counted as decoded for its instrument's venue")
    void countsDecodedPerVenue() {
        detector.onTrade(spot, 100, 1, true, 1L, 10, 10, -1);
        detector.onTrade(spot, 100, 1, false, 2L, 11, 11, -1);
        detector.onTrade(futures, 100, 1, true, 3L, -1, -1, -1);

        assertEquals(2, metrics.tradesDecoded(Venue.BINANCE_SPOT));
        assertEquals(1, metrics.tradesDecoded(Venue.BINANCE_FUTURES));
        assertEquals(0, metrics.tradesDecoded(Venue.BYBIT_SPOT));
    }

    // ---- one fill ----

    @Test
    @DisplayName("a large trade is judged only once the instrument's next trade closes its sweep")
    void judgedOnClose() {
        detector.onTrade(spot, 100, 200, true, T, 10, 10, -1);
        assertTrue(store.recent(null, null, 10).isEmpty(), "still open");

        closeWith(spot);
        assertEquals(1, only().fills());
    }

    @Test
    @DisplayName("another instrument's trade does not close the sweep")
    void otherInstrumentDoesNotClose() {
        detector.onTrade(spot, 100, 200, true, T, 10, 10, -1);
        closeWith(futures);

        assertTrue(store.recent(null, null, 10).isEmpty());
    }

    @Test
    @DisplayName("a sweep below the minimum notional raises no alert")
    void belowThreshold() {
        detector.onTrade(spot, 100, 99.99, true, T, 10, 10, -1);
        closeWith(spot);

        assertTrue(store.recent(null, null, 10).isEmpty());
        assertEquals(0, metrics.tradeAlerts(Venue.BINANCE_SPOT));
    }

    @Test
    @DisplayName("a sweep exactly at the minimum notional alerts")
    void atThreshold() {
        detector.onTrade(spot, 100, 100, true, T, 10, 10, -1);
        closeWith(spot);

        assertEquals(1, store.recent(null, null, 10).size());
        assertEquals(1, metrics.tradeAlerts(Venue.BINANCE_SPOT));
    }

    @Test
    @DisplayName("a one-fill alert carries the trade: side, price, qty, notional, ids, times, id")
    void oneFillCarriesTrade() {
        long before = System.currentTimeMillis();
        detector.onTrade(futures, 65_000, 0.5, false, T, 8_285_985_083L, 8_285_985_090L, -1);
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
        assertEquals(MIN, a.minNotional(), "the default threshold it was judged against");
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

        detector.onTrade(ethFutures, 3_000, 10, true, T, 1, 1, -1);   // 30K < 50K override
        detector.onTrade(ethSpot, 3_000, 10, true, T, 1, 1, -1);      // 30K ≥ 10K default
        closeWith(ethFutures);
        closeWith(ethSpot);

        List<TradeAlert> alerts = store.recent(null, null, 10);
        assertEquals(1, alerts.size());
        assertSame(ethSpot.instrument(), alerts.getFirst().instrument());
        assertEquals(MIN, alerts.getFirst().minNotional());

        detector.onTrade(ethFutures, 3_000, 20, true, T, 2, 2, -1);   // 60K ≥ 50K override
        closeWith(ethFutures);
        TradeAlert overridden = store.recent(null, null, 1).getFirst();
        assertSame(ethFutures.instrument(), overridden.instrument());
        assertEquals(50_000, overridden.minNotional(), "carries the override it was judged against");
    }

    // ---- sweeps ----

    @Test
    @DisplayName("ten contiguous fills walking up, each below the minimum, alert once as one sweep")
    void tenFillBuySweep() {
        for (int i = 0; i < 10; i++) {
            detector.onTrade(spot, 100 + i, 10, true, T, 100 + i, 100 + i, -1);   // 1,000–1,090 each
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
        detector.onTrade(spot, 100, 60, false, T, 1, 1, -1);
        detector.onTrade(spot, 99, 60, false, T, 2, 2, -1);
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
            detector.onTrade(spot, 100 + i, 9, true, T, 100 + i, 100 + i, -1);   // 9,405 in all
        }
        closeWith(spot);

        assertTrue(store.recent(null, null, 10).isEmpty());
    }

    @Test
    @DisplayName("a sweep with one fill at the minimum on its own is not coalescing-only")
    void notCoalescedOnly() {
        detector.onTrade(spot, 100, 100, true, T, 1, 1, -1);    // 10,000 alone
        detector.onTrade(spot, 101, 10, true, T, 2, 2, -1);
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
        detector.onTrade(spot, 100, 60, true, T, 1, 1, -1);
        detector.onTrade(spot, 101, 60, true, T, 2, 2, -1);
        closeWith(spot);

        assertEquals(2, only().fills());
    }

    @Test
    @DisplayName("a side change closes the sweep")
    void sideChangeCloses() {
        detector.onTrade(spot, 100, 60, true, T, 1, 1, -1);
        detector.onTrade(spot, 99, 60, false, T, 2, 2, -1);
        assertSplit();
    }

    @Test
    @DisplayName("a trade-time change closes the sweep")
    void timeChangeCloses() {
        detector.onTrade(spot, 100, 60, true, T, 1, 1, -1);
        detector.onTrade(spot, 101, 60, true, T + 1, 2, 2, -1);
        assertSplit();
    }

    @Test
    @DisplayName("a gap in trade ids closes the sweep")
    void idGapCloses() {
        detector.onTrade(spot, 100, 60, true, T, 1, 1, -1);
        detector.onTrade(spot, 101, 60, true, T, 3, 3, -1);
        assertSplit();
    }

    @Test
    @DisplayName("an equal price closes the sweep: a second order in the same millisecond")
    void equalPriceCloses() {
        detector.onTrade(spot, 100, 60, true, T, 1, 1, -1);
        detector.onTrade(spot, 100, 60, true, T, 2, 2, -1);
        assertSplit();
    }

    @Test
    @DisplayName("a price moving against the aggressor closes the sweep")
    void reversalCloses() {
        detector.onTrade(spot, 100, 60, true, T, 1, 1, -1);
        detector.onTrade(spot, 99, 60, true, T, 2, 2, -1);
        assertSplit();
    }

    @Test
    @DisplayName("an aggTrade spanning several ids continues when the next starts right after its last")
    void multiIdFill() {
        detector.onTrade(spot, 100, 60, true, T, 1, 5, -1);
        detector.onTrade(spot, 101, 60, true, T, 6, 9, -1);
        closeWith(spot);

        TradeAlert a = only();
        assertEquals(1, a.firstTradeId());
        assertEquals(9, a.lastTradeId());
    }

    @Test
    @DisplayName("without trade ids, side, time and price decide alone")
    void noIds() {
        detector.onTrade(spot, 100, 60, true, T, -1, -1, -1);
        detector.onTrade(spot, 101, 60, true, T, -1, -1, -1);
        detector.onTrade(spot, 101, 60, true, T, -1, -1, -1);   // equal price: closes, 6K alone
        closeWith(spot);

        TradeAlert a = only();
        assertEquals(2, a.fills());
        assertEquals(-1, a.firstTradeId());
        assertEquals("BINANCE_SPOT:BTCUSDT:t" + T + "B", a.id());
    }

    @Test
    @DisplayName("the closing trade opens the next sweep, which is judged in turn")
    void closerOpensNext() {
        detector.onTrade(spot, 100, 200, true, T, 1, 1, -1);
        detector.onTrade(spot, 100, 300, false, T + 5, 2, 2, -1);
        detector.onTrade(spot, 100, 1, true, T + 9, 3, 3, -1);

        List<TradeAlert> alerts = store.recent(null, null, 10);
        assertEquals(2, alerts.size());
        assertFalse(alerts.get(0).aggressorBuy(), "newest first");
        assertEquals(30_000, alerts.get(0).notional(), 1e-9);
        assertEquals(20_000, alerts.get(1).notional(), 1e-9);
    }

    @Test
    @DisplayName("alerts are counted by fills per sweep and as coalescing-only")
    void alertMetrics() {
        detector.onTrade(spot, 100, 200, true, T, 1, 1, -1);                    // 1 fill
        for (int i = 0; i < 4; i++) detector.onTrade(spot, 100 + i, 30, true, T + 1, 2 + i, 2 + i, -1);  // 4 fills
        closeWith(spot);

        assertEquals(2, metrics.tradeAlerts(Venue.BINANCE_SPOT));
        assertEquals(1, metrics.tradeAlertsCoalescedOnly(Venue.BINANCE_SPOT));
        assertEquals(1, metrics.tradeAlertFills(Venue.BINANCE_SPOT, 0));
        assertEquals(0, metrics.tradeAlertFills(Venue.BINANCE_SPOT, 1));
        assertEquals(1, metrics.tradeAlertFills(Venue.BINANCE_SPOT, 2));
        assertEquals(0, metrics.tradeAlertFills(Venue.BINANCE_SPOT, 3));
        assertEquals(0, metrics.tradeAlerts(Venue.BINANCE_FUTURES));
    }

    // ---- taker-order ids ----

    @Test
    @DisplayName("equal taker ids continue across an equal price, and nothing else is checked")
    void equalTakerIdsContinue() {
        detector.onTrade(mexcSpot, 100, 60, true, T, 1, 1, 500);
        detector.onTrade(mexcSpot, 100, 60, true, T, 7, 7, 500);       // equal price, id gap
        detector.onTrade(mexcSpot, 99, 60, true, T + 3, 8, 8, 500);    // reversal, later time
        detector.onFrameEnd(mexcSpot);

        TradeAlert a = only();
        assertEquals(3, a.fills());
        assertEquals(17_940, a.notional(), 1e-9);
    }

    @Test
    @DisplayName("different taker ids close the sweep, even with the same time, contiguous ids and a price toward the aggressor")
    void differentTakerIdsClose() {
        detector.onTrade(mexcSpot, 100, 60, true, T, 1, 1, 500);
        detector.onTrade(mexcSpot, 101, 60, true, T, 2, 2, 501);
        detector.onFrameEnd(mexcSpot);

        assertTrue(store.recent(null, null, 10).isEmpty());
    }

    @Test
    @DisplayName("a side change closes the sweep even under one taker id")
    void takerIdSideChangeCloses() {
        detector.onTrade(mexcSpot, 100, 60, true, T, 1, 1, 500);
        detector.onTrade(mexcSpot, 100, 60, false, T, 2, 2, 500);
        detector.onFrameEnd(mexcSpot);

        assertTrue(store.recent(null, null, 10).isEmpty());
    }

    // ---- maker fills (rule A) ----

    @Test
    @DisplayName("with maker fills, an equal price continues the sweep")
    void makerFillsEqualPriceContinues() {
        detector.onTrade(mexcFutures, 100, 60, true, T, 1, 1, -1);
        detector.onTrade(mexcFutures, 100, 60, true, T, 2, 2, -1);
        closeWith(mexcFutures);

        assertEquals(2, only().fills());
    }

    @Test
    @DisplayName("with maker fills, a price moving against the aggressor still closes the sweep")
    void makerFillsReversalCloses() {
        detector.onTrade(mexcFutures, 100, 60, false, T, 1, 1, -1);
        detector.onTrade(mexcFutures, 101, 60, false, T, 2, 2, -1);
        closeWith(mexcFutures);

        assertTrue(store.recent(null, null, 10).isEmpty());
    }

    @Test
    @DisplayName("with maker fills, a 1 ms time step closes the sweep")
    void makerFillsTimeStepCloses() {
        detector.onTrade(mexcFutures, 100, 60, true, T, 1, 1, -1);
        detector.onTrade(mexcFutures, 100, 60, true, T + 1, 2, 2, -1);
        closeWith(mexcFutures);

        assertTrue(store.recent(null, null, 10).isEmpty());
    }

    @Test
    @DisplayName("with maker fills, a gap in trade ids closes the sweep")
    void makerFillsIdGapCloses() {
        detector.onTrade(mexcFutures, 100, 60, true, T, 1, 1, -1);
        detector.onTrade(mexcFutures, 100, 60, true, T, 3, 3, -1);
        closeWith(mexcFutures);

        assertTrue(store.recent(null, null, 10).isEmpty());
    }

    // ---- levels ----

    @Test
    @DisplayName("levels counts price changes: 5 fills at one price are 1 level")
    void fiveFillsOneLevel() {
        for (int i = 0; i < 5; i++) detector.onTrade(mexcFutures, 100, 30, true, T, 1 + i, 1 + i, -1);
        closeWith(mexcFutures);

        TradeAlert a = only();
        assertEquals(5, a.fills());
        assertEquals(1, a.levels());
    }

    @Test
    @DisplayName("fills over three prices are 3 levels; on Binance every fill is its own level")
    void levelsPerPrice() {
        detector.onTrade(mexcFutures, 100, 30, true, T, 1, 1, -1);
        detector.onTrade(mexcFutures, 100, 30, true, T, 2, 2, -1);
        detector.onTrade(mexcFutures, 101, 30, true, T, 3, 3, -1);
        detector.onTrade(mexcFutures, 102, 30, true, T, 4, 4, -1);
        detector.onTrade(mexcFutures, 102, 30, true, T, 5, 5, -1);
        closeWith(mexcFutures);
        TradeAlert mexc = only();
        assertEquals(5, mexc.fills());
        assertEquals(3, mexc.levels());

        for (int i = 0; i < 4; i++) detector.onTrade(spot, 100 + i, 30, true, T, 1 + i, 1 + i, -1);
        closeWith(spot);
        TradeAlert binance = store.recent(Venue.BINANCE_SPOT, null, 10).getFirst();
        assertEquals(4, binance.fills());
        assertEquals(4, binance.levels());
    }

    @Test
    @DisplayName("coalescedOnly is per level: one 16K order filled as four 4K fills at one price, threshold 10K, is not coalescing-only")
    void coalescedOnlyPerLevel() {
        for (int i = 0; i < 4; i++) detector.onTrade(mexcSpot, 100, 40, true, T, 1 + i, 1 + i, 500);
        detector.onFrameEnd(mexcSpot);

        TradeAlert a = only();
        assertEquals(16_000, a.notional(), 1e-9);
        assertFalse(a.coalescedOnly(), "one level reached 10K");
    }

    @Test
    @DisplayName("coalescedOnly when no level reaches the threshold: two 6K levels of two 3K fills each")
    void coalescedOnlyAcrossLevels() {
        detector.onTrade(mexcSpot, 100, 30, true, T, 1, 1, 500);
        detector.onTrade(mexcSpot, 100, 30, true, T, 2, 2, 500);
        detector.onTrade(mexcSpot, 101, 30, true, T, 3, 3, 500);
        detector.onTrade(mexcSpot, 101, 30, true, T, 4, 4, 500);
        detector.onFrameEnd(mexcSpot);

        TradeAlert a = only();
        assertEquals(2, a.levels());
        assertTrue(a.coalescedOnly());
    }

    // ---- frame end ----

    @Test
    @DisplayName("onFrameEnd judges the open sweep when the venue closes at frame end, once")
    void frameEndCloses() {
        detector.onTrade(mexcSpot, 100, 200, true, T, 1, 1, 500);
        assertTrue(store.recent(null, null, 10).isEmpty(), "still open");

        detector.onFrameEnd(mexcSpot);
        assertEquals(1, only().fills());

        detector.onFrameEnd(mexcSpot);
        detector.onTrade(mexcSpot, 1, 0.001, true, T + 60_000, 9, 9, 900);
        detector.onFrameEnd(mexcSpot);
        assertEquals(1, store.recent(null, null, 10).size(), "a closed sweep is never judged again");
    }

    @Test
    @DisplayName("onFrameEnd leaves the sweep open on a venue that closes on the next trade")
    void frameEndIgnoredElsewhere() {
        detector.onTrade(spot, 100, 200, true, T, 1, 1, -1);
        detector.onTrade(mexcFutures, 100, 200, true, T, 1, 1, -1);
        detector.onFrameEnd(spot);
        detector.onFrameEnd(mexcFutures);
        assertTrue(store.recent(null, null, 10).isEmpty());

        closeWith(spot);
        closeWith(mexcFutures);
        assertEquals(2, store.recent(null, null, 10).size());
    }

    @Test
    @DisplayName("a frame-end close at the frame boundary splits one taker order into two sweeps")
    void orderSpanningFramesSplits() {
        detector.onTrade(mexcSpot, 100, 60, true, T, 1, 1, 500);
        detector.onFrameEnd(mexcSpot);
        detector.onTrade(mexcSpot, 100, 60, true, T, 2, 2, 500);
        detector.onFrameEnd(mexcSpot);

        assertTrue(store.recent(null, null, 10).isEmpty(), "6K and 6K, judged apart");
    }

    @Test
    @DisplayName("onFrameEnd on a slot without trade state does nothing")
    void frameEndWithoutTradeState() {
        detector.onFrameEnd(new InstrumentSlot(Instrument.of(20, Venue.BYBIT_SPOT, "BTCUSDT", "BTC", "USDT"),
                null, null, null));
        assertTrue(store.recent(null, null, 10).isEmpty());
    }

    // ---- filtered ----

    @Test
    @DisplayName("a filtered trade is counted per venue and never opens, continues or closes a sweep")
    void filtered() {
        detector.onTrade(mexcFutures, 100, 60, true, T, 1, 1, -1);
        detector.onFiltered(mexcFutures);
        detector.onTrade(mexcFutures, 100, 60, true, T, 2, 2, -1);
        detector.onFiltered(mexcSpot);
        closeWith(mexcFutures);

        assertEquals(2, only().fills(), "the filtered trade did not close the sweep");
        assertEquals(1, metrics.tradesFiltered(Venue.MEXC_FUTURES));
        assertEquals(1, metrics.tradesFiltered(Venue.MEXC_SPOT));
        assertEquals(3, metrics.tradesDecoded(Venue.MEXC_FUTURES), "filtered trades are not decoded trades");
    }

    // ---- clock ----

    @Test
    @DisplayName("detectedAtMs comes from the detector's clock")
    void clock() {
        TradeDetector clocked = new TradeDetector(metrics, new TradeThresholds(props), store, () -> 42L);
        clocked.onTrade(mexcSpot, 100, 200, true, T, 1, 1, 500);
        clocked.onFrameEnd(mexcSpot);

        assertEquals(42L, only().detectedAtMs());
    }

    // ---- helpers ----

    /** The two 6K fills were judged apart: no alert. */
    private void assertSplit() {
        closeWith(spot);
        assertTrue(store.recent(null, null, 10).isEmpty(), () -> store.recent(null, null, 10).toString());
    }

    /** A tiny trade much later, on its own id range: closes whatever sweep is open, alerts on nothing. */
    private void closeWith(InstrumentSlot slot) {
        detector.onTrade(slot, 1, 0.001, true, T + 60_000, 1_000_000_000L, 1_000_000_000L, -1);
    }

    private TradeAlert only() {
        List<TradeAlert> alerts = store.recent(null, null, 10);
        assertEquals(1, alerts.size(), alerts.toString());
        return alerts.getFirst();
    }

    private static InstrumentSlot slot(int id, Venue venue, String symbol) {
        return slot(id, venue, symbol, NO_DECODER);
    }

    private static InstrumentSlot slot(int id, Venue venue, String symbol, TradeDecoder decoder) {
        String base = symbol.replace("_", "").replace("USDT", "");
        return new InstrumentSlot(Instrument.of(id, venue, symbol, base, "USDT"), null, null, null, decoder);
    }

    /** A decoder that decodes nothing and only carries the SPI flags. */
    private record FlagDecoder(boolean reportsMakerFills, boolean closesSweepAtFrameEnd) implements TradeDecoder {
        @Override
        public boolean decode(InstrumentSlot slot, String rawJson, TradeSink sink) {
            return true;
        }
    }
}
