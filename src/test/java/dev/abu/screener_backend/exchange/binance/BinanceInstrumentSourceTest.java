package dev.abu.screener_backend.exchange.binance;

import dev.abu.screener_backend.exchange.Venue;
import dev.abu.screener_backend.exchange.binance.dto.ExchangeInfoResponse;
import dev.abu.screener_backend.exchange.spi.InstrumentCandidate;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import reactor.core.publisher.Mono;
import tools.jackson.databind.json.JsonMapper;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * Binance's inclusion policy, driven through real {@code exchangeInfo}-shaped JSON and a stubbed
 * {@link BinanceRestClient}.
 */
class BinanceInstrumentSourceTest {

    private static final JsonMapper JSON = JsonMapper.builder().build();

    private static final String FUTURES = """
            {"symbols": [
              {"symbol": "BTCUSDT",        "baseAsset": "BTC",  "quoteAsset": "USDT", "status": "TRADING", "contractType": "PERPETUAL"},
              {"symbol": "SOLUSDT",        "baseAsset": "SOL",  "quoteAsset": "USDT", "status": "TRADING", "contractType": "PERPETUAL"},
              {"symbol": "ETHUSDT_250926", "baseAsset": "ETH",  "quoteAsset": "USDT", "status": "TRADING", "contractType": "CURRENT_QUARTER"},
              {"symbol": "XRPUSDT",        "baseAsset": "XRP",  "quoteAsset": "USDT", "status": "SETTLING", "contractType": "PERPETUAL"},
              {"symbol": "ETHBTC",         "baseAsset": "ETH",  "quoteAsset": "BTC",  "status": "TRADING", "contractType": "PERPETUAL"},
              {"symbol": "USDCUSDT",       "baseAsset": "USDC", "quoteAsset": "USDT", "status": "TRADING", "contractType": "PERPETUAL"}
            ]}
            """;

    private static final String SPOT = """
            {"symbols": [
              {"symbol": "BTCUSDT",  "baseAsset": "BTC",  "quoteAsset": "USDT", "status": "TRADING"},
              {"symbol": "SOLUSDT",  "baseAsset": "SOL",  "quoteAsset": "USDT", "status": "BREAK"},
              {"symbol": "DOGEUSDT", "baseAsset": "DOGE", "quoteAsset": "USDT", "status": "TRADING"},
              {"symbol": "XRPUSDT",  "baseAsset": "XRP",  "quoteAsset": "USDT", "status": "TRADING"},
              {"symbol": "ETHBTC",   "baseAsset": "ETH",  "quoteAsset": "BTC",  "status": "TRADING"},
              {"symbol": "USDCUSDT", "baseAsset": "USDC", "quoteAsset": "USDT", "status": "TRADING"}
            ]}
            """;

    @Test
    @DisplayName("claims exactly the two Binance venues")
    void venues() {
        BinanceInstrumentSource source = source(true, Mono.empty(), Mono.empty());

        assertEquals(Set.of(Venue.BINANCE_SPOT, Venue.BINANCE_FUTURES), source.venues());
    }

    @Test
    @DisplayName("futures: TRADING ∧ PERPETUAL ∧ quote USDT ∧ not excluded")
    void futuresPolicy() {
        Map<Venue, List<InstrumentCandidate>> result = source(true, parse(SPOT), parse(FUTURES)).fetch();

        assertEquals(List.of("BTCUSDT", "SOLUSDT"), symbols(result.get(Venue.BINANCE_FUTURES)));
    }

    @Test
    @DisplayName("spot with spot-requires-futures: the futures intersection, plus the spot filters")
    void spotPolicyIntersected() {
        Map<Venue, List<InstrumentCandidate>> result = source(true, parse(SPOT), parse(FUTURES)).fetch();

        // SOLUSDT is in futures but not TRADING on spot; DOGEUSDT/XRPUSDT have no eligible future.
        assertEquals(List.of("BTCUSDT"), symbols(result.get(Venue.BINANCE_SPOT)));
    }

    @Test
    @DisplayName("spot without spot-requires-futures: every TRADING, USDT-quoted, non-excluded pair")
    void spotPolicyIndependent() {
        Map<Venue, List<InstrumentCandidate>> result = source(false, parse(SPOT), parse(FUTURES)).fetch();

        assertEquals(List.of("BTCUSDT", "DOGEUSDT", "XRPUSDT"), symbols(result.get(Venue.BINANCE_SPOT)));
    }

    @Test
    @DisplayName("candidates carry base and quote assets")
    void candidateShape() {
        Map<Venue, List<InstrumentCandidate>> result = source(true, parse(SPOT), parse(FUTURES)).fetch();

        assertEquals(new InstrumentCandidate("BTCUSDT", "BTC", "USDT"), result.get(Venue.BINANCE_SPOT).getFirst());
    }

    @Test
    @DisplayName("a failing REST call throws rather than returning an empty universe")
    void restFailureThrows() {
        Mono<ExchangeInfoResponse> failing = Mono.error(
                new BinanceApiException(HttpStatus.SERVICE_UNAVAILABLE, "down"));

        assertThrows(RuntimeException.class, () -> source(true, parse(SPOT), failing).fetch());
        assertThrows(RuntimeException.class, () -> source(true, failing, parse(FUTURES)).fetch());
    }

    @Test
    @DisplayName("an empty body or a missing symbols array throws")
    void malformedResponseThrows() {
        assertThrows(IllegalStateException.class, () -> source(true, Mono.empty(), parse(FUTURES)).fetch());
        assertThrows(IllegalStateException.class, () -> source(true, parse("{}"), parse(FUTURES)).fetch());
    }

    // ---------------------------------------------------------------- fixtures

    private static BinanceInstrumentSource source(boolean spotRequiresFutures,
                                                  Mono<ExchangeInfoResponse> spot,
                                                  Mono<ExchangeInfoResponse> futures) {
        BinanceDiscoveryProperties discovery = new BinanceDiscoveryProperties(
                "USDT", "PERPETUAL", spotRequiresFutures, Set.of("USDCUSDT"));
        return new BinanceInstrumentSource(new StubRestClient(spot, futures), discovery);
    }

    private static Mono<ExchangeInfoResponse> parse(String json) {
        return Mono.just(JSON.readValue(json, ExchangeInfoResponse.class));
    }

    private static List<String> symbols(List<InstrumentCandidate> candidates) {
        return candidates.stream().map(InstrumentCandidate::nativeSymbol).sorted().toList();
    }

    /** Serves canned responses, and pins that the source asks for the right endpoints. */
    private static final class StubRestClient extends BinanceRestClient {
        private final Mono<ExchangeInfoResponse> spot;
        private final Mono<ExchangeInfoResponse> futures;

        StubRestClient(Mono<ExchangeInfoResponse> spot, Mono<ExchangeInfoResponse> futures) {
            super(null, null);
            this.spot = spot;
            this.futures = futures;
        }

        @Override
        @SuppressWarnings("unchecked")
        public <T> Mono<T> getSpot(String path, Class<T> responseType) {
            assertEquals(BinanceInstrumentSource.SPOT_EXCHANGE_INFO, path);
            return (Mono<T>) spot;
        }

        @Override
        @SuppressWarnings("unchecked")
        public <T> Mono<T> getFutures(String path, Class<T> responseType) {
            assertEquals(BinanceInstrumentSource.FUTURES_EXCHANGE_INFO, path);
            return (Mono<T>) futures;
        }
    }
}
