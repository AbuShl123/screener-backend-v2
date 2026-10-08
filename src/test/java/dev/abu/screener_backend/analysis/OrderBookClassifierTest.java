package dev.abu.screener_backend.analysis;

import dev.abu.screener_backend.analysis.filter.LevelFilter;
import dev.abu.screener_backend.analysis.filter.MinAgeFilter;
import dev.abu.screener_backend.marketdata.Instrument;
import dev.abu.screener_backend.marketdata.InstrumentTest;
import dev.abu.screener_backend.marketdata.Venue;
import dev.abu.screener_backend.marketdata.core.book.OrderBook;
import dev.abu.screener_backend.feed.depth.ClassifiedLevel;
import dev.abu.screener_backend.feed.depth.FeedEventType;
import dev.abu.screener_backend.feed.depth.OrderBookFeedStore;
import dev.abu.screener_backend.feed.depth.OrderBookUpdate;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Venue-awareness of {@link OrderBookClassifier} (state and feed entries are keyed by
 * {@code feedKey}, user rules are matched by {@code ruleKey}) and the tier-0 exclusion.
 */
class OrderBookClassifierTest {

    private static final long T0 = 1_700_000_000_000L;

    private static final Instrument BINANCE_BTC = Instrument.of(0, Venue.BINANCE_SPOT, "BTCUSDT", "BTC", "USDT");
    private static final Instrument OTHER_BTC = InstrumentTest.otherExchangeSpot(1, "BTC_USDT", "BTC", "USDT");
    private static final Instrument BINANCE_XYZ = Instrument.of(2, Venue.BINANCE_FUTURES, "XYZUSDT", "XYZ", "USDT");
    private static final Instrument MEXC_XYZ = Instrument.of(3, Venue.MEXC_FUTURES, "XYZ_USDT", "XYZ", "USDT");

    /** A synced book around 100,000 with a ~$10M bid at the spread — visible under every rule here. */
    private static OrderBook syncedBook() {
        OrderBook ob = new OrderBook(0.1, 2.0);
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
        OrderBookClassifier classifier = new OrderBookClassifier(global, new DefaultClassificationRule(), Map.of());

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
        OrderBookClassifier classifier = new OrderBookClassifier(new OrderBookFeedStore(), new DefaultClassificationRule(), Map.of());
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
        OrderBookClassifier classifier = new OrderBookClassifier(new OrderBookFeedStore(), new DefaultClassificationRule(), Map.of());
        UserClassificationContext ctx = contextWithRule("BTCUSDT:FUTURES");
        classifier.setActiveUserContexts(new UserClassificationContext[]{ctx});

        classifier.process(BINANCE_BTC, syncedBook());

        assertTrue(ctx.states().isEmpty());
        assertTrue(ctx.feedStore().getSnapshot().isEmpty());
    }

    @Test
    @DisplayName("tier-0 levels are never emitted, and a side with none ships empty")
    void tierZeroLevelsAreNeverEmitted() {
        OrderBookClassifier classifier = new OrderBookClassifier(new OrderBookFeedStore(), new DefaultClassificationRule(), Map.of());
        UserClassificationContext ctx = contextWithRule("BTCUSDT:SPOT");
        classifier.setActiveUserContexts(new UserClassificationContext[]{ctx});

        // One ~$10M tier-1 bid; every other level is ~$1K and tier 0 under the 100K/1% rule.
        OrderBook ob = syncedBook();
        ob.applyLevel(true, 99_980.0, 0.01, T0);
        ob.applyLevel(true, 99_970.0, 0.01, T0);
        ob.applyLevel(false, 100_020.0, 0.01, T0);
        ob.computeDistance();

        classifier.process(BINANCE_BTC, ob);

        OrderBookUpdate update = ctx.feedStore().getSnapshot().get(BINANCE_BTC.feedKey());
        assertEquals(FeedEventType.ADD, update.type());
        assertEquals(99_990.0, update.bids()[0].price());
        assertEquals(1, update.bids()[0].tier());
        for (int i = 1; i < OrderBookClassifier.TOP_LEVELS; i++) assertNull(update.bids()[i]);
        for (ClassifiedLevel ask : update.asks()) assertNull(ask);
    }

