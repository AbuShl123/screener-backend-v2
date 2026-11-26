package dev.abu.screener_backend.marketdata.core.spike;

import dev.abu.screener_backend.config.SpikeAlertProperties;
import dev.abu.screener_backend.marketdata.Instrument;
import dev.abu.screener_backend.marketdata.Venue;
import dev.abu.screener_backend.marketdata.core.health.PipelineMetrics;
import dev.abu.screener_backend.marketdata.core.slot.InstrumentSlot;
import dev.abu.screener_backend.marketdata.spi.KlineDecoder;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link SpikeDetector} against a real slot and store. Price: 5-minute window, 5% threshold, 1%
 * re-arm margin. Volume: 2-minute windows (to keep the scenarios short), +200% and +$70K.
 */
class SpikeDetectorTest {

    private static final long M = 60_000;
    private static final long T0 = 1_795_689_420_000L;
    private static final KlineDecoder NO_OP = (slot, json, sink) -> true;

    private final SpikeAlertProperties props = props(true);
    private final SpikeAlertStore store = new SpikeAlertStore(props);
    private final PipelineMetrics metrics = new PipelineMetrics();
    private final SpikeDetector detector = new SpikeDetector(props, store, metrics, () -> 42L);
    private final InstrumentSlot slot = slot(props);

    private static SpikeAlertProperties props(boolean enabled) {
        return new SpikeAlertProperties(enabled, true, 100, 10,
                new SpikeAlertProperties.Price(5, 5, 1),
                new SpikeAlertProperties.Volume(2, 200, 70_000));
    }

    private static InstrumentSlot slot(SpikeAlertProperties props) {
        return new InstrumentSlot(Instrument.of(0, Venue.BINANCE_FUTURES, "PEPEUSDT", "PEPE", "USDT"),
                null, null, null, null, NO_OP, props.candleRingSize());
    }

    /** One push: candle {@code minute} with the given high, low, close and volume; event time inside it. */
    private void push(int minute, double high, double low, double close, double volume) {
        detector.onKline(slot, T0 + minute * M, high, low, close, volume, T0 + minute * M + 1_000);
    }

    /** {@code n} flat candles at {@code price} with {@code volume} each, minutes 0 … n-1, the last one forming. */
    private void flat(int n, double price, double volume) {
        for (int i = 0; i < n; i++) push(i, price, price, price, volume);
    }

    private List<SpikeAlert> alerts(SpikeKind kind) {
        return store.recent(null, null, kind, 100);
    }

    @Test
    @DisplayName("a 6% rise from the window low is one PRICE alert: old price = low, new price = close, pct signed +")
    void pumpAlerts() {
        flat(5, 100, 1_000);
        push(4, 106, 100, 106, 1_000);

        List<SpikeAlert> price = alerts(SpikeKind.PRICE);
        assertEquals(1, price.size());
        SpikeAlert a = price.getFirst();
        assertEquals(6.0, a.pct(), 1e-9);
        assertEquals(100, a.from());
        assertEquals(106, a.to());
        assertEquals(5, a.windowMinutes());
        assertEquals(T0 + 4 * M + 1_000, a.timeMs());
        assertEquals(42L, a.detectedAtMs());
        assertEquals("BINANCE_FUTURES:PEPEUSDT:PRICE:" + a.timeMs(), a.id());
        assertEquals(1, metrics.priceSpikes(Venue.BINANCE_FUTURES));
    }

    @Test
    @DisplayName("a dip then a rip inside the window counts from the dip: 103 → 100 → 106 is +6%")
    void dipThenRip() {
        push(0, 103, 103, 103, 1);
        push(1, 103, 100, 100, 1);
        push(2, 101, 100, 101, 1);
        push(3, 102, 101, 102, 1);
        push(4, 106, 102, 106, 1);

        assertEquals(6.0, alerts(SpikeKind.PRICE).getFirst().pct(), 1e-9);
    }

    @Test
    @DisplayName("a 6% fall from the window high is a dump: pct negative, old price = high")
    void dumpAlerts() {
        flat(5, 100, 1);
        push(4, 100, 94, 94, 1);

        SpikeAlert a = alerts(SpikeKind.PRICE).getFirst();
        assertEquals(-6.0, a.pct(), 1e-9);
        assertEquals(100, a.from());
        assertEquals(94, a.to());
        assertTrue(!a.up());
    }

    @Test
    @DisplayName("no price alert until the window is full")
    void priceWaitsForWarmWindow() {
        push(0, 100, 100, 100, 1);
        push(1, 120, 100, 120, 1); // +20%, but only one closed candle of the four needed
        assertTrue(alerts(SpikeKind.PRICE).isEmpty());
    }

    @Test
    @DisplayName("a move alerts once per level: quiet from 5% to 9.9%, again at 10%")
    void escalation() {
        flat(5, 100, 1);
        push(4, 105.5, 100, 105.5, 1);
        push(4, 107, 100, 107, 1);
        push(4, 109.9, 100, 109.9, 1);
        assertEquals(1, alerts(SpikeKind.PRICE).size());

        push(4, 110.2, 100, 110.2, 1);
        assertEquals(2, alerts(SpikeKind.PRICE).size());
    }

