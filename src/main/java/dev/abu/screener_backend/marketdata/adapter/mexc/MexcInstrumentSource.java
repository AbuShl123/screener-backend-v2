package dev.abu.screener_backend.marketdata.adapter.mexc;

import dev.abu.screener_backend.marketdata.Venue;
import dev.abu.screener_backend.marketdata.adapter.mexc.dto.MexcContractDto;
import dev.abu.screener_backend.marketdata.adapter.mexc.dto.MexcSpotSymbolDto;
import dev.abu.screener_backend.marketdata.spi.InstrumentCandidate;
import dev.abu.screener_backend.marketdata.spi.InstrumentSource;
import lombok.extern.slf4j.Slf4j;
import reactor.core.publisher.Mono;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * MEXC's instrument universe, spot and futures together.
 *
 * <p>One source for both venues because the policies are coupled, as on Binance: spot inclusion
 * needs the eligible futures list, so the two cannot be fetched or fail independently.
 *
 * <h3>Inclusion policy</h3>
 * <pre>
 * futures = quoteCoin USDT ∧ futureType 1 (perpetual) ∧ state 0 (enabled) ∧ apiAllowed ∧ not TradFi
 * TradFi  = conceptPlate ∋ mc-trade-zone-tradfi ∨ symbol *STOCK_USDT
 * spot    = quoteAsset USDT ∧ status "1" ∧ isSpotTradingAllowed ∧ baseAsset ∈ baseCoinName(futures)
 * </pre>
 * Hardcoded, not configuration: it defines what the pipeline can handle, not a tunable — a non-USDT
 * quote, for one, needs different notional math. TradFi perpetuals (stocks, ETFs, indices,
 * commodities, forex) are a product decision instead: they track traditional markets, not crypto,
 * and are over 40% of the universe. MEXC tags them with the {@code mc-trade-zone-tradfi} sector, which
 * catches names like {@code XAU_USDT}, {@code USOIL_USDT} or {@code NVIDIA_USDT}; the
 * {@code *STOCK_USDT} suffix stays as a backstop for the odd untagged tokenized stock. The exchange-agnostic exclusion list
 * ({@code screener.discovery.excluded-symbols}) is applied afterwards by core's
 * {@code InstrumentUniverseService}, so the counts logged here include excluded symbols.
 *
 * <p>Spot inherits the futures filters, TradFi included, through the match. The match is on the
 * contract's {@code baseCoinName}, not {@code baseCoin}: on some contracts {@code baseCoin} is an
 * internal id ({@code FILECOIN}, {@code TRUMPOFFICIAL}) and would miss the spot pair
 * ({@code external-docs/mexc/mexc-spot-depth-empirical.md} §6). A contract without a
 * {@code baseCoinName} matches no spot pair.
 *
 * <h3>Mapping</h3>
 * Futures: {@code BTC_USDT} → {@code nativeSymbol=BTC_USDT}, {@code base=BTC}, {@code quote=USDT},
 * so the instrument's {@code symbol} is {@code BTCUSDT} and user rules written for Binance apply
 * unchanged. {@code contractSize} becomes the quantity multiplier, because MEXC futures depth
 * quantities are contract counts. {@code base} stays {@code baseCoin} even where it is an internal
 * id, so {@code FILECOIN_USDT} is {@code FILECOINUSDT}; switching to {@code baseCoinName} was
 * rejected (same doc, §6).
 *
 * <p>Spot: {@code nativeSymbol}, {@code base} and {@code quote} come from the spot row, multiplier 1
 * (quantities are base asset). So {@code TRUMPUSDT} on spot sits next to {@code TRUMPOFFICIALUSDT}
 * on futures.
 *
 * <p>Constructed by {@link MexcAdapterConfig}; deliberately not a {@code @Component}.
 */
@Slf4j
public class MexcInstrumentSource implements InstrumentSource {

    private static final String QUOTE_COIN = "USDT";
    private static final int PERPETUAL = 1;
    private static final int STATE_ENABLED = 0;
    private static final String TOKENIZED_STOCK_SUFFIX = "STOCK_" + QUOTE_COIN;
    private static final String TRADFI_PLATE = "mc-trade-zone-tradfi";

    private static final String SPOT_STATUS_ONLINE = "1";
    private static final Set<Venue> VENUES = Set.of(Venue.MEXC_SPOT, Venue.MEXC_FUTURES);

    private final MexcSpotRestClient spotClient;
    private final MexcFuturesRestClient futuresClient;

