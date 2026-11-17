package dev.abu.screener_backend.marketdata.core.trade;

import dev.abu.screener_backend.config.TradeAlertProperties;
import dev.abu.screener_backend.marketdata.Venue;
import org.springframework.stereotype.Component;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The most recent trade alerts, plus a count per {@code (venue, native symbol)} since startup.
 *
 * <p>Written by the shard threads, read by REST threads. Alerts are rare (a few a second at most,
 * against hundreds of trades), so a {@code synchronized} deque and a {@link ConcurrentHashMap#merge}
 * are enough; neither runs per trade.
 *
 * <p>An in-memory debug surface for now: where alerts finally live, and what a client is replayed on
 * connect, are Phase 3 decisions ({@code .claude/plans/trade-alerts-plan.md}).
 */
@Component
public class TradeAlertStore {

    private final int capacity;
    /** Newest first. Guarded by itself. */
    private final ArrayDeque<TradeAlert> recent;
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
        }
        counts.merge(new SymbolKey(alert.instrument().venue(), alert.instrument().nativeSymbol()), 1L, Long::sum);
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