    @Test
    @DisplayName("re-arm needs the move to fall below threshold − margin, so hovering at the threshold does not flap")
    void rearmMargin() {
        flat(5, 100, 1);
        push(4, 105, 100, 105, 1);   // fires
        push(4, 105, 100, 104.5, 1); // 4.5%: inside the margin, still disarmed
        push(4, 105, 100, 105.2, 1); // back above 5%: no second alert
        assertEquals(1, alerts(SpikeKind.PRICE).size());

        push(4, 105.2, 100, 103.5, 1); // 3.5% < 4%: re-armed
        push(4, 105.6, 100, 105.6, 1); // 5.6%: fires again
        assertEquals(2, alerts(SpikeKind.PRICE).size());
    }

    @Test
    @DisplayName("volume: last 2 closed candles at least 3× and $70K above the 2 before alerts once, on rollover")
    void volumeSpike() {
        push(0, 1, 1, 1, 10_000);
        push(1, 1, 1, 1, 10_000);
        push(2, 1, 1, 1, 50_000);
        push(3, 1, 1, 1, 50_000);
        assertTrue(alerts(SpikeKind.VOLUME).isEmpty()); // candle 3 still forming: only 3 closed

        push(4, 1, 1, 1, 0); // closes candle 3: now = 100K, prev = 20K → +400%, +$80K
        List<SpikeAlert> volume = alerts(SpikeKind.VOLUME);
        assertEquals(1, volume.size());
        SpikeAlert a = volume.getFirst();
        assertEquals(400, a.pct(), 1e-9);
        assertEquals(20_000, a.from());
        assertEquals(100_000, a.to());
        assertEquals(2, a.windowMinutes());
        assertEquals(1, metrics.volumeSpikes(Venue.BINANCE_FUTURES));
    }

    @Test
    @DisplayName("volume needs both conditions: +500% on an illiquid ticker below the $ floor stays quiet")
    void volumeNeedsAbsoluteFloor() {
        push(0, 1, 1, 1, 100);
        push(1, 1, 1, 1, 100);
        push(2, 1, 1, 1, 600);
        push(3, 1, 1, 1, 600);
        push(4, 1, 1, 1, 0);
        assertTrue(alerts(SpikeKind.VOLUME).isEmpty());
    }

    @Test
    @DisplayName("volume needs both conditions: +$100K on a major that is only +2% stays quiet")
    void volumeNeedsPercentage() {
        push(0, 1, 1, 1, 2_500_000);
        push(1, 1, 1, 1, 2_500_000);
        push(2, 1, 1, 1, 2_550_000);
        push(3, 1, 1, 1, 2_550_000);
        push(4, 1, 1, 1, 0);
        assertTrue(alerts(SpikeKind.VOLUME).isEmpty());
    }

    @Test
    @DisplayName("after a volume alert, the sliding window does not re-fire until the increase drops below the threshold")
    void volumeRearm() {
        push(0, 1, 1, 1, 10_000);
        push(1, 1, 1, 1, 10_000);
        push(2, 1, 1, 1, 50_000);
        push(3, 1, 1, 1, 50_000);
        push(4, 1, 1, 1, 200_000); // rollover of 3: fires
        push(5, 1, 1, 1, 200_000); // rollover of 4: now 250K vs prev 60K, still > +200%: quiet
        assertEquals(1, alerts(SpikeKind.VOLUME).size());

        push(6, 1, 1, 1, 200_000); // rollover of 5: now 400K vs prev 100K, +300%: still disarmed
        push(7, 1, 1, 1, 200_000); // rollover of 6: now 400K vs prev 250K, +60%: re-armed
        push(8, 1, 1, 1, 0);       // rollover of 7: now 400K vs 400K: armed, nothing to report
        assertEquals(1, alerts(SpikeKind.VOLUME).size());
    }

    @Test
    @DisplayName("a previous window with no volume passes the percentage check, the floor decides; pct is +∞")
    void volumeFromZero() {
        push(0, 1, 1, 1, 0);
        push(1, 1, 1, 1, 0);
        push(2, 1, 1, 1, 40_000);
        push(3, 1, 1, 1, 40_000);
        push(4, 1, 1, 1, 0);
        assertEquals(Double.POSITIVE_INFINITY, alerts(SpikeKind.VOLUME).getFirst().pct());
    }

    @Test
    @DisplayName("a gap in the candles empties the window: no alert across the hole")
    void gapSilencesUntilRefilled() {
        flat(5, 100, 1);
        push(9, 110, 110, 110, 1); // minutes 5–8 missing; +10% against the old candles, but they are gone
        assertTrue(alerts(SpikeKind.PRICE).isEmpty());
        assertEquals(1, metrics.candleGaps(Venue.BINANCE_FUTURES));
    }

    @Test
    @DisplayName("with detection disabled, candles are kept but nothing alerts")
    void disabledKeepsCandles() {
        SpikeAlertProperties off = props(false);
        SpikeDetector quiet = new SpikeDetector(off, store, metrics, () -> 0L);
        for (int i = 0; i < 5; i++) quiet.onKline(slot, T0 + i * M, 100, 100, 100, 1, 0);
        quiet.onKline(slot, T0 + 4 * M, 120, 100, 120, 1, 0);

        assertTrue(store.recent(null, null, null, 10).isEmpty());
        assertEquals(4, slot.candles().filled());
    }

    @Test
    @DisplayName("a slot without candle state is ignored")
    void slotWithoutCandles() {
        InstrumentSlot bare = new InstrumentSlot(Instrument.of(1, Venue.BINANCE_SPOT, "BTCUSDT", "BTC", "USDT"),
                null, null, null);
        detector.onKline(bare, T0, 1, 1, 1, 1, 0);
        assertEquals(0, metrics.klines(Venue.BINANCE_SPOT));
    }
}
