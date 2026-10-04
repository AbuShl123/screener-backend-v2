package dev.abu.screener_backend.feed;

import dev.abu.screener_backend.analysis.ThresholdClassificationRule;
import dev.abu.screener_backend.analysis.UserClassificationContext;
import dev.abu.screener_backend.analysis.UserClassificationRules;
import dev.abu.screener_backend.analysis.UserFeedRegistry;
import dev.abu.screener_backend.marketdata.Instrument;
import dev.abu.screener_backend.marketdata.InstrumentTest;
import dev.abu.screener_backend.marketdata.Venue;
import dev.abu.screener_backend.ws.UserWebSocketSession;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Venue-awareness of {@link OrderBookBroadcaster}: custom-rule filtering by {@code ruleKey} across
 * exchanges, and the {@code exchange} field on every message shape.
 *
 * <p>Hand-rolled doubles, as in {@code UserFeedRegistryTest}: sessions get a {@code null} Jakarta
 * session and never start a send loop; enqueued batches are captured instead.
 */
class OrderBookBroadcasterTest {

    private static final Instrument BINANCE_BTC = Instrument.of(0, Venue.BINANCE_SPOT, "BTCUSDT", "BTC", "USDT");
    private static final Instrument OTHER_BTC = InstrumentTest.otherExchangeSpot(1, "BTC_USDT", "BTC", "USDT");
    private static final Instrument BINANCE_ETH = Instrument.of(2, Venue.BINANCE_SPOT, "ETHUSDT", "ETH", "USDT");

    /** Serves a fixed active-context array; no DB, no shards. */
    static class FakeRegistry extends UserFeedRegistry {
        UserClassificationContext[] active = new UserClassificationContext[0];

        FakeRegistry() {
            super(null, null);
        }

        @Override
        public UserClassificationContext[] activeContexts() {
            return active;
        }
    }

    /** Captures every message the broadcaster enqueues. */
    static class CapturingSession extends UserWebSocketSession {
        final List<String> sent = new ArrayList<>();

        CapturingSession() {
            super(null, UUID.randomUUID());
        }

        @Override
        public boolean enqueueBatch(List<String> batch) {
            sent.addAll(batch);
            return true;
        }
    }

    private OrderBookFeedStore global;
    private FakeRegistry registry;
    private OrderBookBroadcaster broadcaster;

    @BeforeEach
    void setUp() {
        global = new OrderBookFeedStore();
        registry = new FakeRegistry();
        broadcaster = new OrderBookBroadcaster(global, registry);
    }

    private static OrderBookUpdate add(Instrument inst) {
        ClassifiedLevel[] bids = new ClassifiedLevel[5];
        ClassifiedLevel[] asks = new ClassifiedLevel[5];
        bids[0] = new ClassifiedLevel(99.0, 1.0, 1, 0L, 0.01);
        asks[0] = new ClassifiedLevel(101.0, 1.0, 0, 0L, 0.01);
        return new OrderBookUpdate(inst, FeedEventType.ADD, bids, asks);
    }

    private static OrderBookUpdate drop(Instrument inst) {
        return new OrderBookUpdate(inst, FeedEventType.DROP, null, null);
    }

    private static void submit(OrderBookFeedStore store, OrderBookUpdate update) {
        store.submit(update.instrument().feedKey(), update);
    }

    private static UserClassificationContext contextWithRule(String ruleKey) {
        ThresholdClassificationRule leaf = ThresholdClassificationRule.of(List.of(
                new ThresholdClassificationRule.TierThreshold(1, 100_000, 0.01)));
        return new UserClassificationContext(UUID.randomUUID(),
                new UserClassificationRules(Map.of(ruleKey, leaf)),
                new OrderBookFeedStore(),
                new ConcurrentHashMap<>());
    }

    private static int count(String haystack, String needle) {
        Matcher m = Pattern.compile(Pattern.quote(needle)).matcher(haystack);
        int n = 0;
        while (m.find()) n++;
        return n;
    }

    @Test
    @DisplayName("custom snapshot drops the configured symbol from the global feed on every exchange")
    void customSnapshotFiltersEveryExchange() {
        submit(global, add(BINANCE_BTC));
        submit(global, add(OTHER_BTC));
        submit(global, add(BINANCE_ETH));

        UserClassificationContext ctx = contextWithRule("BTCUSDT:SPOT");
        submit(ctx.feedStore(), add(BINANCE_BTC));
        registry.active = new UserClassificationContext[]{ctx};

        CapturingSession session = new CapturingSession();
        session.setContext(ctx);
        broadcaster.addSession(session);

        broadcaster.drain();

        assertEquals(1, session.sent.size());
        String snapshot = session.sent.getFirst();
        assertTrue(snapshot.contains("\"type\":\"SNAPSHOT\""));
        // ETH from the global feed, BTC once — from the personal feed; OTHER_BTC's global entry is gone.
        assertEquals(2, count(snapshot, "\"exchange\":\"BINANCE\""));
        assertEquals(1, count(snapshot, "\"symbol\":\"BTCUSDT\""));
        assertEquals(1, count(snapshot, "\"symbol\":\"ETHUSDT\""));
    }

    @Test
    @DisplayName("custom live updates filter global entries by ruleKey, regardless of exchange")
    void customUpdatesFilterEveryExchange() {
        UserClassificationContext ctx = contextWithRule("BTCUSDT:SPOT");
        registry.active = new UserClassificationContext[]{ctx};

        CapturingSession session = new CapturingSession();
        session.setContext(ctx);
        session.setStatus(UserWebSocketSession.Status.READY);
        broadcaster.addSession(session);

        submit(global, add(BINANCE_BTC));
        submit(global, drop(OTHER_BTC));
        submit(global, add(BINANCE_ETH));
        submit(ctx.feedStore(), drop(OTHER_BTC));

        broadcaster.drain();

        // Personal OTHER_BTC DROP + global ETH ADD; both global BTC entries filtered out.
        assertEquals(2, session.sent.size());
        String all = String.join("\n", session.sent);
        assertEquals(1, count(all, "\"symbol\":\"BTCUSDT\""));
        assertEquals(1, count(all, "\"symbol\":\"ETHUSDT\""));
    }

    @Test
    @DisplayName("every message shape carries exchange, ahead of symbol and market")
    void payloadCarriesExchange() {
        CapturingSession session = new CapturingSession();
        broadcaster.addSession(session);

        submit(global, add(BINANCE_ETH));
        broadcaster.drain(); // snapshot

        submit(global, drop(BINANCE_ETH));
        submit(global, add(BINANCE_BTC));
        broadcaster.drain(); // DROP + ADD

        assertEquals(3, session.sent.size());
        assertTrue(session.sent.get(0).startsWith(
                "{\"seq\":1,\"type\":\"SNAPSHOT\",\"data\":[{\"exchange\":\"BINANCE\",\"symbol\":\"ETHUSDT\",\"market\":\"SPOT\","));
        String live = String.join("\n", session.sent.subList(1, 3));
        assertTrue(live.contains(
                "\"type\":\"DROP\",\"exchange\":\"BINANCE\",\"symbol\":\"ETHUSDT\",\"market\":\"SPOT\"}"));
        assertTrue(live.contains(
                "\"type\":\"ADD\",\"exchange\":\"BINANCE\",\"symbol\":\"BTCUSDT\",\"market\":\"SPOT\",\"bids\":"));
    }
}
