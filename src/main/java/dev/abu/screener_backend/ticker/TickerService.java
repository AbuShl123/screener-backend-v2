package dev.abu.screener_backend.ticker;

import dev.abu.screener_backend.binance.api.BinanceRestClient;
import dev.abu.screener_backend.binance.api.dto.BinanceSymbolDto;
import dev.abu.screener_backend.binance.api.dto.ExchangeInfoResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;

import java.time.Duration;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Fetches active tickers from Binance and maintains the {@link TickerRegistry}.
 *
 * <h3>Inclusion rules (all conditions must be met)</h3>
 * <ol>
 *   <li>Symbol must be USDT-quoted (e.g. {@code BTCUSDT}; {@code ETHBTC} is excluded).</li>
 *   <li>Symbol must have an active USDT PERPETUAL futures contract
 *       ({@code status=TRADING}, {@code contractType=PERPETUAL}).</li>
 *   <li>Tickers that also have an active USDT spot market are flagged with
 *       {@link Ticker#hasSpot()} {@code = true}.</li>
 *   <li>Spot-only tickers are excluded entirely.</li>
 * </ol>
 *
 * <h3>Failure behaviour</h3>
 * If either the spot or futures REST call fails, the error is logged and the existing
 * registry contents are preserved. The application never crashes due to a failed refresh.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class TickerService {

    private static final String TRADING_STATUS = "TRADING";
    private static final String QUOTE_ASSET    = "USDT";
    private static final String CONTRACT_TYPE  = "PERPETUAL";

    private static final Set<String> EXCLUDED_SYMBOLS = Set.of(
            "USDCUSDT", "FDUSDUSDT", "DAIUSDT", "PYUSDUSDT", "USD1USDT", "XAUTUSDT", "PAXGUSDT"
    );

    private final BinanceRestClient      restClient;
    private final TickerRegistry         registry;
    private final ApplicationEventPublisher eventPublisher;

    /**
     * Fetches spot and futures exchange info concurrently, applies all inclusion rules,
     * and atomically replaces the {@link TickerRegistry}.
     *
     * <p>Both REST calls are issued in parallel via {@link Mono#zip}. This method
     * blocks until both complete — intentionally synchronous so callers (the startup
     * listener and the scheduler) can reason about completion without subscribing.
     *
     * <p>On any error the existing registry is preserved and no exception is propagated.
     */
    public void refreshTickers() {
        log.info("Refreshing ticker list from Binance...");
        try {
            Mono.zip(
                    restClient.getSpot("/api/v3/exchangeInfo", ExchangeInfoResponse.class),
                    restClient.getFutures("/fapi/v1/exchangeInfo", ExchangeInfoResponse.class),
                    this::buildTickerMap
            ).blockOptional(Duration.ofSeconds(30)).ifPresent(tickerMap -> {
                registry.replace(tickerMap);
                eventPublisher.publishEvent(new TickersRefreshedEvent(this, tickerMap));
            });
        } catch (Exception e) {
            log.error("Ticker refresh failed — retaining existing data ({} tickers)", registry.size(), e);
        }
    }

    private Map<String, Ticker> buildTickerMap(ExchangeInfoResponse spot, ExchangeInfoResponse futures) {
        Set<String> futuresSymbols = futures.getSymbols().stream()
                .filter(s -> TRADING_STATUS.equals(s.getStatus()))
                .filter(s -> CONTRACT_TYPE.equals(s.getContractType()))
                .filter(s -> QUOTE_ASSET.equals(s.getQuoteAsset()))
                .map(BinanceSymbolDto::getSymbol)
                .filter(sym -> !EXCLUDED_SYMBOLS.contains(sym))
                .collect(Collectors.toSet());

        Set<String> spotSymbols = spot.getSymbols().stream()
                .filter(s -> TRADING_STATUS.equals(s.getStatus()))
                .filter(s -> QUOTE_ASSET.equals(s.getQuoteAsset()))
                .map(BinanceSymbolDto::getSymbol)
                .collect(Collectors.toSet());

        Map<String, Ticker> result = new HashMap<>(futuresSymbols.size());
        for (String symbol : futuresSymbols) {
            result.put(symbol, new Ticker(symbol, true, spotSymbols.contains(symbol)));
        }

        log.debug("Ticker map built: {} total ({} with spot)", result.size(),
                result.values().stream().filter(Ticker::hasSpot).count());

//        return result.entrySet().stream().limit(3).collect(Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue));
//        return result.entrySet().stream().filter((k) -> k.getKey().equalsIgnoreCase("XRPUSDT"))
//                .collect(Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue));
        return result;
    }
}
