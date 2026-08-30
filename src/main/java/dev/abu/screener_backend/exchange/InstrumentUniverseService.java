package dev.abu.screener_backend.exchange;

import dev.abu.screener_backend.config.ExchangesProperties;
import dev.abu.screener_backend.config.ExchangesProperties.DiscoveryProperties;
import dev.abu.screener_backend.exchange.binance.BinanceRestClient;
import dev.abu.screener_backend.exchange.binance.dto.BinanceSymbolDto;
import dev.abu.screener_backend.exchange.binance.dto.ExchangeInfoResponse;
import dev.abu.screener_backend.exchange.book.BookSlotTable;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Discovers the tradable instrument universe and drives registration, slot allocation and
 * subscription.
 *
 * <p>Replaces {@code TickerService}. The behavioural difference is one of modelling, not of
 * coverage: {@code Ticker(symbol, hasFutures, hasSpot)} bundled both markets into one object —
 * which is exactly what forced the order-book store's composite string key — whereas {@code BTCUSDT}
 * is now two {@link Instrument}s, {@code (BINANCE_SPOT, "BTCUSDT")} and
 * {@code (BINANCE_FUTURES, "BTCUSDT")}, with two ids, two slots and two books.
 *
 * <h3>Inclusion policy</h3>
 * Restated from {@code TickerService}'s hardcoded filters into config
 * ({@code screener.exchanges.binance.discovery}), so it can be changed without a code edit:
 * <pre>
 * futures = status TRADING ∧ contractType PERPETUAL ∧ quote USDT ∧ not excluded
 * spot    = status TRADING ∧ quote USDT ∧ not excluded ∧ (spot-requires-futures → symbol ∈ futures)
 * </pre>
 * With the shipped values the resulting universe is identical to {@code TickerService}'s.
 *
 * <h3>Ordering invariant</h3>
 * {@code register all → allocate slots → publish → fire event → transport subscribes.} The event
 * listener runs synchronously on this thread, so the ordering holds naturally — but it is asserted
 * here rather than assumed. See {@link BookSlotTable}.
 *
 * <h3>Failure behaviour</h3>
 * Unchanged: a failed refresh is logged and the existing universe is retained. A network blip must
 * never be read as a mass delisting.
 */
@Slf4j
@Service
public class InstrumentUniverseService {

    private static final String TRADING_STATUS = "TRADING";

    private final BinanceRestClient restClient;
    private final InstrumentRegistry registry;
    private final BookSlotTable slots;
    private final DiscoveryProperties discovery;
    private final ApplicationEventPublisher eventPublisher;

    /** Ids in the universe as of the last successful refresh. Discovery thread only. */
    private Set<Integer> activeIds = Set.of();

    public InstrumentUniverseService(BinanceRestClient restClient,
                                     InstrumentRegistry registry,
                                     BookSlotTable slots,
                                     ExchangesProperties exchanges,
                                     ApplicationEventPublisher eventPublisher) {
        this.restClient = restClient;
        this.registry = registry;
        this.slots = slots;
        this.discovery = exchanges.exchange(Exchange.BINANCE).discovery();
        this.eventPublisher = eventPublisher;
    }

    /**
     * Fetches spot and futures exchange info concurrently, applies the inclusion policy, and
     * registers the resulting universe.
     *
     * <p>Both REST calls are issued in parallel via {@link Mono#zip} and the combinator only
     * <em>filters</em> — registration happens on the calling thread after the block, keeping id
     * assignment single-threaded. Intentionally synchronous so the startup listener and the
     * scheduler can reason about completion without subscribing.
     */
    public void refresh() {
        log.info("Refreshing instrument universe from Binance...");
        try {
            Mono.zip(
                    restClient.getSpot("/api/v3/exchangeInfo", ExchangeInfoResponse.class),
                    restClient.getFutures("/fapi/v1/exchangeInfo", ExchangeInfoResponse.class),
                    this::selectCandidates
            ).blockOptional(Duration.ofSeconds(30)).ifPresent(this::apply);
        } catch (Exception e) {
            log.error("Instrument universe refresh failed — retaining existing data ({} instruments)",
                    activeIds.size(), e);
        }
    }

