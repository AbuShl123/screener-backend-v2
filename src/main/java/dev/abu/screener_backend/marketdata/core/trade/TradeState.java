package dev.abu.screener_backend.marketdata.core.trade;

/**
 * One instrument's sweep: the fills {@link TradeDetector} has folded together since the last
 * trade that did not continue it.
 *
 * <p>Held in the instrument's slot and touched only by its shard consumer thread, so it is plain
 * mutable primitives with no synchronization. Allocated once per instrument and reused, so following
 * a sweep never allocates.
 *
 * <p>A closed sweep's fields stay in place until the next {@code open} overwrites them; only
 * {@link #open} says whether they describe a sweep in progress.
 *
 * <p>Fields are package-private: {@link TradeDetector} owns the rules, this class is only storage.
 */
public final class TradeState {

    /**
     * The venue's decoder reports one fill per resting order, so a sweep can continue at an equal
     * price ({@code TradeDecoder.reportsMakerFills}). Fixed for the instrument's lifetime.
     */
    final boolean makerFills;
    /**
     * A sweep is complete at the end of its frame ({@code TradeDecoder.closesSweepAtFrameEnd}).
     * Fixed for the instrument's lifetime.
     */
    final boolean closeAtFrameEnd;

    /** A sweep is in progress: opened and not yet judged. */
    boolean open;
    boolean aggressorBuy;
    /** Venue trade time of the first fill. */
    long tradeTimeMs;
    /** First fill's first trade id, or {@code -1} when the venue has none. */
    long firstTradeId;
    /** Latest fill's last trade id, or {@code -1}. */
    long lastTradeId;
    /** The taker order's venue id, or {@code -1} when the venue has none. */
    long takerOrderId;
    double sumQty;
    /** Sum of {@code price × qty} over the fills, in fill order. */
    double sumNotional;
    /** Notional summed over the current price level: the fills since the price last changed. */
    double levelNotional;
    /** Largest {@link #levelNotional}: tells whether the sweep alerts only once its levels are summed. */
    double maxLevelNotional;
    double firstPrice;
    double lastPrice;
    int fills;
    /** Price levels taken: 1 plus the number of fills whose price differs from the previous fill's. */
    int levels;
    /**
     * Local processing time of the latest fill, epoch ms (the detector's clock): what the idle close
     * measures against. It is the shard's time, not the venue's, so ring lag delays fills and ticks
     * alike.
     */
    long lastFillAtMs;
    /**
     * The sweep was closed by the idle close rather than by a trade. Kept with the closed sweep's
     * fields until the next {@code open}, so that a fill which would have continued it is counted as
     * an idle split.
     */
    boolean closedByIdle;

    public TradeState(boolean makerFills, boolean closeAtFrameEnd) {
        this.makerFills = makerFills;
        this.closeAtFrameEnd = closeAtFrameEnd;
    }

    /** Starts a new sweep with this fill, discarding the previous one. */
    void open(double price, double qty, boolean aggressorBuy, long tradeTimeMs,
              long firstTradeId, long lastTradeId, long takerOrderId, long nowMs) {
        double notional = price * qty;
        this.open = true;
        this.closedByIdle = false;
        this.lastFillAtMs = nowMs;
        this.aggressorBuy = aggressorBuy;
        this.tradeTimeMs = tradeTimeMs;
        this.firstTradeId = firstTradeId;
        this.lastTradeId = lastTradeId;
        this.takerOrderId = takerOrderId;
        this.sumQty = qty;
        this.sumNotional = notional;
        this.levelNotional = notional;
        this.maxLevelNotional = notional;
        this.firstPrice = price;
        this.lastPrice = price;
        this.fills = 1;
        this.levels = 1;
    }

    /** Folds one more fill into the open sweep. */
    void add(double price, double qty, long lastTradeId, long nowMs) {
        double notional = price * qty;
        this.lastFillAtMs = nowMs;
        if (price != lastPrice) {
            levels++;
            levelNotional = notional;
        } else {
            levelNotional += notional;
        }
        if (levelNotional > maxLevelNotional) maxLevelNotional = levelNotional;
        this.lastTradeId = lastTradeId;
        this.sumQty += qty;
        this.sumNotional += notional;
        this.lastPrice = price;
        this.fills++;
    }
}