    @Test
    @DisplayName("a side losing its last tier-1+ level is cleared by an UPDATE")
    void sideLosingLastQualifyingLevelIsCleared() {
        OrderBookClassifier classifier = new OrderBookClassifier(new OrderBookFeedStore(), new DefaultClassificationRule(), Map.of());
        UserClassificationContext ctx = contextWithRule("BTCUSDT:SPOT");
        classifier.setActiveUserContexts(new UserClassificationContext[]{ctx});

        OrderBook ob = syncedBook();
        ob.applyLevel(false, 100_010.0, 100.0, T0); // ask becomes ~$10M, tier 1
        ob.computeDistance();
        classifier.process(BINANCE_BTC, ob);
        assertEquals(1, ctx.feedStore().getSnapshot().get(BINANCE_BTC.feedKey()).asks()[0].tier());

        ob.applyLevel(false, 100_010.0, 0.01, T0);  // back to ~$1K, tier 0
        ob.computeDistance();
        classifier.process(BINANCE_BTC, ob);

        OrderBookUpdate update = ctx.feedStore().getSnapshot().get(BINANCE_BTC.feedKey());
        assertEquals(FeedEventType.UPDATE, update.type());
        assertEquals(1, update.bids()[0].tier());
        for (ClassifiedLevel ask : update.asks()) assertNull(ask);
    }

    /**
     * A synced book around 100,000 with dust at the spread and one ~$30.9M ask at 3% — tier 4 under
     * the default (normal) rule, and nothing else qualifies.
     */
    private static OrderBook bookWithFarWall() {
        OrderBook ob = new OrderBook(0.1, 2.0);
        ob.applyLevel(true, 99_990.0, 0.01, T0);
        ob.applyLevel(false, 100_010.0, 0.01, T0);
        ob.applyLevel(false, 103_000.0, 300.0, T0);
        ob.computeDistance();
        ob.markSynced();
        return ob;
    }

    @Test
    @DisplayName("a venue's max-visible-distance hides far levels from the default and user passes on that venue only")
    void visibilityCapIsPerVenue() {
        OrderBookFeedStore global = new OrderBookFeedStore();
        OrderBookClassifier classifier = new OrderBookClassifier(global, new DefaultClassificationRule(),
                Map.of(Venue.MEXC_FUTURES, VenueClassification.capped(0.01)));
        UserClassificationContext ctx = new UserClassificationContext(UUID.randomUUID(),
                new UserClassificationRules(Map.of("XYZUSDT:FUTURES", ThresholdClassificationRule.of(List.of(
                        new ThresholdClassificationRule.TierThreshold(1, 100_000, 0.05))))),
                new OrderBookFeedStore(),
                new ConcurrentHashMap<>());
        classifier.setActiveUserContexts(new UserClassificationContext[]{ctx});

        classifier.process(BINANCE_XYZ, bookWithFarWall());
        classifier.process(MEXC_XYZ, bookWithFarWall());

        OrderBookUpdate binance = global.getSnapshot().get(BINANCE_XYZ.feedKey());
        assertEquals(103_000.0, binance.asks()[0].price());
        assertEquals(4, binance.asks()[0].tier());
        assertNull(global.getSnapshot().get(MEXC_XYZ.feedKey()));

        Map<String, OrderBookUpdate> personal = ctx.feedStore().getSnapshot();
        assertEquals(103_000.0, personal.get(BINANCE_XYZ.feedKey()).asks()[0].price());
        assertNull(personal.get(MEXC_XYZ.feedKey()));
    }

    @Test
    @DisplayName("levels inside a venue's max-visible-distance classify as usual")
    void levelsInsideVisibilityCapClassify() {
        OrderBookFeedStore global = new OrderBookFeedStore();
        OrderBookClassifier classifier = new OrderBookClassifier(global, new DefaultClassificationRule(),
                Map.of(Venue.MEXC_FUTURES, VenueClassification.capped(0.01)));

        OrderBook ob = bookWithFarWall();
        ob.applyLevel(false, 100_500.0, 20.0, T0); // ~$2M at 0.5%: tier 3
        ob.computeDistance();
        classifier.process(MEXC_XYZ, ob);

        OrderBookUpdate update = global.getSnapshot().get(MEXC_XYZ.feedKey());
        assertEquals(FeedEventType.ADD, update.type());
        assertEquals(100_500.0, update.asks()[0].price());
        assertEquals(3, update.asks()[0].tier());
        assertNull(update.asks()[1]); // the 3% wall stays hidden
    }

