package dev.abu.screener_backend.marketdata.adapter.bybit;

import dev.abu.screener_backend.marketdata.Venue;
import dev.abu.screener_backend.marketdata.adapter.bybit.dto.BybitInstrumentDto;
import dev.abu.screener_backend.marketdata.spi.InstrumentCandidate;
import dev.abu.screener_backend.marketdata.spi.InstrumentSource;
import lombok.extern.slf4j.Slf4j;
import reactor.core.publisher.Mono;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Bybit's instrument universe, spot and futures (linear USDT perpetuals) together.
 *
 * <p>One source for both venues because the policies are coupled, as on Binance and MEXC: spot
 * inclusion needs the eligible futures list, so the two cannot be fetched or fail independently.
 *
 * <h3>Inclusion policy</h3>
 * <pre>
 * futures = contractType LinearPerpetual ∧ quoteCoin USDT ∧ status Trading ∧ not TradFi
 * TradFi  = symbolType ∈ {stock, ETF, commodity, forex}
 * spot    = quoteCoin USDT ∧ status Trading ∧ symbol ∈ symbol(futures) ∧ stTag ≠ "1"
 * </pre>
 * Hardcoded, not configuration, as in every adapter: it defines what the pipeline can handle. Dated
 * {@code LinearFutures} and USDC perpetuals are out. TradFi perpetuals are a product decision, as on
 * MEXC: they track traditional markets, not crypto, and are over a third of Bybit's USDT perpetuals
 * (305 of 821, 2026-11-15). The list is a denylist, so a new crypto class is kept rather than
 * silently dropped; Innovation Zone pairs ({@code symbolType innovation}) are kept. Special Treatment
 * (risk-flagged) is excluded on spot only, where Bybit marks it with {@code stTag "1"}; the linear
 * {@code ST} tag is deliberately ignored. The exchange-agnostic exclusion list
 * ({@code screener.discovery.excluded-symbols}) is applied afterwards by core's
 * {@code InstrumentUniverseService}, so the counts logged here include excluded symbols.
 *
 * <p>Spot matches futures on the full symbol, the Binance rule, so it inherits the TradFi filter.
 * Multiplier-prefixed perpetuals ({@code 1000BONKUSDT}) therefore have no spot counterpart
 * ({@code BONKUSDT}), exactly as on Binance.
 *
 * <h3>Mapping</h3>
 * {@code nativeSymbol}, {@code base} and {@code quote} come straight from the row, with multiplier
 * 1: both categories quote depth sizes in the base coin. On linear the base keeps the multiplier
 * prefix ({@code 1000000BABYDOGE}), the Binance futures convention, so user rules written for
 * Binance apply unchanged.
 *
 * <p>Constructed by {@link BybitAdapterConfig}; deliberately not a {@code @Component}.
 */
@Slf4j
public class BybitInstrumentSource implements InstrumentSource {

    private static final String QUOTE_COIN = "USDT";
    private static final String TRADING = "Trading";
    private static final String LINEAR_PERPETUAL = "LinearPerpetual";
    /** Lower-cased {@code symbolType}s of non-crypto perpetuals. */
    private static final Set<String> TRADFI_SYMBOL_TYPES = Set.of("stock", "etf", "commodity", "forex");
    private static final String SPECIAL_TREATMENT = "1";
    private static final Set<Venue> VENUES = Set.of(Venue.BYBIT_SPOT, Venue.BYBIT_FUTURES);

    private final BybitRestClient spotClient;
    private final BybitRestClient futuresClient;

    /** @throws IllegalArgumentException if a client is bound to the wrong venue */
    public BybitInstrumentSource(BybitRestClient spotClient, BybitRestClient futuresClient) {
        if (spotClient.venue() != Venue.BYBIT_SPOT || futuresClient.venue() != Venue.BYBIT_FUTURES) {
            throw new IllegalArgumentException("Expected spot and futures clients, got "
                    + spotClient.venue() + " and " + futuresClient.venue());
        }
        this.spotClient = spotClient;
        this.futuresClient = futuresClient;
    }

    @Override
    public Set<Venue> venues() {
        return VENUES;
    }

    /**
     * Issues both {@code instruments-info} calls concurrently and blocks for the pair. No timeout of
     * its own: core interrupts this thread when its per-source timeout expires, and {@code block()}
     * answers by cancelling both requests.
     */
    @Override
    public Map<Venue, List<InstrumentCandidate>> fetch() {
        Map<Venue, List<InstrumentCandidate>> result = Mono.zip(
                spotClient.instrumentsInfo(),
                futuresClient.instrumentsInfo(),
                this::selectCandidates
        ).block();
        if (result == null) {
            throw new IllegalStateException("Bybit instruments-info returned an empty body");
        }
        return result;
    }

    /** Applies the inclusion policy. Pure — runs on a Reactor thread. */
    private Map<Venue, List<InstrumentCandidate>> selectCandidates(List<BybitInstrumentDto> spotRows,
                                                                   List<BybitInstrumentDto> linearRows) {
        List<InstrumentCandidate> futures = new ArrayList<>();
        Set<String> futuresSymbols = new HashSet<>();
        for (BybitInstrumentDto row : linearRows) {
            if (!isEligibleFutures(row)) continue;
            InstrumentCandidate candidate = toCandidate(row, Venue.BYBIT_FUTURES);
            if (candidate != null) {
                futures.add(candidate);
                futuresSymbols.add(row.symbol());
            }
        }

        List<InstrumentCandidate> spot = new ArrayList<>();
        for (BybitInstrumentDto row : spotRows) {
            if (!isEligibleSpot(row, futuresSymbols)) continue;
            InstrumentCandidate candidate = toCandidate(row, Venue.BYBIT_SPOT);
            if (candidate != null) {
                spot.add(candidate);
            }
        }

        log.debug("Bybit eligible before exclusions: {} spot of {} listed, {} futures of {} listed",
                spot.size(), spotRows.size(), futures.size(), linearRows.size());
        return Map.of(Venue.BYBIT_SPOT, spot, Venue.BYBIT_FUTURES, futures);
    }

    private static boolean isEligibleFutures(BybitInstrumentDto row) {
        return LINEAR_PERPETUAL.equals(row.contractType())
                && QUOTE_COIN.equals(row.quoteCoin())
                && TRADING.equals(row.status())
                && !isTradFi(row);
    }

    private static boolean isTradFi(BybitInstrumentDto row) {
        return row.symbolType() != null && TRADFI_SYMBOL_TYPES.contains(row.symbolType().toLowerCase(Locale.ROOT));
    }

    private static boolean isEligibleSpot(BybitInstrumentDto row, Set<String> futuresSymbols) {
        return QUOTE_COIN.equals(row.quoteCoin())
                && TRADING.equals(row.status())
                && futuresSymbols.contains(row.symbol())
                && !SPECIAL_TREATMENT.equals(row.stTag());
    }

    /**
     * {@code null} for a row that cannot be tracked. It is skipped rather than thrown: one malformed
     * row must not cost the venue its whole universe refresh.
     */
    private static InstrumentCandidate toCandidate(BybitInstrumentDto row, Venue venue) {
        if (row.symbol() == null || row.baseCoin() == null || row.baseCoin().isEmpty()) {
            log.warn("Skipping {} instrument {}: missing symbol or baseCoin", venue, row.symbol());
            return null;
        }
        return new InstrumentCandidate(row.symbol(), row.baseCoin(), row.quoteCoin());
    }
}
