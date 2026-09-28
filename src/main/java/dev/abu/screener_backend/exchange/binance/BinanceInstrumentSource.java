package dev.abu.screener_backend.exchange.binance;

import dev.abu.screener_backend.exchange.Venue;
import dev.abu.screener_backend.exchange.binance.dto.BinanceSymbolDto;
import dev.abu.screener_backend.exchange.binance.dto.ExchangeInfoResponse;
import dev.abu.screener_backend.exchange.spi.InstrumentCandidate;
import dev.abu.screener_backend.exchange.spi.InstrumentSource;
import lombok.extern.slf4j.Slf4j;
import reactor.core.publisher.Mono;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Binance's instrument universe, spot and futures together.
 *
 * <p>One source for both venues because the policies are coupled: spot inclusion needs the futures
 * list, so the two cannot be fetched or fail independently.
 *
 * <h3>Inclusion policy</h3>
 * Configured under {@code screener.exchanges.binance.discovery} ({@link BinanceDiscoveryProperties}):
 * <pre>
 * futures = status TRADING ∧ contractType PERPETUAL ∧ quote USDT ∧ not excluded
 * spot    = status TRADING ∧ quote USDT ∧ not excluded ∧ (spot-requires-futures → symbol ∈ futures)
 * </pre>
 *
 * <p>Constructed by {@link BinanceAdapterConfig}; deliberately not a {@code @Component}.
 */
@Slf4j
public class BinanceInstrumentSource implements InstrumentSource {

    private static final String TRADING_STATUS = "TRADING";
    private static final Set<Venue> VENUES = Set.of(Venue.BINANCE_SPOT, Venue.BINANCE_FUTURES);

    private final BinanceRestClient spotClient;
    private final BinanceRestClient futuresClient;
    private final BinanceDiscoveryProperties discovery;

    public BinanceInstrumentSource(BinanceRestClient spotClient, BinanceRestClient futuresClient,
                                    BinanceDiscoveryProperties discovery) {
        this.spotClient = spotClient;
        this.futuresClient = futuresClient;
        this.discovery = discovery;
    }

    @Override
    public Set<Venue> venues() {
        return VENUES;
    }

    /**
     * Issues both {@code exchangeInfo} calls concurrently and blocks for the pair.
     *
     * <p>No timeout of its own: core interrupts this thread when its per-source timeout expires,
     * and {@code block()} answers an interrupt by disposing the subscription, which cancels both
     * in-flight HTTP requests.
     *
     * @throws IllegalStateException if either response is missing its symbol list
     */
    @Override
    public Map<Venue, List<InstrumentCandidate>> fetch() {
        Map<Venue, List<InstrumentCandidate>> result = Mono.zip(
                spotClient.exchangeInfo(),
                futuresClient.exchangeInfo(),
                this::selectCandidates
        ).block();
        if (result == null) {
            throw new IllegalStateException("Binance exchangeInfo returned an empty body");
        }
        return result;
    }

    /** Applies the inclusion policy. Pure — runs on a Reactor thread. */
    private Map<Venue, List<InstrumentCandidate>> selectCandidates(ExchangeInfoResponse spot,
                                                                   ExchangeInfoResponse futures) {
        List<BinanceSymbolDto> spotAll = requireSymbols(spot, "spot exchangeInfo");
        List<BinanceSymbolDto> futuresAll = requireSymbols(futures, "futures exchangeInfo");
        Set<String> excluded = discovery.excludedSymbols() == null ? Set.of() : discovery.excludedSymbols();

        List<BinanceSymbolDto> futuresSymbols = futuresAll.stream()
                .filter(s -> TRADING_STATUS.equals(s.getStatus()))
                .filter(s -> discovery.futuresContractType().equals(s.getContractType()))
                .filter(s -> discovery.quoteAsset().equals(s.getQuoteAsset()))
                .filter(s -> !excluded.contains(s.getSymbol()))
                .toList();

        Set<String> futuresNames = futuresSymbols.stream()
                .map(BinanceSymbolDto::getSymbol)
                .collect(Collectors.toSet());

        List<BinanceSymbolDto> spotSymbols = spotAll.stream()
                .filter(s -> TRADING_STATUS.equals(s.getStatus()))
                .filter(s -> discovery.quoteAsset().equals(s.getQuoteAsset()))
                .filter(s -> !excluded.contains(s.getSymbol()))
                .filter(s -> !discovery.spotRequiresFutures() || futuresNames.contains(s.getSymbol()))
                .toList();

        log.info("Instrument universe selected: {} spot, {} futures", spotSymbols.size(), futuresSymbols.size());
        return Map.of(
                Venue.BINANCE_SPOT, toCandidates(spotSymbols),
                Venue.BINANCE_FUTURES, toCandidates(futuresSymbols));
    }

    /** A missing list is a changed response shape, not "Binance lists nothing" — fail loudly. */
    private static List<BinanceSymbolDto> requireSymbols(ExchangeInfoResponse response, String label) {
        if (response.getSymbols() == null) {
            throw new IllegalStateException("Binance " + label + " response has no symbols array");
        }
        return response.getSymbols();
    }

    private static List<InstrumentCandidate> toCandidates(List<BinanceSymbolDto> symbols) {
        return symbols.stream()
                .map(s -> new InstrumentCandidate(s.getSymbol(), s.getBaseAsset(), s.getQuoteAsset()))
                .toList();
    }
}