    @Test
    @DisplayName("levels a venue's filter rejects free their slots, in the default and user passes alike")
    void filteredLevelsFreeTheirSlots() {
        // Seven tier-1+ asks; the filter rejects the two largest.
        OrderBook ob = new OrderBook(0.1, 2.0);
        ob.applyLevel(true, 99_990.0, 0.01, T0);
        for (int i = 1; i <= 7; i++) ob.applyLevel(false, 100_000.0 + i * 10, i * 10.0, T0);
        ob.computeDistance();
        ob.markSynced();
        LevelFilter rejectTwoLargest = (level, isBid, book, now) -> isBid || level.getKey() < 100_060.0;

        OrderBookFeedStore global = new OrderBookFeedStore();
        OrderBookClassifier classifier = new OrderBookClassifier(global, new DefaultClassificationRule(),
                Map.of(Venue.MEXC_FUTURES, new VenueClassification(0.01, rejectTwoLargest)));
        UserClassificationContext ctx = contextWithRule("XYZUSDT:FUTURES");
        classifier.setActiveUserContexts(new UserClassificationContext[]{ctx});

        classifier.process(MEXC_XYZ, ob);

        for (OrderBookUpdate update : List.of(global.getSnapshot().get(MEXC_XYZ.feedKey()),
                ctx.feedStore().getSnapshot().get(MEXC_XYZ.feedKey()))) {
            assertArrayEquals(new double[]{100_010.0, 100_020.0, 100_030.0, 100_040.0, 100_050.0},
                    sortedAskPrices(update));
        }
    }

    /** The ask prices of {@code update}'s top 5, sorted. */
    private static double[] sortedAskPrices(OrderBookUpdate update) {
        double[] prices = new double[OrderBookClassifier.TOP_LEVELS];
        for (int i = 0; i < prices.length; i++) prices[i] = update.asks()[i].price();
        Arrays.sort(prices);
        return prices;
    }

    @Test
    @DisplayName("with the five largest levels too young, the next five fill the top 5 on the filtered venue only")
    void minAgeFilterPassesOlderLevels() {
        // Ten tier-1+ asks inside 1%: the five largest just placed, the five smaller ones a minute old.
        long now = System.currentTimeMillis();
        OrderBook ob = new OrderBook(0.1, 2.0);
        ob.applyLevel(true, 99_990.0, 0.01, now - 60_000);
        for (int i = 1; i <= 10; i++) {
            ob.applyLevel(false, 100_000.0 + i * 10, i * 10.0, i <= 5 ? now - 60_000 : now);
        }
        ob.computeDistance();
        ob.markSynced();

        OrderBookFeedStore global = new OrderBookFeedStore();
        OrderBookClassifier classifier = new OrderBookClassifier(global, new DefaultClassificationRule(),
                Map.of(Venue.MEXC_FUTURES, new VenueClassification(0.01, new MinAgeFilter(Duration.ofSeconds(30)))));
        UserClassificationContext ctx = contextWithRule("XYZUSDT:FUTURES");
        classifier.setActiveUserContexts(new UserClassificationContext[]{ctx});

        classifier.process(MEXC_XYZ, ob);
        classifier.process(BINANCE_XYZ, ob);

        double[] old = {100_010.0, 100_020.0, 100_030.0, 100_040.0, 100_050.0};
        double[] largest = {100_060.0, 100_070.0, 100_080.0, 100_090.0, 100_100.0};
        assertArrayEquals(old, sortedAskPrices(global.getSnapshot().get(MEXC_XYZ.feedKey())));
        assertArrayEquals(old, sortedAskPrices(ctx.feedStore().getSnapshot().get(MEXC_XYZ.feedKey())));
        assertArrayEquals(largest, sortedAskPrices(global.getSnapshot().get(BINANCE_XYZ.feedKey())));
        assertArrayEquals(largest, sortedAskPrices(ctx.feedStore().getSnapshot().get(BINANCE_XYZ.feedKey())));
    }

    @Test
    @DisplayName("a book whose every tier-1+ level is filtered out is LOW and emits nothing")
    void fullyFilteredBookIsLow() {
        long now = System.currentTimeMillis();
        OrderBook ob = new OrderBook(0.1, 2.0);
        ob.applyLevel(true, 99_990.0, 100.0, now);  // ~$10M, just placed
        ob.applyLevel(false, 100_010.0, 0.01, now);
        ob.computeDistance();
        ob.markSynced();

        OrderBookFeedStore global = new OrderBookFeedStore();
        OrderBookClassifier classifier = new OrderBookClassifier(global, new DefaultClassificationRule(),
                Map.of(Venue.MEXC_FUTURES, new VenueClassification(0.01, new MinAgeFilter(Duration.ofSeconds(30)))));

        classifier.process(MEXC_XYZ, ob);

        assertNull(global.getSnapshot().get(MEXC_XYZ.feedKey()));
    }
}
