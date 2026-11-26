package dev.abu.screener_backend.marketdata.core.candle;

/**
 * One instrument's recent candles: the forming candle and a ring of the last closed ones.
 *
 * <p>Lives in the instrument's slot and is mutated only on its shard consumer thread, so it needs
 * no locks. Primitive arrays, allocated once: an update allocates nothing.
 *
 * <h3>Rollover</h3>
 * A candle closes when a push for a <em>later</em> open time arrives, not on the venue's "closed"
 * flag: that flag is lost if the connection drops at the wrong moment. The forming candle is then
 * pushed into the ring with its last known values.
 *
 * <h3>Gaps</h3>
 * When the next open time is more than one interval later, minutes are missing (a reconnect, or a
 * minute in which the venue sent nothing). The ring then no longer holds consecutive candles, so
 * {@link #filled()} restarts from zero and the detectors wait for their windows to refill. Filling
 * the hole with empty candles would be wrong after a disconnect: the missing minutes would look
 * like zero volume and fake a volume spike once data resumes.
 *
 * <p>Indexes passed to the {@code closed*} accessors count back from the newest closed candle:
 * {@code 0} is the candle that closed last.
 */
public final class CandleStore {

    /** The push was for an older candle than the forming one, and was ignored. */
    public static final int STALE = 0;
    /** The forming candle was updated (or the first candle started). */
    public static final int UPDATED = 1;
    /** The forming candle closed into the ring and the push started the next one. */
    public static final int ROLLED = 2;
    /** Minutes were skipped: the ring restarted empty and the push started a new forming candle. */
    public static final int GAP = 3;

    private final long intervalMs;
    private final int capacity;
    private final double[] highs;
    private final double[] lows;
    private final double[] quoteVolumes;
    /** Slot of the newest closed candle. */
    private int head = -1;
    /** Consecutive closed candles held, newest back, at most {@link #capacity}. */
    private int filled;

    /** Open time of the forming candle, or {@code -1} before the first push. */
    private long formingOpenTime = -1;
    private double formingHigh;
    private double formingLow;
    private double formingClose;
    private double formingQuoteVolume;

    /**
     * @param capacity   closed candles kept; must cover the longest detector window
     * @param intervalMs candle interval, e.g. 60 000 for 1m candles
     */
    public CandleStore(int capacity, long intervalMs) {
        if (capacity <= 0) throw new IllegalArgumentException("capacity must be > 0, got " + capacity);
        if (intervalMs <= 0) throw new IllegalArgumentException("intervalMs must be > 0, got " + intervalMs);
        this.capacity = capacity;
        this.intervalMs = intervalMs;
        this.highs = new double[capacity];
        this.lows = new double[capacity];
        this.quoteVolumes = new double[capacity];
    }

    /**
     * Applies one push of a candle's full current state.
     *
     * @return {@link #STALE}, {@link #UPDATED}, {@link #ROLLED} or {@link #GAP}
     */
    public int update(long openTime, double high, double low, double close, double quoteVolume) {
        int result;
        if (formingOpenTime < 0 || openTime == formingOpenTime) {
            result = UPDATED;
        } else if (openTime < formingOpenTime) {
            return STALE;
        } else if (openTime - formingOpenTime == intervalMs) {
            push();
            result = ROLLED;
        } else {
            filled = 0;
            result = GAP;
        }
        formingOpenTime = openTime;
        formingHigh = high;
        formingLow = low;
        formingClose = close;
        formingQuoteVolume = quoteVolume;
        return result;
    }

    private void push() {
        head = head + 1 == capacity ? 0 : head + 1;
        highs[head] = formingHigh;
        lows[head] = formingLow;
        quoteVolumes[head] = formingQuoteVolume;
        if (filled < capacity) filled++;
    }

    private int index(int back) {
        if (back < 0 || back >= filled) {
            throw new IndexOutOfBoundsException("closed candle " + back + " of " + filled);
        }
        int i = head - back;
        return i < 0 ? i + capacity : i;
    }

    /** Consecutive closed candles available, newest back. */
    public int filled() {
        return filled;
    }

    public int capacity() {
        return capacity;
    }

    public long intervalMs() {
        return intervalMs;
    }

    /** {@code true} once the first push has arrived. */
    public boolean hasForming() {
        return formingOpenTime >= 0;
    }

    public long formingOpenTime() {
        return formingOpenTime;
    }

    public double formingHigh() {
        return formingHigh;
    }

    public double formingLow() {
        return formingLow;
    }

    public double formingClose() {
        return formingClose;
    }

    public double formingQuoteVolume() {
        return formingQuoteVolume;
    }

    /** @param back {@code 0} for the newest closed candle, up to {@code filled() - 1} */
    public double closedHigh(int back) {
        return highs[index(back)];
    }

    /** @param back {@code 0} for the newest closed candle, up to {@code filled() - 1} */
    public double closedLow(int back) {
        return lows[index(back)];
    }

    /** @param back {@code 0} for the newest closed candle, up to {@code filled() - 1} */
    public double closedQuoteVolume(int back) {
        return quoteVolumes[index(back)];
    }
}
