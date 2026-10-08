package dev.abu.screener_backend.marketdata.core.book;

public class PriceLevelEntry {

    public double quantity;
    /** When the current order at this price appeared. Reset by {@link OrderBook#applyLevel} on large growth. */
    public long firstSeenMillis;
    /** Lowest quantity since {@link #firstSeenMillis} was last set. */
    public double minQuantity;
    public double distance; // fractional distance from mid-price (0.05 = 5%); updated after each diff in apply30PercentFilter

    public PriceLevelEntry(double quantity, long firstSeenMillis) {
        this.quantity        = quantity;
        this.firstSeenMillis = firstSeenMillis;
        this.minQuantity     = quantity;
    }
}
