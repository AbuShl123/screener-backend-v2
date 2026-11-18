package dev.abu.screener_backend.monitoring;

import dev.abu.screener_backend.config.TradeAlertProperties;
import dev.abu.screener_backend.marketdata.Instrument;
import dev.abu.screener_backend.marketdata.Venue;
import dev.abu.screener_backend.marketdata.core.trade.TradeAlert;
import dev.abu.screener_backend.marketdata.core.trade.TradeAlertStore;
import dev.abu.screener_backend.marketdata.core.trade.TradeAlertStore.SymbolCount;
import dev.abu.screener_backend.monitoring.MonitoringController.TradeAlertView;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** {@code /api/monitoring/trade-alerts} and {@code /trade-alerts/summary}. */
class MonitoringControllerTradeAlertsTest {

    private static final Instrument BTC_FUT = Instrument.of(7, Venue.BINANCE_FUTURES, "BTCUSDT", "BTC", "USDT");
    private static final Instrument ETH_SPOT = Instrument.of(8, Venue.BINANCE_SPOT, "ETHUSDT", "ETH", "USDT");

    private final TradeAlertStore store = new TradeAlertStore(new TradeAlertProperties(1, null, 5, 100));
    private final MonitoringController controller = new MonitoringController(null, null, null, null, store);

    @Test
    @DisplayName("an alert is shown with its stable id, by venue and native symbol, its side spelled out, its threshold and readable times")
    void view() {
        store.add(new TradeAlert(BTC_FUT, false, 65_000, 2, 130_000, 100_000, 64_990, 65_010, 3, true,
                100, 102, 1_794_860_803_450L, 1_794_860_803_600L));

        TradeAlertView v = controller.getTradeAlerts(null, null, 100).getFirst();

        assertEquals(new TradeAlertView("BINANCE_FUTURES:BTCUSDT:100", Venue.BINANCE_FUTURES, "BTCUSDT",
                "SELL", 130_000, 100_000, 2, 65_000, 64_990, 65_010, 3, true, 100, 102,
                Instant.ofEpochMilli(1_794_860_803_450L), Instant.ofEpochMilli(1_794_860_803_600L)), v);
    }

    @Test
    @DisplayName("filters pass through; a blank symbol means every symbol; limit is clamped to [1, capacity]")
    void filtersAndLimit() {
        for (long id = 1; id <= 5; id++) store.add(alert(id % 2 == 0 ? ETH_SPOT : BTC_FUT, id));

        assertEquals(List.of(4L, 2L), ids(controller.getTradeAlerts(Venue.BINANCE_SPOT, null, 100)));
        assertEquals(List.of(5L, 3L, 1L), ids(controller.getTradeAlerts(null, " btcusdt ", 100)));
        assertEquals(5, controller.getTradeAlerts(null, " ", 100).size());
        assertEquals(List.of(5L), ids(controller.getTradeAlerts(null, null, 0)));
        assertEquals(5, controller.getTradeAlerts(null, null, 1_000_000).size());
    }

    @Test
    @DisplayName("the summary lists the per-symbol counts, most alerts first")
    void summary() {
        store.add(alert(ETH_SPOT, 1));
        store.add(alert(BTC_FUT, 2));
        store.add(alert(BTC_FUT, 3));

        assertEquals(List.of(
                new SymbolCount(Venue.BINANCE_FUTURES, "BTCUSDT", 2),
                new SymbolCount(Venue.BINANCE_SPOT, "ETHUSDT", 1)), controller.getTradeAlertSummary());
    }

    private static List<Long> ids(List<TradeAlertView> views) {
        return views.stream().map(TradeAlertView::firstTradeId).toList();
    }

    private static TradeAlert alert(Instrument instrument, long id) {
        return new TradeAlert(instrument, true, 100, 1000, 100_000, 1, 100, 100, 1, false, id, id, id, id);
    }
}
