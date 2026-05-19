package dev.abu.screener_backend.ticker;

import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Collection;
import java.util.Comparator;
import java.util.List;

/**
 * Debug REST endpoint exposing the current state of the ticker registry.
 *
 * <p>Intended for development and operational verification only — not part of the
 * public screener API. Useful for confirming that the correct number of tickers have
 * been loaded and that spot/futures flags are applied correctly.
 */
@RestController
@RequestMapping("/api/screener")
@RequiredArgsConstructor
public class TickerController {

    private final TickerRegistry registry;

    /**
     * Returns all tickers currently tracked by the screener, sorted alphabetically,
     * along with aggregate counts.
     *
     * @return a summary of the ticker registry including per-market counts and the full list
     */
    @GetMapping("/tickers")
    public TickerSummaryResponse getTickers() {
        Collection<Ticker> tickers = registry.getAll().values();
        int spotCount    = (int) tickers.stream().filter(Ticker::hasSpot).count();
        int futuresCount = (int) tickers.stream().filter(Ticker::hasFutures).count();
        List<Ticker> sorted = tickers.stream()
                .sorted(Comparator.comparing(Ticker::symbol))
                .toList();
        return new TickerSummaryResponse(sorted.size(), spotCount, futuresCount, sorted);
    }

    /**
     * Response body for the {@code GET /api/screener/tickers} endpoint.
     *
     * @param total        total number of tracked tickers
     * @param spotCount    number of tickers also active on the spot market
     * @param futuresCount number of tickers with an active futures contract (equals {@code total})
     * @param tickers      alphabetically-sorted list of all tracked tickers
     */
    public record TickerSummaryResponse(
            int total,
            int spotCount,
            int futuresCount,
            List<Ticker> tickers
    ) {}
}
