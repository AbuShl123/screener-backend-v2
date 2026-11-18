package dev.abu.screener_backend.feed.trade;

import dev.abu.screener_backend.analysis.ThresholdClassificationRule;
import dev.abu.screener_backend.analysis.UserClassificationContext;
import dev.abu.screener_backend.analysis.UserClassificationRules;
import dev.abu.screener_backend.config.TradeAlertProperties;
import dev.abu.screener_backend.feed.depth.OrderBookFeedStore;
import dev.abu.screener_backend.marketdata.Instrument;
import dev.abu.screener_backend.marketdata.Venue;
import dev.abu.screener_backend.marketdata.core.trade.TradeAlert;
import dev.abu.screener_backend.marketdata.core.trade.TradeAlertStore;
import dev.abu.screener_backend.ws.UserWebSocketSession;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** {@link TradeChannel} driven directly, one tick per {@code drain()}, against a real store. */
@ExtendWith(OutputCaptureExtension.class)
class TradeChannelTest {

    private static final Instrument SOL_FUT = Instrument.of(0, Venue.BINANCE_FUTURES, "SOLUSDT", "SOL", "USDT");
    private static final Instrument MEXC_SOL = Instrument.of(1, Venue.MEXC_FUTURES, "SOL_USDT", "SOL", "USDT");
    private static final long T = 1_794_931_200_123L;

    private static final int CAPACITY = 5;
    private static final int REPLAY = 3;

    private final TradeAlertProperties props = new TradeAlertProperties(1, null, CAPACITY, REPLAY);
    private final TradeAlertStore store = new TradeAlertStore(props);
    private final TradeChannel channel = new TradeChannel(store, props);

    @Test
    @DisplayName("one alert is one TRADE envelope: normalized symbol, data fields in contract order, notional at 2 dp")
    void bodyShape() {
        store.add(new TradeAlert(MEXC_SOL, false, 139.83, 4380.0, 612_455.4049, 500_000, 139.91, 139.74, 7,
                true, 3_653_778_376L, 3_653_778_390L, T, T + 200));
        channel.drain();

        assertEquals(List.of("{\"type\":\"TRADE\",\"exchange\":\"MEXC\",\"market\":\"FUTURES\",\"symbol\":\"SOLUSDT\","
                        + "\"data\":{\"id\":\"MEXC_FUTURES:SOL_USDT:3653778376\",\"side\":\"SELL\",\"time\":1794931200123,"
                        + "\"notional\":612455.4,\"qty\":4380.0,\"avgPrice\":139.83,\"firstPrice\":139.91,"
                        + "\"lastPrice\":139.74,\"levels\":7,\"threshold\":500000.0}}"),
                updates(session()));
    }

    @Test
    @DisplayName("a buy is BUY")
    void buySide() {
        store.add(alert(SOL_FUT, 1, true));
        channel.drain();

        assertTrue(updates(session()).getFirst().contains("\"side\":\"BUY\""));
    }

    @Test
    @DisplayName("default and custom-rules sessions both get every alert")
    void everySessionGetsEveryAlert() {
        store.add(alert(SOL_FUT, 1, true));
        store.add(alert(MEXC_SOL, 2, false));
        channel.drain();

        UserWebSocketSession custom = session();
        custom.setContext(contextWithRule("SOLUSDT:FUTURES"));
        List<String> forDefault = updates(session());

        assertEquals(2, forDefault.size());
        assertEquals(forDefault, updates(custom));
    }

    @Test
    @DisplayName("a tick with no new alerts sends nothing")
    void noAlertsNoOutput() {
        store.add(alert(SOL_FUT, 1, true));
        channel.drain();
        channel.drain();

        assertEquals(List.of(), updates(session()));
    }

    @Test
    @DisplayName("alerts drained while no session exists still reach the replay")
    void replayFillsWithoutSessions() {
        store.add(alert(SOL_FUT, 1, true));
        channel.drain();                       // nobody connected
        store.add(alert(SOL_FUT, 2, true));
        channel.drain();

        assertEquals(List.of(1L, 2L), ids(snapshot(session())));
    }

    @Test
    @DisplayName("the snapshot is oldest first and bounded at replay-size")
    void replayBounded() {
        for (long id = 1; id <= 4; id++) {
            store.add(alert(SOL_FUT, id, true));
            channel.drain();
        }

        assertEquals(List.of(2L, 3L, 4L), ids(snapshot(session())));
    }

    @Test
    @DisplayName("an alert added after drain() is in neither that tick's snapshot nor its updates, and in the next tick's updates")
    void addedAfterDrainArrivesNextTick() {
        store.add(alert(SOL_FUT, 1, true));
        channel.drain();
        store.add(alert(SOL_FUT, 2, true));   // between this tick's drain and its collect calls

        assertEquals(List.of(1L), ids(snapshot(session())));
        assertEquals(List.of(1L), ids(updates(session())));

        channel.drain();
        assertEquals(List.of(2L), ids(updates(session())));
        assertEquals(List.of(1L, 2L), ids(snapshot(session())));
    }

    @Test
    @DisplayName("more alerts than the store keeps between drains: a WARN with the count, then the retained ones")
    void overflowLogsAndContinues(CapturedOutput output) {
        for (long id = 1; id <= CAPACITY + 2; id++) store.add(alert(SOL_FUT, id, true));
        channel.drain();

        assertTrue(output.getAll().contains("2 trade alert(s) were dropped"), output.getAll());
        assertEquals(List.of(3L, 4L, 5L, 6L, 7L), ids(updates(session())));

        store.add(alert(SOL_FUT, 8, true));
        channel.drain();
        assertEquals(List.of(8L), ids(updates(session())));
    }

    // ---- helpers ----

    private List<String> updates(UserWebSocketSession session) {
        List<String> out = new ArrayList<>();
        channel.collectUpdates(session, out);
        return out;
    }

    private List<String> snapshot(UserWebSocketSession session) {
        List<String> out = new ArrayList<>();
        channel.collectSnapshot(session, out);
        return out;
    }

    /** The trailing digits of each body's {@code id}: the alert's first trade id. */
    private static List<Long> ids(List<String> bodies) {
        return bodies.stream().map(b -> {
            int start = b.indexOf("\"id\":\"") + 6;
            String id = b.substring(start, b.indexOf('"', start));
            return Long.parseLong(id.substring(id.lastIndexOf(':') + 1));
        }).toList();
    }

    private static UserWebSocketSession session() {
        return new UserWebSocketSession(null, UUID.randomUUID());
    }

    private static UserClassificationContext contextWithRule(String ruleKey) {
        ThresholdClassificationRule leaf = ThresholdClassificationRule.of(List.of(
                new ThresholdClassificationRule.TierThreshold(1, 100_000, 0.01)));
        return new UserClassificationContext(UUID.randomUUID(),
                new UserClassificationRules(Map.of(ruleKey, leaf)),
                new OrderBookFeedStore(),
                new ConcurrentHashMap<>());
    }

    private static TradeAlert alert(Instrument instrument, long id, boolean buy) {
        return new TradeAlert(instrument, buy, 100, 1000, 100_000, 30_000, 100, 100, 1, false, id, id, T, T);
    }
}
