package dev.abu.screener_backend.ticker;

import dev.abu.screener_backend.exchange.Instrument;
import dev.abu.screener_backend.exchange.InstrumentRegistry;
import dev.abu.screener_backend.exchange.Venue;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Comparator;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

/**
 * Debug REST endpoint exposing the tracked instrument universe.
 *
 * <p>Intended for development and operational verification only — not part of the public screener
 * API, and the one endpoint whose response shape is allowed to change with the pipeline. Useful for
 * confirming that the expected number of instruments loaded per venue.
 *
 * <p><b>The {@code id} field is a debugging aid only.</b> It is a process-local array index,
 * reassigned from zero on every restart and meaningless outside this JVM. No client may persist it,
 * cache it, or use it to refer to an instrument — {@code (venue, symbol)} is the identity.
 */
@RestController
@RequestMapping("/api/tickers")
@RequiredArgsConstructor
public class TickerController {

    private final InstrumentRegistry registry;

    /**
     * Returns every tracked instrument sorted by {@code (venue, symbol)}, with per-venue counts.
     *
     * <p>One symbol generally appears twice — once per venue — because spot and futures are
     * separate instruments with separate order books.
     */
    @GetMapping
    public InstrumentSummaryResponse getInstruments() {
        List<InstrumentView> instruments = registry.all().stream()
                .sorted(Comparator
                        .comparing((Instrument i) -> i.venue().ordinal())
                        .thenComparing(Instrument::nativeSymbol))
                .map(i -> new InstrumentView(i.id(), i.venue(), i.nativeSymbol(), i.canonical()))
                .toList();

        Map<Venue, Integer> byVenue = new EnumMap<>(Venue.class);
        for (InstrumentView view : instruments) {
            byVenue.merge(view.venue(), 1, Integer::sum);
        }

        return new InstrumentSummaryResponse(instruments.size(), byVenue, instruments);
    }

    /**
     * @param total       total number of tracked instruments across all venues
     * @param byVenue     instrument count per venue
     * @param instruments the full list, sorted by {@code (venue, symbol)}
     */
    public record InstrumentSummaryResponse(
            int total,
            Map<Venue, Integer> byVenue,
            List<InstrumentView> instruments
    ) {}

    /**
     * @param id        process-local runtime index — debugging only, never durable
     * @param venue     the venue this instrument trades on
     * @param symbol    the exchange's native symbol
     * @param canonical cross-venue pair name, e.g. {@code "BTC/USDT"}
     */
    public record InstrumentView(int id, Venue venue, String symbol, String canonical) {}
}