    public MexcInstrumentSource(MexcSpotRestClient spotClient, MexcFuturesRestClient futuresClient) {
        this.spotClient = spotClient;
        this.futuresClient = futuresClient;
    }

    @Override
    public Set<Venue> venues() {
        return VENUES;
    }

    /**
     * Issues {@code /api/v3/exchangeInfo} and {@code /api/v1/contract/detail} concurrently and blocks
     * for the pair. No timeout of its own: core interrupts this thread when its per-source timeout
     * expires, and {@code block()} answers by cancelling both requests.
     */
    @Override
    public Map<Venue, List<InstrumentCandidate>> fetch() {
        Map<Venue, List<InstrumentCandidate>> result = Mono.zip(
                spotClient.exchangeInfo(),
                futuresClient.contractDetail(),
                this::selectCandidates
        ).block();
        if (result == null) {
            throw new IllegalStateException("MEXC exchangeInfo or contract/detail returned an empty body");
        }
        return result;
    }

    /** Applies the inclusion policy. Pure — runs on a Reactor thread. */
    private Map<Venue, List<InstrumentCandidate>> selectCandidates(List<MexcSpotSymbolDto> spotSymbols,
                                                                   List<MexcContractDto> contracts) {
        List<InstrumentCandidate> futures = new ArrayList<>();
        Set<String> futuresBaseNames = new HashSet<>();
        for (MexcContractDto contract : contracts) {
            if (!isEligible(contract)) continue;
            if (contract.baseCoinName() != null) {
                futuresBaseNames.add(contract.baseCoinName());
            }
            InstrumentCandidate candidate = toCandidate(contract);
            if (candidate != null) {
                futures.add(candidate);
            }
        }

        List<InstrumentCandidate> spot = new ArrayList<>();
        for (MexcSpotSymbolDto symbol : spotSymbols) {
            if (isEligible(symbol, futuresBaseNames)) {
                InstrumentCandidate candidate = toCandidate(symbol);
                if (candidate != null) {
                    spot.add(candidate);
                }
            }
        }

        log.debug("MEXC eligible before exclusions: {} spot of {} listed, {} futures of {} listed",
                spot.size(), spotSymbols.size(), futures.size(), contracts.size());
        return Map.of(Venue.MEXC_SPOT, spot, Venue.MEXC_FUTURES, futures);
    }

    private static boolean isEligible(MexcSpotSymbolDto symbol, Set<String> futuresBaseNames) {
        return QUOTE_COIN.equals(symbol.quoteAsset())
                && SPOT_STATUS_ONLINE.equals(symbol.status())
                && Boolean.TRUE.equals(symbol.spotTradingAllowed())
                && futuresBaseNames.contains(symbol.baseAsset());
    }

    /** {@code null} for a row that cannot be tracked; skipped rather than thrown, as for futures. */
    private static InstrumentCandidate toCandidate(MexcSpotSymbolDto symbol) {
        if (symbol.symbol() == null) {
            log.warn("Skipping MEXC spot pair with base {}: missing symbol", symbol.baseAsset());
            return null;
        }
        return new InstrumentCandidate(symbol.symbol(), symbol.baseAsset(), symbol.quoteAsset());
    }

    private static boolean isEligible(MexcContractDto contract) {
        return QUOTE_COIN.equals(contract.quoteCoin())
                && Integer.valueOf(PERPETUAL).equals(contract.futureType())
                && Integer.valueOf(STATE_ENABLED).equals(contract.state())
                && Boolean.TRUE.equals(contract.apiAllowed())
                && !isTradFi(contract);
    }

    private static boolean isTradFi(MexcContractDto contract) {
        return (contract.conceptPlate() != null && contract.conceptPlate().contains(TRADFI_PLATE))
                || (contract.symbol() != null && contract.symbol().endsWith(TOKENIZED_STOCK_SUFFIX));
    }

    /**
     * {@code null} for a contract that cannot be tracked correctly. It is skipped rather than thrown:
     * one malformed row must not cost the venue its whole universe refresh.
     */
    private static InstrumentCandidate toCandidate(MexcContractDto contract) {
        Double contractSize = contract.contractSize();
        if (contract.symbol() == null || contract.baseCoin() == null
                || contractSize == null || !(contractSize > 0) || contractSize.isInfinite()) {
            log.warn("Skipping MEXC contract {}: missing symbol/baseCoin or bad contractSize {}",
                    contract.symbol(), contractSize);
            return null;
        }
        return new InstrumentCandidate(contract.symbol(), contract.baseCoin(), contract.quoteCoin(), contractSize);
    }
}
