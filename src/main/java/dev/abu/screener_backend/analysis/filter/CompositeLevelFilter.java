package dev.abu.screener_backend.analysis.filter;

import dev.abu.screener_backend.marketdata.core.book.OrderBook;
import dev.abu.screener_backend.marketdata.core.book.PriceLevelEntry;

import java.util.Arrays;
import java.util.List;
import java.util.Map;

/** Accepts a level only if every filter accepts it, checked in order: put the cheaper filters first. */
public final class CompositeLevelFilter implements LevelFilter {

    private final LevelFilter[] filters;

    private CompositeLevelFilter(LevelFilter[] filters) {
        this.filters = filters;
    }

    /** {@link LevelFilter#ACCEPT_ALL} for none, the filter itself for one, otherwise a composite. */
    public static LevelFilter allOf(List<LevelFilter> filters) {
        return switch (filters.size()) {
            case 0 -> ACCEPT_ALL;
            case 1 -> filters.getFirst();
            default -> new CompositeLevelFilter(filters.toArray(LevelFilter[]::new));
        };
    }

    @Override
    public boolean accept(Map.Entry<Double, PriceLevelEntry> level, boolean isBid, OrderBook book, long nowMillis) {
        for (LevelFilter f : filters) {
            if (!f.accept(level, isBid, book, nowMillis)) return false;
        }
        return true;
    }

    @Override
    public String toString() {
        return "allOf" + Arrays.toString(filters);
    }
}
