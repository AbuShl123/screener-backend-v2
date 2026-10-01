package dev.abu.screener_backend.analysis;

import dev.abu.screener_backend.exchange.Instrument;
import dev.abu.screener_backend.exchange.InstrumentTest;
import dev.abu.screener_backend.exchange.Venue;
import dev.abu.screener_backend.exchange.book.OrderBook;
import dev.abu.screener_backend.feed.FeedEventType;
import dev.abu.screener_backend.feed.OrderBookFeedStore;
import dev.abu.screener_backend.feed.OrderBookUpdate;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Venue-awareness of {@link OrderBookClassifier}: state and feed entries are keyed by
 * {@code feedKey}, user rules are matched by {@code ruleKey}.
 */
class OrderBookClassifierTest {

    private static final long T0 = 1_700_000_000_000L;

    private static final Instrument BINANCE_BTC = Instrument.of(0, Venue.BINANCE_SPOT, "BTCUSDT", "BTC", "USDT");
    private static final Instrument OTHER_BTC = InstrumentTest.otherExchangeSpot(1, "BTC_USDT", "BTC", "USDT");

    /** A synced book around 100,000 with a ~$10M bid at the spread — visible under every rule here. */
    private static OrderBook syncedBook() {
        OrderBook ob = new OrderBook(0.1);
        ob.applyLevel(true, 99_990.0, 100.0, T0);
        ob.applyLevel(false, 100_010.0, 0.01, T0);
        ob.computeDistance();
        ob.markSynced();
        return ob;
    }

    private static UserClassificationContext contextWithRule(String ruleKey) {
        ThresholdClassificationRule leaf = ThresholdClassificationRule.of(List.of(
                new ThresholdClassificationRule.TierThreshold(1, 100_000, 0.01)));
        return new UserClassificationContext(UUID.randomUUID(),
                new UserClassificationRules(Map.of(ruleKey, leaf)),
                new OrderBookFeedStore(),
                new ConcurrentHashMap<>());
    }

    @Test
    @DisplayName("same symbol on two exchanges gets two global feed entries")
    void defaultPassKeepsExchangesApart() {
        OrderBookFeedStore global = new OrderBookFeedStore();
        OrderBookClassifier classifier = new OrderBookClassifier(global, new DefaultClassificationRule());

        classifier.process(BINANCE_BTC, syncedBook());
        classifier.process(OTHER_BTC, syncedBook());

        Map<String, OrderBookUpdate> snapshot = global.getSnapshot();
        assertEquals(2, snapshot.size());
        assertSame(BINANCE_BTC, snapshot.get(BINANCE_BTC.feedKey()).instrument());
        assertSame(OTHER_BTC, snapshot.get(OTHER_BTC.feedKey()).instrument());
        assertEquals(FeedEventType.ADD, snapshot.get(OTHER_BTC.feedKey()).type());
    }

    @Test
    @DisplayName("one user rule applies on both exchanges, with separate state and feed entries")
    void userRuleAppliesOnEveryExchange() {
        OrderBookClassifier classifier = new OrderBookClassifier(new OrderBookFeedStore(), new DefaultClassificationRule());
        UserClassificationContext ctx = contextWithRule("BTCUSDT:SPOT");
        classifier.setActiveUserContexts(new UserClassificationContext[]{ctx});

        classifier.process(BINANCE_BTC, syncedBook());
        classifier.process(OTHER_BTC, syncedBook());

        assertEquals(2, ctx.states().size());
        assertNotSame(ctx.states().get(BINANCE_BTC.feedKey()), ctx.states().get(OTHER_BTC.feedKey()));

        Map<String, OrderBookUpdate> personal = ctx.feedStore().getSnapshot();
        assertEquals(2, personal.size());
        assertTrue(personal.containsKey(BINANCE_BTC.feedKey()));
        assertTrue(personal.containsKey(OTHER_BTC.feedKey()));
    }

    @Test
    @DisplayName("a rule for another market does not run the user pass")
    void unconfiguredRuleKeyIsSkipped() {
        OrderBookClassifier classifier = new OrderBookClassifier(new OrderBookFeedStore(), new DefaultClassificationRule());
        UserClassificationContext ctx = contextWithRule("BTCUSDT:FUTURES");
        classifier.setActiveUserContexts(new UserClassificationContext[]{ctx});

        classifier.process(BINANCE_BTC, syncedBook());

        assertTrue(ctx.states().isEmpty());
        assertTrue(ctx.feedStore().getSnapshot().isEmpty());
    }
}
