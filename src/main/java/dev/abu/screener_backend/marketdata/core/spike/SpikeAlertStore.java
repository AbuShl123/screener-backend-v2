package dev.abu.screener_backend.marketdata.core.spike;

import dev.abu.screener_backend.config.SpikeAlertProperties;
import dev.abu.screener_backend.marketdata.Venue;
import org.springframework.stereotype.Component;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Iterator;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The most recent spike alerts, plus a count per {@code (venue, native symbol, kind)} since startup.
 *
 * <p>The hand-off between detection and delivery, as {@code TradeAlertStore} is for large trades:
 * the shard threads {@link #add} alerts, the {@code /ws} broadcaster takes the new ones with
 * {@link #drainSince}, and the admin endpoints read {@link #recent} and {@link #counts}. Draining
 * is non-destructive, so the admin view and the feed read the same ring.
 *
 * <p>Alerts are rare (a handful a minute across the universe), so a {@code synchronized} deque is
 * enough.
 */
@Component
public class SpikeAlertStore {

    private final int capacity;
    /** Newest first. Guarded by itself. */
    private final ArrayDeque<SpikeAlert> recent;
    /** Alerts ever added, including those since dropped from {@link #recent}. Guarded by {@code recent}. */
    private long added;
    private final ConcurrentHashMap<SymbolKey, Long> counts = new ConcurrentHashMap<>();

    public SpikeAlertStore(SpikeAlertProperties props) {
        this.capacity = props.recentCapacity();
        this.recent = new ArrayDeque<>(capacity);
    }

    /** Rare path: called on a shard thread once per alert. */
    public void add(SpikeAlert alert) {
        synchronized (recent) {
            if (recent.size() == capacity) recent.removeLast();
            recent.addFirst(alert);
            added++;
        }
        counts.merge(new SymbolKey(alert.instrument().venue(), alert.instrument().nativeSymbol(), alert.kind()),
                1L, Long::sum);
    }

    /**
     * Appends to {@code out}, oldest first, every retained alert added after the first {@code seen},
     * and returns the number of alerts ever added: the caller's next {@code seen}. Non-destructive.
     * If more than {@code recent-capacity} alerts arrived since {@code seen}, the oldest are gone.
     *
     * @param seen what the previous call returned; {@code 0} on the first call
     */
    public long drainSince(long seen, List<SpikeAlert> out) {
        synchronized (recent) {
            int n = (int) Math.min(added - seen, recent.size());
            if (n <= 0) return added;
            Iterator<SpikeAlert> oldestFirst = recent.descendingIterator();
            for (int skip = recent.size() - n; skip > 0; skip--) oldestFirst.next();
            while (oldestFirst.hasNext()) out.add(oldestFirst.next());
            return added;
        }
    }

    /**
     * Up to {@code limit} of the retained alerts, newest first.
     *
     * @param venue  only this venue; {@code null} for every venue
     * @param symbol only this native symbol, ignoring case; {@code null} for every symbol
     * @param kind   only this kind; {@code null} for both
     */
    public List<SpikeAlert> recent(Venue venue, String symbol, SpikeKind kind, int limit) {
        List<SpikeAlert> out = new ArrayList<>(Math.min(Math.max(limit, 0), capacity));
        synchronized (recent) {
            for (SpikeAlert alert : recent) {
                if (out.size() >= limit) break;
                if (venue != null && alert.instrument().venue() != venue) continue;
                if (symbol != null && !alert.instrument().nativeSymbol().equalsIgnoreCase(symbol)) continue;
                if (kind != null && alert.kind() != kind) continue;
                out.add(alert);
            }
        }
        return out;
    }

    /** Alerts per {@code (venue, native symbol, kind)} since startup, most alerts first. */
    public List<SymbolCount> counts() {
        List<SymbolCount> out = new ArrayList<>(counts.size());
        counts.forEach((key, count) -> out.add(new SymbolCount(key.venue(), key.symbol(), key.kind(), count)));
        out.sort(Comparator.comparingLong(SymbolCount::alerts).reversed()
                .thenComparing(SymbolCount::venue)
                .thenComparing(SymbolCount::symbol)
                .thenComparing(SymbolCount::kind));
        return out;
    }

    /** The retained-alert bound, {@code screener.spike-alerts.recent-capacity}. */
    public int capacity() {
        return capacity;
    }

    private record SymbolKey(Venue venue, String symbol, SpikeKind kind) {}

    public record SymbolCount(Venue venue, String symbol, SpikeKind kind, long alerts) {}
}
