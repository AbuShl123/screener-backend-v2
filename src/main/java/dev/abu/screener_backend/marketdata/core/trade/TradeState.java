package dev.abu.screener_backend.marketdata.core.trade;

/**
 * One instrument's open sweep: the fills {@link TradeDetector} has folded together since the last
 * trade that did not continue it.
 *
 * <p>Held in the instrument's slot and touched only by its shard consumer thread, so it is plain
 * mutable primitives with no synchronization. Allocated once per instrument and reused, so following
 * a sweep never allocates.
 *
 * <p>Fields are package-private: {@link TradeDetector} owns the rules, this class is only storage.
 */
public final class TradeState {

    /** A sweep is in progress. {@code false} only before the instrument's first trade. */
    boolean open;
    boolean aggressorBuy;
    /** Venue trade time shared by every fill of the sweep. */
    long tradeTimeMs;
    /** First fill's first trade id, or {@code -1} when the venue has none. */
    long firstTradeId;
    /** Latest fill's last trade id, or {@code -1}. */
    long lastTradeId;
    double sumQty;
    /** Sum of {@code price × qty} over the fills, in fill order. */
    double sumNotional;
    /** Largest single fill's {@code price × qty}: tells whether the sweep alerts only once summed. */
    double maxFillNotional;
    double firstPrice;
    double lastPrice;
    int fills;

    /** Starts a new sweep with this fill, discarding the previous one. */
    void open(double price, double qty, boolean aggressorBuy, long tradeTimeMs,
              long firstTradeId, long lastTradeId) {
        double notional = price * qty;
        this.open = true;
        this.aggressorBuy = aggressorBuy;
        this.tradeTimeMs = tradeTimeMs;
        this.firstTradeId = firstTradeId;
        this.lastTradeId = lastTradeId;
        this.sumQty = qty;
        this.sumNotional = notional;
        this.maxFillNotional = notional;
        this.firstPrice = price;
        this.lastPrice = price;
        this.fills = 1;
    }

    /** Folds one more fill into the open sweep. */
    void add(double price, double qty, long lastTradeId) {
        double notional = price * qty;
        this.lastTradeId = lastTradeId;
        this.sumQty += qty;
        this.sumNotional += notional;
        if (notional > maxFillNotional) maxFillNotional = notional;
        this.lastPrice = price;
        this.fills++;
    }
}