    /**
     * Applies the inclusion policy. Pure — runs on a Reactor thread and touches no registry state.
     *
     * <p>Candidates come back sorted by {@code (venue, nativeSymbol)} so ids are reproducible
     * across restarts, which makes parity runs diffable.
     */
    private List<Candidate> selectCandidates(ExchangeInfoResponse spot, ExchangeInfoResponse futures) {
        Set<String> excluded = discovery.excludedSymbols() == null ? Set.of() : discovery.excludedSymbols();

        List<BinanceSymbolDto> futuresSymbols = futures.getSymbols().stream()
                .filter(s -> TRADING_STATUS.equals(s.getStatus()))
                .filter(s -> discovery.futuresContractType().equals(s.getContractType()))
                .filter(s -> discovery.quoteAsset().equals(s.getQuoteAsset()))
                .filter(s -> !excluded.contains(s.getSymbol()))
                .toList();

        Set<String> futuresNames = futuresSymbols.stream()
                .map(BinanceSymbolDto::getSymbol)
                .collect(Collectors.toSet());

        List<BinanceSymbolDto> spotSymbols = spot.getSymbols().stream()
                .filter(s -> TRADING_STATUS.equals(s.getStatus()))
                .filter(s -> discovery.quoteAsset().equals(s.getQuoteAsset()))
                .filter(s -> !excluded.contains(s.getSymbol()))
                .filter(s -> !discovery.spotRequiresFutures() || futuresNames.contains(s.getSymbol()))
                .toList();

        List<Candidate> candidates = new ArrayList<>(futuresSymbols.size() + spotSymbols.size());
        for (BinanceSymbolDto s : spotSymbols) candidates.add(toCandidate(Venue.BINANCE_SPOT, s));
        for (BinanceSymbolDto s : futuresSymbols) candidates.add(toCandidate(Venue.BINANCE_FUTURES, s));
        candidates.sort(Comparator
                .<Candidate>comparingInt(c -> c.venue().ordinal())
                .thenComparing(Candidate::nativeSymbol));

        log.debug("Instrument universe selected: {} spot, {} futures", spotSymbols.size(), futuresSymbols.size());
        return candidates;
    }

    /** Discovery thread. Registers, allocates, publishes, then announces — in that order. */
    private void apply(List<Candidate> candidates) {
        List<Instrument> added = new ArrayList<>();
        Set<Integer> current = new HashSet<>(candidates.size() * 2);

        for (Candidate c : candidates) {
            boolean isNew = registry.find(c.venue(), c.nativeSymbol()).isEmpty();
            Instrument instrument = registry.register(c.venue(), c.nativeSymbol(), c.base(), c.quote());
            current.add(instrument.id());
            if (isNew) {
                slots.allocate(instrument);
                added.add(instrument);
            }
        }

        // Must precede the event: subscribing before the array is visible could route a message to
        // an index past its end.
        slots.publish();

        List<Instrument> removed = new ArrayList<>();
        for (Integer id : activeIds) {
            if (!current.contains(id)) {
                Instrument instrument = registry.byId(id);
                if (instrument != null) removed.add(instrument);
            }
        }
        activeIds = current;

        log.info("Instrument universe updated — {} tracked ({} added, {} removed)",
                current.size(), added.size(), removed.size());
        eventPublisher.publishEvent(new InstrumentUniverseChangedEvent(this, added, removed));
    }

    private static Candidate toCandidate(Venue venue, BinanceSymbolDto dto) {
        return new Candidate(venue, dto.getSymbol(), dto.getBaseAsset(), dto.getQuoteAsset());
    }

    private record Candidate(Venue venue, String nativeSymbol, String base, String quote) {}
}
