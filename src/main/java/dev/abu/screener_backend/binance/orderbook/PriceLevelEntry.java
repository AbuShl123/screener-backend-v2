package dev.abu.screener_backend.binance.orderbook;

public class PriceLevelEntry {

    public double quantity;
    public final long firstSeenMillis;

    public PriceLevelEntry(double quantity, long firstSeenMillis) {
        this.quantity       = quantity;
        this.firstSeenMillis = firstSeenMillis;
    }
}
