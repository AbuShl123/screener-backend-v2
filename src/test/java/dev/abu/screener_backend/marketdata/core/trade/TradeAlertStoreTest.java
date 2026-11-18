package dev.abu.screener_backend.marketdata.core.trade;

import dev.abu.screener_backend.config.TradeAlertProperties;
import dev.abu.screener_backend.marketdata.Instrument;
import dev.abu.screener_backend.marketdata.Venue;
import dev.abu.screener_backend.marketdata.core.trade.TradeAlertStore.SymbolCount;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class TradeAlertStoreTest {

    private static final Instrument BTC_FUT = instrument(0, Venue.BINANCE_FUTURES, "BTCUSDT");
    private static final Instrument ETH_FUT = instrument(1, Venue.BINANCE_FUTURES, "ETHUSDT");
    private static final Instrument BTC_SPOT = instrument(2, Venue.BINANCE_SPOT, "BTCUSDT");

    private final TradeAlertStore store = new TradeAlertStore(new TradeAlertProperties(1, null, 3, 3, null, 250));

    @Test
    @DisplayName("recent alerts come newest first")
    void newestFirst() {
        store.add(alert(BTC_FUT, 1));
        store.add(alert(BTC_FUT, 2));
        store.add(alert(BTC_FUT, 3));

        assertEquals(List.of(3L, 2L, 1L), ids(store.recent(null, null, 10)));
    }

    @Test
    @DisplayName("only the last recent-capacity alerts are kept; the counts keep everything")
    void capacityBound() {
        for (long id = 1; id <= 5; id++) store.add(alert(BTC_FUT, id));

        assertEquals(List.of(5L, 4L, 3L), ids(store.recent(null, null, 10)));
        assertEquals(List.of(new SymbolCount(Venue.BINANCE_FUTURES, "BTCUSDT", 5)), store.counts());
    }

    @Test
    @DisplayName("limit caps the result")
    void limit() {
        for (long id = 1; id <= 3; id++) store.add(alert(BTC_FUT, id));

        assertEquals(List.of(3L, 2L), ids(store.recent(null, null, 2)));
        assertEquals(List.of(), store.recent(null, null, 0));
    }

    @Test
    @DisplayName("venue and symbol filter the alerts; the symbol match ignores case")
    void filters() {
        store.add(alert(BTC_FUT, 1));
        store.add(alert(ETH_FUT, 2));
        store.add(alert(BTC_SPOT, 3));

        assertEquals(List.of(2L, 1L), ids(store.recent(Venue.BINANCE_FUTURES, null, 10)));
        assertEquals(List.of(3L, 1L), ids(store.recent(null, "btcusdt", 10)));
        assertEquals(List.of(3L), ids(store.recent(Venue.BINANCE_SPOT, "BTCUSDT", 10)));
        assertEquals(List.of(), store.recent(Venue.BYBIT_SPOT, null, 10));
    }

    @Test
    @DisplayName("counts are per venue and symbol, most alerts first")
    void counts() {
        store.add(alert(ETH_FUT, 1));
        store.add(alert(BTC_SPOT, 2));
        store.add(alert(BTC_SPOT, 3));
        store.add(alert(BTC_FUT, 4));

        assertEquals(List.of(
                new SymbolCount(Venue.BINANCE_SPOT, "BTCUSDT", 2),
                new SymbolCount(Venue.BINANCE_FUTURES, "BTCUSDT", 1),
                new SymbolCount(Venue.BINANCE_FUTURES, "ETHUSDT", 1)), store.counts());
    }

    // ---- drainSince ----

    @Test
    @DisplayName("drainSince appends the new alerts oldest first and returns the new count")
    void drainNewOldestFirst() {
        List<TradeAlert> out = new ArrayList<>();
        assertEquals(0, store.drainSince(0, out));
        assertEquals(List.of(), out);

        store.add(alert(BTC_FUT, 1));
        store.add(alert(BTC_FUT, 2));
        long seen = store.drainSince(0, out);
        assertEquals(2, seen);
        assertEquals(List.of(1L, 2L), ids(out));

        out.clear();
        store.add(alert(ETH_FUT, 3));
        assertEquals(3, store.drainSince(seen, out));
        assertEquals(List.of(3L), ids(out));
    }

    @Test
    @DisplayName("drainSince with nothing new appends nothing and returns the same count")
    void drainNothingNew() {
        store.add(alert(BTC_FUT, 1));
        long seen = store.drainSince(0, new ArrayList<>());

        List<TradeAlert> out = new ArrayList<>();
        assertEquals(seen, store.drainSince(seen, out));
        assertEquals(List.of(), out);
    }

    @Test
    @DisplayName("more than recent-capacity alerts since seen: the retained ones, and a count showing the loss")
    void drainOverflow() {
        store.add(alert(BTC_FUT, 1));
        long seen = store.drainSince(0, new ArrayList<>());
        for (long id = 2; id <= 6; id++) store.add(alert(BTC_FUT, id)); // 5 new, capacity 3

        List<TradeAlert> out = new ArrayList<>();
        long total = store.drainSince(seen, out);
        assertEquals(6, total);
        assertEquals(List.of(4L, 5L, 6L), ids(out));
        assertEquals(2, (total - seen) - out.size(), "two alerts were lost");
    }

    @Test
    @DisplayName("draining does not change what recent() shows")
    void drainIsNonDestructive() {
        store.add(alert(BTC_FUT, 1));
        store.add(alert(BTC_FUT, 2));
        store.drainSince(0, new ArrayList<>());

        assertEquals(List.of(2L, 1L), ids(store.recent(null, null, 10)));
    }

    private static List<Long> ids(List<TradeAlert> alerts) {
        return alerts.stream().map(TradeAlert::firstTradeId).toList();
    }

    private static TradeAlert alert(Instrument instrument, long id) {
        return new TradeAlert(instrument, true, 100, 1000, 100_000, 1, 100, 100, 1, 1, false, id, id, id, id);
    }

    private static Instrument instrument(int id, Venue venue, String symbol) {
        return Instrument.of(id, venue, symbol, symbol.replace("USDT", ""), "USDT");
    }
}
