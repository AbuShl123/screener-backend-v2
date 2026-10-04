package dev.abu.screener_backend.exchange.mexc;

import dev.abu.screener_backend.exchange.Venue;
import dev.abu.screener_backend.exchange.mexc.dto.MexcContractDto;
import dev.abu.screener_backend.exchange.spi.InstrumentCandidate;
import dev.abu.screener_backend.exchange.spi.InstrumentSource;
import lombok.extern.slf4j.Slf4j;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * MEXC's futures instrument universe.
 *
 * <h3>Inclusion policy</h3>
 * <pre>
 * futures = quoteCoin USDT ∧ futureType 1 (perpetual) ∧ state 0 (enabled) ∧ apiAllowed ∧ symbol not *STOCK_USDT
 * </pre>
 * Hardcoded, not configuration: it defines what the pipeline can handle, not a tunable — a non-USDT
 * quote, for one, needs different notional math. Tokenized-stock perpetuals ({@code PLTRSTOCK_USDT})
 * are a product decision instead: they track equities, not crypto, and are about a third of the
 * universe. No field marks them, so the name suffix is the filter. The exchange-agnostic exclusion list
 * ({@code screener.discovery.excluded-symbols}) is applied afterwards by core's
 * {@code InstrumentUniverseService}, so the count logged here includes excluded contracts.
 *
 * <h3>Mapping</h3>
 * {@code BTC_USDT} → {@code nativeSymbol=BTC_USDT}, {@code base=BTC}, {@code quote=USDT}, so the
 * instrument's {@code symbol} is {@code BTCUSDT} and user rules written for Binance apply unchanged.
 * {@code contractSize} becomes the quantity multiplier, because MEXC depth quantities are contract
 * counts.
 *
 * <p>Constructed by {@link MexcAdapterConfig}; deliberately not a {@code @Component}.
 */
@Slf4j
public class MexcInstrumentSource implements InstrumentSource {

    private static final String QUOTE_COIN = "USDT";
    private static final int PERPETUAL = 1;
    private static final int STATE_ENABLED = 0;
    private static final String TOKENIZED_STOCK_SUFFIX = "STOCK_" + QUOTE_COIN;

    private final MexcFuturesRestClient client;

    public MexcInstrumentSource(MexcFuturesRestClient client) {
        this.client = client;
    }

    @Override
    public Set<Venue> venues() {
        return Set.of(Venue.MEXC_FUTURES);
    }

    /**
     * Blocks on {@code /api/v1/contract/detail}. No timeout of its own: core interrupts this thread
     * when its per-source timeout expires, and {@code block()} answers by cancelling the request.
     */
    @Override
    public Map<Venue, List<InstrumentCandidate>> fetch() {
        List<MexcContractDto> contracts = client.contractDetail().block();
        if (contracts == null) {
            throw new IllegalStateException("MEXC contract/detail returned an empty body");
        }
        return Map.of(Venue.MEXC_FUTURES, selectCandidates(contracts));
    }

    private List<InstrumentCandidate> selectCandidates(List<MexcContractDto> contracts) {
        List<InstrumentCandidate> candidates = new ArrayList<>();
        for (MexcContractDto contract : contracts) {
            if (isEligible(contract)) {
                InstrumentCandidate candidate = toCandidate(contract);
                if (candidate != null) {
                    candidates.add(candidate);
                }
            }
        }
        log.debug("MEXC eligible before exclusions: {} futures of {} listed", candidates.size(), contracts.size());
        return candidates;
    }

    private static boolean isEligible(MexcContractDto contract) {
        return QUOTE_COIN.equals(contract.quoteCoin())
                && Integer.valueOf(PERPETUAL).equals(contract.futureType())
                && Integer.valueOf(STATE_ENABLED).equals(contract.state())
                && Boolean.TRUE.equals(contract.apiAllowed())
                && !isTokenizedStock(contract);
    }

    private static boolean isTokenizedStock(MexcContractDto contract) {
        return contract.symbol() != null && contract.symbol().endsWith(TOKENIZED_STOCK_SUFFIX);
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
