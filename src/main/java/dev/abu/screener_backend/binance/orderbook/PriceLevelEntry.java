package dev.abu.screener_backend.binance.orderbook;

public class PriceLevelEntry {

    public double quantity;
    public final long firstSeenMillis;
    public double distance; // fractional distance from mid-price (0.05 = 5%); updated after each diff in apply30PercentFilter

    public PriceLevelEntry(double quantity, long firstSeenMillis) {
        this.quantity        = quantity;
        this.firstSeenMillis = firstSeenMillis;
    }
}
