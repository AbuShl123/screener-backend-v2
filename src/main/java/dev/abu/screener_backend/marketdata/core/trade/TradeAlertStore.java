package dev.abu.screener_backend.marketdata.core.trade;

import dev.abu.screener_backend.config.TradeAlertProperties;
import dev.abu.screener_backend.marketdata.Venue;
import org.springframework.stereotype.Component;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Iterator;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The most recent trade alerts, plus a count per {@code (venue, native symbol)} since startup.
 *
 * <p>The hand-off between detection and delivery, as {@code OrderBookFeedStore} is for depth: the
 * shard threads {@link #add} alerts, the {@code /ws} broadcaster takes the new ones with
 * {@link #drainSince}, and the admin endpoints read {@link #recent} and {@link #counts}. Draining is
 * non-destructive, so the admin view and the feed read the same ring.
 *
 * <p>Alerts are rare (a few a second at most, against hundreds of trades), so a {@code synchronized}
 * deque and a {@link ConcurrentHashMap#merge} are enough; neither runs per trade.
 */
@Component
public class TradeAlertStore {

    private final int capacity;
    /** Newest first. Guarded by itself. */
    private final ArrayDeque<TradeAlert> recent;
    /** Alerts ever added, including those since dropped from {@link #recent}. Guarded by {@code recent}. */
    private long added;
    private final ConcurrentHashMap<SymbolKey, Long> counts = new ConcurrentHashMap<>();

    public TradeAlertStore(TradeAlertProperties props) {
        this.capacity = props.recentCapacity();
        this.recent = new ArrayDeque<>(capacity);
    }

    /** Rare path: called on a shard thread once per alert. */
    public void add(TradeAlert alert) {
        synchronized (recent) {
            if (recent.size() == capacity) recent.removeLast();
            recent.addFirst(alert);
            added++;
        }
        counts.merge(new SymbolKey(alert.instrument().venue(), alert.instrument().nativeSymbol()), 1L, Long::sum);
    }

    /**
     * Appends to {@code out}, oldest first, every retained alert added after the first {@code seen},
     * and returns the number of alerts ever added: the caller's next {@code seen}. Non-destructive.
     *
     * <p>If more than {@code recent-capacity} alerts arrived since {@code seen}, the oldest of them are
     * gone: the caller sees it as {@code (returned − seen) > } the number appended.
     *
     * @param seen what the previous call returned; {@code 0} on the first call
     */
    public long drainSince(long seen, List<TradeAlert> out) {
        synchronized (recent) {
            int n = (int) Math.min(added - seen, recent.size());
            if (n <= 0) return added;
            // The deque is newest first, so walk it from the oldest end and skip the ones already seen.
            Iterator<TradeAlert> oldestFirst = recent.descendingIterator();
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
     */
    public List<TradeAlert> recent(Venue venue, String symbol, int limit) {
        List<TradeAlert> out = new ArrayList<>(Math.min(Math.max(limit, 0), capacity));
        synchronized (recent) {
            for (TradeAlert alert : recent) {
                if (out.size() >= limit) break;
                if (venue != null && alert.instrument().venue() != venue) continue;
                if (symbol != null && !alert.instrument().nativeSymbol().equalsIgnoreCase(symbol)) continue;
                out.add(alert);
            }
        }
        return out;
    }

    /** Alerts per {@code (venue, native symbol)} since startup, most alerts first. */
    public List<SymbolCount> counts() {
        List<SymbolCount> out = new ArrayList<>(counts.size());
        counts.forEach((key, count) -> out.add(new SymbolCount(key.venue(), key.symbol(), count)));
        out.sort(Comparator.comparingLong(SymbolCount::alerts).reversed()
                .thenComparing(SymbolCount::venue)
                .thenComparing(SymbolCount::symbol));
        return out;
    }

    /** The retained-alert bound, {@code screener.trade-alerts.recent-capacity}. */
    public int capacity() {
        return capacity;
    }

    private record SymbolKey(Venue venue, String symbol) {}

    /**
     * @param symbol native symbol, the spelling a {@code min-notional} override is keyed by
     */
    public record SymbolCount(Venue venue, String symbol, long alerts) {}
}
