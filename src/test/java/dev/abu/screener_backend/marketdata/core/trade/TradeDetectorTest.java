package dev.abu.screener_backend.marketdata.core.trade;

import dev.abu.screener_backend.marketdata.Instrument;
import dev.abu.screener_backend.marketdata.Venue;
import dev.abu.screener_backend.marketdata.core.health.PipelineMetrics;
import dev.abu.screener_backend.marketdata.core.slot.InstrumentSlot;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class TradeDetectorTest {

    private final PipelineMetrics metrics = new PipelineMetrics();
    private final TradeDetector detector = new TradeDetector(metrics);

    @Test
    @DisplayName("every trade is counted as decoded for its instrument's venue")
    void countsDecodedPerVenue() {
        InstrumentSlot spot = slot(0, Venue.BINANCE_SPOT);
        InstrumentSlot futures = slot(1, Venue.BINANCE_FUTURES);

        detector.onTrade(spot, 100, 1, true, 1L, 10, 10);
        detector.onTrade(spot, 100, 1, false, 2L, 11, 11);
        detector.onTrade(futures, 100, 1, true, 3L, -1, -1);

        assertEquals(2, metrics.tradesDecoded(Venue.BINANCE_SPOT));
        assertEquals(1, metrics.tradesDecoded(Venue.BINANCE_FUTURES));
        assertEquals(0, metrics.tradesDecoded(Venue.BYBIT_SPOT));
    }

    private static InstrumentSlot slot(int id, Venue venue) {
        return new InstrumentSlot(Instrument.of(id, venue, "BTCUSDT", "BTC", "USDT"), null, null, null);
    }
}
