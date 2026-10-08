package dev.abu.screener_backend.marketdata.core.book;

import dev.abu.screener_backend.config.OrderbookProperties;
import dev.abu.screener_backend.marketdata.Instrument;
import dev.abu.screener_backend.marketdata.spi.DepthSyncStrategy;
import dev.abu.screener_backend.marketdata.spi.SyncStrategyRegistry;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.Arrays;

/**
 * All live {@link BookSlot}s, in an array indexed by instrument id.
 *
 * <p>Replaces the {@code ConcurrentHashMap<String, OrderBook>} store keyed on
 * {@code "SYMBOL:MARKET"}. That key had to be rebuilt on every depth message; an array index does
 * not, so both the map lookup and the per-message concatenation are gone.
 *
 * <h3>Threading and publication order</h3>
 * {@link #allocate} is called by the discovery thread only and mutates a private staging copy.
 * {@link #publish} makes the batch visible with a single volatile store. Discovery must
 * <b>allocate all → publish → fire the universe-changed event → subscribe</b>: if a subscribe frame
 * went out first, a reader could resolve an id whose index is past the end of the published array.
 *
 * <p>{@link #get} bounds-checks and returns {@code null} rather than throwing. A {@code null} slot
 * should be permanently impossible; the caller counts and drops it instead of dereferencing.
 */
@Component
@RequiredArgsConstructor
public class BookSlotTable {

    private static final BookSlot[] EMPTY = new BookSlot[0];

    private final OrderbookProperties props;
    private final SyncStrategyRegistry strategyRegistry;

    /** Hot path reads this. Copy-on-write: never mutated after publication. */
    private volatile BookSlot[] slots = EMPTY;

    /** Discovery thread only; {@code null} between publications. */
    private BookSlot[] staging;

    /**
     * Creates the slot and its {@link OrderBook} for a newly registered instrument. Not visible to
     * readers until {@link #publish()}. Discovery thread only.
     */
    public void allocate(Instrument instrument) {
        if (staging == null) {
            staging = slots.clone();
        }
        int id = instrument.id();
        if (id >= staging.length) {
            staging = Arrays.copyOf(staging, Math.max(id + 1, staging.length * 2));
        }
        DepthSyncStrategy strategy = strategyRegistry.forVenue(instrument.venue());
        staging[id] = new BookSlot(instrument, new OrderBook(props.priceFilterThreshold(), props.ageResetGrowth()),
                strategy, strategy.newContext());
    }

    /** Publishes every slot allocated since the last call. Discovery thread only. */
    public void publish() {
        if (staging == null) return;
        slots = staging;
        staging = null;
    }

    /** Hot path. Returns {@code null} for an id with no slot — never throws. */
    public BookSlot get(int id) {
        BookSlot[] snapshot = slots;
        return (id >= 0 && id < snapshot.length) ? snapshot[id] : null;
    }

    /** Cold path — monitoring and the sync-count log. The array must not be modified. */
    public BookSlot[] snapshot() {
        return slots;
    }
}
