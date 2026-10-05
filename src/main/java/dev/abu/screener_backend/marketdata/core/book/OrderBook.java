package dev.abu.screener_backend.marketdata.core.book;

import lombok.Getter;
import lombok.extern.slf4j.Slf4j;

import java.util.Comparator;
import java.util.TreeMap;

/**
 * Local orderbook for a single instrument.
 */
@Slf4j
public class OrderBook {

    @Getter
    private volatile OrderBookState state = OrderBookState.PENDING;

    private final double filterThreshold;

    /** Live bids TreeMap. Must only be accessed by this shard's consumer thread. */
    @Getter
    private final TreeMap<Double, PriceLevelEntry> bids; // reverseOrder → firstKey() = best bid

    /** Live asks TreeMap. Must only be accessed by this shard's consumer thread. */
    @Getter
    private final TreeMap<Double, PriceLevelEntry> asks; // natural order → firstKey() = best ask

    public OrderBook(double filterThreshold) {
        this.filterThreshold = filterThreshold;
        this.bids = new TreeMap<>(Comparator.reverseOrder());
        this.asks = new TreeMap<>();
    }

    public void markRecovering() {
        this.state = OrderBookState.RECOVERING;
    }

    public void markPending() {
        this.state = OrderBookState.PENDING;
    }

    public void markSynced() {
        this.state = OrderBookState.SYNCED;
    }

    /**
     * Stores a price level into a tree set. If qty is 0, removes the price entry.
     * If an entry with a given price exists - updates the existing value.
     * @param isBid true if a given price level is a bid order, false otherwise
     * @param price order price
     * @param qty quantity being traded
     * @param millis time discovered in milliseconds
     */
    public void applyLevel(boolean isBid, double price, double qty, long millis) {
        TreeMap<Double, PriceLevelEntry> map = isBid ? bids : asks;
        if (qty == 0.0) {
            map.remove(price);
            return;
        }
        PriceLevelEntry entry = map.get(price);
        if (entry == null) {
            map.put(price, new PriceLevelEntry(qty, millis));
        } else {
            entry.quantity = qty;
        }
    }

    public void clearLevels() {
        bids.clear();
        asks.clear();
    }

    /**
     * Sweep all levels outside ±filterThreshold of mid-price and update distance on survivors.
     * <p>
     * {@code distance} is stored as a <b>fraction</b> of mid-price ({@code 0.05} = 5%), never a
     * percentage. This is the project-wide unit for proximity: the classifier and every
     * {@link dev.abu.screener_backend.analysis.ClassificationRule} compare it directly against
     * fractional thresholds, and the orderbook's own {@code filterThreshold} is a fraction too.
     */
    public void computeDistance() {
        if (bids.isEmpty() || asks.isEmpty()) return;
        double midPrice = (bids.firstKey() + asks.firstKey()) / 2.0;
        double lower = midPrice * (1.0 - filterThreshold);
        double upper = midPrice * (1.0 + filterThreshold);

        var bidIt = bids.entrySet().iterator();
        while (bidIt.hasNext()) {
            var e = bidIt.next();
            double key = e.getKey();
            if (key < lower || key > upper) {
                bidIt.remove();
            } else {
                e.getValue().distance = Math.abs(key - midPrice) / midPrice;
            }
        }

        var askIt = asks.entrySet().iterator();
        while (askIt.hasNext()) {
            var e = askIt.next();
            double key = e.getKey();
            if (key < lower || key > upper) {
                askIt.remove();
            } else {
                e.getValue().distance = Math.abs(key - midPrice) / midPrice;
            }
        }
    }

}
